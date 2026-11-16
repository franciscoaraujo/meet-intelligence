package com.meetingintelligence.domain.question;

import com.meetingintelligence.domain.ai.TextRelevance;
import com.meetingintelligence.domain.enums.InterviewRole;
import com.meetingintelligence.domain.language.InterviewLanguage;
import com.meetingintelligence.domain.speech.SpeakerRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classificador local de enunciados por regras pontuadas (spec 005, RF-02/RF-03). Sem LLM: rápido,
 * explicável (devolve os sinais usados) e calibrado pelos corpus em src/test/resources/question-corpus*.csv.
 *
 * Regras por idioma (spec 011): português, inglês e francês. A pontuação é a mesma nos três.
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

    /**
     * Regras de um idioma, aplicadas ao texto normalizado (minúsculas, sem acento e sem pontuação, exceto "?").
     *
     * @param interrogativeStart começo interrogativo ("como", "what", "comment")
     * @param falseInterrogative afirmações que começam como pergunta ("como eu disse", "as I said")
     * @param request            pedidos/imperativos ("explique", "tell me", "expliquez")
     * @param embeddedQuestion   pergunta no meio da fala, depois de um comentário ("...how would you...")
     * @param secondPerson       dirigida ao candidato ("você", "you", "vous")
     */
    record Rules(Pattern interrogativeStart, Pattern falseInterrogative, Pattern request, Pattern embeddedQuestion,
                 Pattern secondPerson) {}

    private static final Rules PORTUGUESE = new Rules(
            Pattern.compile("^(o que|que|qual|quais|como|por que|porque|pq|quando|onde|quem|quanto|quantos|quantas"
                    + "|voce ja|voce conhece|voce sabe|voce tem|voce usaria|voce usou|voce prefere|vc"
                    + "|ja trabalhou|ja usou|ja teve|tem experiencia|conhece|sabe)\\b"),
            // Falas que começam com interrogativo mas são afirmações ("como eu disse...", "como você pode ver...")
            Pattern.compile("^(como (eu|a gente|nos|ja|falei|disse|mencionei|voces sabem)|o que (eu|a gente|nos) |que bom|que legal|quando eu"
                    + "|como (voce|vc) (pode ver|ve|viu|mencionou|disse|falou|comentou|sabe|bem sabe|deve saber|ja sabe))\\b"),
            Pattern.compile("\\b(explique|explica|explicar|descreva|descreve|descrever|conte|conta|contar|me fale|fale sobre|fala sobre"
                    + "|fale um pouco|fala um pouco|me diga|diga|compare|compara|comparar|detalhe|detalha|cite|liste|defina"
                    + "|de um exemplo|da um exemplo|me da um exemplo|me de um exemplo"
                    + "|como voce (faria|trataria|implementaria|resolveria|lidaria|garantiria|estruturaria|testaria|modelaria|abordaria)"
                    + "|gostaria de entender|gostaria que voce|quero entender|queria entender"
                    + "|pode explicar|poderia explicar|consegue explicar|pode falar|poderia falar|pode me falar)\\b"),
            // Pergunta no meio da fala (entrevista real de 2026-11-15): interrogativo + "você" + verbo no condicional
            Pattern.compile("\\b(como|qual|quais|que|por que|o que|quando|onde|quanto)( [a-z0-9]+){0,3} (voce|vc|voces) [a-z]+(ria|riam)\\b"),
            Pattern.compile("\\b(voce|voces|vc|sua|seu|suas|seus|te)\\b"));

    private static final Rules ENGLISH = new Rules(
            Pattern.compile("^(what|whats|how|why|when|where|who|whom|which|can you|could you|would you|will you|do you|did you"
                    + "|have you|are you|were you|is there|tell me|walk me through|describe|explain|give me)\\b"),
            Pattern.compile("^(how (i|we) |what (i|we) (mean|meant|said|want)|as (i|we) (said|mentioned)|when (i|we) "
                    + "|how you (can see|saw|mentioned|said|know)|what you (said|mentioned))"),
            Pattern.compile("\\b(explain|describe|tell me|walk me through|talk (me )?about|give me an example|give an example"
                    + "|compare|define|elaborate|share an example"
                    + "|how would you|what would you|how do you|how did you|how can you"
                    + "|(can|could) you (explain|describe|tell|walk|talk|give|share)|i would like to (know|understand|hear))\\b"),
            Pattern.compile("\\b(how|what|why|which|when|where)( [a-z0-9]+){0,3} (would|do|did|could|will|should|have) you\\b"),
            Pattern.compile("\\b(you|your|yours|yourself)\\b"));

    private static final Rules FRENCH = new Rules(
            Pattern.compile("^(qu est ce|qu est|quest ce|que pensez|comment|pourquoi|quand|ou est|ou se|quel|quelle|quels|quelles"
                    + "|qui|combien|est ce que|pouvez vous|peux tu|pourriez vous|pourrais tu|avez vous|as tu|connaissez vous"
                    + "|connais tu|expliquez|explique|decrivez|decris|parlez moi|parle moi|racontez|raconte)\\b"),
            Pattern.compile("^(comme je|comme on|comment je|quand je|ce que je|comme vous (l avez dit|avez dit|le savez|pouvez voir))"),
            Pattern.compile("\\b(expliquez|explique moi|expliquer|decrivez|decris moi|parlez moi|parle moi|racontez|raconte moi"
                    + "|donnez (moi )?un exemple|donne moi un exemple|comparez|definissez|detaillez"
                    + "|comment (vous|tu) (feriez|ferais|geriez|gererais|utiliseriez|utiliserais|concevriez|garantiriez|aborderiez|organiseriez)"
                    + "|j aimerais (savoir|comprendre)|pouvez vous (expliquer|decrire|parler|donner)|pourriez vous (expliquer|decrire|parler))\\b"),
            // "comment vous feriez..." ou com inversão: "comment surveilleriez-vous...", "quelle architecture utiliseriez-vous..."
            Pattern.compile("\\b(comment|pourquoi|quel|quelle|quels|quelles|quand|ou)( [a-z0-9]+){0,3} "
                    + "((vous|tu) [a-z]+(riez|rais|rait)|[a-z]+(riez|rais|rait|ez) (vous|tu))\\b"),
            Pattern.compile("\\b(vous|tu|votre|vos|ton|ta|tes|toi)\\b"));

    private static final Map<InterviewLanguage, Rules> RULES = Map.of(
            InterviewLanguage.PT_BR, PORTUGUESE, InterviewLanguage.EN_US, ENGLISH, InterviewLanguage.FR_FR, FRENCH);

    private static final Set<String> TECH_TERMS = Set.of(
            "java", "spring", "kafka", "rabbit", "rabbitmq", "solid", "sql", "postgres", "postgresql", "docker",
            "kubernetes", "api", "rest", "grpc", "microsservico", "microsservicos", "micro", "servico", "arquitetura",
            "thread", "threads", "concorrencia", "transacao", "transacoes", "cache", "redis", "mensageria", "fila",
            "polimorfismo", "heranca", "encapsulamento", "outbox", "saga", "cap", "hashmap", "garbage", "collector",
            "banco", "dados", "indice", "teste", "testes", "tdd", "ddd", "hexagonal", "padrao", "resiliencia",
            "escalabilidade", "latencia", "consistencia", "distribuido", "distribuida", "monolito", "batch",
            "react", "typescript", "aws", "cloud", "jvm", "oauth", "jwt", "seguranca", "algoritmo", "complexidade",
            // Spec 011: inglês e francês
            "microservice", "microservices", "architecture", "messaging", "queue", "database", "transaction",
            "transactions", "consistency", "scalability", "latency", "resilience", "distributed", "concurrency",
            "testing", "security", "pattern", "patterns", "donnees", "base", "securite",
            "concurrence", "distribue", "distribuee", "evolutivite", "latence", "file");

    private UtteranceClassifier() {}

    /** Português (compatibilidade com a spec 005). */
    public static Classification classify(String text, SpeakerRole speaker, InterviewRole interviewRole) {
        return classify(text, speaker, interviewRole, InterviewLanguage.PT_BR);
    }

    /**
     * @param text          enunciado completo
     * @param speaker       quem falou (spec 003)
     * @param interviewRole papel do usuário na entrevista (nulo = candidato)
     * @param language      idioma da entrevista (spec 011); nulo = português
     */
    public static Classification classify(String text, SpeakerRole speaker, InterviewRole interviewRole, InterviewLanguage language) {
        Rules rules = RULES.getOrDefault(language == null ? InterviewLanguage.PT_BR : language, PORTUGUESE);
        List<String> signals = new ArrayList<>();
        String raw = text == null ? "" : text.trim();
        String n = TextRelevance.normalize(raw).replaceAll("[^a-z0-9?\\s]", " ").replaceAll("\\s+", " ").trim();

        boolean userIsCandidate = interviewRole == null || interviewRole == InterviewRole.CANDIDATE;
        if (speaker == SpeakerRole.SELF && userIsCandidate) {
            return new Classification(UtteranceType.CANDIDATE_ANSWER, 0, List.of("fala do próprio candidato"));
        }

        double score = 0;
        boolean questionMark = raw.endsWith("?") || raw.contains("? ");
        boolean falseInterrogative = rules.falseInterrogative().matcher(n).find();
        boolean interrogative = rules.interrogativeStart().matcher(n).find() && !falseInterrogative;
        if (!interrogative && rules.embeddedQuestion().matcher(n).find()) {
            interrogative = true;
            signals.add("pergunta no meio da fala");
        }
        boolean request = rules.request().matcher(n).find() && !falseInterrogative;
        boolean secondPerson = rules.secondPerson().matcher(n).find();
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
