package com.meetingintelligence.application.dto;

import com.meetingintelligence.domain.enums.CaptureMechanism;
import com.meetingintelligence.domain.enums.MeetingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeetingResponse {
    private UUID id;
    private UUID organizationId;
    private String title;
    private String description;
    private OffsetDateTime scheduledStart;
    private OffsetDateTime scheduledEnd;
    private OffsetDateTime actualStart;
    private OffsetDateTime actualEnd;
    /** Spec 020: data do arquivamento; null = ativa. */
    private OffsetDateTime archivedAt;
    private MeetingStatus status;
    private String googleMeetLink;
    private String googleCalendarEventId;
    private CaptureMechanism captureMechanism;
    private String language;
    /** Papel do usuário na entrevista (CANDIDATE padrão, INTERVIEWER). */
    private com.meetingintelligence.domain.enums.InterviewRole userInterviewRole;
    private com.meetingintelligence.domain.enums.AnswerStyle answerStyle;
    private OffsetDateTime createdAt;
}

