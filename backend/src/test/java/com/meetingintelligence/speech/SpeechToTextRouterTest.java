package com.meetingintelligence.speech;

import com.meetingintelligence.domain.speech.AudioTranscriber;
import com.meetingintelligence.domain.speech.FailureReason;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionChainResult;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.speech.SpeechToTextRouter;
import com.meetingintelligence.infrastructure.speech.SpeechToTextRouterTestAccess;
import com.meetingintelligence.infrastructure.speech.TranscriptionChainFactory.ChainStep;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.meetingintelligence.speech.WavFixtures.wav;
import static org.assertj.core.api.Assertions.assertThat;

class SpeechToTextRouterTest {

    /** Provedor programável: devolve os resultados na ordem e conta chamadas. */
    static class FakeTranscriber implements AudioTranscriber {
        final String name;
        final Deque<TranscriptionOutcome> outcomes = new ArrayDeque<>();
        int calls;
        Runnable onCall = () -> {};

        FakeTranscriber(String name, TranscriptionOutcome... outcomes) {
            this.name = name;
            this.outcomes.addAll(List.of(outcomes));
        }

        @Override
        public String providerName() {
            return name;
        }

        @Override
        public TranscriptionOutcome transcribe(SttRequest request, SttCredentials credentials) {
            calls++;
            onCall.run();
            return outcomes.size() > 1 ? outcomes.poll() : outcomes.peek();
        }
    }

