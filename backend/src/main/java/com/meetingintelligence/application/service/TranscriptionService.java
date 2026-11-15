package com.meetingintelligence.application.service;

import com.meetingintelligence.application.dto.AudioChunkCommand;
import com.meetingintelligence.application.dto.ChunkTranscriptionResponse;
import com.meetingintelligence.application.exception.ResourceNotFoundException;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.TranscriptIngestion;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.domain.speech.AudioSource;
import com.meetingintelligence.domain.speech.SpeakerRole;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionChainResult;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationAiConfigRepository;
import com.meetingintelligence.infrastructure.persistence.repository.TranscriptIngestionRepository;
import com.meetingintelligence.infrastructure.persistence.repository.TranscriptSegmentRepository;
import com.meetingintelligence.infrastructure.speech.SpeechToTextRouter;
import com.meetingintelligence.infrastructure.speech.TranscriptionChainFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Transcrição de áudio (ao vivo e gravações) e de falas já reconhecidas no navegador.
 *
 * Áudio ao vivo (spec 002): idempotência por clientSegmentId → cadeia de provedores
 * (SpeechToTextRouter) fora de transação → persistência só em TRANSCRIBED. Uma lista vazia nunca
 * é tratada como sucesso; NO_SPEECH e FAILED são resultados explícitos.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptionService {

    static final String WEB_SPEECH_SOURCE = "WEB_SPEECH";
    static final String MANUAL_SOURCE = "MANUAL";

    private final MeetingRepository meetingRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final OrganizationAiConfigRepository aiConfigRepository;
    private final TranscriptIngestionRepository ingestionRepository;
    private final SpeechToTextRouter speechToTextRouter;
    private final TranscriptionChainFactory chainFactory;
    private final SimpMessagingTemplate messagingTemplate;
    private final com.meetingintelligence.application.service.ai.TranscriptChunkAggregatorService chunkAggregatorService;
    private final PlatformTransactionManager transactionManager;
    private final SpeakerAttributionService speakerAttribution;
    private final com.meetingintelligence.application.service.question.QuestionDetectionService questionDetection;

    // ------------------------------------------------------------- Áudio ao vivo

    /**
     * Processa um trecho de áudio ao vivo (aba do Meet ou microfone). Não roda numa transação única:
     * a chamada aos provedores pode levar segundos e não deve segurar conexão de banco.
     */
    public ChunkTranscriptionResponse processAudioChunk(AudioChunkCommand cmd, UUID organizationId, UUID userId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID clientSegmentId = cmd.clientSegmentId() != null ? cmd.clientSegmentId() : UUID.randomUUID();
        AudioSource source = cmd.audioSource() != null ? cmd.audioSource() : AudioSource.TAB;

        MDC.put("meetingId", String.valueOf(cmd.meetingId()));
        MDC.put("clientSegmentId", clientSegmentId.toString());
        MDC.put("audioSource", source.name());
        try {
            // 1. Pertence à organização do usuário? Já recebido (retry)?
            Claim claim = tx.execute(status -> claimIngestion(cmd.meetingId(), organizationId, clientSegmentId, source));
            if (claim.duplicateResponse() != null) {
                log.info("Trecho repetido (clientSegmentId já recebido): status={}", claim.duplicateResponse().getStatus());
                return claim.duplicateResponse();
            }

            // 2. Cadeia de provedores, fora de transação
            SttRequest request = new SttRequest(cmd.audio(), cmd.mimeType(), claim.language(), SttRequest.Mode.LIVE);
            TranscriptionChainResult result = speechToTextRouter.transcribe(
                    request, chainFactory.build(claim.orgConfig(), SttRequest.Mode.LIVE));

            // 3. Persistência conforme o resultado
            return tx.execute(status -> completeIngestion(claim.ingestionId(), cmd, source, result, userId));
        } finally {
            MDC.remove("meetingId");
            MDC.remove("clientSegmentId");
            MDC.remove("audioSource");
        }
    }

    private record Claim(UUID ingestionId, String language,
                         com.meetingintelligence.domain.entity.OrganizationAiConfig orgConfig,
                         ChunkTranscriptionResponse duplicateResponse) {}

    private Claim claimIngestion(UUID meetingId, UUID organizationId, UUID clientSegmentId, AudioSource source) {
        Meeting meeting = findMeetingOfOrganization(meetingId, organizationId);
        var orgConfig = aiConfigRepository.findByOrganizationId(organizationId).orElse(null);
        String language = meeting.getLanguage();

        var existing = ingestionRepository.findByMeetingIdAndClientSegmentId(meetingId, clientSegmentId);
        if (existing.isPresent()) {
            TranscriptIngestion ing = existing.get();
            if (ing.getStatus() != TranscriptIngestion.Status.FAILED) {
                return new Claim(ing.getId(), language, orgConfig, duplicateResponse(ing));
            }
            // Falhou antes: permite nova tentativa com o mesmo id
            ing.setStatus(TranscriptIngestion.Status.PROCESSING);
            ingestionRepository.save(ing);
            return new Claim(ing.getId(), language, orgConfig, null);
        }

        try {
            TranscriptIngestion ing = ingestionRepository.saveAndFlush(TranscriptIngestion.builder()
                    .meetingId(meetingId)
                    .clientSegmentId(clientSegmentId)
                    .audioSource(source)
                    .status(TranscriptIngestion.Status.PROCESSING)
                    .build());
            return new Claim(ing.getId(), language, orgConfig, null);
        } catch (DataIntegrityViolationException concurrent) {
            // Mesmo trecho chegando em paralelo: o outro processamento entrega o resultado
            return new Claim(null, language, orgConfig, ChunkTranscriptionResponse.builder()
                    .status(ChunkTranscriptionResponse.PROCESSING)
                    .duplicate(true)
                    .build());
        }
    }

    private ChunkTranscriptionResponse duplicateResponse(TranscriptIngestion ing) {
        List<TranscriptSegment> segments = ing.getSegmentId() == null ? List.of()
                : segmentRepository.findById(ing.getSegmentId()).map(List::of).orElse(List.of());
        return ChunkTranscriptionResponse.builder()
                .status(ing.getStatus().name())
                .provider(ing.getProvider())
                .segments(segments)
                .duplicate(true)
                .build();
    }

    private ChunkTranscriptionResponse completeIngestion(UUID ingestionId, AudioChunkCommand cmd, AudioSource source,
                                                         TranscriptionChainResult result, UUID userId) {
        TranscriptIngestion ing = ingestionRepository.findById(ingestionId).orElseThrow();
        ing.setProvider(result.provider());
        ChunkTranscriptionResponse.ChunkTranscriptionResponseBuilder response = ChunkTranscriptionResponse.builder()
                .status(result.status().name())
                .provider(result.provider())
                .attempts(result.attempts())
                .failureReason(result.failureReason() != null ? result.failureReason().name() : null);

        switch (result.status()) {
            case NO_SPEECH -> ing.setStatus(TranscriptIngestion.Status.NO_SPEECH);
            case FAILED -> {
                ing.setStatus(TranscriptIngestion.Status.FAILED);
                log.warn("Transcrição falhou em todos os provedores: tentativas={}", result.attempts().size());
            }
            case TRANSCRIBED -> {
                ing.setStatus(TranscriptIngestion.Status.TRANSCRIBED);
                Meeting meeting = meetingRepository.findById(cmd.meetingId()).orElseThrow();
                String text = result.segments().stream()
                        .map(TranscriptionSegmentDto::getText)
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.joining(" "));

                if (isRepeatOfRecentSegment(meeting.getId(), text)) {
                    // Comportamento preservado: o mesmo texto nos últimos 3 segmentos não é gravado de novo
                    response.duplicate(true).segments(List.of());
                } else {
                    var speaker = speakerAttribution.forLiveAudio(meeting, source, userId);
                    long start = cmd.captureStartMs() != null ? cmd.captureStartMs() : legacyStartMs(meeting.getId());
                    long end = cmd.captureEndMs() != null ? cmd.captureEndMs() : start + 5000L;
                    TranscriptSegment saved = persistSpeech(meeting, speaker, text, start, end, result.provider(),
                            source, cmd.captureStartMs(), cmd.captureEndMs());
                    ing.setSegmentId(saved.getId());
                    response.segments(List.of(saved));
                }
            }
        }
        ingestionRepository.save(ing);
        return response.build();
    }

    private boolean isRepeatOfRecentSegment(UUID meetingId, String text) {
        List<TranscriptSegment> existing = segmentRepository.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(meetingId);
        for (int i = Math.max(0, existing.size() - 3); i < existing.size(); i++) {
            String prev = existing.get(i).getOriginalText();
            if (prev != null && prev.trim().equalsIgnoreCase(text.trim())) return true;
        }
        return false;
    }

    /** Horário artificial usado antes da spec 001 (clientes que não enviam o horário de captura). */
    private long legacyStartMs(UUID meetingId) {
        return segmentRepository.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(meetingId).size() * 5000L;
    }

    // --------------------------------------------------------- Gravação enviada

    /**
     * Transcreve uma gravação enviada (upload). Em FAILED não grava nada; o chamador decide o status
     * da gravação a partir do resultado.
     */
    @Transactional
    public TranscriptionChainResult processRecording(UUID meetingId, byte[] audioBytes, String mimeType) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ResourceNotFoundException("Reunião não encontrada: " + meetingId));
        var orgConfig = meeting.getOrganization() != null
                ? aiConfigRepository.findByOrganizationId(meeting.getOrganization().getId()).orElse(null) : null;

        SttRequest request = new SttRequest(audioBytes, mimeType, meeting.getLanguage(), SttRequest.Mode.BATCH);
        TranscriptionChainResult result = speechToTextRouter.transcribe(request, chainFactory.build(orgConfig, SttRequest.Mode.BATCH));
        if (result.status() != TranscriptionChainResult.Status.TRANSCRIBED) {
            log.warn("Gravação da reunião {} sem transcrição: status={}", meetingId, result.status());
            return result;
        }

        for (TranscriptionSegmentDto dto : result.segments()) {
            var speaker = speakerAttribution.forUpload(meeting, dto);
            TranscriptSegment saved = segmentRepository.save(TranscriptSegment.builder()
                    .meeting(meeting)
                    .participant(speaker.participant())
                    .speakerLabel(speaker.label())
                    .speakerRole(speaker.role())
                    .speakerConfidence(speaker.confidence())
                    .originalText(dto.getText())
                    .startTimeMs(dto.getStartTimeMs() != null ? dto.getStartTimeMs() : 0L)
                    .endTimeMs(dto.getEndTimeMs() != null ? dto.getEndTimeMs() : 0L)
                    .confidence(dto.getConfidence())
                    .language(dto.getLanguage() != null ? dto.getLanguage() : meeting.getLanguage())
                    .source(result.provider())
                    .audioSource(AudioSource.UPLOAD)
                    .build());
            publish(meeting, saved, saved.getOriginalText());
        }
        return result;
    }

    // ------------------------------------------------ Fala reconhecida no navegador

    /**
     * Fala já reconhecida no navegador: Web Speech do microfone (audioSource=MIC) ou texto digitado
     * (MANUAL) com o falante escolhido pelo usuário.
     */
    @Transactional
    public TranscriptSegment processLiveTextSegment(UUID meetingId, UUID organizationId, UUID userId, String speakerLabel,
                                                   SpeakerRole speakerRoleHint, AudioSource audioSource, String text,
                                                   long startTimeMs, long endTimeMs) {
        Meeting meeting = findMeetingOfOrganization(meetingId, organizationId);
        AudioSource source = audioSource == AudioSource.MIC ? AudioSource.MIC : AudioSource.MANUAL;
        var speaker = speakerAttribution.forBrowserText(meeting, source, speakerLabel, speakerRoleHint, userId);
        return persistSpeech(meeting, speaker, text, startTimeMs, endTimeMs,
                source == AudioSource.MIC ? WEB_SPEECH_SOURCE : MANUAL_SOURCE, source, null, null);
    }

    /** Correção manual de quem falou (spec 003, RF-09): confiança passa a 1,0. */
    @Transactional
    public TranscriptSegment updateSegmentSpeaker(UUID segmentId, String speakerLabel, SpeakerRole role) {
        TranscriptSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Segmento de transcrição não encontrado: " + segmentId));
        if (speakerLabel == null || speakerLabel.isBlank()) {
            throw new IllegalArgumentException("speakerLabel é obrigatório.");
        }
        segment.setSpeakerLabel(speakerLabel.trim());
        if (role != null) segment.setSpeakerRole(role);
        segment.setSpeakerConfidence(BigDecimal.ONE);
        TranscriptSegment saved = segmentRepository.save(segment);
        try {
            messagingTemplate.convertAndSend("/topic/meetings/" + saved.getMeeting().getId() + "/transcripts", saved);
        } catch (Exception e) {
            log.warn("Erro ao emitir WebSocket para correção de falante: {}", e.getClass().getSimpleName());
        }
        return saved;
    }

    /**
     * Grava uma fala, fundindo com o segmento anterior do mesmo falante quando é continuação.
     * Publica no WebSocket e alimenta o agregador de IA.
     */
    private TranscriptSegment persistSpeech(Meeting meeting, SpeakerAttributionService.SpeakerAttribution speaker,
                                            String text, long startTimeMs, long endTimeMs, String provider,
                                            AudioSource audioSource, Long captureStartMs, Long captureEndMs) {
        String cleanText = text != null ? text.trim() : "";
        long safeEnd = endTimeMs > startTimeMs ? endTimeMs : startTimeMs + 3000L;

        // Continuação de uma fala imediatamente anterior do mesmo locutor (pausa curta)
        List<TranscriptSegment> existingSegments = segmentRepository.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(meeting.getId());
        if (!existingSegments.isEmpty() && !cleanText.isEmpty()) {
            TranscriptSegment last = existingSegments.get(existingSegments.size() - 1);
            String lastText = last.getOriginalText() != null ? last.getOriginalText().trim() : "";
            // Funde só falas do mesmo falante e da mesma origem (aba e microfone nunca se misturam)
            boolean sameSpeaker = Objects.equals(last.getSpeakerLabel(), speaker.label())
                    && Objects.equals(last.getAudioSource(), audioSource);
            long timeGapMs = Math.abs(startTimeMs - last.getStartTimeMs());

            if (sameSpeaker && shouldMergeWithPreviousSegment(lastText, cleanText, timeGapMs)) {
                last.setOriginalText(mergeSpeechFragments(lastText, cleanText));
                last.setEndTimeMs(Math.max(last.getEndTimeMs(), safeEnd));
                if (captureEndMs != null) last.setCaptureEndMs(captureEndMs);
                TranscriptSegment updated = segmentRepository.save(last);
                // A IA recebe só o trecho novo; o segmento fundido inteiro já foi analisado antes (RF-11)
                publish(meeting, updated, cleanText);
                return updated;
            }
        }

        TranscriptSegment saved = segmentRepository.save(TranscriptSegment.builder()
                .meeting(meeting)
                .participant(speaker.participant())
                .speakerLabel(speaker.label())
                .speakerRole(speaker.role())
                .speakerConfidence(speaker.confidence())
                .originalText(cleanText)
                .startTimeMs(startTimeMs)
                .endTimeMs(safeEnd)
                .confidence(BigDecimal.valueOf(0.96))
                .language(meeting.getLanguage() != null ? meeting.getLanguage() : "pt-BR")
                .source(provider)
                .audioSource(audioSource)
                .captureStartMs(captureStartMs)
                .captureEndMs(captureEndMs)
                .build());
        publish(meeting, saved, cleanText);
        return saved;
    }

    /**
     * Publica o segmento: agregador (resumo/tarefas/decisões), WebSocket e detecção de perguntas.
     *
     * @param newText somente o texto novo deste segmento (na fusão, o fragmento acrescentado)
     */
    private void publish(Meeting meeting, TranscriptSegment segment, String newText) {
        try {
            chunkAggregatorService.onNewSegment(meeting, segment, newText);
        } catch (Exception e) {
            log.warn("Erro ao alimentar agregador semântico de IA: {}", e.getClass().getSimpleName());
        }
        var event = new com.meetingintelligence.application.service.question.SegmentEvent(
                meeting.getId(), segment.getId(), newText, segment.getAudioSource(), segment.getSpeakerRole(),
                meeting.getUserInterviewRole(), segment.getCaptureStartMs(), segment.getCaptureEndMs());
        // Só após o commit: a detecção roda em outra thread e precisa enxergar o segmento gravado
        afterCommit(() -> questionDetection.onSegment(event));
        try {
            messagingTemplate.convertAndSend("/topic/meetings/" + meeting.getId() + "/transcripts", segment);
        } catch (Exception e) {
            log.warn("Erro ao emitir WebSocket para transcrição: {}", e.getClass().getSimpleName());
        }
    }

    private static void afterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }

    private Meeting findMeetingOfOrganization(UUID meetingId, UUID organizationId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ResourceNotFoundException("Reunião não encontrada: " + meetingId));
        if (organizationId != null && (meeting.getOrganization() == null
                || !organizationId.equals(meeting.getOrganization().getId()))) {
            // Mesmo status de "não existe", para não revelar reuniões de outras organizações
            throw new ResourceNotFoundException("Reunião não encontrada: " + meetingId);
        }
        return meeting;
    }


    private boolean shouldMergeWithPreviousSegment(String prevText, String newText, long timeGapMs) {
        if (prevText.isEmpty() || newText.isEmpty()) return false;
        String prevLower = prevText.toLowerCase();
        String newLower = newText.toLowerCase();

        // Se o novo trecho já contém o texto anterior inteiro (ou compartilha o mesmo início longo de pergunta), substitui/funde
        if (newLower.startsWith(prevLower) || prevLower.startsWith(newLower)) {
            return true;
        }
        int minPrefix = Math.min(20, Math.min(prevLower.length(), newLower.length()));
        if (minPrefix >= 15 && prevLower.substring(0, minPrefix).equals(newLower.substring(0, minPrefix)) && timeGapMs <= 18000L) {
            return true;
        }

        // Se o novo trecho começa com preposição/conjunção ou tem 1-3 palavras de complemento (ex: "de ordenação", "ordenação", "em micro serviços")
        boolean startsWithContinuation = newLower.startsWith("de ")
                || newLower.startsWith("da ")
                || newLower.startsWith("do ")
                || newLower.startsWith("dos ")
                || newLower.startsWith("das ")
                || newLower.startsWith("e ")
                || newLower.startsWith("ou ")
                || newLower.startsWith("em ")
                || newLower.startsWith("no ")
                || newLower.startsWith("na ")
                || newLower.startsWith("nos ")
                || newLower.startsWith("nas ")
                || newLower.startsWith("para ")
                || newLower.startsWith("por ")
                || newLower.startsWith("com ")
                || newLower.startsWith("que ")
                || newLower.startsWith("entre ")
                || newLower.startsWith("sobre ")
                || newLower.startsWith("baseada ");

        boolean prevEndsIncomplete = prevLower.endsWith(" de")
                || prevLower.endsWith(" da")
                || prevLower.endsWith(" do")
                || prevLower.endsWith(" dos")
                || prevLower.endsWith(" das")
                || prevLower.endsWith(" e")
                || prevLower.endsWith(" ou")
                || prevLower.endsWith(" em")
                || prevLower.endsWith(" para")
                || prevLower.endsWith(" com")
                || prevLower.endsWith(" por")
                || prevLower.endsWith(" que")
                || prevLower.endsWith(" entre")
                || prevLower.endsWith(" sobre")
                || prevLower.endsWith(" algoritmos")
                || prevLower.endsWith(" arquitetura")
                || prevLower.endsWith(" conceito");

        int newWordCount = newText.split("\\s+").length;
        int prevWordCount = prevText.split("\\s+").length;

        if ((startsWithContinuation || prevEndsIncomplete || newWordCount <= 3) && timeGapMs <= 12000L) {
            return true;
        }

        // Se chegou em menos de 6 segundos e a frase anterior era curta e não tinha pontuação final
        return timeGapMs <= 6000L && prevWordCount <= 9 && !prevText.endsWith("?") && !prevText.endsWith(".");
    }

    private String mergeSpeechFragments(String prevText, String newText) {
        String prevLower = prevText.toLowerCase();
        String newLower = newText.toLowerCase();
        if (newLower.startsWith(prevLower)) {
            return newText;
        }
        if (prevLower.startsWith(newLower)) {
            return prevText;
        }
        int minPrefix = Math.min(20, Math.min(prevLower.length(), newLower.length()));
        if (minPrefix >= 15 && prevLower.substring(0, minPrefix).equals(newLower.substring(0, minPrefix))) {
            return newText.length() >= prevText.length() ? newText : prevText;
        }
        return prevText + " " + newText;
    }

    @Transactional(readOnly = true)
    public List<TranscriptSegment> getTranscriptsByMeeting(UUID meetingId) {
        return segmentRepository.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(meetingId);
    }

    @Transactional
    public TranscriptSegment correctSegment(UUID segmentId, String newText) {
        TranscriptSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("Segmento de transcrição não encontrado: " + segmentId));

        // Preserva o texto original e grava a correção
        segment.setCorrectedText(newText);
        return segmentRepository.save(segment);
    }
}
