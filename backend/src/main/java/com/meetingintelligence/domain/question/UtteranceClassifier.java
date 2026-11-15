package com.meetingintelligence.domain.question;

import com.meetingintelligence.domain.ai.TextRelevance;
import com.meetingintelligence.domain.enums.InterviewRole;
import com.meetingintelligence.domain.speech.SpeakerRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classificador local de enunciados por regras pontuadas (spec 005, RF-02/RF-03). Sem LLM: rápido,
 * explicável (devolve os sinais usados) e calibrado pelo corpus em src/test/resources/question-corpus.csv.
 *
 * Conteúdo técnico é só reforço: a menção a um termo nunca basta para virar pergunta (RN-01).
 */
public final class UtteranceClassifier {

    /** Limiar para responder automaticamente (RF-05). */
    public static final double AUTO_ANSWER_THRESHOLD = 0.6;
    /** Entre este valor e o limiar: pergunta registrada sem resposta automática (RN-03). */
    public static final double REVIEW_THRESHOLD = 0.4;

    public record Classification(UtteranceType type, double score, List<String> signals) {
        public boolean autoAnswer() {
            return type.isQuestion() && score >= AUTO_ANSWER_THRESHOLD;
        }
    }

    private static final Pattern INTERROGATIVE_START = Pattern.compile(
            "^(o que|que|qual|quais|como|por que|porque|pq|quando|onde|quem|quanto|quantos|quantas"
                    + "|voce ja|voce conhece|voce sabe|voce tem|voce usaria|voce usou|voce prefere|vc"
                    + "|ja trabalhou|ja usou|ja teve|tem experiencia|conhece|sabe)\\b");

    /** Falas que começam com interrogativo mas são afirmações ("como eu disse...", "o que eu quero dizer..."). */
    private static final Pattern FALSE_INTERROGATIVE = Pattern.compile(
            "^(como (eu|a gente|nos|ja|falei|disse|mencionei|voces sabem)|o que (eu|a gente|nos) |que bom|que legal|quando eu)\\b");

    private static final Pattern REQUEST = Pattern.compile(
            "\\b(explique|explica|explicar|descreva|descreve|descrever|conte|conta|contar|me fale|fale sobre|fala sobre"
                    + "|fale um pouco|fala um pouco|me diga|diga|compare|compara|comparar|detalhe|detalha|cite|liste|defina"
                    + "|de um exemplo|da um exemplo|me da um exemplo|me de um exemplo"
                    + "|como voce (faria|trataria|implementaria|resolveria|lidaria|garantiria|estruturaria|testaria|modelaria|abordaria)"
                    + "|gostaria de entender|gostaria que voce|quero entender|queria entender"
                    + "|pode explicar|poderia explicar|consegue explicar|pode falar|poderia falar|pode me falar)\\b");

    private static final Pattern SECOND_PERSON = Pattern.compile("\\b(voce|voces|vc|sua|seu|suas|seus|te)\\b");

    private static final Set<String> TECH_TERMS = Set.of(
            "java", "spring", "kafka", "rabbit", "rabbitmq", "solid", "sql", "postgres", "postgresql", "docker",
            "kubernetes", "api", "rest", "grpc", "microsservico", "microsservicos", "micro", "servico", "arquitetura",
            "thread", "threads", "concorrencia", "transacao", "transacoes", "cache", "redis", "mensageria", "fila",
            "polimorfismo", "heranca", "encapsulamento", "outbox", "saga", "cap", "hashmap", "garbage", "collector",
            "banco", "dados", "indice", "teste", "testes", "tdd", "ddd", "hexagonal", "padrao", "resiliencia",
            "escalabilidade", "latencia", "consistencia", "distribuido", "distribuida", "monolito", "batch",
            "react", "typescript", "aws", "cloud", "jvm", "oauth", "jwt", "seguranca", "algoritmo", "complexidade");

    private UtteranceClassifier() {}

    /**
     * @param text          enunciado completo
     * @param speaker       quem falou (spec 003)
     * @param interviewRole papel do usuário na entrevista (nulo = candidato)
     */
    public static Classification classify(String text, SpeakerRole speaker, InterviewRole interviewRole) {
        List<String> signals = new ArrayList<>();
        String raw = text == null ? "" : text.trim();
        String n = TextRelevance.normalize(raw).replaceAll("[^a-z0-9?\\s]", " ").replaceAll("\\s+", " ").trim();

        boolean userIsCandidate = interviewRole == null || interviewRole == InterviewRole.CANDIDATE;
        if (speaker == SpeakerRole.SELF && userIsCandidate) {
            return new Classification(UtteranceType.CANDIDATE_ANSWER, 0, List.of("fala do próprio candidato"));
        }

        double score = 0;
        boolean questionMark = raw.endsWith("?") || raw.contains("? ");
        boolean interrogative = INTERROGATIVE_START.matcher(n).find() && !FALSE_INTERROGATIVE.matcher(n).find();
        boolean request = REQUEST.matcher(n).find();
        boolean secondPerson = SECOND_PERSON.matcher(n).find();
        boolean technical = containsTechnicalTerm(n);
        int words = n.isEmpty() ? 0 : n.split(" ").length;

        if (questionMark) { score += 0.45; signals.add("ponto de interrogação"); }
        if (interrogative) { score += 0.35; signals.add("início interrogativo"); }
        if (request) { score += 0.45; signals.add("pedido/imperativo"); }
        if (speaker == SpeakerRole.REMOTE && userIsCandidate) { score += 0.15; signals.add("fala do entrevistador"); }
        if (secondPerson && (questionMark || interrogative || request)) { score += 0.10; signals.add("dirigida ao candidato"); }
        if (technical) { score += 0.10; signals.add("conteúdo técnico (reforço)"); }
        score = Math.min(1.0, score);

        UtteranceType type;
        if (questionMark || interrogative) {
            type = UtteranceType.DIRECT_QUESTION;
        } else if (request) {
            type = UtteranceType.INDIRECT_QUESTION;
        } else if (technical && words >= 4) {
            type = UtteranceType.TECHNICAL_DISCUSSION;
        } else if (words < 4) {
            type = UtteranceType.OTHER;
        } else {
            type = UtteranceType.CONTEXT_COMMENT;
        }
        return new Classification(type, Math.round(score * 100) / 100.0, List.copyOf(signals));
    }

    private static boolean containsTechnicalTerm(String normalized) {
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (TECH_TERMS.contains(token)) return true;
        }
        return false;
    }
}