    private SimpleMeterRegistry meters;
    private MutableClock clock;
    private List<Long> sleeps;
    private SpeechToTextRouter router;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        clock = new MutableClock();
        sleeps = new java.util.ArrayList<>();
        router = SpeechToTextRouterTestAccess.create(meters, clock, ms -> {
            sleeps.add(ms);
            clock.advance(ms);
        }, 500, 30_000, 300_000);
    }

    private static SttRequest liveWav(long durationMs) {
        return new SttRequest(wav(durationMs), "audio/wav", "pt-BR", SttRequest.Mode.LIVE);
    }

    private static ChainStep step(AudioTranscriber t) {
        return new ChainStep(t, new AudioTranscriber.SttCredentials("key", null));
    }

    private static TranscriptionOutcome ok(String provider, String text) {
        return new TranscriptionOutcome.Transcribed(provider, List.of(
                TranscriptionSegmentDto.builder().text(text).startTimeMs(0L).endTimeMs(0L).build()));
    }

    private static TranscriptionOutcome failed(String provider, FailureReason reason) {
        return new TranscriptionOutcome.Failed(provider, reason, reason.name());
    }

    @Test
    @DisplayName("CA-01: falha do primeiro provedor aciona o segundo, que transcreve")
    void fallsBackToNextProviderOnFailure() {
        var gemini = new FakeTranscriber("GEMINI", failed("GEMINI", FailureReason.HTTP_4XX));
        var deepgram = new FakeTranscriber("DEEPGRAM", ok("DEEPGRAM", "explique o conceito de SOLID"));

        TranscriptionChainResult result = router.transcribe(liveWav(2000), List.of(step(gemini), step(deepgram)));

        assertThat(result.status()).isEqualTo(TranscriptionChainResult.Status.TRANSCRIBED);
        assertThat(result.provider()).isEqualTo("DEEPGRAM");
        assertThat(result.segments()).extracting(TranscriptionSegmentDto::getText).containsExactly("explique o conceito de SOLID");
        assertThat(result.attempts()).extracting(TranscriptionChainResult.Attempt::provider).containsExactly("GEMINI", "DEEPGRAM");
        assertThat(result.attempts().get(0).detail()).doesNotContain("SOLID");
    }

    @Test
    @DisplayName("CA-02: todos falham → FAILED explícito, sem segmentos")
    void allProvidersFailing() {
        var a = new FakeTranscriber("A", failed("A", FailureReason.HTTP_4XX));
        var b = new FakeTranscriber("B", failed("B", FailureReason.PARSE_ERROR));

        TranscriptionChainResult result = router.transcribe(liveWav(2000), List.of(step(a), step(b)));

        assertThat(result.status()).isEqualTo(TranscriptionChainResult.Status.FAILED);
        assertThat(result.segments()).isEmpty();
        assertThat(result.failureReason()).isEqualTo(FailureReason.PARSE_ERROR);
        assertThat(meters.counter("stt.chain.exhausted").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("CA-03: 'sem fala' do primeiro provedor encerra a cadeia sem chamar os demais")
    void noSpeechStopsChain() {
        var a = new FakeTranscriber("A", new TranscriptionOutcome.NoSpeech("A"));
        var b = new FakeTranscriber("B", ok("B", "não deveria"));

        TranscriptionChainResult result = router.transcribe(liveWav(2000), List.of(step(a), step(b)));

        assertThat(result.status()).isEqualTo(TranscriptionChainResult.Status.NO_SPEECH);
        assertThat(b.calls).isZero();
    }

    @Test
    @DisplayName("CA-05/RNF-02: falha transitória tem 1 nova tentativa no mesmo provedor e depois segue a cadeia")
    void retriesTransientFailureOnceThenMovesOn() {
        var a = new FakeTranscriber("A", failed("A", FailureReason.TIMEOUT));
        var b = new FakeTranscriber("B", ok("B", "texto"));

        TranscriptionChainResult result = router.transcribe(liveWav(2000), List.of(step(a), step(b)));

        assertThat(a.calls).isEqualTo(2);
        assertThat(sleeps).containsExactly(500L);
        assertThat(result.provider()).isEqualTo("B");
    }

    @Test
    @DisplayName("Falha transitória recuperada na nova tentativa usa o mesmo provedor")
    void transientFailureRecoveredOnRetry() {
        var a = new FakeTranscriber("A", failed("A", FailureReason.HTTP_5XX), ok("A", "texto"));

        TranscriptionChainResult result = router.transcribe(liveWav(2000), List.of(step(a)));

        assertThat(a.calls).isEqualTo(2);
        assertThat(result.status()).isEqualTo(TranscriptionChainResult.Status.TRANSCRIBED);
    }

    @Test
    @DisplayName("CA-07: HTTP 4xx (ex.: 401) não tem nova tentativa")
    void noRetryOnClientError() {
        var a = new FakeTranscriber("A", failed("A", FailureReason.HTTP_4XX));

        router.transcribe(liveWav(2000), List.of(step(a)));

        assertThat(a.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("RNF-03: orçamento de tempo esgotado interrompe a cadeia com TIMEOUT_BUDGET")
    void budgetExhaustion() {
        var slow = new FakeTranscriber("SLOW", failed("SLOW", FailureReason.HTTP_4XX));
        slow.onCall = () -> clock.advance(31_000);
        var never = new FakeTranscriber("NEVER", ok("NEVER", "x"));

        TranscriptionChainResult result = router.transcribe(liveWav(2000), List.of(step(slow), step(never)));

        assertThat(result.status()).isEqualTo(TranscriptionChainResult.Status.FAILED);
        assertThat(result.failureReason()).isEqualTo(FailureReason.TIMEOUT_BUDGET);
        assertThat(never.calls).isZero();
    }

    @Test
    @DisplayName("RF-06: áudio com menos de 300 ms é NO_SPEECH sem chamar provedores")
    void tooShortAudioIsNoSpeech() {
        var a = new FakeTranscriber("A", ok("A", "x"));

        TranscriptionChainResult result = router.transcribe(liveWav(200), List.of(step(a)));

        assertThat(result.status()).isEqualTo(TranscriptionChainResult.Status.NO_SPEECH);
        assertThat(a.calls).isZero();
    }

    @Test
    @DisplayName("WAV corrompido é FAILED/INVALID_AUDIO sem chamar provedores")
    void invalidWavFailsFast() {
        var a = new FakeTranscriber("A", ok("A", "x"));
        byte[] corrupted = "RIFF____WAVEjunk".getBytes();

        TranscriptionChainResult result = router.transcribe(
                new SttRequest(corrupted, "audio/wav", "pt-BR", SttRequest.Mode.LIVE), List.of(step(a)));

        assertThat(result.failureReason()).isEqualTo(FailureReason.INVALID_AUDIO);
        assertThat(a.calls).isZero();
    }

    @Test
    @DisplayName("INVALID_AUDIO informado por um provedor interrompe a cadeia")
    void providerInvalidAudioStopsChain() {
        var a = new FakeTranscriber("A", failed("A", FailureReason.INVALID_AUDIO));
        var b = new FakeTranscriber("B", ok("B", "x"));

        router.transcribe(liveWav(2000), List.of(step(a), step(b)));

        assertThat(b.calls).isZero();
    }

    @Test
    @DisplayName("Sem provedores configurados: FAILED/NO_PROVIDER_CONFIGURED")
    void noProvidersConfigured() {
        TranscriptionChainResult result = router.transcribe(liveWav(2000), List.of());

        assertThat(result.status()).isEqualTo(TranscriptionChainResult.Status.FAILED);
        assertThat(result.failureReason()).isEqualTo(FailureReason.NO_PROVIDER_CONFIGURED);
    }

    @Test
    @DisplayName("Provedor que lança exceção vira falha e a cadeia continua")
    void providerExceptionIsContained() {
        AudioTranscriber throwing = new FakeTranscriber("BOOM") {
            @Override
            public TranscriptionOutcome transcribe(SttRequest request, SttCredentials credentials) {
                throw new IllegalStateException("bug");
            }
        };
        var b = new FakeTranscriber("B", ok("B", "texto"));

        TranscriptionChainResult result = router.transcribe(liveWav(2000), List.of(step(throwing), step(b)));

        assertThat(result.provider()).isEqualTo("B");
        assertThat(result.attempts().get(0).reason()).isEqualTo(FailureReason.UNEXPECTED);
    }

    @Test
    @DisplayName("RNF-05: métricas por provedor e resultado")
    void recordsMetrics() {
        var a = new FakeTranscriber("A", failed("A", FailureReason.HTTP_4XX));
        var b = new FakeTranscriber("B", ok("B", "texto"));

        router.transcribe(liveWav(2000), List.of(step(a), step(b)));

        assertThat(meters.counter("stt.requests", "provider", "A", "outcome", "FAILED").count()).isEqualTo(1.0);
        assertThat(meters.counter("stt.requests", "provider", "B", "outcome", "TRANSCRIBED").count()).isEqualTo(1.0);
        assertThat(meters.timer("stt.latency", "provider", "B").count()).isEqualTo(1);
    }

    /** Relógio controlado pelo teste. */
    static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000);

        void advance(long ms) {
            millis.addAndGet(ms);
        }

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
