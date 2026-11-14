package com.meetingintelligence.domain.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMRequest {
    private String systemPrompt;
    private String userPrompt;
    private Double temperature;
    private Integer maxTokens;
    private boolean jsonMode;
    private Map<String, Object> metadata;
}

