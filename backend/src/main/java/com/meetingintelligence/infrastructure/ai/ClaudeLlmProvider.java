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
    private final SseClient sse;
    private final String streamUrl;

    private static final String URL = "https://api.anthropic.com/v1/messages";

    @org.springframework.beans.factory.annotation.Autowired
    public ClaudeLlmProvider(
            @Value("${app.ai.claude.api-key:mock-key}") String apiKey,
            @Value("${app.ai.claude.model:" + AiModels.CLAUDE_DEFAULT + "}") String model,
            ObjectMapper objectMapper) {
        this(apiKey, model, objectMapper, new SseClient(java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(20)), URL);
    }

    /** Construtor de teste: permite apontar o streaming para um servidor local. */
    ClaudeLlmProvider(String apiKey, String model, ObjectMapper objectMapper, SseClient sse, String streamUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.sse = sse;
        this.streamUrl = streamUrl;
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

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        apiHeaders(effectiveKey, effectiveModel).forEach(headers::set);
        Map<String, Object> body = buildBody(request, effectiveModel);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(URL, new HttpEntity<>(body, headers), String.class);
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

    /**
     * Streaming pela Messages API ({@code "stream": true}, spec 008). Repassa os {@code text_delta};
     * blocos de raciocínio são ignorados. Sem chave, usa a simulação local em uma única parte.
     */
    @Override
    public LLMResponse stream(LLMRequest request, String overrideApiKey, String overrideModel,
                              com.meetingintelligence.domain.ai.LlmStreamListener listener) {
        long startTime = System.currentTimeMillis();
        String effectiveKey = (overrideApiKey != null && !overrideApiKey.isBlank()) ? overrideApiKey : this.apiKey;
        String effectiveModel = (overrideModel != null && !overrideModel.isBlank()) ? overrideModel : this.model;
        if (effectiveKey == null || effectiveKey.isBlank() || effectiveKey.equals("mock-key")) {
            return LLMProvider.super.stream(request, overrideApiKey, overrideModel, listener);
        }

        Map<String, Object> body = buildBody(request, effectiveModel);
        body.put("stream", true);
        // Sem o fallback do lado da Anthropic no streaming: uma reexecução no meio do stream duplicaria texto
        // já entregue; a falha é tratada pelo AIOrchestrator e pelas novas tentativas do copiloto.
        body.remove("fallbacks");
        Map<String, String> headers = apiHeaders(effectiveKey, effectiveModel);
        headers.remove("anthropic-beta");
        StreamAccumulator acc = new StreamAccumulator(objectMapper, listener);
        try {
            sse.post(streamUrl, headers, objectMapper.writeValueAsString(body), acc::accept);
        } catch (SseClient.HttpStatusException e) {
            String detail = extractApiErrorMessage(e.body());
            log.error("Erro no streaming da Anthropic Claude API ({}): {}", e.status(), detail);
            throw new IllegalStateException("Claude API retornou HTTP " + e.status() + ": " + detail, e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Falha ao montar a requisição da Claude API", e);
        }
        if (!acc.stopped) {
            throw new IllegalStateException("Streaming da Claude API terminou sem message_stop");
        }
        if ("refusal".equals(acc.stopReason)) {
            throw new IllegalStateException("Claude recusou a solicitação (stop_reason=refusal).");
        }
        if (acc.text.isEmpty()) {
            throw new IllegalStateException("Claude não retornou texto (stop_reason=" + acc.stopReason + ").");
        }
        String servedModel = acc.model != null ? acc.model : effectiveModel;
        return LLMResponse.builder()
                .providerName(getProviderName())
                .modelName(servedModel)
                .content(acc.text.toString())
                .promptTokens(acc.inputTokens)
                .completionTokens(acc.outputTokens)
                .totalTokens(acc.inputTokens + acc.outputTokens)
                .estimatedCostUsd(estimateCost(servedModel, acc.inputTokens, acc.outputTokens))
                .latencyMs(System.currentTimeMillis() - startTime)
                .build();
    }

    /** Processa, na ordem, os eventos SSE da Messages API. */
    static final class StreamAccumulator {
        private final ObjectMapper objectMapper;
        private final com.meetingintelligence.domain.ai.LlmStreamListener listener;
        final StringBuilder text = new StringBuilder();
        String model;
        String stopReason;
        int inputTokens;
        int outputTokens;
        boolean stopped;

        StreamAccumulator(ObjectMapper objectMapper, com.meetingintelligence.domain.ai.LlmStreamListener listener) {
            this.objectMapper = objectMapper;
            this.listener = listener;
        }

        void accept(SseClient.Event event) {
            if (event.data() == null || event.data().isBlank()) return;
            JsonNode node;
            try {
                node = objectMapper.readTree(event.data());
            } catch (Exception e) {
                log.debug("Evento SSE da Claude API ignorado (JSON inválido)");
                return;
            }
            switch (node.path("type").asText(event.event() == null ? "" : event.event())) {
                case "message_start" -> {
                    JsonNode message = node.path("message");
                    model = message.path("model").asText(null);
                    inputTokens = message.path("usage").path("input_tokens").asInt(0);
                    outputTokens = message.path("usage").path("output_tokens").asInt(0);
                }
                case "content_block_delta" -> {
                    JsonNode delta = node.path("delta");
                    if ("text_delta".equals(delta.path("type").asText())) {
                        String piece = delta.path("text").asText("");
                        if (!piece.isEmpty()) {
                            text.append(piece);
                            listener.onDelta(piece);
                        }
                    }
                }
                case "message_delta" -> {
                    stopReason = node.path("delta").path("stop_reason").asText(stopReason);
                    if (node.path("usage").has("output_tokens")) {
                        outputTokens = node.path("usage").path("output_tokens").asInt(outputTokens);
                    }
                }
                case "message_stop" -> stopped = true;
                case "error" -> throw new IllegalStateException("Claude API falhou no streaming: "
                        + node.path("error").path("type").asText("") + " - " + node.path("error").path("message").asText("sem detalhes"));
                default -> { /* ping, content_block_start/stop: ignorados */ }
            }
        }
    }

    private Map<String, String> apiHeaders(String key, String effectiveModel) {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-api-key", key);
        headers.put("anthropic-version", "2023-06-01");
        if (supportsServerSideFallback(effectiveModel)) {
            headers.put("anthropic-beta", "server-side-fallback-2026-07-01");
        }
        return headers;
    }

    private static Map<String, Object> buildBody(LLMRequest request, String effectiveModel) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", effectiveModel);
        body.put("max_tokens", resolveMaxTokens(request, effectiveModel));
        // Modelos Claude atuais (geração 4.7+/5.x) rejeitam temperature/top_p/top_k com HTTP 400.
        if (acceptsSamplingParams(effectiveModel)) {
            body.put("temperature", request.getTemperature() != null ? request.getTemperature() : 0.2);
        }
        // Fallback no servidor: se o modelo recusar a resposta por política, a API reexecuta no modelo de apoio.
        if (supportsServerSideFallback(effectiveModel)) {
            body.put("fallbacks", "default");
        }
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            body.put("system", request.getSystemPrompt());
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", request.getUserPrompt()));
        body.put("messages", messages);
        return body;
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

