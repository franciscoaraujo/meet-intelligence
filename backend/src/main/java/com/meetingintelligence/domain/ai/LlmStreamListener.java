package com.meetingintelligence.domain.ai;

/**
 * Recebe o texto de uma resposta de LLM em partes, na ordem em que o provedor as envia (spec 008).
 * O texto completo, o modelo e o uso de tokens chegam no {@link LLMResponse} devolvido no fim.
 */
@FunctionalInterface
public interface LlmStreamListener {
    void onDelta(String text);
}
