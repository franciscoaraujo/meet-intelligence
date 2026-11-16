package com.meetingintelligence.domain.entity;

import com.meetingintelligence.domain.question.UtteranceType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Pergunta detectada na entrevista (spec 005); a resposta do copiloto vira um AIInsight vinculado. */
@Entity
@Table(name = "interview_questions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewQuestion {

    public enum Status { DETECTED, ANSWERING, ANSWERED, FAILED, DISMISSED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    /** Spec 015: fala do entrevistador logo antes da pergunta (o cenário a que ela se refere). */
    @Column(name = "context_text", columnDefinition = "TEXT")
    private String contextText;

    @Enumerated(EnumType.STRING)
    @Column(name = "utterance_type", nullable = false, length = 30)
    private UtteranceType utteranceType;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal score;

    /** true quando a pontuação atingiu o limiar de resposta automática (RF-05). */
    @Column(name = "auto_answer", nullable = false)
    private boolean autoAnswer;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "source_segment_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] sourceSegmentIds;

    @Column(name = "capture_start_ms")
    private Long captureStartMs;

    @Column(name = "capture_end_ms")
    private Long captureEndMs;

    @Column(name = "answer_insight_id")
    private UUID answerInsightId;

    @Column(name = "answer_version", nullable = false)
    private int answerVersion;

    @Column(name = "failure_reason", length = 50)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
