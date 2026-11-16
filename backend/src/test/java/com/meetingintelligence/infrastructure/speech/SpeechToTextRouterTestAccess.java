package com.meetingintelligence.infrastructure.speech;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;

/** Acesso de teste ao construtor com relógio e pausa controláveis. */
public final class SpeechToTextRouterTestAccess {

    private SpeechToTextRouterTestAccess() {}

    public static SpeechToTextRouter create(MeterRegistry meters, Clock clock, SpeechToTextRouter.Sleeper sleeper,
                                            long retryDelayMs, long liveBudgetMs, long batchBudgetMs) {
        return new SpeechToTextRouter(meters, clock, sleeper, retryDelayMs, liveBudgetMs, batchBudgetMs);
    }
}
