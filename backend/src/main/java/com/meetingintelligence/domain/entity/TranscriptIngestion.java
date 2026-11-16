package com.meetingintelligence.domain.entity;

import com.meetingintelligence.domain.speech.AudioSource;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Registro de um trecho de áudio recebido (idempotência por clientSegmentId, spec 002). */
@Entity
@Table(name = "transcript_ingestions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TranscriptIngestion {

    public enum Status { PROCESSING, TRANSCRIBED, NO_SPEECH, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "client_segment_id", nullable = false)
    private UUID clientSegmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "audio_source", nullable = false, length = 10)
    private AudioSource audioSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 50)
    private String provider;

    @Column(name = "segment_id")
    private UUID segmentId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
