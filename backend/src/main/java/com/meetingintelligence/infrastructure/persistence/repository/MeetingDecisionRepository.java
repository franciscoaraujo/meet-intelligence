package com.meetingintelligence.infrastructure.persistence.repository;

import com.meetingintelligence.domain.entity.MeetingDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MeetingDecisionRepository extends JpaRepository<MeetingDecision, UUID> {
    List<MeetingDecision> findByMeetingIdOrderByTimestampMsAsc(UUID meetingId);
}

