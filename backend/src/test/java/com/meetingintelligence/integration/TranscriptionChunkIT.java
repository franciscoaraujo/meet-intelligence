package com.meetingintelligence.integration;

import com.meetingintelligence.application.service.ai.TranscriptChunkAggregatorService;
import com.meetingintelligence.domain.speech.AudioTranscriber;
import com.meetingintelligence.domain.speech.FailureReason;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import com.meetingintelligence.infrastructure.speech.TranscriptionChainFactory;
import com.meetingintelligence.infrastructure.speech.TranscriptionChainFactory.ChainStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.meetingintelligence.speech.WavFixtures.wav;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 002 ponta a ponta no backend: HTTP /chunk → cadeia de provedores (falsos, sem rede) →
 * PostgreSQL real. Valida o contrato da resposta, o fallback, a idempotência pelo índice único
 * e o isolamento entre organizações.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.s3.endpoint=http://127.0.0.1:1",
        "app.speech.retry-delay-ms=0"
})
class TranscriptionChunkIT extends AbstractPostgresIT {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private TranscriptionChainFactory chainFactory;
    @MockBean private TranscriptChunkAggregatorService aggregator;

    private final AtomicInteger primaryCalls = new AtomicInteger();
    private final AtomicInteger fallbackCalls = new AtomicInteger();
    private UUID orgId;
    private UUID meetingId;

    private AudioTranscriber fake(String name, AtomicInteger calls, TranscriptionOutcome outcome) {
        return new AudioTranscriber() {
            @Override
            public String providerName() {
                return name;
            }

            @Override
            public TranscriptionOutcome transcribe(SttRequest request, SttCredentials credentials) {
                calls.incrementAndGet();
                return outcome;
            }
        };
    }

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        userId = UUID.randomUUID();
        meetingId = UUID.randomUUID();
        jdbc.update("insert into organizations(id, name, slug) values (?, 'Org IT', ?)", orgId, "org-" + orgId);
        jdbc.update("insert into users(id, email, full_name) values (?, ?, 'Usuário IT')", userId, userId + "@it.test");
        jdbc.update("insert into meetings(id, organization_id, created_by_user_id, title) values (?, ?, ?, 'Entrevista IT')",
                meetingId, orgId, userId);

