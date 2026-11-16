package com.meetingintelligence.infrastructure.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.ai.AiModels;
import com.meetingintelligence.domain.ai.LLMProvider;
import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.ai.LlmStreamListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
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
    private final SseClient sse;
    private final String streamUrl;

    @Autowired
    public GrokLlmProvider(
            @Value("${app.ai.grok.api-key:mock-key}") String apiKey,
            @Value("${app.ai.grok.model:" + AiModels.GROK_DEFAULT + "}") String model,
            ObjectMapper objectMapper) {
        this(apiKey, model, objectMapper, new SseClient(Duration.ofSeconds(10), Duration.ofSeconds(20)), URL);
    }

    /** Construtor de teste: permite apontar o streaming para um servidor local. */
    GrokLlmProvider(String apiKey, String model, ObjectMapper objectMapper, SseClient sse, String streamUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.sse = sse;
        this.streamUrl = streamUrl;
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
        String effectiveKey = resolveKey(overrideApiKey);
        String effectiveModel = resolveModel(overrideModel);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(effectiveKey);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(URL, new HttpEntity<>(buildBody(request, effectiveModel), headers), String.class);
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
            return buildResponse(request, text, root, effectiveModel, latencyMs);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Resposta inesperada da Grok API: " + e.getMessage(), e);
        }
    }

    /**
     * Streaming pela Responses API ({@code "stream": true}, spec 008). Repassa cada
     * {@code response.output_text.delta} e usa {@code response.completed} para modelo e uso de tokens.
     */
    @Override
    public LLMResponse stream(LLMRequest request, String overrideApiKey, String overrideModel, LlmStreamListener listener) {
        long startTime = System.currentTimeMillis();
        String effectiveKey = resolveKey(overrideApiKey);
        String effectiveModel = resolveModel(overrideModel);

        Map<String, Object> body = buildBody(request, effectiveModel);
        body.put("stream", true);
        StreamAccumulator acc = new StreamAccumulator(objectMapper, listener);
        try {
            sse.post(streamUrl, Map.of("Authorization", "Bearer " + effectiveKey), objectMapper.writeValueAsString(body), acc::accept);
        } catch (SseClient.HttpStatusException e) {
            String detail = extractApiErrorMessage(e.body());
            log.error("Erro no streaming da xAI Grok API ({}): {}", e.status(), detail);
            throw new IllegalStateException("Grok API retornou HTTP " + e.status() + ": " + detail, e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao montar a requisição da Grok API", e);
        }
        if (acc.finalResponse == null) {
            throw new IllegalStateException("Streaming da Grok API terminou sem response.completed");
        }
        String text = acc.text.toString().trim();
        if (text.isBlank()) {
            throw new IllegalStateException("Grok não retornou texto (status=" + acc.finalResponse.path("status").asText("desconhecido") + ").");
        }
        return buildResponse(request, text, acc.finalResponse, effectiveModel, System.currentTimeMillis() - startTime);
    }

    /** Processa, na ordem, os eventos SSE da Responses API. */
    static final class StreamAccumulator {
        private final ObjectMapper objectMapper;
        private final LlmStreamListener listener;
        final StringBuilder text = new StringBuilder();
        JsonNode finalResponse;

        StreamAccumulator(ObjectMapper objectMapper, LlmStreamListener listener) {
            this.objectMapper = objectMapper;
            this.listener = listener;
        }

        void accept(SseClient.Event event) {
            String data = event.data();
            if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) return;
            JsonNode node;
            try {
                node = objectMapper.readTree(data);
            } catch (Exception e) {
                log.debug("Evento SSE da Grok API ignorado (JSON inválido)");
                return;
            }
            String type = node.path("type").asText(event.event() == null ? "" : event.event());
            switch (type) {
                case "response.output_text.delta" -> {
                    String delta = node.path("delta").asText("");
                    if (!delta.isEmpty()) {
                        text.append(delta);
                        listener.onDelta(delta);
                    }
                }
                case "response.completed", "response.incomplete" -> finalResponse = node.path("response");
                case "response.failed" -> throw new IllegalStateException("Grok API falhou no streaming: "
                        + node.path("response").path("error").path("message").asText("sem detalhes"));
                case "error" -> throw new IllegalStateException("Grok API falhou no streaming: "
                        + node.path("message").asText(node.path("error").path("message").asText("sem detalhes")));
                default -> { /* eventos de ciclo de vida e de raciocínio: ignorados */ }
            }
        }
    }

    private String resolveKey(String overrideApiKey) {
        String effectiveKey = isRealKey(overrideApiKey) ? overrideApiKey : this.apiKey;
        // Sem simulação própria: sem chave, falha e o orquestrador usa o provedor de fallback
        if (!isRealKey(effectiveKey)) {
            throw new IllegalStateException("API Key do Grok (xAI) não configurada.");
        }
        return effectiveKey.trim();
    }

    private String resolveModel(String overrideModel) {
        return (overrideModel != null && !overrideModel.isBlank()) ? overrideModel : this.model;
    }

    private static Map<String, Object> buildBody(LLMRequest request, String effectiveModel) {
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
        return body;
    }

    private LLMResponse buildResponse(LLMRequest request, String text, JsonNode root, String effectiveModel, long latencyMs) {
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
