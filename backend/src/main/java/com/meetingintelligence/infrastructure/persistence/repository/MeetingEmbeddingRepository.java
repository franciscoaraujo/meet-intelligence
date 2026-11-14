package com.meetingintelligence.infrastructure.persistence.repository;

import com.meetingintelligence.domain.entity.MeetingEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MeetingEmbeddingRepository extends JpaRepository<MeetingEmbedding, UUID> {

    @Query(value = """
        SELECT e.* FROM meeting_embeddings e
        WHERE e.meeting_id = :meetingId
        ORDER BY e.embedding <=> cast(:queryVector as vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<MeetingEmbedding> findSimilarChunks(
            @Param("meetingId") UUID meetingId,
            @Param("queryVector") String queryVector,
            @Param("limit") int limit
    );

    List<MeetingEmbedding> findByMeetingId(UUID meetingId);
}

