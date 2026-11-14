package com.meetingintelligence.infrastructure.persistence.repository;

import com.meetingintelligence.domain.entity.MeetingTask;
import com.meetingintelligence.domain.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MeetingTaskRepository extends JpaRepository<MeetingTask, UUID> {
    List<MeetingTask> findByMeetingIdOrderByCreatedAtAsc(UUID meetingId);
    List<MeetingTask> findByMeetingIdAndStatus(UUID meetingId, TaskStatus status);
}

