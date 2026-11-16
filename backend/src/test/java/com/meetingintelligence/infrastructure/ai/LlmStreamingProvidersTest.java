package com.meetingintelligence.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 008, T-02..T-05: leitor SSE e streaming dos três provedores contra um servidor HTTP local
 * que reproduz o formato de eventos de cada API. Nenhuma chamada externa.
 */
class LlmStreamingProvidersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** Servidor que responde com os blocos dados, com pausa opcional entre eles e corte opcional da conexão. */
    private String serve(int status, List<String> chunks, long pauseMs, boolean cutConnection) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null) auth = exchange.getRequestHeaders().getFirst("x-api-key");
            if (auth == null) auth = exchange.getRequestHeaders().getFirst("x-goog-api-key");
            lastAuthHeader.set(auth);
            exchange.getResponseHeaders().add("Content-Type", status < 400 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                for (String chunk : chunks) {
                    out.write(chunk.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if (pauseMs > 0) Thread.sleep(pauseMs);
                }
                if (cutConnection) {
                    exchange.getHttpContext().getServer().stop(0);
                    return;
                }
            } catch (InterruptedException | IOException ignored) {
                // cliente encerrou
            }
            out.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static LLMRequest jsonRequest() {
        return LLMRequest.builder().systemPrompt("sys").userPrompt("pergunta").maxTokens(500).jsonMode(true).build();
    }

    private static SseClient sse(Duration idle) {
        return new SseClient(Duration.ofSeconds(2), idle);
    }

    // ------------------------------------------------------------------ SseClient

    @Test
    @DisplayName("SseClient: junta linhas data: múltiplas, ignora comentários e entrega event/data na ordem")
    void sseParsesEvents() throws Exception {
        String url = serve(200, List.of(": keep-alive\n\n", "event: a\ndata: 1\n\n", "data: x\ndata: y\n\n", "event: b\ndata: fim"), 0, false);
        List<SseClient.Event> events = new ArrayList<>();

        sse(Duration.ofSeconds(2)).post(url, java.util.Map.of(), "{}", events::add);

        assertThat(events).containsExactly(new SseClient.Event("a", "1"), new SseClient.Event(null, "x\ny"),
                new SseClient.Event("b", "fim"));
    }

    @Test
    @DisplayName("SseClient: HTTP 4xx vira HttpStatusException com o corpo, sem eventos")
    void sseHttpError() throws Exception {
        String url = serve(401, List.of("{\"error\":{\"message\":\"chave inválida\"}}"), 0, false);
        assertThatThrownBy(() -> sse(Duration.ofSeconds(2)).post(url, java.util.Map.of(), "{}", e -> { }))
                .isInstanceOfSatisfying(SseClient.HttpStatusException.class, e -> {
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e.body()).contains("chave inválida");
                });
    }

    @Test
    @DisplayName("SseClient: sem dados por mais que o limite de inatividade → falha (timeout)")
    void sseIdleTimeout() throws Exception {
        String url = serve(200, List.of("data: 1\n\n", "data: 2\n\n"), 1500, false);
        List<SseClient.Event> events = new ArrayList<>();
        assertThatThrownBy(() -> sse(Duration.ofMillis(300)).post(url, java.util.Map.of(), "{}", events::add))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sem dados");
        assertThat(events).extracting(SseClient.Event::data).containsExactly("1");
    }

    // ------------------------------------------------------------------ Grok

    private static String grokDelta(String text) throws Exception {
        return "event: response.output_text.delta\ndata: " + MAPPER.writeValueAsString(java.util.Map.of(
                "type", "response.output_text.delta", "delta", text)) + "\n\n";
    }

    @Test
    @DisplayName("Grok: repassa os output_text.delta na ordem e usa response.completed para modelo e tokens")
    void grokStreams() throws Exception {
        String url = serve(200, List.of(
                "event: response.created\ndata: {\"type\":\"response.created\"}\n\n",
                "event: response.reasoning_summary_text.delta\ndata: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"pensando\"}\n\n",
                grokDelta("```json\n{\"spoken_answer\": \"Olá"), grokDelta(" mundo\"}\n```"),
                "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"model\":\"grok-4.20-0309-non-reasoning\",\"status\":\"completed\",\"usage\":{\"input_tokens\":100,\"output_tokens\":20,\"total_tokens\":120}}}\n\n"),
                5, false);
        GrokLlmProvider grok = new GrokLlmProvider("xai-test", "grok-4.7", MAPPER, sse(Duration.ofSeconds(2)), url);
        List<String> parts = new ArrayList<>();

        LLMResponse response = grok.stream(jsonRequest(), null, "grok-4.20-0309-non-reasoning", parts::add);

        assertThat(parts).containsExactly("```json\n{\"spoken_answer\": \"Olá", " mundo\"}\n```");
        assertThat(response.getContent()).isEqualTo("{\"spoken_answer\": \"Olá mundo\"}");
        assertThat(response.getModelName()).isEqualTo("grok-4.20-0309-non-reasoning");
        assertThat(response.getTotalTokens()).isEqualTo(120);
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer xai-test");
        assertThat(MAPPER.readTree(lastRequestBody.get()).path("stream").asBoolean()).isTrue();
        assertThat(MAPPER.readTree(lastRequestBody.get()).path("store").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("Grok: conexão encerrada sem response.completed → falha (tentativa abortada)")
    void grokCutWithoutCompletion() throws Exception {
        String url = serve(200, List.of(grokDelta("{\"spoken_answer\": \"Eu")), 0, false);
        GrokLlmProvider grok = new GrokLlmProvider("xai-test", "grok-4.7", MAPPER, sse(Duration.ofSeconds(2)), url);
        List<String> parts = new ArrayList<>();
        assertThatThrownBy(() -> grok.stream(jsonRequest(), null, null, parts::add))
                .hasMessageContaining("sem response.completed");
        assertThat(parts).hasSize(1);
    }

    @Test
    @DisplayName("Grok: HTTP 404 (modelo inexistente) falha antes de qualquer trecho, com a mensagem da API")
    void grokModelNotFound() throws Exception {
        String url = serve(404, List.of("{\"error\":\"The model grok-x does not exist\"}"), 0, false);
        GrokLlmProvider grok = new GrokLlmProvider("xai-test", "grok-4.7", MAPPER, sse(Duration.ofSeconds(2)), url);
        List<String> parts = new ArrayList<>();
        assertThatThrownBy(() -> grok.stream(jsonRequest(), null, "grok-x", parts::add))
                .hasMessageContaining("HTTP 404").hasMessageContaining("grok-x does not exist");
        assertThat(parts).isEmpty();
    }

    // ------------------------------------------------------------------ Claude

    private static String claudeEvent(String type, String json) {
        return "event: " + type + "\ndata: " + json + "\n\n";
    }

    @Test
    @DisplayName("Claude: repassa text_delta (ignora thinking), lê modelo/uso e exige message_stop")
    void claudeStreams() throws Exception {
        String url = serve(200, List.of(
                claudeEvent("message_start", "{\"type\":\"message_start\",\"message\":{\"model\":\"claude-haiku-5-5\",\"usage\":{\"input_tokens\":300,\"output_tokens\":1}}}"),
                claudeEvent("ping", "{\"type\":\"ping\"}"),
                claudeEvent("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}"),
                claudeEvent("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"{\\\"spoken_answer\\\": \\\"Oi\"}}"),
                claudeEvent("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"!\\\"}\"}}"),
                claudeEvent("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":42}}"),
                claudeEvent("message_stop", "{\"type\":\"message_stop\"}")), 0, false);
        ClaudeLlmProvider claude = new ClaudeLlmProvider("sk-ant-test", "claude-opus-5-5", MAPPER, sse(Duration.ofSeconds(2)), url);
        List<String> parts = new ArrayList<>();

        LLMResponse response = claude.stream(jsonRequest(), null, "claude-haiku-5-5", parts::add);

        assertThat(parts).containsExactly("{\"spoken_answer\": \"Oi", "!\"}");
        assertThat(response.getContent()).isEqualTo("{\"spoken_answer\": \"Oi!\"}");
        assertThat(response.getModelName()).isEqualTo("claude-haiku-5-5");
        assertThat(response.getPromptTokens()).isEqualTo(300);
        assertThat(response.getCompletionTokens()).isEqualTo(42);
        assertThat(lastAuthHeader.get()).isEqualTo("sk-ant-test");
        var body = MAPPER.readTree(lastRequestBody.get());
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.has("fallbacks")).isFalse();
        assertThat(body.has("temperature")).isFalse();
    }

    @Test
    @DisplayName("Claude: evento error no meio do streaming → falha com o tipo do erro")
    void claudeErrorEvent() throws Exception {
        String url = serve(200, List.of(
                claudeEvent("message_start", "{\"type\":\"message_start\",\"message\":{\"model\":\"claude-opus-5-5\",\"usage\":{}}}"),
                claudeEvent("error", "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}")), 0, false);
        ClaudeLlmProvider claude = new ClaudeLlmProvider("sk-ant-test", "claude-opus-5-5", MAPPER, sse(Duration.ofSeconds(2)), url);
        assertThatThrownBy(() -> claude.stream(jsonRequest(), null, null, t -> { }))
                .hasMessageContaining("overloaded_error");
    }

    @Test
    @DisplayName("Claude sem chave: simulação local em uma parte (caminho padrão do LLMProvider)")
    void claudeWithoutKey() {
        ClaudeLlmProvider claude = new ClaudeLlmProvider("mock-key", "claude-opus-5-5", MAPPER, sse(Duration.ofSeconds(2)), "http://127.0.0.1:9/");
        List<String> parts = new ArrayList<>();
        LLMResponse response = claude.stream(jsonRequest(), null, null, parts::add);
        assertThat(parts).containsExactly(response.getContent());
    }

    // ------------------------------------------------------------------ Gemini

    private static String geminiChunk(String text, String finishReason, boolean thought) throws Exception {
        var part = new java.util.HashMap<String, Object>();
        part.put("text", text);
        if (thought) part.put("thought", true);
        var candidate = new java.util.HashMap<String, Object>();
        candidate.put("content", java.util.Map.of("parts", List.of(part), "role", "model"));
        if (finishReason != null) candidate.put("finishReason", finishReason);
        var root = new java.util.HashMap<String, Object>();
        root.put("candidates", List.of(candidate));
        if (finishReason != null) {
            root.put("usageMetadata", java.util.Map.of("promptTokenCount", 50, "candidatesTokenCount", 10, "thoughtsTokenCount", 5));
        }
        return "data: " + MAPPER.writeValueAsString(root) + "\r\n\r\n";
    }

    @Test
    @DisplayName("Gemini: repassa as partes de texto (ignora thought), chave no cabeçalho e não na URL")
    void geminiStreams() throws Exception {
        String base = serve(200, List.of(geminiChunk("pensando", null, true), geminiChunk("{\"spoken_answer\": \"A", null, false),
                geminiChunk("B\"}", "STOP", false)), 0, false);
        GeminiLlmProvider gemini = new GeminiLlmProvider("AIza-test", "gemini-3.5-flash-lite", MAPPER, sse(Duration.ofSeconds(2)), base);
        List<String> parts = new ArrayList<>();

        LLMResponse response = gemini.stream(jsonRequest(), null, null, parts::add);

        assertThat(parts).containsExactly("{\"spoken_answer\": \"A", "B\"}");
        assertThat(response.getContent()).isEqualTo("{\"spoken_answer\": \"AB\"}");
        assertThat(response.getPromptTokens()).isEqualTo(50);
        assertThat(response.getCompletionTokens()).isEqualTo(15);
        assertThat(lastAuthHeader.get()).isEqualTo("AIza-test");
        assertThat(MAPPER.readTree(lastRequestBody.get()).path("generationConfig").path("responseMimeType").asText())
                .isEqualTo("application/json");
    }

    @Test
    @DisplayName("Gemini: streaming sem finishReason → falha")
    void geminiWithoutFinish() throws Exception {
        String base = serve(200, List.of(geminiChunk("{\"spoken_answer\": \"A", null, false)), 0, false);
        GeminiLlmProvider gemini = new GeminiLlmProvider("AIza-test", "gemini-3.5-flash-lite", MAPPER, sse(Duration.ofSeconds(2)), base);
        assertThatThrownBy(() -> gemini.stream(jsonRequest(), null, null, t -> { })).hasMessageContaining("sem finishReason");
    }
}
