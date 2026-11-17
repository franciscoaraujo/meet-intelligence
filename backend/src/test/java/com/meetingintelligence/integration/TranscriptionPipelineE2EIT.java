package com.meetingintelligence.integration;

import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.speech.AudioTranscriber;
import com.meetingintelligence.domain.speech.FailureReason;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.ai.AIOrchestrator;
import com.meetingintelligence.infrastructure.security.JwtService;
import com.meetingintelligence.infrastructure.speech.TranscriptionChainFactory;
import com.meetingintelligence.infrastructure.speech.TranscriptionChainFactory.ChainStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.meetingintelligence.speech.WavFixtures.wav;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Spec 006 (T-07): pipeline ponta a ponta no backend, sem rede externa.
 * HTTP /chunk (aba) → STT principal falha → fallback transcreve → segmento no WebSocket →
 * pergunta detectada após o silêncio → copiloto (LLM simulado por mock) → card no WebSocket.
 * Banco PostgreSQL real (Testcontainers) e cliente STOMP real via SockJS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"app.s3.endpoint=http://127.0.0.1:1", "app.speech.retry-delay-ms=0"})
class TranscriptionPipelineE2EIT extends AbstractPostgresIT {

    private static final String COPILOT_JSON = """
            {"is_question_for_candidate": true, "depth": "INTERMEDIATE",
             "spoken_answer": "O SOLID é um conjunto de cinco princípios de design orientado a objetos. Responsabilidade única, aberto para extensão e fechado para modificação, substituição de Liskov, segregação de interfaces e inversão de dependência. Na prática eles deixam o código mais fácil de testar e de evoluir, porque cada classe muda por um motivo só.",
             "sections": {"direct_answer": "Cinco princípios de design orientado a objetos.",
                          "technical_explanation": "SRP, OCP, LSP, ISP e DIP.",
                          "practical_example": "Separar a regra de negócio do acesso a dados num serviço Spring."},
             "resume_facts_used": [], "assumptions": ["'sólido' interpretado como SOLID"], "confidence": 0.9}
            """;

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;

    @MockBean private TranscriptionChainFactory chainFactory;
    @MockBean private AIOrchestrator orchestrator;

    private final List<String> transcriptsTopic = new CopyOnWriteArrayList<>();
    private final List<String> questionsTopic = new CopyOnWriteArrayList<>();
    private final List<String> insightsTopic = new CopyOnWriteArrayList<>();
    private final List<String> copilotTopic = new CopyOnWriteArrayList<>();
    private final AtomicInteger primaryCalls = new AtomicInteger();
    private volatile String nextTranscript;
    private StompSession stomp;
    private UUID meetingId;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        meetingId = UUID.randomUUID();
        jdbc.update("insert into organizations(id, name, slug) values (?, 'Org E2E', ?)", orgId, "e2e-" + orgId);
        jdbc.update("insert into users(id, email, full_name) values (?, ?, 'Candidato E2E')", userId, userId + "@e2e.test");
        jdbc.update("insert into meetings(id, organization_id, created_by_user_id, title) values (?, ?, ?, 'Entrevista E2E')",
                meetingId, orgId, userId);
        token = jwtService.generateToken(userId, userId + "@e2e.test", orgId, "OWNER");

        AudioTranscriber primary = new AudioTranscriber() {
            public String providerName() { return "GEMINI"; }
            public TranscriptionOutcome transcribe(SttRequest r, SttCredentials c) {
                primaryCalls.incrementAndGet();
                return new TranscriptionOutcome.Failed("GEMINI", FailureReason.HTTP_5XX, "HTTP 503");
            }
        };
        AudioTranscriber fallback = new AudioTranscriber() {
            public String providerName() { return "DEEPGRAM"; }
            public TranscriptionOutcome transcribe(SttRequest r, SttCredentials c) {
                return new TranscriptionOutcome.Transcribed("DEEPGRAM", List.of(TranscriptionSegmentDto.builder()
                        .text(nextTranscript).startTimeMs(0L).endTimeMs(0L).build()));
            }
        };
        var creds = new AudioTranscriber.SttCredentials("k", null);
        when(chainFactory.build(any(), any())).thenReturn(List.of(new ChainStep(primary, creds), new ChainStep(fallback, creds)));
        // Copiloto falso em streaming (spec 008): entrega o JSON em pedaços de 16 caracteres
        when(orchestrator.executeStreaming(any(LLMRequest.class), any(), eq("INTERVIEW_ANSWER"), any())).thenAnswer(inv -> {
            com.meetingintelligence.domain.ai.LlmStreamListener listener = inv.getArgument(3);
            for (int i = 0; i < COPILOT_JSON.length(); i += 16) {
                listener.onDelta(COPILOT_JSON.substring(i, Math.min(COPILOT_JSON.length(), i + 16)));
            }
            return
                LLMResponse.builder().providerName("CLAUDE").modelName("claude-opus-5-5").content(COPILOT_JSON).build();
        });

