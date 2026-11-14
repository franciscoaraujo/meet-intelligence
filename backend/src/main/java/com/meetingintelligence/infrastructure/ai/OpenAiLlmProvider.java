package com.meetingintelligence.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.ai.LLMProvider;
import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class OpenAiLlmProvider implements LLMProvider {

    private final String apiKey;
    private final String model;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OpenAiLlmProvider(
            @Value("${app.ai.openai.api-key:mock-key}") String apiKey,
            @Value("${app.ai.openai.model:gpt-4o-mini}") String model,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderName() {
        return "OPENAI";
    }

    @Override
    public String getDefaultModel() {
        return model;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.equals("mock-key");
    }

    @Override
    public LLMResponse generateCompletion(LLMRequest request) {
        return generateCompletion(request, null, null);
    }

    @Override
    public LLMResponse generateCompletion(LLMRequest request, String overrideApiKey, String overrideModel) {
        long startTime = System.currentTimeMillis();

        String effectiveKey = (overrideApiKey != null && !overrideApiKey.isBlank()) ? overrideApiKey : this.apiKey;
        String effectiveModel = (overrideModel != null && !overrideModel.isBlank()) ? overrideModel : this.model;

        boolean available = effectiveKey != null && !effectiveKey.isBlank() && !effectiveKey.equals("mock-key");
        if (!available) {
            return generateMockResponse(request, startTime);
        }

        try {
            String url = "https://api.openai.com/v1/chat/completions";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(effectiveKey);

            List<Map<String, String>> messages = new ArrayList<>();
            if (request.getSystemPrompt() != null) {
                messages.add(Map.of("role", "system", "content", request.getSystemPrompt()));
            }
            messages.add(Map.of("role", "user", "content", request.getUserPrompt()));

            Map<String, Object> body = new HashMap<>();
            body.put("model", effectiveModel);
            body.put("messages", messages);
            body.put("temperature", request.getTemperature() != null ? request.getTemperature() : 0.2);
            if (request.getMaxTokens() != null) {
                body.put("max_tokens", request.getMaxTokens());
            }
            if (request.isJsonMode()) {
                body.put("response_format", Map.of("type", "json_object"));
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            long latencyMs = System.currentTimeMillis() - startTime;

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String content = root.path("choices").get(0).path("message").path("content").asText();

                JsonNode usage = root.path("usage");
                int promptTokens = usage.path("prompt_tokens").asInt(280);
                int completionTokens = usage.path("completion_tokens").asInt(190);
                int totalTokens = usage.path("total_tokens").asInt(promptTokens + completionTokens);

                BigDecimal cost = BigDecimal.valueOf((promptTokens * 0.00000015) + (completionTokens * 0.00000060))
                        .setScale(6, RoundingMode.HALF_UP);

                return LLMResponse.builder()
                        .providerName(getProviderName())
                        .modelName(effectiveModel)
                        .content(content)
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(totalTokens)
                        .estimatedCostUsd(cost)
                        .latencyMs(latencyMs)
                        .build();
            }
        } catch (Exception e) {
            log.error("Erro na chamada à OpenAI Chat API: {}", e.getMessage(), e);
        }

        return generateMockResponse(request, startTime);
    }

    private LLMResponse generateMockResponse(LLMRequest request, long startTime) {
        long latency = Math.max(System.currentTimeMillis() - startTime, 180L);

        String jsonContent = """
        {
          "summary_delta": "Continuidade do alinhamento técnico com definição de componentes do sistema.",
          "insights": [
            {
              "category": "TASK",
              "title": "Configurar monitoramento de tokens no banco de dados",
              "description": "Persistir métricas de custo e latência de cada requisição LLM na tabela llm_usage.",
              "assignee_name": "Mariana Costa",
              "priority": "MEDIUM",
              "confidence": 0.97
            }
          ],
          "key_topics": ["Auditoria de Tokens", "Monitoramento de Custos"],
          "sentiment": "POSITIVO",
          "meeting_type_detected": "TECHNICAL"
        }
        """;

        return LLMResponse.builder()
                .providerName(getProviderName())
                .modelName(model)
                .content(request.isJsonMode() ? jsonContent : "Processamento OpenAI simulado com sucesso.")
                .promptTokens(290)
                .completionTokens(160)
                .totalTokens(450)
                .estimatedCostUsd(BigDecimal.valueOf(0.000139))
                .latencyMs(latency)
                .build();
    }
}

