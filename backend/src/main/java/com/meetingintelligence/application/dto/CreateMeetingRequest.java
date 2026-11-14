package com.meetingintelligence.application.dto;

import com.meetingintelligence.domain.enums.CaptureMechanism;
import com.meetingintelligence.domain.enums.MeetingType;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateMeetingRequest {
    @NotBlank(message = "O título da reunião é obrigatório")
    private String title;
    private String description;
    private OffsetDateTime scheduledStart;
    private OffsetDateTime scheduledEnd;
    private String googleMeetLink;
    private String googleCalendarEventId;
    @Builder.Default
    private CaptureMechanism captureMechanism = CaptureMechanism.BROWSER_CAPTURE;
    @Builder.Default
    private MeetingType meetingType = MeetingType.GENERAL;
    @Builder.Default
    private String language = "pt-BR";
    private List<String> participantEmails;
}

