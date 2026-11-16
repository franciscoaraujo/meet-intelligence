package com.meetingintelligence.infrastructure.speech;

import com.meetingintelligence.domain.speech.FailureReason;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionChainResult;
import com.meetingintelligence.domain.speech.TranscriptionChainResult.Attempt;
import com.meetingintelligence.domain.speech.TranscriptionChainResult.Status;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import com.meetingintelligence.infrastructure.speech.TranscriptionChainFactory.ChainStep;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executa a cadeia de provedores de STT (spec 002):
 * <ul>
 *   <li>FAILED avança para o próximo provedor; NO_SPEECH e TRANSCRIBED encerram;</li>
 *   <li>1 nova tentativa por provedor só para falhas transitórias (timeout, 429, 5xx, rede);</li>
 *   <li>orçamento de tempo total por chamada; INVALID_AUDIO interrompe a cadeia;</li>
 *   <li>trechos curtos demais são NO_SPEECH sem chamar provedores.</li>
 * </ul>
 * Logs e métricas não contêm áudio nem texto transcrito.
 */
@Component
@Slf4j
public class SpeechToTextRouter {

    static final String PRECHECK = "PRECHECK";
    static final long MIN_SPEECH_MS = 300;
    static final int MIN_NON_WAV_BYTES = 1500;

