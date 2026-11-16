package com.meetingintelligence.domain.copilot;

import com.meetingintelligence.domain.ai.TextRelevance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Valida a resposta do copiloto (spec 004, RF-03/RF-04/RN-01). Devolve a lista de problemas; vazia = válida.
 *
 * RN-01 (heurística): frases em primeira pessoa no passado ("trabalhei", "usei", "implementei"...) só
 * podem citar tecnologias/organizações presentes na Base de Conhecimento. Limitação conhecida: não
 * detecta toda paráfrase de experiência inventada.
 */
public final class CopilotAnswerValidator {

    static final int MIN_SPOKEN_WORDS = 30;
    static final int MAX_SPOKEN_WORDS = 200;

    private static final Pattern FIRST_PERSON_EXPERIENCE = Pattern.compile(
            "\\b(trabalhei|usei|utilizei|implementei|desenvolvi|construi|liderei|atuei|migrei|criei|projetei"
                    + "|mantive|apliquei|configurei|integrei|arquitetei|participei|fui responsavel|tive experiencia"
                    + "|na minha experiencia|no meu ultimo|no projeto que|na empresa"
                    // Spec 011: inglês e francês (o copiloto responde no idioma da entrevista)
                    + "|i (have )?(worked|used|implemented|built|developed|led|designed|migrated|created|maintained|applied"
                    + "|configured|integrated|architected|deployed|shipped)|i was responsible|i ve (worked|used|built|led)"
                    + "|in my experience|in my (last|previous|current) (job|role|company|project)|at my (last|previous|current)"
                    + "|j.?ai (travaille|utilise|implemente|developpe|construit|dirige|concu|migre|cree|maintenu|applique"
                    + "|configure|integre|deploye|mis en place|participe)|j.?etais responsable|dans mon experience"
                    + "|dans mon (dernier|precedent|ancien) (poste|projet|emploi))\\b");

    /** Termos técnicos que, citados como experiência, precisam estar no currículo. */
    private static final Set<String> CLAIMABLE_TERMS = Set.of(
            "java", "spring", "kafka", "rabbitmq", "redis", "postgresql", "postgres", "mysql", "oracle", "mongodb",
            "docker", "kubernetes", "aws", "azure", "gcp", "terraform", "react", "angular", "typescript", "python",
            "golang", "kotlin", "scala", "node", "graphql", "grpc", "camel", "keycloak", "debezium", "elasticsearch",
            "cassandra", "dynamodb", "lambda", "jenkins", "gitlab", "airflow", "spark", "hadoop", "flink", "c++");

    private static final Pattern TECHNICAL_QUESTION = Pattern.compile(
            "\\b(java|spring|arquitetura|microsservic\\w*|mensageria|kafka|rabbit\\w*|sql|banco|distribuid\\w*"
                    + "|concorrencia|thread\\w*|transac\\w*|consistencia|solid|padrao|padroes|design|api|cache|escal\\w*"
                    // Spec 011: inglês e francês
                    + "|architecture|microservice\\w*|messaging|database|distributed|distribue\\w*|concurrency|concurrence"
                    + "|consistency|coherence|pattern\\w*|scalab\\w*|evolutivite|base de donnees)\\b");

    private CopilotAnswerValidator() {}

    /**
     * Spec 016, RF-05: o prompt pede 60-140 palavras (60-180 com várias partes); o validador tolera até
     * 200 (240 com várias partes), a mesma folga de 60 palavras.
     */
    public static final int TARGET_MAX_SPOKEN_WORDS = 140;
    public static final int MULTI_PART_TARGET_MAX_SPOKEN_WORDS = 180;
    static final int MULTI_PART_MAX_SPOKEN_WORDS = 240;

    /** Limite pedido no prompt. */
    public static int targetMaxSpokenWords(int parts) {
        return parts > 1 ? MULTI_PART_TARGET_MAX_SPOKEN_WORDS : TARGET_MAX_SPOKEN_WORDS;
    }

    /** Limite aceito pelo validador. */
    public static int maxSpokenWords(int parts) {
        return parts > 1 ? MULTI_PART_MAX_SPOKEN_WORDS : MAX_SPOKEN_WORDS;
    }

    public static List<String> validate(CopilotAnswer answer, String question, String knowledgeBaseText) {
        return validate(answer, question, knowledgeBaseText, 1);
    }

    public static List<String> validate(CopilotAnswer answer, String question, String knowledgeBaseText, int parts) {
        int maxWords = maxSpokenWords(parts);
        List<String> problems = new ArrayList<>();
        if (answer == null) {
            problems.add("resposta vazia");
            return problems;
        }
        if (!answer.questionForCandidate()) return problems; // não é pergunta: nada a validar
        if (answer.spokenAnswer() == null || answer.spokenAnswer().isBlank()) {
            problems.add("spoken_answer ausente");
        } else {
            int words = answer.spokenAnswer().trim().split("\\s+").length;
            if (words < MIN_SPOKEN_WORDS || words > maxWords) {
                problems.add("spoken_answer com " + words + " palavras (esperado " + MIN_SPOKEN_WORDS + "-" + maxWords + ")");
            }
        }

        CopilotAnswer.Sections s = answer.sectionsOrEmpty();
        if (isTechnical(question)) {
            if (blank(s.directAnswer()) || blank(s.technicalExplanation())
                    || (blank(s.practicalExample()) && blank(s.tradeOffs()))) {
                problems.add("pergunta técnica sem direct_answer + technical_explanation + (practical_example ou trade_offs)");
            }
        }

        List<String> invented = inventedExperience(answer, knowledgeBaseText);
        if (!invented.isEmpty()) {
            problems.add("experiência não presente no currículo: " + String.join(", ", invented));
        }
        return problems;
    }

    static boolean isTechnical(String question) {
        return question != null && TECHNICAL_QUESTION.matcher(TextRelevance.normalize(question)).find();
    }

    /** Termos citados como experiência própria que não aparecem na Base de Conhecimento. */
    static List<String> inventedExperience(CopilotAnswer answer, String knowledgeBaseText) {
        String kb = TextRelevance.normalize(knowledgeBaseText == null ? "" : knowledgeBaseText);
        CopilotAnswer.Sections s = answer.sectionsOrEmpty();
        String all = String.join(" . ", nonNull(answer.spokenAnswer()), nonNull(s.directAnswer()),
                nonNull(s.technicalExplanation()), nonNull(s.practicalExample()), nonNull(s.bestPractices()),
                nonNull(s.tradeOffs()));
        Set<String> invented = new LinkedHashSet<>();
        for (String sentence : all.split("(?<=[.!?])\\s+|\\n+")) {
            String n = TextRelevance.normalize(sentence);
            if (!FIRST_PERSON_EXPERIENCE.matcher(n).find()) continue;
            Matcher m = Pattern.compile("[a-z0-9+#]+").matcher(n);
            while (m.find()) {
                String token = m.group();
                if (CLAIMABLE_TERMS.contains(token) && !kb.contains(token)) invented.add(token);
            }
        }
        return new ArrayList<>(invented);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
