package com.meetingintelligence.domain.ai;

public interface LLMProvider {

    String getProviderName();

    String getDefaultModel();

    boolean isAvailable();

    LLMResponse generateCompletion(LLMRequest request);

    default LLMResponse generateCompletion(LLMRequest request, String overrideApiKey, String overrideModel) {
        return generateCompletion(request);
    }

    /**
     * Gera a resposta entregando o texto em partes ao {@code listener} (spec 008, RF-01).
     * Provedores sem streaming usam esta implementação: uma única parte com o texto inteiro.
     */
    default LLMResponse stream(LLMRequest request, String overrideApiKey, String overrideModel, LlmStreamListener listener) {
        LLMResponse full = generateCompletion(request, overrideApiKey, overrideModel);
        if (full.getContent() != null && !full.getContent().isEmpty()) {
            listener.onDelta(full.getContent());
        }
        return full;
    }
}
