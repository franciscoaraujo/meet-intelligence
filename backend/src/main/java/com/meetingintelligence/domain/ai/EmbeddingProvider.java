package com.meetingintelligence.domain.ai;

public interface EmbeddingProvider {

    String getProviderName();

    int getDimensions();

    boolean isAvailable();

    float[] generateEmbedding(String text);
}

