package com.meetingintelligence.infrastructure.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Cliente mínimo de Server-Sent Events sobre o {@link HttpClient} do JDK (spec 008, RNF-04: sem
 * dependência nova). Faz um POST com corpo JSON e entrega cada evento ({@code event:} + {@code data:})
 * na ordem. Se nenhuma linha chegar dentro de {@code idleTimeout}, a conexão é fechada e a leitura falha.
 */
public class SseClient {

    public record Event(String event, String data) {}

    /** Resposta HTTP de erro (4xx/5xx) antes de qualquer evento. */
    public static class HttpStatusException extends IllegalStateException {
        private final int status;
        private final String body;

        public HttpStatusException(int status, String body) {
            super("HTTP " + status);
            this.status = status;
            this.body = body;
        }

        public int status() { return status; }
        public String body() { return body; }
    }

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-idle-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient http;
    private final Duration idleTimeout;

    public SseClient(Duration connectTimeout, Duration idleTimeout) {
        this(HttpClient.newBuilder().connectTimeout(connectTimeout).build(), idleTimeout);
    }

    public SseClient(HttpClient http, Duration idleTimeout) {
        this.http = http;
        this.idleTimeout = idleTimeout;
    }

    public void post(String url, Map<String, String> headers, String jsonBody, Consumer<Event> onEvent) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        headers.forEach(request::header);

        HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("Falha de rede no streaming: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Streaming interrompido", e);
        }

        InputStream body = response.body();
        if (response.statusCode() >= 400) {
            String errorBody;
            try (body) {
                errorBody = new String(body.readNBytes(16_384), StandardCharsets.UTF_8);
            } catch (IOException e) {
                errorBody = "";
            }
            throw new HttpStatusException(response.statusCode(), errorBody);
        }

        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        AtomicBoolean timedOut = new AtomicBoolean(false);
        long idleNanos = idleTimeout.toNanos();
        long checkMs = Math.max(10, Math.min(1000, idleTimeout.toMillis() / 4));
        ScheduledFuture<?> watchdog = WATCHDOG.scheduleAtFixedRate(() -> {
            if (System.nanoTime() - lastActivity.get() > idleNanos && timedOut.compareAndSet(false, true)) {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // a leitura em andamento falha e encerra o streaming
                }
            }
        }, checkMs, checkMs, TimeUnit.MILLISECONDS);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String eventName = null;
            StringBuilder data = null;
            String line;
            while ((line = reader.readLine()) != null) {
                lastActivity.set(System.nanoTime());
                if (line.isEmpty()) {
                    if (data != null) onEvent.accept(new Event(eventName, data.toString()));
                    eventName = null;
                    data = null;
                } else if (line.startsWith(":")) {
                    // comentário/keep-alive
                } else if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    if (value.startsWith(" ")) value = value.substring(1);
                    data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
                }
            }
            if (timedOut.get()) {
                throw new IllegalStateException("Streaming sem dados por mais de " + idleTimeout.toSeconds() + " s");
            }
            if (data != null) onEvent.accept(new Event(eventName, data.toString()));
        } catch (IOException e) {
            if (timedOut.get()) {
                throw new IllegalStateException("Streaming sem dados por mais de " + idleTimeout.toMillis() + " ms", e);
            }
            throw new IllegalStateException("Conexão de streaming interrompida: " + e.getMessage(), e);
        } finally {
            watchdog.cancel(false);
        }
    }
}
