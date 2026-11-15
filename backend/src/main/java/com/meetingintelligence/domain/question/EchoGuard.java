package com.meetingintelligence.domain.question;

import com.meetingintelligence.domain.ai.TextRelevance;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detecta eco provável (spec 005, RF-07): fala do microfone muito parecida com uma fala da aba dos
 * últimos segundos — o microfone captou o alto-falante. Não substitui o fone de ouvido.
 */
public final class EchoGuard {

    private record Recent(String text, long atMs) {}

    private final long windowMs;
    private final double threshold;
    private final Map<UUID, Deque<Recent>> recentTab = new HashMap<>();

    public EchoGuard(long windowMs, double threshold) {
        this.windowMs = windowMs;
        this.threshold = threshold;
    }

    public static EchoGuard withSpecDefaults() {
        return new EchoGuard(5000, 0.8);
    }

    public synchronized void recordTab(UUID meetingId, String text, long atMs) {
        Deque<Recent> q = recentTab.computeIfAbsent(meetingId, k -> new ArrayDeque<>());
        q.addLast(new Recent(text, atMs));
        prune(q, atMs);
    }

    public synchronized boolean isEcho(UUID meetingId, String micText, long atMs) {
        Deque<Recent> q = recentTab.get(meetingId);
        if (q == null || micText == null || micText.isBlank()) return false;
        prune(q, atMs);
        for (Recent r : q) {
            if (TextRelevance.jaccard(r.text(), micText) >= threshold) return true;
        }
        return false;
    }

    private void prune(Deque<Recent> q, long nowMs) {
        while (!q.isEmpty() && nowMs - q.peekFirst().atMs() > windowMs) q.pollFirst();
    }
}
