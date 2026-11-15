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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * xAI Grok via Responses API ({@code POST https://api.x.ai/v1/responses}).
 *
 * A Chat Completions da xAI está descontinuada; a Responses API é a interface recomendada.
 * Não envia temperature (os modelos de raciocínio usam a amostragem padrão) nem formato
 * json_object (não documentado na Responses API): no modo JSON o prompt já exige JSON
 * estrito e a resposta é limpa de cercas markdown.
 */
@Component
@Slf4j
public class GrokLlmProvider implements LLMProvider {

    private static final String URL = "https://api.x.ai/v1/responses";

    private final String apiKey;
    private final String model;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GrokLlmProvider(
            @Value("${app.ai.grok.api-key:mock-key}") String apiKey,
            @Value("${app.ai.grok.model:" + AiModels.GROK_DEFAULT + "}") String model,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderName() {
        return "GROK";
    }

    @Override
    public String getDefaultModel() {
        return model;
    }

    @Override
    public boolean isAvailable() {
        return isRealKey(apiKey);
    }

    @Override
    public LLMResponse generateCompletion(LLMRequest request) {
        return generateCompletion(request, null, null);
    }

    @Override
    public LLMResponse generateCompletion(LLMRequest request, String overrideApiKey, String overrideModel) {
        long startTime = System.currentTimeMillis();
        String effectiveKey = isRealKey(overrideApiKey) ? overrideApiKey : this.apiKey;
        String effectiveModel = (overrideModel != null && !overrideModel.isBlank()) ? overrideModel : this.model;

        // Sem simulação própria: sem chave, falha e o orquestrador usa o provedor de fallback
        if (!isRealKey(effectiveKey)) {
            throw new IllegalStateException("API Key do Grok (xAI) não configurada.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(effectiveKey.trim());

        Map<String, Object> body = new HashMap<>();
        body.put("model", effectiveModel);
        body.put("input", request.getUserPrompt());
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            body.put("instructions", request.getSystemPrompt());
        }
        if (request.getMaxTokens() != null) {
            // max_output_tokens limita só o texto visível (não o raciocínio)
            body.put("max_output_tokens", request.getMaxTokens());
        }
        // Conteúdo de reuniões e currículo não deve ficar armazenado na xAI (o padrão guarda por 30 dias)
        body.put("store", false);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(URL, new HttpEntity<>(body, headers), String.class);
        } catch (HttpStatusCodeException e) {
            String detail = extractApiErrorMessage(e.getResponseBodyAsString());
            log.error("Erro na chamada à xAI Grok API ({}): {}", e.getStatusCode().value(), detail);
            throw new IllegalStateException("Grok API retornou HTTP " + e.getStatusCode().value() + ": " + detail, e);
        } catch (RestClientException e) {
            log.error("Falha de rede ao chamar a xAI Grok API: {}", e.getMessage());
            throw new IllegalStateException("Falha de rede ao chamar a Grok API: " + e.getMessage(), e);
        }

        long latencyMs = System.currentTimeMillis() - startTime;
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            String text = extractOutputText(root);
            if (text.isBlank()) {
                throw new IllegalStateException("Grok não retornou texto (status=" + root.path("status").asText("desconhecido")
                        + ", detalhes=" + root.path("incomplete_details") + ").");
            }
            if (request.isJsonMode()) {
                text = stripMarkdownFences(text);
            }

            JsonNode usage = root.path("usage");
            int inputTokens = usage.has("input_tokens") ? usage.path("input_tokens").asInt(0) : usage.path("prompt_tokens").asInt(0);
            int outputTokens = usage.has("output_tokens") ? usage.path("output_tokens").asInt(0) : usage.path("completion_tokens").asInt(0);

            return LLMResponse.builder()
                    .providerName(getProviderName())
                    .modelName(root.path("model").asText(effectiveModel))
                    .content(text)
                    .promptTokens(inputTokens)
                    .completionTokens(outputTokens)
                    .totalTokens(usage.path("total_tokens").asInt(inputTokens + outputTokens))
                    .estimatedCostUsd(AiModels.estimateCost(effectiveModel, inputTokens, outputTokens))
                    .latencyMs(latencyMs)
                    .build();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Resposta inesperada da Grok API: " + e.getMessage(), e);
        }
    }

    /** Texto final: itens "message" de output[], blocos "output_text". Itens "reasoning" são ignorados. */
    public static String extractOutputText(JsonNode root) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            if (!"message".equals(item.path("type").asText())) continue;
            for (JsonNode block : item.path("content")) {
                if ("output_text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
        }
        return text.toString().trim();
    }

    /** Remove cercas ```json ... ``` que alguns modelos colocam em volta de respostas JSON. */
    public static String stripMarkdownFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                return t.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return t;
    }

    private static boolean isRealKey(String key) {
        return key != null && !key.isBlank() && !key.equals("mock-key");
    }

    private String extractApiErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "sem detalhes";
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode err = root.path("error");
            if (err.isTextual()) return err.asText();
            String message = err.path("message").asText(root.path("message").asText(""));
            return message.isBlank() ? responseBody : message;
        } catch (Exception ignored) {
            return responseBody;
        }
    }
}
