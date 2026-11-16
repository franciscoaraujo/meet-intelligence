package com.meetingintelligence.domain.question;

import com.meetingintelligence.domain.ai.TextRelevance;
import com.meetingintelligence.domain.language.InterviewLanguage;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Complemento de pergunta (spec 016, RF-01): "e mais uma coisa…", "and also…", "et si…" dito logo
 * depois de uma pergunta entra no card dela. Função pura; na dúvida, não junta (RN-01).
 */
public final class FollowUpDetector {

    /** Distância máxima entre a última parte e o complemento. */
    public static final long MAX_GAP_MS = 30_000;
    /** Uma pergunta junta no máximo 3 partes (a principal + 2 complementos). */
    public static final int MAX_PARTS = 3;
    /** Fala sem cara de pergunta só é complemento com ao menos 3 palavras depois do marcador ("e se o Redis cair"). */
    public static final int MIN_WORDS_WITHOUT_QUESTION = 3;

    private static final Map<InterviewLanguage, Pattern> MARKERS = Map.of(
            InterviewLanguage.PT_BR, Pattern.compile("^(e mais uma coisa|mais uma coisa|outra coisa|alem disso|e tambem|e ainda"
                    + "|so complementando|complementando|aproveitando|e se|e como|e qual|e quais|e por que|e porque|e quando"
                    + "|e onde|e o que)\\b"),
            InterviewLanguage.EN_US, Pattern.compile("^(and also|also|one more thing|another thing|on top of that|following up"
                    + "|and how|and what|and why|and when|and which|what if|and if)\\b"),
            InterviewLanguage.FR_FR, Pattern.compile("^(et aussi|encore une chose|autre chose|de plus|en plus|et comment"
                    + "|et pourquoi|et quand|et quel|et quelle|et si)\\b"));

    /**
     * Situação da fala nova em relação à última parte da pergunta principal.
     *
     * @param text                 texto da fala nova
     * @param language             idioma da entrevista (nulo = português)
     * @param classifiedAsQuestion o classificador já a considerou pergunta
     * @param gapMs                tempo desde a detecção da última parte
     * @param principalParts       partes que a principal já tem
     * @param candidateSpoke       o candidato falou entre as duas
     * @param newTopic             o contexto antes dela traz assunto novo (spec 015)
     */
    public record Situation(String text, InterviewLanguage language, boolean classifiedAsQuestion, long gapMs,
                            int principalParts, boolean candidateSpoke, boolean newTopic) {}

    private FollowUpDetector() {}

    public static boolean isFollowUp(Situation s) {
        if (s.gapMs() < 0 || s.gapMs() > MAX_GAP_MS) return false;
        if (s.principalParts() >= MAX_PARTS || s.candidateSpoke() || s.newTopic()) return false;
        String n = normalize(s.text());
        Matcher m = marker(s.language()).matcher(n);
        if (!m.find()) return false;
        // "e se o Redis cair" sem "?" não parece pergunta para o classificador, mas é complemento
        String rest = n.substring(m.end()).trim();
        return s.classifiedAsQuestion() || (!rest.isEmpty() && rest.split(" ").length >= MIN_WORDS_WITHOUT_QUESTION);
    }

    public static boolean startsWithMarker(String text, InterviewLanguage language) {
        return marker(language).matcher(normalize(text)).find();
    }

    private static Pattern marker(InterviewLanguage language) {
        return MARKERS.getOrDefault(language == null ? InterviewLanguage.PT_BR : language, MARKERS.get(InterviewLanguage.PT_BR));
    }

    private static String normalize(String text) {
        return TextRelevance.normalize(text == null ? "" : text).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }
}
