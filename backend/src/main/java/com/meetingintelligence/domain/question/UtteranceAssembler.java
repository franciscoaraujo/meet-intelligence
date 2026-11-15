package com.meetingintelligence.domain.question;

import com.meetingintelligence.domain.speech.AudioSource;
import com.meetingintelligence.domain.speech.SpeakerRole;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Junta fragmentos de fala consecutivos da MESMA origem num enunciado (spec 005, RF-04).
 * O enunciado fecha por silêncio, quando a outra origem começa a falar (resposta começou) ou ao
 * atingir o limite de palavras. Estado em memória por reunião; o tempo é sempre passado
 * explicitamente (testável sem relógio real).
 */
public final class UtteranceAssembler {

    public record Fragment(UUID segmentId, String text, AudioSource source, SpeakerRole role,
                           Long captureStartMs, Long captureEndMs, long receivedAtMs) {}

    public record Utterance(UUID meetingId, AudioSource source, SpeakerRole role, String text, List<UUID> segmentIds,
                            Long captureStartMs, Long captureEndMs, long lastFragmentAtMs) {
        public int words() {
            return text.isBlank() ? 0 : text.trim().split("\\s+").length;
        }
    }

    private record Key(UUID meetingId, AudioSource source) {}

    private static final class Open {
        final UUID meetingId;
        final AudioSource source;
        SpeakerRole role;
        final StringBuilder text = new StringBuilder();
        final List<UUID> segmentIds = new ArrayList<>();
        Long captureStartMs;
        Long captureEndMs;
        long lastAtMs;

        Open(UUID meetingId, AudioSource source) {
            this.meetingId = meetingId;
            this.source = source;
        }

        void add(Fragment f) {
            if (!text.isEmpty()) text.append(' ');
            text.append(f.text().trim());
            if (!segmentIds.contains(f.segmentId())) segmentIds.add(f.segmentId());
            if (captureStartMs == null) captureStartMs = f.captureStartMs();
            if (f.captureEndMs() != null) captureEndMs = f.captureEndMs();
            role = f.role();
            lastAtMs = f.receivedAtMs();
        }

        Utterance close() {
            return new Utterance(meetingId, source, role, text.toString(), List.copyOf(segmentIds),
                    captureStartMs, captureEndMs, lastAtMs);
        }

        int words() {
            return text.isEmpty() ? 0 : text.toString().trim().split("\\s+").length;
        }
    }

    private final long continuationGapMs;
    private final long closeSilenceMs;
    private final int maxWords;
    private final Map<Key, Open> open = new HashMap<>();

    public UtteranceAssembler(long continuationGapMs, long closeSilenceMs, int maxWords) {
        this.continuationGapMs = continuationGapMs;
        this.closeSilenceMs = closeSilenceMs;
        this.maxWords = maxWords;
    }

    /** Valores da spec: continuidade até 2,5 s, fechamento após 1,5 s de silêncio, máximo de 40 palavras. */
    public static UtteranceAssembler withSpecDefaults() {
        return new UtteranceAssembler(2500, 1500, 40);
    }

    /** Adiciona um fragmento e devolve os enunciados que fecharam por causa dele. */
    public synchronized List<Utterance> append(UUID meetingId, Fragment fragment) {
        List<Utterance> closed = new ArrayList<>();
        if (fragment.text() == null || fragment.text().isBlank()) return closed;

        // A outra origem começou a falar: fecha o enunciado aberto dela (ex.: o candidato começou a responder)
        Iterator<Map.Entry<Key, Open>> it = open.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Open> e = it.next();
            if (e.getKey().meetingId().equals(meetingId) && e.getKey().source() != fragment.source()) {
                closed.add(e.getValue().close());
                it.remove();
            }
        }

        Key key = new Key(meetingId, fragment.source());
        Open current = open.get(key);
        if (current != null && fragment.receivedAtMs() - current.lastAtMs > continuationGapMs) {
            closed.add(current.close());
            open.remove(key);
            current = null;
        }
        if (current == null) {
            current = new Open(meetingId, fragment.source());
            open.put(key, current);
        }
        current.add(fragment);
        if (current.words() >= maxWords) {
            closed.add(current.close());
            open.remove(key);
        }
        return closed;
    }

    /** Fecha os enunciados da reunião sem fala nova há pelo menos {@code closeSilenceMs}. */
    public synchronized List<Utterance> closeDue(UUID meetingId, long nowMs) {
        List<Utterance> closed = new ArrayList<>();
        Iterator<Map.Entry<Key, Open>> it = open.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Open> e = it.next();
            Open o = e.getValue();
            if (e.getKey().meetingId().equals(meetingId) && nowMs - o.lastAtMs >= closeSilenceMs) {
                closed.add(o.close());
                it.remove();
            }
        }
        return closed;
    }

    /** Fecha imediatamente o enunciado aberto de uma origem (gatilho manual). */
    public synchronized Optional<Utterance> closeNow(UUID meetingId, AudioSource source) {
        Open o = open.remove(new Key(meetingId, source));
        return Optional.ofNullable(o).map(Open::close);
    }

    public long closeSilenceMs() {
        return closeSilenceMs;
    }
}
