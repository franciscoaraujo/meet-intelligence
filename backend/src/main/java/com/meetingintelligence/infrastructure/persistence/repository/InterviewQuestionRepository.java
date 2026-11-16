package com.meetingintelligence.infrastructure.persistence.repository;

import com.meetingintelligence.domain.entity.InterviewQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, UUID> {
    List<InterviewQuestion> findTop20ByMeetingIdOrderByCreatedAtDesc(UUID meetingId);

    List<InterviewQuestion> findByMeetingIdOrderByCreatedAtAsc(UUID meetingId);
}
