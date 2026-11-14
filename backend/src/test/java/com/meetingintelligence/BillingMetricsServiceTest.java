package com.meetingintelligence;

import com.meetingintelligence.application.dto.DashboardMetricsDto;
import com.meetingintelligence.application.service.BillingMetricsService;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.enums.MeetingStatus;
import com.meetingintelligence.domain.enums.PlanType;
import com.meetingintelligence.infrastructure.persistence.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingMetricsServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private MeetingRepository meetingRepository;

    @Mock
    private AIInsightRepository insightRepository;

    @Mock
    private MeetingDecisionRepository decisionRepository;

    @Mock
    private MeetingTaskRepository taskRepository;

    @Mock
    private LLMUsageRepository llmUsageRepository;

    @InjectMocks
    private BillingMetricsService billingMetricsService;

    @Test
    @DisplayName("Deve calcular corretamente métricas e limites para organização no plano PRO")
    void shouldCalculateMetricsForProPlan() {
        UUID orgId = UUID.randomUUID();
        Organization org = Organization.builder()
                .id(orgId)
                .name("Acme Corp")
                .slug("acme")
                .planType(PlanType.PRO)
                .build();

        Meeting m1 = Meeting.builder().organization(org).status(MeetingStatus.COMPLETED).build();
        Meeting m2 = Meeting.builder().organization(org).status(MeetingStatus.IN_PROGRESS).build();

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(meetingRepository.findAll()).thenReturn(List.of(m1, m2));
        when(llmUsageRepository.findByOrganizationId(orgId)).thenReturn(List.of());

        DashboardMetricsDto metrics = billingMetricsService.getMetrics(orgId);

        assertNotNull(metrics);
        assertEquals(PlanType.PRO, metrics.getPlanType());
        assertEquals(3000, metrics.getMonthlyMinutesLimit()); // 50h = 3000 min
        assertEquals(2, metrics.getTotalMeetings());
        assertEquals(1, metrics.getActiveMeetings());
        assertEquals(1, metrics.getCompletedMeetings());
        assertTrue(metrics.getRecordedHours() > 0);
        assertTrue(metrics.getTranscribedHours() > 0);
    }

    @Test
    @DisplayName("Deve calcular limites de plano FREE quando configurado")
    void shouldCalculateMetricsForFreePlan() {
        UUID orgId = UUID.randomUUID();
        Organization org = Organization.builder()
                .id(orgId)
                .name("Free Startup")
                .slug("free")
                .planType(PlanType.FREE)
                .build();

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(meetingRepository.findAll()).thenReturn(List.of());
        when(llmUsageRepository.findByOrganizationId(orgId)).thenReturn(List.of());

        DashboardMetricsDto metrics = billingMetricsService.getMetrics(orgId);

        assertNotNull(metrics);
        assertEquals(PlanType.FREE, metrics.getPlanType());
        assertEquals(300, metrics.getMonthlyMinutesLimit()); // 5h = 300 min
        assertEquals(0, metrics.getTotalMeetings());
        assertEquals(0.0, metrics.getQuotaUsagePercentage());
    }
}
