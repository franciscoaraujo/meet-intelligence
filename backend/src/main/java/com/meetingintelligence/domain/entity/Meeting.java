package com.meetingintelligence.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.meetingintelligence.domain.enums.CaptureMechanism;
import com.meetingintelligence.domain.enums.MeetingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meetings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Meeting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "scheduled_start")
    private OffsetDateTime scheduledStart;

    @Column(name = "scheduled_end")
    private OffsetDateTime scheduledEnd;

    @Column(name = "actual_start")
    private OffsetDateTime actualStart;

    @Column(name = "actual_end")
    private OffsetDateTime actualEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private MeetingStatus status = MeetingStatus.SCHEDULED;

    @Column(name = "google_meet_link", length = 500)
    private String googleMeetLink;

    @Column(name = "google_calendar_event_id")
    private String googleCalendarEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_mechanism", nullable = false, length = 50)
    @Builder.Default
    private CaptureMechanism captureMechanism = CaptureMechanism.BROWSER_CAPTURE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_profile_id")
    private AIProfile aiProfile;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String language = "pt-BR";

    /** Papel do usuário na entrevista (spec 003, RF-04); nulo = candidato. */
    @Enumerated(EnumType.STRING)
    @Column(name = "user_interview_role", length = 20)
    private com.meetingintelligence.domain.enums.InterviewRole userInterviewRole;

    /** Tom das respostas do copiloto (spec 014). */
    @Enumerated(EnumType.STRING)
    @Column(name = "answer_style", nullable = false, length = 20)
    @Builder.Default
    private com.meetingintelligence.domain.enums.AnswerStyle answerStyle = com.meetingintelligence.domain.enums.AnswerStyle.NEUTRAL;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}

