package com.meetingintelligence.infrastructure.persistence.repository;

import com.meetingintelligence.domain.entity.AIConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AIConversationRepository extends JpaRepository<AIConversation, UUID> {
    Optional<AIConversation> findByMeetingId(UUID meetingId);
}

