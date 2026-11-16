package com.meetingintelligence.application.service.question;

import java.util.UUID;

/** Gera (ou regenera) a sugestão de resposta para uma pergunta detectada (spec 004). */
public interface InterviewCopilot {
    void answer(UUID questionId);
}
