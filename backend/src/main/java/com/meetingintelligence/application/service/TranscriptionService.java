package com.meetingintelligence.application.service;

import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.MeetingParticipant;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.domain.speech.TranscriptionResult;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingParticipantRepository;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.persistence.repository.TranscriptSegmentRepository;
import com.meetingintelligence.infrastructure.speech.SpeechToTextRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptionService {

    private final MeetingRepository meetingRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final MeetingParticipantRepository participantRepository;
    private final SpeechToTextRouter speechToTextRouter;
    private final SimpMessagingTemplate messagingTemplate;
    private final com.meetingintelligence.application.service.ai.TranscriptChunkAggregatorService chunkAggregatorService;

    @Transactional
    public List<TranscriptSegment> processAudio(UUID meetingId, byte[] audioBytes, String mimeType, boolean isLiveStream) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Reunião não encontrada: " + meetingId));

        log.info("Processando áudio para transcrição da reunião '{}' (Tamanho: {} bytes)", meeting.getTitle(), audioBytes.length);

        TranscriptionResult result = speechToTextRouter.transcribeAudio(
                audioBytes,
                mimeType,
                meeting.getLanguage(),
                isLiveStream
        );

        List<MeetingParticipant> existingParticipants = participantRepository.findByMeetingId(meetingId);
        List<TranscriptSegment> savedSegments = new ArrayList<>();

        for (TranscriptionSegmentDto dto : result.getSegments()) {
            // Localiza ou mapeia participante por tag de falante
            MeetingParticipant participant = findOrCreateParticipant(meeting, dto, existingParticipants);

            TranscriptSegment segment = TranscriptSegment.builder()
                    .meeting(meeting)
                    .participant(participant)
                    .speakerLabel(dto.getSpeakerLabel() != null ? dto.getSpeakerLabel() : "Participante")
                    .originalText(dto.getText())
                    .startTimeMs(dto.getStartTimeMs())
                    .endTimeMs(dto.getEndTimeMs())
                    .confidence(dto.getConfidence())
                    .language(dto.getLanguage() != null ? dto.getLanguage() : meeting.getLanguage())
                    .source(result.getProvider())
                    .build();

            TranscriptSegment saved = segmentRepository.save(segment);
            savedSegments.add(saved);

            // Envia para o agregador de blocos semânticos para análise automática de IA
            try {
                chunkAggregatorService.onNewSegment(meeting, saved);
            } catch (Exception e) {
                log.warn("Erro ao alimentar agregador semântico de IA: {}", e.getMessage());
            }

            // Notifica o frontend em tempo real via WebSocket
            try {
                messagingTemplate.convertAndSend("/topic/meetings/" + meetingId + "/transcripts", saved);
            } catch (Exception e) {
                log.warn("Erro ao emitir WebSocket para transcrição: {}", e.getMessage());
            }
        }

        return savedSegments;
    }

    @Transactional
    public TranscriptSegment processLiveTextSegment(UUID meetingId, String speakerLabel, String text, long startTimeMs, long endTimeMs) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Reunião não encontrada: " + meetingId));

        List<MeetingParticipant> existingParticipants = participantRepository.findByMeetingId(meetingId);
        MeetingParticipant participant = existingParticipants.isEmpty() ? null : existingParticipants.get(0);

        TranscriptSegment segment = TranscriptSegment.builder()
                .meeting(meeting)
                .participant(participant)
                .speakerLabel(speakerLabel != null && !speakerLabel.isBlank() ? speakerLabel : "Participante (Voz ao Vivo)")
                .originalText(text.trim())
                .startTimeMs(startTimeMs)
                .endTimeMs(endTimeMs > startTimeMs ? endTimeMs : startTimeMs + 3000L)
                .confidence(java.math.BigDecimal.valueOf(0.96))
                .language(meeting.getLanguage() != null ? meeting.getLanguage() : "pt-BR")
                .source("WEB_SPEECH_LIVE")
                .build();

        TranscriptSegment saved = segmentRepository.save(segment);

        // Alimenta o agregador semântico que dispara a IA (Insights, Resumo e pgvector)
        try {
            chunkAggregatorService.onNewSegment(meeting, saved);
        } catch (Exception e) {
            log.warn("Erro ao alimentar agregador semântico de IA: {}", e.getMessage());
        }

        // Notifica o frontend em tempo real via WebSocket STOMP
        try {
            messagingTemplate.convertAndSend("/topic/meetings/" + meetingId + "/transcripts", saved);
        } catch (Exception e) {
            log.warn("Erro ao emitir WebSocket para transcrição ao vivo: {}", e.getMessage());
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public List<TranscriptSegment> getTranscriptsByMeeting(UUID meetingId) {
        return segmentRepository.findByMeetingIdOrderByStartTimeMsAsc(meetingId);
    }

    @Transactional
    public TranscriptSegment correctSegment(UUID segmentId, String newText) {
        TranscriptSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("Segmento de transcrição não encontrado: " + segmentId));

        // Preserva o texto original e grava a correção
        segment.setCorrectedText(newText);
        return segmentRepository.save(segment);
    }

    private MeetingParticipant findOrCreateParticipant(
            Meeting meeting,
            TranscriptionSegmentDto dto,
            List<MeetingParticipant> participants) {

        if (dto.getSpeakerTag() == null) {
            return participants.isEmpty() ? null : participants.get(0);
        }

        for (MeetingParticipant p : participants) {
            if (dto.getSpeakerTag().equals(p.getSpeakerTag())) {
                return p;
            }
        }

        // Se for um novo falante detectado pela diarização, cria participante
        MeetingParticipant newParticipant = participantRepository.save(MeetingParticipant.builder()
                .meeting(meeting)
                .name(dto.getSpeakerLabel() != null ? dto.getSpeakerLabel() : "Participante " + dto.getSpeakerTag())
                .speakerTag(dto.getSpeakerTag())
                .role("PARTICIPANT")
                .build());

        participants.add(newParticipant);
        return newParticipant;
    }
}

