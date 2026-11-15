package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.OrganizationAiConfigDto;
import com.meetingintelligence.application.dto.TestAiConnectionDto;
import com.meetingintelligence.application.dto.UpdateAiConfigRequest;
import com.meetingintelligence.application.service.OrganizationAiConfigService;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Configuração de IA (BYOK) da organização do usuário autenticado.
 *
 * A organização vem sempre do JWT — nunca de parâmetro da requisição — para que as chaves
 * sejam salvas na organização certa e um usuário não leia nem altere a configuração de outra.
 */
@RestController
@RequestMapping("/api/v1/organization/ai-config")
@RequiredArgsConstructor
@Tag(name = "Configuração de IA & BYOK", description = "Permite ao cliente configurar seu próprio provedor de IA (Gemini, GPT ou Claude)")
public class OrganizationAiConfigController {

    private final OrganizationAiConfigService aiConfigService;

    @GetMapping
    @Operation(summary = "Obter configuração de IA da organização", description = "Retorna o provedor ativo (GEMINI, OPENAI, CLAUDE) e chaves mascaradas")
    public ResponseEntity<OrganizationAiConfigDto> getAiConfig(@AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(aiConfigService.getAiConfig(currentUser.getOrganizationId()));
    }

    @PutMapping
    @Operation(summary = "Atualizar configuração de IA (BYOK)", description = "Permite configurar chaves e selecionar Gemini, GPT ou Claude")
    public ResponseEntity<OrganizationAiConfigDto> updateAiConfig(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestBody UpdateAiConfigRequest request) {
        return ResponseEntity.ok(aiConfigService.updateAiConfig(currentUser.getOrganizationId(), request));
    }

    @PostMapping("/test")
    @Operation(summary = "Testar conexão com provedor de IA",
            description = "Valida a chave e a conectividade. Se a chave vier vazia ou mascarada, usa a chave já salva da organização.")
    public ResponseEntity<TestAiConnectionDto.Response> testConnection(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestBody TestAiConnectionDto.Request request) {
        return ResponseEntity.ok(aiConfigService.testAiConnection(currentUser.getOrganizationId(), request));
    }
}
