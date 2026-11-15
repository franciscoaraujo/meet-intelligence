package com.meetingintelligence.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "transcript_segments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class TranscriptSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id")
    private MeetingParticipant participant;

    @Column(name = "speaker_label", length = 100)
    private String speakerLabel;

    @Column(name = "original_text", nullable = false, columnDefinition = "TEXT")
    private String originalText;

    @Column(name = "corrected_text", columnDefinition = "TEXT")
    private String correctedText;

    @Column(name = "start_time_ms", nullable = false)
    private Long startTimeMs;

    @Column(name = "end_time_ms", nullable = false)
    private Long endTimeMs;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String language = "pt-BR";

    /** Provedor que produziu o texto (GEMINI, DEEPGRAM, OPENAI_TRANSCRIBE, WEB_SPEECH, ...). */
    @Column(nullable = false, length = 50)
    @Builder.Default
    private String source = "DEEPGRAM";

    /** Origem do áudio; nulo em segmentos anteriores à spec 002. */
    @Enumerated(EnumType.STRING)
    @Column(name = "audio_source", length = 10)
    private com.meetingintelligence.domain.speech.AudioSource audioSource;

    /** Papel de quem falou (spec 003); nulo em segmentos anteriores. */
    @Enumerated(EnumType.STRING)
    @Column(name = "speaker_role", length = 10)
    private com.meetingintelligence.domain.speech.SpeakerRole speakerRole;

    /** Confiança na atribuição do falante (0-1); 1,0 quando corrigida manualmente. */
    @Column(name = "speaker_confidence", precision = 4, scale = 3)
    private java.math.BigDecimal speakerConfidence;

    /** Início/fim no relógio da sessão de captura do cliente (ms); nulo quando o cliente não envia. */
    @Column(name = "capture_start_ms")
    private Long captureStartMs;

    @Column(name = "capture_end_ms")
    private Long captureEndMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}

