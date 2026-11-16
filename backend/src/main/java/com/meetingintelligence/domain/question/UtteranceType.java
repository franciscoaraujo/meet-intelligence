package com.meetingintelligence.domain.question;

/** Classificação de um enunciado (fala completa) na entrevista (spec 005, RF-02). */
public enum UtteranceType {
    DIRECT_QUESTION,
    INDIRECT_QUESTION,
    TECHNICAL_DISCUSSION,
    CONTEXT_COMMENT,
    CANDIDATE_ANSWER,
    OTHER,
    /**
     * O candidato apertou "Pergunta terminou": o texto é a fala do entrevistador desde a pergunta
     * anterior (pode ter comentários antes) e o copiloto sempre responde, sem descartar.
     */
    MANUAL_TRIGGER;

    public boolean isQuestion() {
        return this == DIRECT_QUESTION || this == INDIRECT_QUESTION || this == MANUAL_TRIGGER;
    }
}
