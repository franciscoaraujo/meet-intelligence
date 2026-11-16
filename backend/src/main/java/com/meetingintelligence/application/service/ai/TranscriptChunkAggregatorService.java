package com.meetingintelligence.application.service.ai;

import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.TranscriptChunk;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.infrastructure.async.AfterCommit;

import com.meetingintelligence.infrastructure.persistence.repository.TranscriptChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agrega a transcrição em blocos para a análise incremental da reunião (resumo, tarefas, decisões)
 * e para a indexação semântica (RAG).
 *
 * Spec 005: perguntas NÃO passam mais por aqui (ver QuestionDetectionService). O bloco fecha por
 * volume — {@value #MIN_WORDS_PER_CHUNK} palavras ou {@value #MAX_CHUNK_AGE_MS} ms — e não a cada
 * palavra-chave (P-11). Cada fala entra uma única vez (só o texto novo das fusões) e o índice do
 * bloco é calculado sob trava por reunião.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptChunkAggregatorService {

    static final int MIN_WORDS_PER_CHUNK = 120;
    static final long MAX_CHUNK_AGE_MS = 60_000;

    private final TranscriptChunkRepository chunkRepository;
    private final AIInsightExtractorService insightExtractorService;
    private final EmbeddingIndexingService embeddingIndexingService;

    private static final class Buffer {
        final StringBuilder text = new StringBuilder();
        int words;
        Long startTimeMs;
        long endTimeMs;
        long openedAtWallMs;
    }

    private final ConcurrentHashMap<UUID, Buffer> buffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    /**
     * @param newText somente o texto novo deste segmento (na fusão, o fragmento acrescentado)
     */
    @Transactional
    public void onNewSegment(Meeting meeting, TranscriptSegment segment, String newText) {
        if (newText == null || newText.isBlank() || segment.isEchoSuspected()) return;
        UUID meetingId = meeting.getId();
        synchronized (lockFor(meetingId)) {
            Buffer buffer = buffers.computeIfAbsent(meetingId, id -> {
                Buffer b = new Buffer();
                b.openedAtWallMs = System.currentTimeMillis();
                return b;
            });
            if (buffer.startTimeMs == null) buffer.startTimeMs = segment.getStartTimeMs();
            buffer.endTimeMs = segment.getEndTimeMs();
            String speaker = segment.getSpeakerLabel() != null ? "[" + segment.getSpeakerLabel() + "]: " : "";
            buffer.text.append(speaker).append(newText.trim()).append('\n');
            buffer.words += newText.trim().split("\\s+").length;

            boolean byVolume = buffer.words >= MIN_WORDS_PER_CHUNK;
            boolean byAge = System.currentTimeMillis() - buffer.openedAtWallMs >= MAX_CHUNK_AGE_MS;
            if (byVolume || byAge) {
                flushLocked(meeting);
            }
        }
    }

    /**
     * Fecha o bloco em andamento e reprocessa blocos que ficaram sem análise (fim da reunião ou botão
     * "Consolidar com IA"). Não reanalisa conteúdo já analisado (antes: refazia os últimos 10
     * segmentos e duplicava insights).
     */
    @Transactional
    public void flushRemaining(Meeting meeting) {
        UUID meetingId = meeting.getId();
        synchronized (lockFor(meetingId)) {
            flushLocked(meeting);
        }
        List<TranscriptChunk> unprocessed = chunkRepository.findByMeetingIdOrderByChunkIndexAsc(meetingId).stream()
                .filter(c -> !c.isProcessedByAi())
                .toList();
        int startIdx = Math.max(0, unprocessed.size() - 3);
        for (TranscriptChunk pending : unprocessed.subList(startIdx, unprocessed.size())) {
            dispatch(meeting, pending);
        }
    }

    private void flushLocked(Meeting meeting) {
        Buffer buffer = buffers.remove(meeting.getId());
        if (buffer == null || buffer.text.isEmpty()) return;

        int nextIndex = chunkRepository.findByMeetingIdOrderByChunkIndexAsc(meeting.getId()).stream()
                .mapToInt(TranscriptChunk::getChunkIndex).max().orElse(0) + 1;
        TranscriptChunk saved = chunkRepository.save(TranscriptChunk.builder()
                .meeting(meeting)
                .chunkIndex(nextIndex)
                .textContent(buffer.text.toString().trim())
                .startTimeMs(buffer.startTimeMs != null ? buffer.startTimeMs : 0L)
                .endTimeMs(buffer.endTimeMs)
                .processedByAi(false)
                .build());
        log.info("Bloco {} gerado para a reunião {} ({} palavras)", nextIndex, meeting.getId(), buffer.words);
        dispatch(meeting, saved);
    }

    /** IA e indexação só após o commit: a análise roda em outra thread e referencia o bloco gravado. */
    private void dispatch(Meeting meeting, TranscriptChunk chunk) {
        AfterCommit.run(() -> {
            embeddingIndexingService.indexChunkAsync(meeting, chunk);
            insightExtractorService.processChunkAsync(meeting, chunk);
        });
    }

    private Object lockFor(UUID meetingId) {
        return locks.computeIfAbsent(meetingId, id -> new Object());
    }
}
