package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.OrganizationDto;
import com.meetingintelligence.application.service.OrganizationService;
import com.meetingintelligence.domain.enums.OrganizationRole;
import com.meetingintelligence.domain.enums.PlanType;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/v1/organization")
@RequiredArgsConstructor
@Tag(name = "Organização & Multi-Tenancy", description = "Gestão de equipe, planos de assinatura e privacidade LGPD")
public class OrganizationController {

    private final OrganizationService organizationService;

    /*
     * Spec 007 (S-05): a organização é sempre a do usuário logado. Antes vinha de um parâmetro
     * opcional da URL (ou era a primeira do banco), o que deixava qualquer usuário ler e alterar
     * qualquer organização.
     */

    @GetMapping
    @Operation(summary = "Obter dados da organização e equipe", description = "Retorna plano atual, membros cadastrados e dados da organização do usuário")
    public ResponseEntity<OrganizationDto> getOrganization(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(organizationService.getOrganizationDetails(user.getOrganizationId()));
    }

    @PatchMapping("/plan")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Atualizar plano de assinatura (Upgrade / Downgrade)", description = "Altera o plano para FREE, PRO ou BUSINESS; exige OWNER ou ADMIN")
    public ResponseEntity<OrganizationDto> updatePlan(@AuthenticationPrincipal UserPrincipal user,
                                                      @RequestParam PlanType planType) {
        return ResponseEntity.ok(organizationService.updatePlan(user.getOrganizationId(), planType));
    }

    @PostMapping("/members/invite")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Convidar novo membro para o workspace", description = "Adiciona usuário com papel especificado; exige OWNER ou ADMIN, e convidar OWNER exige OWNER")
    public ResponseEntity<OrganizationDto.MemberItemDto> inviteMember(@AuthenticationPrincipal UserPrincipal user,
                                                                      @RequestBody InviteMemberRequest request) {
        OrganizationRole role = request.getRole() != null ? request.getRole() : OrganizationRole.MEMBER;
        if (role == OrganizationRole.OWNER && !"OWNER".equals(user.getRole())) {
            throw new AccessDeniedException("Só o dono convida outro dono.");
        }
        OrganizationDto.MemberItemDto member = organizationService.inviteMember(
                user.getOrganizationId(), request.getEmail(), role);
        return ResponseEntity.ok(member);
    }

    @Data
    public static class InviteMemberRequest {
        private String email;
        private OrganizationRole role;
    }
}
