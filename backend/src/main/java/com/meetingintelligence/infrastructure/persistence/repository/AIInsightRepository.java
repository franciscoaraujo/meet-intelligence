package com.meetingintelligence.infrastructure.persistence.repository;

import com.meetingintelligence.domain.entity.AIInsight;
import com.meetingintelligence.domain.enums.InsightCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AIInsightRepository extends JpaRepository<AIInsight, UUID> {
    List<AIInsight> findByMeetingIdOrderByCreatedAtAsc(UUID meetingId);
    List<AIInsight> findByMeetingIdAndCategory(UUID meetingId, InsightCategory category);
}