        AudioTranscriber primary = fake("GEMINI", primaryCalls,
                new TranscriptionOutcome.Failed("GEMINI", FailureReason.HTTP_5XX, "HTTP 503"));
        AudioTranscriber fallback = fake("DEEPGRAM", fallbackCalls, new TranscriptionOutcome.Transcribed("DEEPGRAM",
                List.of(TranscriptionSegmentDto.builder().text("Explique o conceito de SOLID").startTimeMs(0L).endTimeMs(0L).build())));
        AudioTranscriber.SttCredentials creds = new AudioTranscriber.SttCredentials("k", null);
        when(chainFactory.build(any(), any())).thenReturn(List.of(new ChainStep(primary, creds), new ChainStep(fallback, creds)));
    }

    private UUID userId;

    private UsernamePasswordAuthenticationToken userOf(UUID organizationId) {
        UserPrincipal principal = UserPrincipal.builder()
                .id(userId).email(userId + "@it.test").organizationId(organizationId).role("OWNER").build();
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private String body(UUID clientSegmentId) {
        return body(clientSegmentId, "TAB");
    }

    private String body(UUID clientSegmentId, String audioSource) {
        return """
                {"base64Audio":"%s","mimeType":"audio/wav","clientSegmentId":"%s",
                 "audioSource":"%s","captureStartMs":120000,"captureEndMs":124000}
                """.formatted(Base64.getEncoder().encodeToString(wav(2000)), clientSegmentId, audioSource);
    }

    @Test
    @DisplayName("CA-01/CA-06: fallback transcreve, grava uma vez e o reenvio do mesmo trecho é idempotente")
    void fallbackAndIdempotency() throws Exception {
        UUID clientSegmentId = UUID.randomUUID();

        mvc.perform(post("/api/v1/meetings/{id}/transcripts/chunk", meetingId)
                        .with(authentication(userOf(orgId)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(clientSegmentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRANSCRIBED"))
                .andExpect(jsonPath("$.provider").value("DEEPGRAM"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.attempts[0].provider").value("GEMINI"))
                .andExpect(jsonPath("$.attempts[0].reason").value("HTTP_5XX"))
                .andExpect(jsonPath("$.segments[0].originalText").value("Explique o conceito de SOLID"))
                .andExpect(jsonPath("$.segments[0].audioSource").value("TAB"))
                .andExpect(jsonPath("$.segments[0].captureStartMs").value(120000))
                .andExpect(jsonPath("$.segments[0].speakerRole").value("REMOTE"))
                .andExpect(jsonPath("$.segments[0].speakerLabel").value("Participante remoto (Meet)"));

        mvc.perform(post("/api/v1/meetings/{id}/transcripts/chunk", meetingId)
                        .with(authentication(userOf(orgId)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(clientSegmentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRANSCRIBED"))
                .andExpect(jsonPath("$.duplicate").value(true));

        Integer segments = jdbc.queryForObject(
                "select count(*) from transcript_segments where meeting_id = ?", Integer.class, meetingId);
        String source = jdbc.queryForObject(
                "select source from transcript_segments where meeting_id = ?", String.class, meetingId);
        Integer ingestions = jdbc.queryForObject(
                "select count(*) from transcript_ingestions where meeting_id = ? and client_segment_id = ?",
                Integer.class, meetingId, clientSegmentId);

        assertThat(segments).isEqualTo(1);
        assertThat(source).isEqualTo("DEEPGRAM");
        assertThat(ingestions).isEqualTo(1);
        assertThat(fallbackCalls.get()).isEqualTo(1);
        // 1 chamada + 1 retry (HTTP 5xx é transitório)
        assertThat(primaryCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA-02: sem provedores, resposta FAILED explícita e nada gravado")
    void noProviders() throws Exception {
        when(chainFactory.build(any(), any())).thenReturn(List.of());

        mvc.perform(post("/api/v1/meetings/{id}/transcripts/chunk", meetingId)
                        .with(authentication(userOf(orgId)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("NO_PROVIDER_CONFIGURED"))
                .andExpect(jsonPath("$.segments").isEmpty());

        assertThat(jdbc.queryForObject("select count(*) from transcript_segments where meeting_id = ?",
                Integer.class, meetingId)).isZero();
    }

    @Test
    @DisplayName("Reunião de outra organização: 404, sem transcrever")
    void otherOrganization() throws Exception {
        mvc.perform(post("/api/v1/meetings/{id}/transcripts/chunk", meetingId)
                        .with(authentication(userOf(UUID.randomUUID())))
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID())))
                .andExpect(status().isNotFound());

        assertThat(primaryCalls.get()).isZero();
    }

    @Test
    @DisplayName("Spec 003 CA-02/CA-03: áudio do microfone é SELF, vinculado ao participante do usuário; a aba continua REMOTE")
    void microphoneIsSelfAndTabIsRemote() throws Exception {
        mvc.perform(post("/api/v1/meetings/{id}/transcripts/chunk", meetingId)
                        .with(authentication(userOf(orgId)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), "MIC")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].speakerRole").value("SELF"))
                .andExpect(jsonPath("$.segments[0].speakerLabel").value("Usuário IT"))
                .andExpect(jsonPath("$.segments[0].audioSource").value("MIC"));

        Integer selfParticipants = jdbc.queryForObject(
                "select count(*) from meeting_participants where meeting_id = ? and email = ?",
                Integer.class, meetingId, userId + "@it.test");
        assertThat(selfParticipants).isEqualTo(1);
    }

    @Test
    @DisplayName("Spec 003: texto digitado com falante escolhido é MANUAL com confiança 1,0")
    void manualTextKeepsChosenSpeaker() throws Exception {
        mvc.perform(post("/api/v1/meetings/{id}/transcripts/live-text", meetingId)
                        .with(authentication(userOf(orgId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"speakerLabel":"Entrevistador","speakerRole":"REMOTE","text":"Explique SOLID",
                                 "startTimeMs":0,"endTimeMs":1000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audioSource").value("MANUAL"))
                .andExpect(jsonPath("$.speakerRole").value("REMOTE"))
                .andExpect(jsonPath("$.speakerConfidence").value(1))
                .andExpect(jsonPath("$.source").value("MANUAL"));
    }

    @Test
    @DisplayName("Base64 inválido: 400")
    void invalidBase64() throws Exception {
        mvc.perform(post("/api/v1/meetings/{id}/transcripts/chunk", meetingId)
                        .with(authentication(userOf(orgId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"base64Audio\":\"%%%nao-e-base64%%%\",\"mimeType\":\"audio/wav\"}"))
                .andExpect(status().isBadRequest());
    }
}
