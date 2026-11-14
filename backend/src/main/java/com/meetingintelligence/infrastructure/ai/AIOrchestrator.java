package com.meetingintelligence.infrastructure.ai;

import com.meetingintelligence.domain.ai.LLMProvider;
import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.entity.LLMUsage;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.OrganizationAiConfig;
import com.meetingintelligence.infrastructure.persistence.repository.LLMUsageRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationAiConfigRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@Slf4j
public class AIOrchestrator {

    private final GeminiLlmProvider geminiProvider;
    private final OpenAiLlmProvider openAiProvider;
    private final ClaudeLlmProvider claudeProvider;
    private final LLMUsageRepository llmUsageRepository;
    private final OrganizationAiConfigRepository aiConfigRepository;
    private final String defaultProviderName;
    private final CircuitBreaker circuitBreaker;

    public AIOrchestrator(
            GeminiLlmProvider geminiProvider,
            OpenAiLlmProvider openAiProvider,
            ClaudeLlmProvider claudeProvider,
            LLMUsageRepository llmUsageRepository,
            OrganizationAiConfigRepository aiConfigRepository,
            @Value("${app.ai.default-provider:GEMINI}") String defaultProviderName) {
        this.geminiProvider = geminiProvider;
        this.openAiProvider = openAiProvider;
        this.claudeProvider = claudeProvider;
        this.llmUsageRepository = llmUsageRepository;
        this.aiConfigRepository = aiConfigRepository;
        this.defaultProviderName = defaultProviderName;

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .slidingWindowSize(10)
                .build();
        this.circuitBreaker = CircuitBreaker.of("llmCircuitBreaker", config);
    }

    public LLMResponse execute(LLMRequest request, Meeting meeting, String operationType) {
        // 1. Recupera configuração personalizada de IA da organização (BYOK)
        Optional<OrganizationAiConfig> configOpt = (meeting != null && meeting.getOrganization() != null)
                ? aiConfigRepository.findByOrganizationId(meeting.getOrganization().getId())
                : Optional.empty();

        String chosenProvider = configOpt.map(OrganizationAiConfig::getActiveProvider)
                .orElse(defaultProviderName).toUpperCase();

        LLMProvider primary = resolveProvider(chosenProvider);
        LLMProvider secondary = resolveFallbackProvider(chosenProvider);

        String customKey = null;
        String customModel = null;

        if (configOpt.isPresent() && Boolean.TRUE.equals(configOpt.get().getUseOrganizationKeys())) {
            OrganizationAiConfig cfg = configOpt.get();
            switch (chosenProvider) {
                case "OPENAI" -> {
                    customKey = cfg.getOpenAiApiKey();
                    customModel = cfg.getOpenAiModel();
                }
                case "CLAUDE" -> {
                    customKey = cfg.getClaudeApiKey();
                    customModel = cfg.getClaudeModel();
                }
                default -> {
                    customKey = cfg.getGeminiApiKey();
                    customModel = cfg.getGeminiModel();
                }
            }
        }

        final String finalCustomKey = customKey;
        final String finalCustomModel = customModel;

        try {
            // Executa o provedor principal com Circuit Breaker
            return circuitBreaker.executeSupplier(() -> {
                LLMResponse response = primary.generateCompletion(request, finalCustomKey, finalCustomModel);
                recordUsage(response, meeting, operationType);
                return response;
            });
        } catch (Exception e) {
            log.warn("Falha no provedor primário {} (Organização BYOK): {}. Acionando fallback para {}",
                    primary.getProviderName(), e.getMessage(), secondary.getProviderName());
            try {
                LLMResponse fallbackResponse = secondary.generateCompletion(request);
                recordUsage(fallbackResponse, meeting, operationType);
                return fallbackResponse;
            } catch (Exception ex) {
                log.error("Todos os provedores de LLM falharam: {}", ex.getMessage(), ex);
                throw new RuntimeException("Serviço de inteligência artificial temporariamente indisponível.");
            }
        }
    }

    private LLMProvider resolveProvider(String providerName) {
        if ("OPENAI".equalsIgnoreCase(providerName)) return openAiProvider;
        if ("CLAUDE".equalsIgnoreCase(providerName)) return claudeProvider;
        return geminiProvider;
    }

    private LLMProvider resolveFallbackProvider(String currentProvider) {
        if ("CLAUDE".equalsIgnoreCase(currentProvider)) return geminiProvider;
        if ("OPENAI".equalsIgnoreCase(currentProvider)) return geminiProvider;
        return openAiProvider;
    }

    private void recordUsage(LLMResponse response, Meeting meeting, String operationType) {
        if (meeting == null || meeting.getOrganization() == null) return;

        try {
            LLMUsage usage = LLMUsage.builder()
                    .organization(meeting.getOrganization())
                    .meeting(meeting)
                    .provider(response.getProviderName())
                    .modelName(response.getModelName())
                    .operationType(operationType)
                    .promptTokens(response.getPromptTokens())
                    .completionTokens(response.getCompletionTokens())
                    .totalTokens(response.getTotalTokens())
                    .estimatedCostUsd(response.getEstimatedCostUsd())
                    .latencyMs(response.getLatencyMs())
                    .build();

            llmUsageRepository.save(usage);
        } catch (Exception e) {
            log.warn("Não foi possível registrar métrica de consumo de LLM: {}", e.getMessage());
        }
    }
}
