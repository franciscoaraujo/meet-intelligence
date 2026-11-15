package com.meetingintelligence.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAiConfigRequest {

    private String activeProvider; // GEMINI, GROK, CLAUDE

    // Chaves opcionais (se vier em branco ou mascarado, mantém o valor atual no banco)
    private String geminiApiKey;
    private String geminiModel;

    private String grokApiKey;
    private String grokModel;

    private String claudeApiKey;
    private String claudeModel;

    private Double temperature;
    private Boolean useOrganizationKeys;
}

