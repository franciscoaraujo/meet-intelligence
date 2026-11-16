package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.DashboardMetricsDto;
import com.meetingintelligence.application.service.BillingMetricsService;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard & Métricas SaaS", description = "Monitoramento de consumo, quotas de planos e estatísticas de IA")
public class DashboardController {

    private final BillingMetricsService billingMetricsService;

    @GetMapping("/metrics")
    @Operation(summary = "Obter métricas operacionais e de billing do tenant", description = "Retorna horas gravadas, transcritas, tokens de LLM e consumo de quota")
    public ResponseEntity<DashboardMetricsDto> getMetrics(@AuthenticationPrincipal UserPrincipal user) {
        // Spec 007: sempre a organização do usuário (antes vinha da URL ou era a primeira do banco)
        UUID orgId = user.getOrganizationId();

        DashboardMetricsDto metrics = billingMetricsService.getMetrics(orgId);
        return ResponseEntity.ok(metrics);
    }
}
