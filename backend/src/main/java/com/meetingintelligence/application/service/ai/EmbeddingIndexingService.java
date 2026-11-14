package com.meetingintelligence.application.service.ai;

import com.meetingintelligence.domain.ai.EmbeddingProvider;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.MeetingEmbedding;
import com.meetingintelligence.domain.entity.TranscriptChunk;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingEmbeddingRepository;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingIndexingService {

    private final EmbeddingProvider embeddingProvider;
    private final MeetingEmbeddingRepository embeddingRepository;

    @Async
    @Transactional
    public void indexChunkAsync(Meeting meeting, TranscriptChunk chunk) {
        try {
            log.info("Indexando embedding vetorial para chunk #{} da reunião '{}'",
                    chunk.getChunkIndex(), meeting.getTitle());

            float[] vector = embeddingProvider.generateEmbedding(chunk.getTextContent());

            MeetingEmbedding embedding = MeetingEmbedding.builder()
                    .meeting(meeting)
                    .embedding(new PGvector(vector).toString())
                    .textPreview(chunk.getTextContent())
                    .startTimeMs(chunk.getStartTimeMs())
                    .endTimeMs(chunk.getEndTimeMs())
                    .build();

            embeddingRepository.save(embedding);
            log.info("Chunk #{} indexado com sucesso no pgvector", chunk.getChunkIndex());
        } catch (Exception e) {
            log.error("Erro ao indexar embedding do chunk #{}: {}", chunk.getChunkIndex(), e.getMessage(), e);
        }
    }
}

