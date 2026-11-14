package com.meetingintelligence.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.ai.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Random;

@Component
@Slf4j
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private final String apiKey;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OpenAiEmbeddingProvider(
            @Value("${app.ai.openai.api-key:mock-key}") String apiKey,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderName() {
        return "OPENAI_EMBEDDINGS";
    }

    @Override
    public int getDimensions() {
        return 1536;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.equals("mock-key");
    }

    @Override
    public float[] generateEmbedding(String text) {
        if (!isAvailable()) {
            return generateMockEmbedding(text);
        }

        try {
            String url = "https://api.openai.com/v1/embeddings";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = Map.of(
                    "model", "text-embedding-3-small",
                    "input", text
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode embeddingArray = root.path("data").get(0).path("embedding");

                float[] vector = new float[1536];
                for (int i = 0; i < 1536 && i < embeddingArray.size(); i++) {
                    vector[i] = (float) embeddingArray.get(i).asDouble();
                }
                return vector;
            }
        } catch (Exception e) {
            log.error("Erro ao gerar embedding na OpenAI: {}", e.getMessage());
        }

        return generateMockEmbedding(text);
    }

    private float[] generateMockEmbedding(String text) {
        float[] vector = new float[1536];
        long seed = text.hashCode();
        Random random = new Random(seed);

        double sumSquares = 0.0;
        for (int i = 0; i < 1536; i++) {
            vector[i] = (float) (random.nextGaussian());
            sumSquares += vector[i] * vector[i];
        }

        // Normalização L2 para garantir comprimento unitário
        double norm = Math.sqrt(sumSquares);
        for (int i = 0; i < 1536; i++) {
            vector[i] = (float) (vector[i] / norm);
        }

        return vector;
    }
}

