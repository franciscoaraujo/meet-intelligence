package com.meetingintelligence.domain.copilot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Sugestão de resposta do copiloto para uma pergunta de entrevista (spec 004, RF-03).
 *
 * @param questionForCandidate confirmação do LLM de que é uma pergunta dirigida ao candidato
 * @param depth                BASIC, INTERMEDIATE ou ADVANCED
 * @param spokenAnswer         versão para falar (primeira pessoa, linguagem falada)
 * @param simulated            true quando gerada pela simulação local (sem chave de IA)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CopilotAnswer(
        @JsonProperty("is_question_for_candidate") boolean questionForCandidate,
        @JsonProperty("depth") String depth,
        @JsonProperty("spoken_answer") String spokenAnswer,
        @JsonProperty("sections") Sections sections,
        @JsonProperty("resume_facts_used") List<String> resumeFactsUsed,
        @JsonProperty("assumptions") List<String> assumptions,
        @JsonProperty("confidence") Double confidence,
        @JsonProperty("simulated") boolean simulated,
        /** Pergunta como o modelo a entendeu (pedida no botão "Pergunta terminou"); opcional. */
        @JsonProperty("question") String interpretedQuestion) {

    public CopilotAnswer(boolean questionForCandidate, String depth, String spokenAnswer, Sections sections,
                         List<String> resumeFactsUsed, List<String> assumptions, Double confidence, boolean simulated) {
        this(questionForCandidate, depth, spokenAnswer, sections, resumeFactsUsed, assumptions, confidence, simulated, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sections(
            @JsonProperty("direct_answer") String directAnswer,
            @JsonProperty("technical_explanation") String technicalExplanation,
            @JsonProperty("practical_example") String practicalExample,
            @JsonProperty("best_practices") String bestPractices,
            @JsonProperty("trade_offs") String tradeOffs) {

        public static Sections empty() {
            return new Sections(null, null, null, null, null);
        }
    }

    public Sections sectionsOrEmpty() {
        return sections != null ? sections : Sections.empty();
    }

    /** Texto completo para o card (versão falada + seções), em markdown simples. */
    public String toDisplayText() {
        StringBuilder sb = new StringBuilder(spokenAnswer == null ? "" : spokenAnswer.trim());
        Sections s = sectionsOrEmpty();
        append(sb, "Resposta direta", s.directAnswer());
        append(sb, "Explicação técnica", s.technicalExplanation());
        append(sb, "Exemplo prático", s.practicalExample());
        append(sb, "Boas práticas", s.bestPractices());
        append(sb, "Trade-offs", s.tradeOffs());
        return sb.toString();
    }

    private static void append(StringBuilder sb, String title, String body) {
        if (body != null && !body.isBlank()) sb.append("\n\n**").append(title).append(":** ").append(body.trim());
    }
}
