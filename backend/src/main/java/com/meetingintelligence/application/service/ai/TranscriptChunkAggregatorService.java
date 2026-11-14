package com.meetingintelligence.application.service.ai;

import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.TranscriptChunk;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.infrastructure.persistence.repository.TranscriptChunkRepository;
import com.meetingintelligence.infrastructure.persistence.repository.TranscriptSegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptChunkAggregatorService {

    private final TranscriptChunkRepository chunkRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final AIInsightExtractorService insightExtractorService;
    private final EmbeddingIndexingService embeddingIndexingService;

    // Buffer em memória para agregação por reunião ativa
    private final ConcurrentHashMap<UUID, StringBuilder> bufferMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> chunkStartMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> wordCountMap = new ConcurrentHashMap<>();

    private static final int MIN_WORDS_PER_CHUNK = 12;
    private static final int MAX_WORDS_PER_CHUNK = 250;

    @Transactional
    public void onNewSegment(Meeting meeting, TranscriptSegment segment) {
        UUID meetingId = meeting.getId();
        String text = segment.getCorrectedText() != null ? segment.getCorrectedText() : segment.getOriginalText();

        bufferMap.putIfAbsent(meetingId, new StringBuilder());
        chunkStartMap.putIfAbsent(meetingId, segment.getStartTimeMs());
        wordCountMap.putIfAbsent(meetingId, 0);

        StringBuilder buffer = bufferMap.get(meetingId);
        String speakerPrefix = segment.getSpeakerLabel() != null ? "[" + segment.getSpeakerLabel() + "]: " : "";
        buffer.append(speakerPrefix).append(text).append("\n");

        int currentWords = wordCountMap.get(meetingId) + text.split("\\s+").length;
        wordCountMap.put(meetingId, currentWords);

        // Verifica se formou bloco coeso para enviar ao LLM
        if (currentWords >= MIN_WORDS_PER_CHUNK) {
            flushChunk(meeting, segment.getEndTimeMs());
        }
    }

    @Transactional
    public void flushRemaining(Meeting meeting) {
        UUID meetingId = meeting.getId();
        if (bufferMap.containsKey(meetingId) && bufferMap.get(meetingId).length() > 0) {
            flushChunk(meeting, System.currentTimeMillis());
            return;
        }

        // Caso o buffer em memória esteja vazio, verifica se existem chunks pendentes de processamento pela IA
        List<TranscriptChunk> existingChunks = chunkRepository.findByMeetingIdOrderByChunkIndexAsc(meetingId);
        List<TranscriptChunk> unprocessed = existingChunks.stream()
                .filter(c -> !c.isProcessedByAi())
                .toList();

        if (!unprocessed.isEmpty()) {
            // Processa os últimos blocos pendentes (até 3 blocos mais recentes para resposta rápida)
            int startIdx = Math.max(0, unprocessed.size() - 3);
            for (int i = startIdx; i < unprocessed.size(); i++) {
                TranscriptChunk pendingChunk = unprocessed.get(i);
                embeddingIndexingService.indexChunkAsync(meeting, pendingChunk);
                insightExtractorService.processChunkAsync(meeting, pendingChunk);
            }
            return;
        }

        // Caso não existam chunks ou todos já tenham sido marcados, consolida os segmentos recentes da transcrição
        List<TranscriptSegment> segments = segmentRepository.findByMeetingIdOrderByStartTimeMsAsc(meetingId);
        if (!segments.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int startSeg = Math.max(0, segments.size() - 10);
            long startMs = segments.get(startSeg).getStartTimeMs();
            long endMs = segments.get(segments.size() - 1).getEndTimeMs();
            for (int i = startSeg; i < segments.size(); i++) {
                TranscriptSegment s = segments.get(i);
                String spk = s.getSpeakerLabel() != null ? "[" + s.getSpeakerLabel() + "]: " : "";
                String txt = s.getCorrectedText() != null ? s.getCorrectedText() : s.getOriginalText();
                sb.append(spk).append(txt).append("\n");
            }
            bufferMap.put(meetingId, sb);
            chunkStartMap.put(meetingId, startMs);
            flushChunk(meeting, endMs);
        }
    }

    private void flushChunk(Meeting meeting, long endTimeMs) {
        UUID meetingId = meeting.getId();
        StringBuilder buffer = bufferMap.remove(meetingId);
        Long startTime = chunkStartMap.remove(meetingId);
        wordCountMap.remove(meetingId);

        if (buffer == null || buffer.length() == 0) return;

        List<TranscriptChunk> existingChunks = chunkRepository.findByMeetingIdOrderByChunkIndexAsc(meetingId);
        int nextIndex = existingChunks.size() + 1;

        TranscriptChunk chunk = TranscriptChunk.builder()
                .meeting(meeting)
                .chunkIndex(nextIndex)
                .textContent(buffer.toString().trim())
                .startTimeMs(startTime != null ? startTime : 0L)
                .endTimeMs(endTimeMs)
                .processedByAi(false)
                .build();

        TranscriptChunk saved = chunkRepository.save(chunk);
        log.info("Bloco semântico {} gerado para reunião {} (Tamanho: {} palavras)",
                nextIndex, meeting.getTitle(), saved.getTextContent().split("\\s+").length);

        // Dispara indexação vetorial no pgvector de forma assíncrona
        embeddingIndexingService.indexChunkAsync(meeting, saved);

        // Dispara análise automática com IA de forma assíncrona
        insightExtractorService.processChunkAsync(meeting, saved);
    }
}

