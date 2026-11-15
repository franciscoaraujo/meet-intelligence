package com.meetingintelligence.infrastructure.persistence.repository;

import com.meetingintelligence.domain.entity.TranscriptSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, UUID> {

    /**
     * Segmentos em ordem cronológica (da fala mais antiga para a mais nova).
     *
     * Ordena pelo instante real em que a fala foi registrada ({@code createdAt}), e não por
     * {@code startTimeMs}: esse campo vem de relógios diferentes conforme a origem (cronômetro
     * da página para o microfone, contagem de segmentos para o áudio da aba do Meet, posição no
     * arquivo para gravações enviadas) e reinicia quando a página é recarregada.
     * {@code startTimeMs} é usado apenas como desempate (ex.: segmentos de um mesmo arquivo).
     */
    List<TranscriptSegment> findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(UUID meetingId);
}
