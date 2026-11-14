package com.meetingintelligence.application.dto;

import com.meetingintelligence.domain.enums.PlanType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardMetricsDto {
    private long totalMeetings;
    private long activeMeetings;
    private long completedMeetings;
    private double recordedHours;
    private double transcribedHours;
    private long totalInsightsGenerated;
    private long totalDecisions;
    private long totalPendingTasks;
    private long totalLlmCalls;
    private long totalTokensConsumed;
    private BigDecimal totalCostUsd;

    // Quotas do Plano Ativo
    private PlanType planType;
    private int monthlyMeetingLimit;
    private int monthlyMinutesLimit;
    private int usedMinutesThisMonth;
    private double quotaUsagePercentage;

    private List<DailyUsageDto> dailyUsage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyUsageDto {
        private String date;
        private int meetingsCount;
        private int tokens;
        private BigDecimal costUsd;
    }
}