    /** Pausa entre tentativas; substituível nos testes. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final MeterRegistry meters;
    private final Clock clock;
    private final Sleeper sleeper;
    private final long retryDelayMs;
    private final long liveBudgetMs;
    private final long batchBudgetMs;

    @Autowired
    public SpeechToTextRouter(
            MeterRegistry meters,
            @Value("${app.speech.retry-delay-ms:500}") long retryDelayMs,
            @Value("${app.speech.live-budget-ms:30000}") long liveBudgetMs,
            @Value("${app.speech.batch-budget-ms:300000}") long batchBudgetMs) {
        this(meters, Clock.systemUTC(), Thread::sleep, retryDelayMs, liveBudgetMs, batchBudgetMs);
    }

    SpeechToTextRouter(MeterRegistry meters, Clock clock, Sleeper sleeper,
                       long retryDelayMs, long liveBudgetMs, long batchBudgetMs) {
        this.meters = meters;
        this.clock = clock;
        this.sleeper = sleeper;
        this.retryDelayMs = retryDelayMs;
        this.liveBudgetMs = liveBudgetMs;
        this.batchBudgetMs = batchBudgetMs;
    }

    public TranscriptionChainResult transcribe(SttRequest request, List<ChainStep> steps) {
        List<Attempt> attempts = new ArrayList<>();

        TranscriptionChainResult precheck = precheck(request, attempts);
        if (precheck != null) return precheck;

        if (steps.isEmpty()) {
            attempts.add(new Attempt(PRECHECK, "FAILED", FailureReason.NO_PROVIDER_CONFIGURED,
                    "nenhum provedor de STT com chave", 0));
            return finish(Status.FAILED, null, List.of(), attempts);
        }

        long deadline = clock.millis() + (request.mode() == SttRequest.Mode.BATCH ? batchBudgetMs : liveBudgetMs);

        for (ChainStep step : steps) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                if (clock.millis() >= deadline) {
                    attempts.add(new Attempt(step.transcriber().providerName(), "FAILED",
                            FailureReason.TIMEOUT_BUDGET, "orçamento de tempo esgotado", 0));
                    return finish(Status.FAILED, null, List.of(), attempts);
                }

                TranscriptionOutcome outcome = call(step, request, attempts);

                if (outcome instanceof TranscriptionOutcome.Transcribed t) {
                    return finish(Status.TRANSCRIBED, t.provider(), t.segments(), attempts);
                }
                if (outcome instanceof TranscriptionOutcome.NoSpeech n) {
                    return finish(Status.NO_SPEECH, n.provider(), List.of(), attempts);
                }
                TranscriptionOutcome.Failed failed = (TranscriptionOutcome.Failed) outcome;
                if (failed.reason() == FailureReason.INVALID_AUDIO) {
                    return finish(Status.FAILED, null, List.of(), attempts);
                }
                if (!failed.retryable() || attempt == 2 || !pause()) {
                    break; // próximo provedor
                }
            }
        }
        return finish(Status.FAILED, null, List.of(), attempts);
    }

    private TranscriptionChainResult precheck(SttRequest request, List<Attempt> attempts) {
        if (request.audio() == null || request.audio().length == 0) {
            attempts.add(new Attempt(PRECHECK, "FAILED", FailureReason.INVALID_AUDIO, "áudio vazio", 0));
            return finish(Status.FAILED, null, List.of(), attempts);
        }
        if (request.isWav()) {
            long durationMs = WavAudioInspector.durationMs(request.audio());
            if (durationMs < 0) {
                attempts.add(new Attempt(PRECHECK, "FAILED", FailureReason.INVALID_AUDIO, "cabeçalho WAV inválido", 0));
                return finish(Status.FAILED, null, List.of(), attempts);
            }
            if (durationMs < MIN_SPEECH_MS) {
                attempts.add(new Attempt(PRECHECK, "NO_SPEECH", null, "áudio de " + durationMs + " ms", 0));
                return finish(Status.NO_SPEECH, PRECHECK, List.of(), attempts);
            }
        } else if (request.audio().length < MIN_NON_WAV_BYTES) {
            attempts.add(new Attempt(PRECHECK, "NO_SPEECH", null, "áudio de " + request.audio().length + " bytes", 0));
            return finish(Status.NO_SPEECH, PRECHECK, List.of(), attempts);
        }
        return null;
    }

    private TranscriptionOutcome call(ChainStep step, SttRequest request, List<Attempt> attempts) {
        String provider = step.transcriber().providerName();
        long start = clock.millis();
        TranscriptionOutcome outcome;
        try {
            outcome = step.transcriber().transcribe(request, step.credentials());
        } catch (RuntimeException e) {
            // Contrato do provedor é não lançar; protege a cadeia mesmo assim
            outcome = new TranscriptionOutcome.Failed(provider, FailureReason.UNEXPECTED, e.getClass().getSimpleName());
        }
        long latency = clock.millis() - start;

        String label = switch (outcome) {
            case TranscriptionOutcome.Transcribed t -> "TRANSCRIBED";
            case TranscriptionOutcome.NoSpeech n -> "NO_SPEECH";
            case TranscriptionOutcome.Failed f -> "FAILED";
        };
        FailureReason reason = outcome instanceof TranscriptionOutcome.Failed f ? f.reason() : null;
        String detail = outcome instanceof TranscriptionOutcome.Failed f ? f.detail() : null;
        attempts.add(new Attempt(provider, label, reason, detail, latency));

        meters.counter("stt.requests", "provider", provider, "outcome", label).increment();
        Timer.builder("stt.latency").tag("provider", provider).register(meters).record(latency, TimeUnit.MILLISECONDS);

        if (reason != null) {
            log.warn("STT {} falhou: reason={} detail={} latencyMs={} bytes={}",
                    provider, reason, detail, latency, request.audio().length);
        } else {
            log.info("STT {} -> {} latencyMs={} bytes={}", provider, label, latency, request.audio().length);
        }
        return outcome;
    }

    private boolean pause() {
        try {
            sleeper.sleep(retryDelayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private TranscriptionChainResult finish(Status status, String provider,
                                            List<com.meetingintelligence.domain.speech.TranscriptionSegmentDto> segments,
                                            List<Attempt> attempts) {
        if (status == Status.FAILED) {
            meters.counter("stt.chain.exhausted").increment();
        }
        return new TranscriptionChainResult(status, provider, segments, attempts);
    }
}