        WebSocketStompClient client = new WebSocketStompClient(new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        // O servidor publica application/json; o conversor padrão de String só aceita text/plain
        client.setMessageConverter(new StringMessageConverter() {
            @Override
            protected boolean supportsMimeType(org.springframework.messaging.MessageHeaders headers) {
                return true;
            }
        });
        // Spec 007 RF-07: o CONNECT leva o token; sem ele a conexão é recusada
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        stomp = client.connectAsync("http://localhost:" + port + "/ws",
                        new org.springframework.web.socket.WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        subscribe("transcripts", transcriptsTopic);
        subscribe("questions", questionsTopic);
        subscribe("insights", insightsTopic);
        subscribe("copilot", copilotTopic);
        Thread.sleep(300); // garante as inscrições antes das mensagens
    }

    @AfterEach
    void tearDown() {
        if (stomp != null && stomp.isConnected()) stomp.disconnect();
    }

    private void subscribe(String topic, List<String> sink) {
        stomp.subscribe("/topic/meetings/" + meetingId + "/" + topic, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                sink.add((String) payload);
            }
        });
    }

    private ResponseEntity<Map> sendChunk(String text, String source, UUID clientSegmentId) {
        nextTranscript = text;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        String body = """
                {"base64Audio":"%s","mimeType":"audio/wav","clientSegmentId":"%s","audioSource":"%s",
                 "captureStartMs":60000,"captureEndMs":63000}
                """.formatted(Base64.getEncoder().encodeToString(wav(2000)), clientSegmentId, source);
        return rest.postForEntity("/api/v1/meetings/{id}/transcripts/chunk", new HttpEntity<>(body, headers), Map.class, meetingId);
    }

    @Test
    @DisplayName("CA-03: falha do STT principal → fallback → segmento, pergunta e resposta do copiloto chegam pelo WebSocket")
    void fullPipeline() {
        ResponseEntity<Map> response = sendChunk("explique o conceito de sólido", "TAB", UUID.randomUUID());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("status", "TRANSCRIBED").containsEntry("provider", "DEEPGRAM");
        assertThat(primaryCalls.get()).isEqualTo(2); // chamada + 1 retry (5xx é transitório)

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(transcriptsTopic).anyMatch(m -> m.contains("explique o conceito de sólido") && m.contains("\"speakerRole\":\"REMOTE\""));
            assertThat(questionsTopic).anyMatch(m -> m.contains("\"status\":\"ANSWERED\""));
            assertThat(insightsTopic).anyMatch(m -> m.contains("\"category\":\"QUESTION\"") && m.contains("cinco princípios"));
            // Spec 008 CA-01: STARTED, trechos da spoken_answer (sem JSON) e COMPLETED pelo WebSocket
            assertThat(copilotTopic).anyMatch(m -> m.contains("\"type\":\"STARTED\""));
            assertThat(copilotTopic).anyMatch(m -> m.contains("\"type\":\"COMPLETED\"") && m.contains("insightId"));
            assertThat(copilotTopic).filteredOn(m -> m.contains("\"type\":\"DELTA\"")).isNotEmpty()
                    .noneMatch(m -> m.contains("spoken_answer") || m.contains("is_question_for_candidate"));
        });
        verify(orchestrator, times(1)).executeStreaming(any(), any(), eq("INTERVIEW_ANSWER"), any());
        assertThat(jdbc.queryForObject("select count(*) from interview_questions where meeting_id = ?", Integer.class, meetingId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("CA-04: reenvio do mesmo trecho não duplica segmento, pergunta nem resposta")
    void idempotentResend() {
        UUID clientSegmentId = UUID.randomUUID();
        sendChunk("qual a diferença entre Kafka e RabbitMQ?", "TAB", clientSegmentId);
        ResponseEntity<Map> again = sendChunk("qual a diferença entre Kafka e RabbitMQ?", "TAB", clientSegmentId);
        assertThat(again.getBody()).containsEntry("duplicate", true);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(insightsTopic).anyMatch(m -> m.contains("\"category\":\"QUESTION\"")));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from transcript_segments where meeting_id = ?", Integer.class, meetingId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from interview_questions where meeting_id = ?", Integer.class, meetingId)).isEqualTo(1);
        });
        verify(orchestrator, times(1)).executeStreaming(any(), any(), eq("INTERVIEW_ANSWER"), any());
    }

    @Test
    @DisplayName("Fala do candidato (microfone) é transcrita como SELF e não aciona o copiloto")
    void candidateSpeechDoesNotTriggerCopilot() {
        sendChunk("o SOLID são cinco princípios de design", "MIC", UUID.randomUUID());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(transcriptsTopic).anyMatch(m -> m.contains("\"speakerRole\":\"SELF\"")));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from interview_questions where meeting_id = ?", Integer.class, meetingId)).isZero());
        verify(orchestrator, never()).executeStreaming(any(), any(), eq("INTERVIEW_ANSWER"), any());
    }
}
