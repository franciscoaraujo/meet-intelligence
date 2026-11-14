package com.meetingintelligence.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meeting_summaries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class MeetingSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false, unique = true)
    private Meeting meeting;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "executive_summary", columnDefinition = "TEXT")
    private String executiveSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_topics", columnDefinition = "JSONB")
    private String keyTopics;

    @Column(name = "current_context", columnDefinition = "TEXT")
    private String currentContext;

    @Column(name = "last_processed_chunk_index", nullable = false)
    @Builder.Default
    private Integer lastProcessedChunkIndex = 0;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}

