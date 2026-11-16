package com.meetingintelligence.domain.question;

import com.meetingintelligence.domain.ai.TextRelevance;

import java.util.List;
import java.util.UUID;

/**
 * Decide se uma pergunta nova é a mesma de uma recente (spec 005, RF-06): similaridade de conteúdo
 * (Jaccard de radicais) ≥ 0,6 dentro de 120 s. A mesma pergunta só gera nova resposta se o texto
 * cresceu ≥ 30% (a pergunta ficou mais completa).
 */
public final class QuestionDeduplicator {

    public enum Kind { NEW, SAME_QUESTION, SAME_QUESTION_GREW }

    /** @param context fala do entrevistador antes da pergunta (spec 015); pode ser nula */
    public record Candidate(UUID id, String text, long createdAtMs, String context) {
        public Candidate(UUID id, String text, long createdAtMs) {
            this(id, text, createdAtMs, null);
        }
    }

    /** Contexto novo com assunto diferente: ao menos 3 radicais, menos da metade já presentes (spec 015, RF-04). */
    static final int NEW_TOPIC_MIN_STEMS = 3;
    static final double NEW_TOPIC_MAX_OVERLAP = 0.5;

    public record Decision(Kind kind, UUID existingId, double similarity) {}

    private final double similarityThreshold;
    private final long windowMs;
    private final double growthToReanswer;

    public QuestionDeduplicator(double similarityThreshold, long windowMs, double growthToReanswer) {
        this.similarityThreshold = similarityThreshold;
        this.windowMs = windowMs;
        this.growthToReanswer = growthToReanswer;
    }

    public static QuestionDeduplicator withSpecDefaults() {
        return new QuestionDeduplicator(0.6, 120_000, 0.30);
    }

    public Decision decide(String newText, long nowMs, List<Candidate> recent) {
        return decide(newText, null, nowMs, recent);
    }

    /**
     * Spec 015: a mesma frase curta ("como você resolveria isso?") depois de um cenário diferente é
     * outra pergunta, mesmo com o texto igual.
     */
    public Decision decide(String newText, String newContext, long nowMs, List<Candidate> recent) {
        Candidate best = null;
        double bestSimilarity = 0;
        for (Candidate c : recent) {
            if (nowMs - c.createdAtMs() > windowMs) continue;
            double sim = similarity(c.text(), newText);
            if (sim > bestSimilarity) {
                bestSimilarity = sim;
                best = c;
            }
        }
        if (best == null || bestSimilarity < similarityThreshold) {
            return new Decision(Kind.NEW, null, bestSimilarity);
        }
        if (introducesNewTopic(newContext, best)) {
            return new Decision(Kind.NEW, null, bestSimilarity);
        }
        // Crescimento medido pelo conteúdo (sem palavras de enquadramento como "pode explicar de novo")
        int oldSize = contentWithoutFraming(best.text()).size();
        boolean grew = oldSize > 0
                && (contentWithoutFraming(newText).size() - oldSize) / (double) oldSize >= growthToReanswer;
        return new Decision(grew ? Kind.SAME_QUESTION_GREW : Kind.SAME_QUESTION, best.id(), bestSimilarity);
    }

    static boolean introducesNewTopic(String newContext, Candidate previous) {
        if (newContext == null || newContext.isBlank()) return false;
        var stems = contentWithoutFraming(newContext);
        if (stems.size() < NEW_TOPIC_MIN_STEMS) return false;
        var known = new java.util.HashSet<>(contentWithoutFraming(previous.text()));
        if (previous.context() != null) known.addAll(contentWithoutFraming(previous.context()));
        long overlap = stems.stream().filter(known::contains).count();
        return (double) overlap / stems.size() < NEW_TOPIC_MAX_OVERLAP;
    }

    /**
     * Similaridade usada na deduplicação: Jaccard, mas uma pergunta que contém a outra quase
     * inteira (continuação: "explique SOLID" → "explique SOLID com exemplos") também conta.
     */
    static double similarity(String a, String b) {
        var sa = contentWithoutFraming(a);
        var sb = contentWithoutFraming(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0;
        var smaller = sa.size() <= sb.size() ? sa : sb;
        var larger = smaller == sa ? sb : sa;
        long contained = smaller.stream().filter(larger::contains).count();
        double containment = (double) contained / smaller.size();
        java.util.Set<String> union = new java.util.HashSet<>(sa);
        union.addAll(sb);
        double jaccard = (double) contained / union.size();
        return Math.max(jaccard, smaller.size() >= 2 && containment >= 0.9 ? 0.9 * containment : 0);
    }

    /**
     * Radicais que só enquadram a pergunta ("explique", "pode explicar", "de novo") e não dizem
     * sobre o que ela é; ignorados na comparação de conteúdo.
     */
    private static final java.util.Set<String> FRAMING_STEMS = java.util.Set.of(
            "explic", "expliq", "descre", "conte", "conta", "contar", "fale", "fala", "falar", "pode", "poderi",
            "consig", "conseg", "gostar", "gostari", "quero", "queria", "entend", "novo", "repeti", "denovo",
            "exempl", "detalh", "diga", "dizer", "compar", "pouco", "sobre");

    private static java.util.Set<String> contentWithoutFraming(String text) {
        java.util.Set<String> stems = new java.util.HashSet<>(TextRelevance.contentStems(text));
        stems.removeAll(FRAMING_STEMS);
        return stems;
    }

}
