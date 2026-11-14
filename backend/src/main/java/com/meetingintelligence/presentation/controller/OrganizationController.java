package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.OrganizationDto;
import com.meetingintelligence.application.service.OrganizationService;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.enums.OrganizationRole;
import com.meetingintelligence.domain.enums.PlanType;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organization")
@RequiredArgsConstructor
@Tag(name = "Organização & Multi-Tenancy", description = "Gestão de equipe, planos de assinatura e privacidade LGPD")
public class OrganizationController {

    private final OrganizationService organizationService;
    private final OrganizationRepository organizationRepository;

    private UUID resolveOrgId(UUID organizationId) {
        if (organizationId != null) return organizationId;
        return organizationRepository.findAll().stream()
                .map(Organization::getId)
                .findFirst()
                .orElse(UUID.randomUUID());
    }

    @GetMapping
    @Operation(summary = "Obter dados da organização e equipe", description = "Retorna plano atual, membros cadastrados e dados da organização")
    public ResponseEntity<OrganizationDto> getOrganization(@RequestParam(required = false) UUID organizationId) {
        UUID orgId = resolveOrgId(organizationId);
        return ResponseEntity.ok(organizationService.getOrganizationDetails(orgId));
    }

    @PatchMapping("/plan")
    @Operation(summary = "Atualizar plano de assinatura (Upgrade / Downgrade)", description = "Altera o plano para FREE, PRO ou BUSINESS")
    public ResponseEntity<OrganizationDto> updatePlan(
            @RequestParam(required = false) UUID organizationId,
            @RequestParam PlanType planType) {
        UUID orgId = resolveOrgId(organizationId);
        return ResponseEntity.ok(organizationService.updatePlan(orgId, planType));
    }

    @PostMapping("/members/invite")
    @Operation(summary = "Convidar novo membro para o workspace", description = "Adiciona usuário com papel especificado (ADMIN, MEMBER, VIEWER)")
    public ResponseEntity<OrganizationDto.MemberItemDto> inviteMember(
            @RequestParam(required = false) UUID organizationId,
            @RequestBody InviteMemberRequest request) {
        UUID orgId = resolveOrgId(organizationId);
        OrganizationDto.MemberItemDto member = organizationService.inviteMember(
                orgId, 
                request.getEmail(), 
                request.getRole() != null ? request.getRole() : OrganizationRole.MEMBER
        );
        return ResponseEntity.ok(member);
    }

    @Data
    public static class InviteMemberRequest {
        private String email;
        private OrganizationRole role;
    }
}
