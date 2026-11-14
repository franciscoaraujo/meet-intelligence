package com.meetingintelligence.application.service;

import com.meetingintelligence.application.dto.OrganizationAiConfigDto;
import com.meetingintelligence.application.dto.TestAiConnectionDto;
import com.meetingintelligence.application.dto.UpdateAiConfigRequest;
import com.meetingintelligence.domain.ai.LLMProvider;
import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.entity.OrganizationAiConfig;
import com.meetingintelligence.infrastructure.ai.ClaudeLlmProvider;
import com.meetingintelligence.infrastructure.ai.GeminiLlmProvider;
import com.meetingintelligence.infrastructure.ai.OpenAiLlmProvider;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationAiConfigRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationAiConfigService {

    private final OrganizationAiConfigRepository aiConfigRepository;
    private final OrganizationRepository organizationRepository;
    private final GeminiLlmProvider geminiProvider;
    private final OpenAiLlmProvider openAiProvider;
    private final ClaudeLlmProvider claudeProvider;

    @Transactional(readOnly = true)
    public OrganizationAiConfigDto getAiConfig(UUID organizationId) {
        OrganizationAiConfig config = aiConfigRepository.findByOrganizationId(organizationId)
                .orElseGet(() -> createDefaultConfig(organizationId));

        return mapToDto(config);
    }

    @Transactional
    public OrganizationAiConfigDto updateAiConfig(UUID organizationId, UpdateAiConfigRequest req) {
        OrganizationAiConfig config = aiConfigRepository.findByOrganizationId(organizationId)
                .orElseGet(() -> {
                    Organization org = organizationRepository.findById(organizationId)
                            .orElseThrow(() -> new IllegalArgumentException("Organização não encontrada: " + organizationId));
                    return OrganizationAiConfig.builder().organization(org).build();
                });

        if (req.getActiveProvider() != null && !req.getActiveProvider().isBlank()) {
            config.setActiveProvider(req.getActiveProvider().toUpperCase().trim());
        }

        // Atualiza chaves somente se novas (ignora strings que contenham "••••")
        if (req.getGeminiApiKey() != null && !req.getGeminiApiKey().isBlank() && !req.getGeminiApiKey().contains("••••")) {
            config.setGeminiApiKey(req.getGeminiApiKey().trim());
        }
        if (req.getGeminiModel() != null && !req.getGeminiModel().isBlank()) {
            config.setGeminiModel(req.getGeminiModel().trim());
        }

        if (req.getOpenAiApiKey() != null && !req.getOpenAiApiKey().isBlank() && !req.getOpenAiApiKey().contains("••••")) {
            config.setOpenAiApiKey(req.getOpenAiApiKey().trim());
        }
        if (req.getOpenAiModel() != null && !req.getOpenAiModel().isBlank()) {
            config.setOpenAiModel(req.getOpenAiModel().trim());
        }

        if (req.getClaudeApiKey() != null && !req.getClaudeApiKey().isBlank() && !req.getClaudeApiKey().contains("••••")) {
            config.setClaudeApiKey(req.getClaudeApiKey().trim());
        }
        if (req.getClaudeModel() != null && !req.getClaudeModel().isBlank()) {
            config.setClaudeModel(req.getClaudeModel().trim());
        }

        if (req.getTemperature() != null) {
            config.setTemperature(req.getTemperature());
        }
        if (req.getUseOrganizationKeys() != null) {
            config.setUseOrganizationKeys(req.getUseOrganizationKeys());
        }

        OrganizationAiConfig saved = aiConfigRepository.save(config);
        log.info("Configuração de IA atualizada para a organização {}: Provedor Ativo = {}",
                organizationId, saved.getActiveProvider());

        return mapToDto(saved);
    }

    public TestAiConnectionDto.Response testAiConnection(TestAiConnectionDto.Request req) {
        String provider = req.getProvider() != null ? req.getProvider().toUpperCase().trim() : "GEMINI";
        String model = req.getModel();
        String apiKey = req.getApiKey();

        log.info("Testando conexão de IA para provedor: {} (Modelo: {})", provider, model);

        LLMProvider targetProvider = switch (provider) {
            case "OPENAI" -> openAiProvider;
            case "CLAUDE" -> claudeProvider;
            default -> geminiProvider;
        };

        LLMRequest testRequest = LLMRequest.builder()
                .systemPrompt("Você é um assistente de validação de conectividade de IA.")
                .userPrompt("Por favor, responda estritamente com a frase: 'Conexão com a IA estabelecida com sucesso!'")
                .maxTokens(40)
                .temperature(0.1)
                .build();

        long start = System.currentTimeMillis();
        try {
            LLMResponse response = targetProvider.generateCompletion(testRequest, apiKey, model);
            long latency = Math.max(System.currentTimeMillis() - start, response.getLatencyMs());

            return TestAiConnectionDto.Response.builder()
                    .success(true)
                    .provider(provider)
                    .model(response.getModelName())
                    .message("Conexão validada com sucesso via " + provider + "!")
                    .latencyMs(latency)
                    .sampleResponse(response.getContent())
                    .build();
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Falha no teste de conexão da IA {}: {}", provider, e.getMessage());
            return TestAiConnectionDto.Response.builder()
                    .success(false)
                    .provider(provider)
                    .model(model)
                    .message("Falha ao comunicar com " + provider + ": " + e.getMessage())
                    .latencyMs(latency)
                    .build();
        }
    }

    private OrganizationAiConfig createDefaultConfig(UUID organizationId) {
        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Organização não encontrada: " + organizationId));

        OrganizationAiConfig config = OrganizationAiConfig.builder()
                .organization(org)
                .activeProvider("GEMINI")
                .geminiModel("gemini-1.5-flash")
                .openAiModel("gpt-4o-mini")
                .claudeModel("claude-3-5-sonnet-20241022")
                .temperature(0.20)
                .useOrganizationKeys(true)
                .build();

        return aiConfigRepository.save(config);
    }

    private OrganizationAiConfigDto mapToDto(OrganizationAiConfig c) {
        return OrganizationAiConfigDto.builder()
                .organizationId(c.getOrganization().getId())
                .activeProvider(c.getActiveProvider())
                .geminiConfigured(c.getGeminiApiKey() != null && !c.getGeminiApiKey().isBlank())
                .geminiMaskedKey(OrganizationAiConfigDto.maskApiKey(c.getGeminiApiKey()))
                .geminiModel(c.getGeminiModel())
                .openAiConfigured(c.getOpenAiApiKey() != null && !c.getOpenAiApiKey().isBlank())
                .openAiMaskedKey(OrganizationAiConfigDto.maskApiKey(c.getOpenAiApiKey()))
                .openAiModel(c.getOpenAiModel())
                .claudeConfigured(c.getClaudeApiKey() != null && !c.getClaudeApiKey().isBlank())
                .claudeMaskedKey(OrganizationAiConfigDto.maskApiKey(c.getClaudeApiKey()))
                .claudeModel(c.getClaudeModel())
                .temperature(c.getTemperature())
                .useOrganizationKeys(c.getUseOrganizationKeys())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}

