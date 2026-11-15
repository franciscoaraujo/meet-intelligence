package com.meetingintelligence.domain.question;

/** Classificação de um enunciado (fala completa) na entrevista (spec 005, RF-02). */
public enum UtteranceType {
    DIRECT_QUESTION,
    INDIRECT_QUESTION,
    TECHNICAL_DISCUSSION,
    CONTEXT_COMMENT,
    CANDIDATE_ANSWER,
    OTHER;

    public boolean isQuestion() {
        return this == DIRECT_QUESTION || this == INDIRECT_QUESTION;
    }
}
