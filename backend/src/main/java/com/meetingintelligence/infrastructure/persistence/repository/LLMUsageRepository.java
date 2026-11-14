package com.meetingintelligence.infrastructure.persistence.repository;

import com.meetingintelligence.domain.entity.LLMUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LLMUsageRepository extends JpaRepository<LLMUsage, UUID> {
    List<LLMUsage> findByMeetingId(UUID meetingId);
    List<LLMUsage> findByOrganizationId(UUID organizationId);
}

