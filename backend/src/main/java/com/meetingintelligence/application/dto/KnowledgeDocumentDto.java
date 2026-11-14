package com.meetingintelligence.application.dto;

import com.meetingintelligence.domain.enums.KnowledgeDocumentType;
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
public class KnowledgeDocumentDto {
    private UUID id;
    private String fileName;
    private String contentType;
    private KnowledgeDocumentType documentType;
    private int charCount;
    private boolean active;
    /** Primeiros caracteres do texto extraído, para o usuário conferir a leitura. */
    private String preview;
    private OffsetDateTime createdAt;
}
