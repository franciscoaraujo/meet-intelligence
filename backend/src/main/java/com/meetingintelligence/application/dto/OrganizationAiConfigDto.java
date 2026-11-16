package com.meetingintelligence.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationAiConfigDto {

    private UUID organizationId;
    private String activeProvider; // GEMINI, GROK, CLAUDE
    
    // Status e chave mascarada
    private boolean geminiConfigured;
    private String geminiMaskedKey;
    private String geminiModel;

    private boolean grokConfigured;
    private String grokMaskedKey;
    private String grokModel;

    private boolean claudeConfigured;
    private String claudeMaskedKey;
    private String claudeModel;

    private Double temperature;
    private Boolean useOrganizationKeys;
    private OffsetDateTime updatedAt;

    public static String maskApiKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        if (rawKey.length() <= 8) {
            return "••••••••";
        }
        String prefix = rawKey.substring(0, Math.min(6, rawKey.length() / 3));
        String suffix = rawKey.substring(rawKey.length() - 4);
        return prefix + "••••••••••••" + suffix;
    }
}

