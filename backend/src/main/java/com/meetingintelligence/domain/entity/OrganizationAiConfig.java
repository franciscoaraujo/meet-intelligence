package com.meetingintelligence.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "organization_ai_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationAiConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false, unique = true)
    private Organization organization;

    @Column(name = "active_provider", nullable = false, length = 50)
    @Builder.Default
    private String activeProvider = "GEMINI"; // GEMINI, OPENAI, CLAUDE

    @Column(name = "gemini_api_key", length = 500)
    private String geminiApiKey;

    @Column(name = "gemini_model", length = 100)
    @Builder.Default
    private String geminiModel = "gemini-1.5-flash";

    @Column(name = "openai_api_key", length = 500)
    private String openAiApiKey;

    @Column(name = "openai_model", length = 100)
    @Builder.Default
    private String openAiModel = "gpt-4o-mini";

    @Column(name = "claude_api_key", length = 500)
    private String claudeApiKey;

    @Column(name = "claude_model", length = 100)
    @Builder.Default
    private String claudeModel = "claude-3-5-sonnet-20241022";

    @Column(name = "temperature")
    @Builder.Default
    private Double temperature = 0.20;

    @Column(name = "use_organization_keys")
    @Builder.Default
    private Boolean useOrganizationKeys = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}

