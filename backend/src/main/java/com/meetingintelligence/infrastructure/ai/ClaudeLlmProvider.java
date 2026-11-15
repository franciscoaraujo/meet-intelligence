package com.meetingintelligence.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.ai.AiModels;
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
public class ClaudeLlmProvider implements LLMProvider {

    private final String apiKey;
    private final String model;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ClaudeLlmProvider(
            @Value("${app.ai.claude.api-key:mock-key}") String apiKey,
            @Value("${app.ai.claude.model:" + AiModels.CLAUDE_DEFAULT + "}") String model,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderName() {
        return "CLAUDE";
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
            log.info("Claude API Key não configurada. Utilizando simulação local (modo de desenvolvimento).");
            return generateMockResponse(request, startTime, effectiveModel);
        }

        String url = "https://api.anthropic.com/v1/messages";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", effectiveKey);
        headers.set("anthropic-version", "2023-06-01");

        Map<String, Object> body = new HashMap<>();
        body.put("model", effectiveModel);
        body.put("max_tokens", resolveMaxTokens(request, effectiveModel));

        // Modelos Claude atuais (geração 4.7+/5.x) rejeitam temperature/top_p/top_k com HTTP 400.
        if (acceptsSamplingParams(effectiveModel)) {
            body.put("temperature", request.getTemperature() != null ? request.getTemperature() : 0.2);
        }

        // Fallback no servidor: se o modelo recusar a resposta por política, a API reexecuta no modelo de apoio.
        if (supportsServerSideFallback(effectiveModel)) {
            headers.set("anthropic-beta", "server-side-fallback-2026-07-01");
            body.put("fallbacks", "default");
        }

        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            body.put("system", request.getSystemPrompt());
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", request.getUserPrompt()));
        body.put("messages", messages);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // Erro real da API (chave inválida, modelo inexistente, parâmetro rejeitado...): propaga para que
            // o teste de conexão mostre a falha e o orquestrador acione o provedor de fallback.
            String detail = extractApiErrorMessage(e.getResponseBodyAsString());
            log.error("Erro na chamada à Anthropic Claude API ({}): {}", e.getStatusCode().value(), detail);
            throw new IllegalStateException("Claude API retornou HTTP " + e.getStatusCode().value() + ": " + detail, e);
        } catch (org.springframework.web.client.RestClientException e) {
            log.error("Falha de rede ao chamar a Anthropic Claude API: {}", e.getMessage());
            throw new IllegalStateException("Falha de rede ao chamar a Claude API: " + e.getMessage(), e);
        }

        long latencyMs = System.currentTimeMillis() - startTime;
        try {
            JsonNode root = objectMapper.readTree(response.getBody());

            String stopReason = root.path("stop_reason").asText("");
            if ("refusal".equals(stopReason)) {
                throw new IllegalStateException("Claude recusou a solicitação (stop_reason=refusal).");
            }

            // A resposta pode trazer blocos de raciocínio (thinking) e de fallback antes do texto: junta só os blocos de texto.
            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
            if (text.isEmpty()) {
                throw new IllegalStateException("Claude não retornou texto (stop_reason=" + stopReason + ").");
            }

            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("input_tokens").asInt(0);
            int outputTokens = usage.path("output_tokens").asInt(0);
            String servedModel = root.path("model").asText(effectiveModel);

            return LLMResponse.builder()
                    .providerName(getProviderName())
                    .modelName(servedModel)
                    .content(text.toString())
                    .promptTokens(inputTokens)
                    .completionTokens(outputTokens)
                    .totalTokens(inputTokens + outputTokens)
                    .estimatedCostUsd(estimateCost(servedModel, inputTokens, outputTokens))
                    .latencyMs(latencyMs)
                    .build();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Resposta inesperada da Claude API: " + e.getMessage(), e);
        }
    }

    /** Modelos anteriores à geração 4.7 ainda aceitam temperature; os atuais retornam 400. */
    static boolean acceptsSamplingParams(String model) {
        return model != null && (model.startsWith("claude-3") || model.matches("claude-(opus|sonnet|haiku)-4-[0-6].*")
                || model.matches("claude-(opus|sonnet|haiku)-4-\\d{8}.*"));
    }

    static boolean supportsServerSideFallback(String model) {
        return model != null && (model.startsWith("claude-opus-5") || model.startsWith("claude-sonnet-5-5")
                || model.startsWith("claude-fable-5"));
    }

    /**
     * Nos modelos atuais o raciocínio adaptativo está sempre ligado e consome o mesmo limite de
     * max_tokens; um limite muito baixo (ex.: 40 no teste de conexão) termina sem texto.
     */
    static int resolveMaxTokens(LLMRequest request, String model) {
        int requested = request.getMaxTokens() != null ? request.getMaxTokens() : 16000;
        return acceptsSamplingParams(model) ? requested : Math.max(requested, 4096);
    }

    static BigDecimal estimateCost(String model, int inputTokens, int outputTokens) {
        return AiModels.estimateCost(model, inputTokens, outputTokens);
    }

    private String extractApiErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "sem detalhes";
        try {
            JsonNode err = objectMapper.readTree(responseBody).path("error");
            String type = err.path("type").asText("");
            String message = err.path("message").asText("");
            return (type.isBlank() ? "" : type + " - ") + (message.isBlank() ? responseBody : message);
        } catch (Exception ignored) {
            return responseBody;
        }
    }

    private LLMResponse generateMockResponse(LLMRequest request, long startTime, String modelName) {
        long latency = Math.max(System.currentTimeMillis() - startTime, 195L);

        String jsonContent = """
        {
          "summary_delta": "Discussão e refinamento técnico utilizando o raciocínio aprofundado do Claude (simulação local).",
          "insights": [
            {
              "category": "DECISION",
              "title": "Adoção do padrão BYOK (Bring Your Own Key) para o LLM Orchestrator",
              "description": "Permitir que clientes corporativos utilizem suas próprias credenciais do xAI Grok, Google Gemini ou Anthropic Claude garantindo conformidade e governança de dados.",
              "consequences": "Maior controle financeiro e retenção garantida para contas corporativas.",
              "confidence": 0.99
            }
          ]
        }
        """;

        String textResponse = request.isJsonMode() ? jsonContent :
                "Análise detalhada concluída via Anthropic Claude (simulação local). Os principais pontos discutidos abrangem a governança de dados, redução de custos e autonomia no uso de chaves proprietárias de IA.";

        return LLMResponse.builder()
                .providerName(getProviderName())
                .modelName(modelName != null ? modelName : model)
                .content(textResponse)
                .promptTokens(260)
                .completionTokens(170)
                .totalTokens(430)
                .estimatedCostUsd(BigDecimal.valueOf(0.003330))
                .latencyMs(latency)
                .build();
    }
}

