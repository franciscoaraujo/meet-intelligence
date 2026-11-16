package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.AIProfileDto;
import com.meetingintelligence.application.service.AIProfileService;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai-profiles")
@RequiredArgsConstructor
@Tag(name = "Perfis de IA", description = "Gerenciamento e customização de perfis analíticos por tipo de reunião")
public class AIProfileController {

    private final AIProfileService profileService;

    @GetMapping
    @Operation(summary = "Listar perfis de IA", description = "Retorna os perfis customizados da organização e modelos padrão do sistema")
    public ResponseEntity<List<AIProfileDto>> listProfiles(@AuthenticationPrincipal UserPrincipal currentUser) {
        List<AIProfileDto> profiles = profileService.listProfiles(currentUser.getOrganizationId());
        return ResponseEntity.ok(profiles);
    }

    @PostMapping
    @Operation(summary = "Criar perfil customizado", description = "Registra um novo perfil com system prompts e categorias adaptadas")
    public ResponseEntity<AIProfileDto> createProfile(
            @Valid @RequestBody AIProfileDto dto,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        AIProfileDto created = profileService.createProfile(currentUser.getOrganizationId(), dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remover perfil", description = "Exclui um perfil customizado")
    public ResponseEntity<Void> deleteProfile(@PathVariable UUID id,
                                              @AuthenticationPrincipal UserPrincipal currentUser) {
        profileService.deleteProfile(id, currentUser.getOrganizationId());
        return ResponseEntity.noContent().build();
    }
}

