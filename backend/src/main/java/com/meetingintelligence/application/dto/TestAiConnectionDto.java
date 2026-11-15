package com.meetingintelligence.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class TestAiConnectionDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        private String provider; // GEMINI, GROK, CLAUDE
        private String apiKey;
        private String model;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private boolean success;
        private String provider;
        private String model;
        private String message;
        private Long latencyMs;
        private String sampleResponse;
    }
}

