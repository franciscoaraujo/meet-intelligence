package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.KnowledgeDocumentDto;
import com.meetingintelligence.application.service.ai.KnowledgeBaseService;
import com.meetingintelligence.domain.enums.KnowledgeDocumentType;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-base")
@RequiredArgsConstructor
@Tag(name = "Base de Conhecimento", description = "Currículo e materiais de referência usados como contexto pela IA")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping
    @Operation(summary = "Listar documentos da Base de Conhecimento")
    public ResponseEntity<List<KnowledgeDocumentDto>> list(@AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(knowledgeBaseService.list(currentUser.getOrganizationId()));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Enviar documento (PDF, DOCX, TXT, MD)",
            description = "Extrai o texto do arquivo e o disponibiliza como contexto para o Copiloto e o Chat da IA")
    public ResponseEntity<KnowledgeDocumentDto> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "documentType", defaultValue = "REFERENCE") KnowledgeDocumentType documentType,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        KnowledgeDocumentDto dto = knowledgeBaseService.upload(file, documentType, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Ativar/desativar documento ou alterar seu tipo")
    public ResponseEntity<KnowledgeDocumentDto> update(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        Boolean active = body.containsKey("active") ? Boolean.valueOf(body.get("active")) : null;
        KnowledgeDocumentType type = body.containsKey("documentType")
                ? KnowledgeDocumentType.valueOf(body.get("documentType"))
                : null;
        return ResponseEntity.ok(knowledgeBaseService.update(id, currentUser.getOrganizationId(), active, type));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remover documento da Base de Conhecimento")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal currentUser) {
        knowledgeBaseService.delete(id, currentUser.getOrganizationId());
        return ResponseEntity.noContent().build();
    }
}
