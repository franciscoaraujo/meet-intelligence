package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.OrganizationAiConfigDto;
import com.meetingintelligence.application.dto.TestAiConnectionDto;
import com.meetingintelligence.application.dto.UpdateAiConfigRequest;
import com.meetingintelligence.application.service.OrganizationAiConfigService;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organization/ai-config")
@RequiredArgsConstructor
@Tag(name = "Configuração de IA & BYOK", description = "Permite ao cliente configurar seu próprio provedor de IA (Gemini, GPT ou Claude)")
public class OrganizationAiConfigController {

    private final OrganizationAiConfigService aiConfigService;
    private final OrganizationRepository organizationRepository;

    private UUID resolveOrgId(UUID organizationId) {
        if (organizationId != null) return organizationId;
        return organizationRepository.findAll().stream()
                .map(Organization::getId)
                .findFirst()
                .orElse(UUID.randomUUID());
    }

    @GetMapping
    @Operation(summary = "Obter configuração de IA do cliente", description = "Retorna o provedor ativo (GEMINI, OPENAI, CLAUDE) e chaves mascaradas")
    public ResponseEntity<OrganizationAiConfigDto> getAiConfig(@RequestParam(required = false) UUID organizationId) {
        UUID orgId = resolveOrgId(organizationId);
        return ResponseEntity.ok(aiConfigService.getAiConfig(orgId));
    }

    @PutMapping
    @Operation(summary = "Atualizar configuração de IA (BYOK)", description = "Permite configurar chaves e selecionar Gemini, GPT ou Claude")
    public ResponseEntity<OrganizationAiConfigDto> updateAiConfig(
            @RequestParam(required = false) UUID organizationId,
            @RequestBody UpdateAiConfigRequest request) {
        UUID orgId = resolveOrgId(organizationId);
        return ResponseEntity.ok(aiConfigService.updateAiConfig(orgId, request));
    }

    @PostMapping("/test")
    @Operation(summary = "Testar conexão com provedor de IA", description = "Valida a chave e a conectividade com Gemini, OpenAI ou Claude")
    public ResponseEntity<TestAiConnectionDto.Response> testConnection(@RequestBody TestAiConnectionDto.Request request) {
        TestAiConnectionDto.Response response = aiConfigService.testAiConnection(request);
        return ResponseEntity.ok(response);
    }
}

