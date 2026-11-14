package com.meetingintelligence.domain.ai;

public interface LLMProvider {

    String getProviderName();

    String getDefaultModel();

    boolean isAvailable();

    LLMResponse generateCompletion(LLMRequest request);

    default LLMResponse generateCompletion(LLMRequest request, String overrideApiKey, String overrideModel) {
        return generateCompletion(request);
    }
}

