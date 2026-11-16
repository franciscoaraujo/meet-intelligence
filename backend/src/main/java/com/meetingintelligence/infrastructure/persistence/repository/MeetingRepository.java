package com.meetingintelligence.infrastructure.persistence.repository;

import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.enums.MeetingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MeetingRepository extends JpaRepository<Meeting, UUID> {
    Page<Meeting> findByOrganizationId(UUID organizationId, Pageable pageable);

    /** Spec 007: a reunião é desta organização? (guarda das rotas e do WebSocket) */
    boolean existsByIdAndOrganization_Id(UUID id, UUID organizationId);
    List<Meeting> findByCreatedByIdOrderByCreatedAtDesc(UUID userId);
    List<Meeting> findByStatus(MeetingStatus status);
    Optional<Meeting> findByOrganizationIdAndGoogleCalendarEventId(UUID organizationId, String googleCalendarEventId);
    List<Meeting> findByOrganizationIdAndStatusOrderByScheduledStartAsc(UUID organizationId, MeetingStatus status);
}

