package com.meetingintelligence.infrastructure.persistence.repository;

import com.meetingintelligence.domain.entity.AIProfile;
import com.meetingintelligence.domain.enums.MeetingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AIProfileRepository extends JpaRepository<AIProfile, UUID> {
    List<AIProfile> findByOrganizationId(UUID organizationId);
    Optional<AIProfile> findByOrganizationIdAndIsDefaultTrue(UUID organizationId);
    List<AIProfile> findByMeetingType(MeetingType meetingType);
}

