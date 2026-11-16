package com.meetingintelligence.infrastructure.ai;

import com.meetingintelligence.domain.ai.LLMProvider;
import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.ai.LlmStreamAbortedException;
import com.meetingintelligence.domain.ai.LlmStreamListener;
import com.meetingintelligence.domain.entity.LLMUsage;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.OrganizationAiConfig;
import com.meetingintelligence.infrastructure.persistence.repository.LLMUsageRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationAiConfigRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class AIOrchestrator {

    /** Operação do copiloto de entrevistas: usa o modelo próprio do copiloto, quando configurado (spec 008, RF-09). */
    public static final String INTERVIEW_ANSWER = "INTERVIEW_ANSWER";
    /** Tradução do card do copiloto (spec 012): também usa o modelo rápido do copiloto. */
    public static final String ANSWER_TRANSLATION = "ANSWER_TRANSLATION";

    private final GeminiLlmProvider geminiProvider;
    private final GrokLlmProvider grokProvider;
    private final ClaudeLlmProvider claudeProvider;
    private final LLMUsageRepository llmUsageRepository;
    private final OrganizationAiConfigRepository aiConfigRepository;
    private final String defaultProviderName;
    private final CircuitBreaker circuitBreaker;
    private final MeterRegistry meters;
    private final String grokCopilotModel;
    private final String claudeCopilotModel;
    private final String geminiCopilotModel;

    public AIOrchestrator(
            GeminiLlmProvider geminiProvider,
            GrokLlmProvider grokProvider,
            ClaudeLlmProvider claudeProvider,
            LLMUsageRepository llmUsageRepository,
            OrganizationAiConfigRepository aiConfigRepository,
            MeterRegistry meters,
            @Value("${app.ai.default-provider:GEMINI}") String defaultProviderName,
            @Value("${app.ai.grok.copilot-model:}") String grokCopilotModel,
            @Value("${app.ai.claude.copilot-model:}") String claudeCopilotModel,
            @Value("${app.ai.gemini.copilot-model:}") String geminiCopilotModel) {
        this.geminiProvider = geminiProvider;
        this.grokProvider = grokProvider;
        this.claudeProvider = claudeProvider;
        this.llmUsageRepository = llmUsageRepository;
        this.aiConfigRepository = aiConfigRepository;
        this.meters = meters;
        this.defaultProviderName = defaultProviderName;
        this.grokCopilotModel = grokCopilotModel;
        this.claudeCopilotModel = claudeCopilotModel;
        this.geminiCopilotModel = geminiCopilotModel;

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .slidingWindowSize(10)
                .build();
        this.circuitBreaker = CircuitBreaker.of("llmCircuitBreaker", config);
    }

    /** Provedor escolhido pela organização, com a chave e o modelo a usar. */
    private record Route(LLMProvider primary, LLMProvider secondary, String key, String model, String fallbackModel) {}

    private Route route(Meeting meeting, String operationType) {
        // 1. Recupera configuração personalizada de IA da organização (BYOK)
        Optional<OrganizationAiConfig> configOpt = (meeting != null && meeting.getOrganization() != null)
                ? aiConfigRepository.findByOrganizationId(meeting.getOrganization().getId())
                : Optional.empty();

        String chosenProvider = configOpt.map(OrganizationAiConfig::getActiveProvider)
                .orElse(defaultProviderName).toUpperCase();

        String customKey = null;
        String customModel = null;
        if (configOpt.isPresent() && Boolean.TRUE.equals(configOpt.get().getUseOrganizationKeys())) {
            OrganizationAiConfig cfg = configOpt.get();
            switch (chosenProvider) {
                case "GROK" -> {
                    customKey = cfg.getGrokApiKey();
                    customModel = cfg.getGrokModel();
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

        String fallbackModel = null;
        if (INTERVIEW_ANSWER.equals(operationType) || ANSWER_TRANSLATION.equals(operationType)) {
            String copilotModel = copilotModel(chosenProvider);
            if (copilotModel != null) customModel = copilotModel;
            fallbackModel = copilotModel("GEMINI");
        }
        return new Route(resolveProvider(chosenProvider), resolveFallbackProvider(chosenProvider), customKey, customModel, fallbackModel);
    }

    public LLMResponse execute(LLMRequest request, Meeting meeting, String operationType) {
        Route route = route(meeting, operationType);
        try {
            // Executa o provedor principal com Circuit Breaker
            return circuitBreaker.executeSupplier(() -> {
                LLMResponse response = route.primary().generateCompletion(request, route.key(), route.model());
                recordUsage(response, meeting, operationType);
                return response;
            });
        } catch (Exception e) {
            log.warn("Falha no provedor primário {} (Organização BYOK): {}. Acionando fallback para {}",
                    route.primary().getProviderName(), e.getMessage(), route.secondary().getProviderName());
            try {
                LLMResponse fallbackResponse = route.secondary().generateCompletion(request, null, route.fallbackModel());
                recordUsage(fallbackResponse, meeting, operationType);
                return fallbackResponse;
            } catch (Exception ex) {
                log.error("Todos os provedores de LLM falharam: {}", ex.getMessage(), ex);
                throw new RuntimeException("Serviço de inteligência artificial temporariamente indisponível.");
            }
        }
    }

    /**
     * Igual a {@link #execute}, entregando o texto em partes (spec 008, RF-06).
     * <ul>
     *   <li>Falha antes da primeira parte: fallback transparente, como no {@code execute}.</li>
     *   <li>Falha depois da primeira parte: {@link LlmStreamAbortedException}; quem chamou descarta a tentativa.</li>
     *   <li>Todos falham antes de qualquer parte: {@link RuntimeException} de indisponibilidade.</li>
     * </ul>
     */
    public LLMResponse executeStreaming(LLMRequest request, Meeting meeting, String operationType, LlmStreamListener listener) {
        Route route = route(meeting, operationType);
        AtomicBoolean delivered = new AtomicBoolean(false);
        LlmStreamListener tracking = text -> {
            delivered.set(true);
            listener.onDelta(text);
        };
        try {
            return circuitBreaker.executeSupplier(() -> {
                LLMResponse response = route.primary().stream(request, route.key(), route.model(), tracking);
                recordUsage(response, meeting, operationType);
                return response;
            });
        } catch (Exception e) {
            if (delivered.get()) {
                meters.counter("copilot.stream.aborted", "provider", route.primary().getProviderName()).increment();
                log.warn("Streaming do provedor {} interrompido após o primeiro trecho: {}", route.primary().getProviderName(), e.getMessage());
                throw new LlmStreamAbortedException("Streaming interrompido: " + e.getMessage(), e);
            }
            log.warn("Falha no provedor primário {} antes do primeiro trecho: {}. Acionando fallback para {}",
                    route.primary().getProviderName(), e.getMessage(), route.secondary().getProviderName());
            meters.counter("copilot.stream.fallback", "from", route.primary().getProviderName(),
                    "to", route.secondary().getProviderName()).increment();
            try {
                LLMResponse fallbackResponse = route.secondary().stream(request, null, route.fallbackModel(), tracking);
                recordUsage(fallbackResponse, meeting, operationType);
                return fallbackResponse;
            } catch (Exception ex) {
                if (delivered.get()) {
                    meters.counter("copilot.stream.aborted", "provider", route.secondary().getProviderName()).increment();
                    throw new LlmStreamAbortedException("Streaming interrompido: " + ex.getMessage(), ex);
                }
                log.error("Todos os provedores de LLM falharam: {}", ex.getMessage(), ex);
                throw new RuntimeException("Serviço de inteligência artificial temporariamente indisponível.");
            }
        }
    }

    private String copilotModel(String providerName) {
        String model = switch (providerName) {
            case "GROK" -> grokCopilotModel;
            case "CLAUDE" -> claudeCopilotModel;
            default -> geminiCopilotModel;
        };
        return model == null || model.isBlank() ? null : model.trim();
    }

    private LLMProvider resolveProvider(String providerName) {
        if ("GROK".equalsIgnoreCase(providerName)) return grokProvider;
        if ("CLAUDE".equalsIgnoreCase(providerName)) return claudeProvider;
        return geminiProvider;
    }

    /**
     * Fallback: Gemini com a chave global do servidor (sem a chave da organização). Em
     * desenvolvimento, sem chave global, cai na simulação local, que usa a Base de Conhecimento.
     */
    private LLMProvider resolveFallbackProvider(String currentProvider) {
        return geminiProvider;
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
