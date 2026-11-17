package com.meetingintelligence.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.service.SttUsageRecorder;
import com.meetingintelligence.domain.entity.LLMUsage;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.speech.FailureReason;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionChainResult;
import com.meetingintelligence.domain.speech.TranscriptionChainResult.Attempt;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.persistence.repository.LLMUsageRepository;
import com.meetingintelligence.infrastructure.speech.GrokSttProvider;
import com.meetingintelligence.infrastructure.speech.SttHttpClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.meetingintelligence.speech.WavFixtures.wav;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 017, RF-07: uso do STT do Grok em llm_usage (base para cotas e cobrança). */
class SttUsageRecorderTest {

    private final LLMUsageRepository repo = mock(LLMUsageRepository.class);
    private final SttUsageRecorder recorder = new SttUsageRecorder(repo,
            new GrokSttProvider(new SttHttpClients(1000, 1000, 1000), new ObjectMapper()));
    private final Meeting meeting = Meeting.builder().id(UUID.randomUUID())
            .organization(Organization.builder().id(UUID.randomUUID()).build()).build();

    private static TranscriptionChainResult grok(TranscriptionChainResult.Status status, long endMs) {
        List<TranscriptionSegmentDto> segments = status == TranscriptionChainResult.Status.TRANSCRIBED
                ? List.of(TranscriptionSegmentDto.builder().text("Olá").startTimeMs(0L).endTimeMs(endMs)
                        .confidence(BigDecimal.ONE).language("pt-BR").build())
                : List.of();
        return new TranscriptionChainResult(status, "GROK_STT", segments,
                List.of(new Attempt("GROK_STT", status.name(), null, null, 840)));
    }

    private LLMUsage saved() {
        ArgumentCaptor<LLMUsage> captor = ArgumentCaptor.forClass(LLMUsage.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("CA-07: áudio ao vivo de 36 s custa 36/3600 × 0,10 = US$ 0,001")
    void liveUsageFromWavDuration() {
        SttRequest live = new SttRequest(wav(36_000), "audio/wav", "pt-BR", SttRequest.Mode.LIVE);

        recorder.record(meeting, live, grok(TranscriptionChainResult.Status.TRANSCRIBED, 2_000));

        LLMUsage u = saved();
        assertThat(u.getProvider()).isEqualTo("GROK");
        assertThat(u.getModelName()).isEqualTo("grok-voice-transcribe-2.0");
        assertThat(u.getOperationType()).isEqualTo("STT_LIVE");
        assertThat(u.getTotalTokens()).isZero();
        assertThat(u.getEstimatedCostUsd()).isEqualByComparingTo("0.001000");
        assertThat(u.getLatencyMs()).isEqualTo(840L);
        assertThat(u.getMeeting()).isSameAs(meeting);
        assertThat(u.getOrganization()).isSameAs(meeting.getOrganization());
    }

    @Test
    @DisplayName("CA-07: gravação (não WAV) usa o fim da última palavra; operação STT_BATCH")
    void batchUsageFromLastWord() {
        SttRequest batch = new SttRequest(new byte[]{1, 2, 3}, "audio/webm", "pt-BR", SttRequest.Mode.BATCH);

        recorder.record(meeting, batch, grok(TranscriptionChainResult.Status.TRANSCRIBED, 3_600_000));

        LLMUsage u = saved();
        assertThat(u.getOperationType()).isEqualTo("STT_BATCH");
        assertThat(u.getEstimatedCostUsd()).isEqualByComparingTo("0.100000");
    }

    @Test
    @DisplayName("'Sem fala' também conta: a xAI cobra pelo áudio enviado")
    void noSpeechIsRecorded() {
        SttRequest live = new SttRequest(wav(5_000), "audio/wav", "pt-BR", SttRequest.Mode.LIVE);

        recorder.record(meeting, live, grok(TranscriptionChainResult.Status.NO_SPEECH, 0));

        assertThat(saved().getEstimatedCostUsd()).isPositive();
    }

    @Test
    @DisplayName("Falha, outro provedor ou reunião sem organização não registram nada")
    void notRecorded() {
        SttRequest live = new SttRequest(wav(5_000), "audio/wav", "pt-BR", SttRequest.Mode.LIVE);

        recorder.record(meeting, live, new TranscriptionChainResult(TranscriptionChainResult.Status.FAILED, null, List.of(),
                List.of(new Attempt("GROK_STT", "FAILED", FailureReason.HTTP_5XX, "HTTP 503", 10))));
        recorder.record(meeting, live, new TranscriptionChainResult(TranscriptionChainResult.Status.TRANSCRIBED, "GEMINI",
                grok(TranscriptionChainResult.Status.TRANSCRIBED, 1).segments(), List.of()));
        recorder.record(Meeting.builder().id(UUID.randomUUID()).build(), live, grok(TranscriptionChainResult.Status.TRANSCRIBED, 1));

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("Erro ao gravar o uso não derruba a transcrição")
    void saveFailureIsSwallowed() {
        when(repo.save(any())).thenThrow(new IllegalStateException("db"));
        SttRequest live = new SttRequest(wav(5_000), "audio/wav", "pt-BR", SttRequest.Mode.LIVE);

        recorder.record(meeting, live, grok(TranscriptionChainResult.Status.TRANSCRIBED, 1));
    }

    @Test
    @DisplayName("Spec 018 CA-04: Deepgram registra DEEPGRAM / nova-3 a US$ 0,258 por hora")
    void deepgramUsage() {
        SttRequest live = new SttRequest(wav(60_000), "audio/wav", "pt-BR", SttRequest.Mode.LIVE);
        TranscriptionChainResult result = new TranscriptionChainResult(TranscriptionChainResult.Status.TRANSCRIBED,
                "DEEPGRAM", grok(TranscriptionChainResult.Status.TRANSCRIBED, 1).segments(),
                List.of(new Attempt("DEEPGRAM", "TRANSCRIBED", null, null, 420)));

        recorder.record(meeting, live, result);

        LLMUsage u = saved();
        assertThat(u.getProvider()).isEqualTo("DEEPGRAM");
        assertThat(u.getModelName()).isEqualTo("nova-3");
        assertThat(u.getOperationType()).isEqualTo("STT_LIVE");
        assertThat(u.getEstimatedCostUsd()).isEqualByComparingTo("0.004300");
        assertThat(u.getLatencyMs()).isEqualTo(420L);
    }

    @Test
    @DisplayName("Spec 018 RF-02: Gemini, gpt-transcribe e STT local não têm preço e não registram")
    void providersWithoutPrice() {
        for (String p : List.of("GEMINI", "OPENAI_TRANSCRIBE", "LOCAL_GOOGLE_STT", "SIMULATION")) {
            assertThat(SttUsageRecorder.applies(new TranscriptionChainResult(TranscriptionChainResult.Status.NO_SPEECH,
                    p, List.of(), List.of()))).as(p).isFalse();
        }
        assertThat(SttUsageRecorder.applies(new TranscriptionChainResult(TranscriptionChainResult.Status.NO_SPEECH,
                "DEEPGRAM", List.of(), List.of()))).isTrue();
    }
}
