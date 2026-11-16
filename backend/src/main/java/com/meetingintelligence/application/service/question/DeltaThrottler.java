package com.meetingintelligence.application.service.question;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Agrupa trechos de texto para publicar no máximo um evento a cada {@code intervalNanos}
 * (spec 008, RNF-03). O primeiro trecho sai na hora (tempo até o primeiro texto) e
 * {@link #flush()} envia o que sobrou. Não usa thread própria: roda na thread que lê o streaming.
 */
public final class DeltaThrottler {

    private final long intervalNanos;
    private final LongSupplier clock;
    private final Consumer<String> sink;
    private final StringBuilder buffer = new StringBuilder();
    private long lastEmit = Long.MIN_VALUE;

    public DeltaThrottler(long intervalNanos, LongSupplier clock, Consumer<String> sink) {
        this.intervalNanos = intervalNanos;
        this.clock = clock;
        this.sink = sink;
    }

    public void offer(String text) {
        if (text == null || text.isEmpty()) return;
        buffer.append(text);
        long now = clock.getAsLong();
        if (lastEmit == Long.MIN_VALUE || now - lastEmit >= intervalNanos) {
            emit(now);
        }
    }

    public void flush() {
        if (!buffer.isEmpty()) emit(clock.getAsLong());
    }

    /** Descarta o texto pendente (tentativa abortada). */
    public void discard() {
        buffer.setLength(0);
    }

    private void emit(long now) {
        String text = buffer.toString();
        buffer.setLength(0);
        lastEmit = now;
        sink.accept(text);
    }
}
