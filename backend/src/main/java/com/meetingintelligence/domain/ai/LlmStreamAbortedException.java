package com.meetingintelligence.domain.ai;

/**
 * O provedor falhou depois de já ter entregue parte do texto (spec 008, RF-06). Nesse ponto não há
 * fallback transparente: quem chamou decide se descarta a tentativa e tenta de novo.
 */
public class LlmStreamAbortedException extends RuntimeException {
    public LlmStreamAbortedException(String message, Throwable cause) {
        super(message, cause);
    }
}
