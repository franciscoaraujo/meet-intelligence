package com.meetingintelligence.application.dto;

import com.meetingintelligence.domain.entity.MeetingDecision;
import com.meetingintelligence.domain.entity.MeetingParticipant;
import com.meetingintelligence.domain.entity.MeetingTask;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeetingMinutesDto {
    private UUID meetingId;
    private String title;
    private String organizationName;
    private String meetingType;
    private OffsetDateTime date;
    private String durationFormatted;
    private List<String> participants;
    private String executiveSummary;
    private List<String> keyTopics;
    private List<DecisionItemDto> decisions;
    private List<TaskItemDto> tasks;
    private String fullTranscriptText;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecisionItemDto {
        private String description;
        private String justification;
        private String responsible;
        private String timeFormatted;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskItemDto {
        private String description;
        private String assignee;
        private String dueDate;
        private String priority;
        private String status;
    }
}

