package com.meetingintelligence.application.service.question;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Implementação provisória até a spec 004 (copiloto dedicado): apenas registra o pedido. */
@Component
@Slf4j
public class PendingInterviewCopilot implements InterviewCopilot {
    @Override
    public void answer(UUID questionId) {
        log.info("Resposta solicitada para a pergunta {} (copiloto dedicado: spec 004)", questionId);
    }
}
