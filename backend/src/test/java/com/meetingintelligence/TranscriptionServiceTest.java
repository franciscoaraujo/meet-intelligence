package com.meetingintelligence;

import com.meetingintelligence.application.service.TranscriptionService;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.MeetingParticipant;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.domain.speech.TranscriptionResult;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingParticipantRepository;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.persistence.repository.TranscriptSegmentRepository;
import com.meetingintelligence.infrastructure.speech.SpeechToTextRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TranscriptionServiceTest {

    @Mock
    private MeetingRepository meetingRepository;

    @Mock
    private TranscriptSegmentRepository segmentRepository;

    @Mock
    private MeetingParticipantRepository participantRepository;

    @Mock
    private SpeechToTextRouter speechToTextRouter;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private com.meetingintelligence.application.service.ai.TranscriptChunkAggregatorService chunkAggregatorService;

    @InjectMocks
    private TranscriptionService transcriptionService;

    @Test
    @DisplayName("Deve processar áudio, salvar segmentos com timestamps e emitir evento via WebSocket")
    void shouldProcessAudioAndSaveSegments() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = Meeting.builder()
                .id(meetingId)
                .title("Reunião de Arquitetura")
                .language("pt-BR")
                .build();

        when(meetingRepository.findById(meetingId)).thenReturn(Optional.of(meeting));

        List<TranscriptionSegmentDto> dtos = List.of(
                TranscriptionSegmentDto.builder()
                        .speakerLabel("Carlos")
                        .speakerTag(1)
                        .text("Olá time, iniciando transcrição.")
                        .startTimeMs(0L)
                        .endTimeMs(3000L)
                        .confidence(BigDecimal.valueOf(0.98))
                        .language("pt-BR")
                        .build()
        );

        TranscriptionResult mockResult = TranscriptionResult.builder()
                .provider("DEEPGRAM")
                .segments(dtos)
                .build();

        when(speechToTextRouter.transcribeAudio(any(), any(), any(), eq(true))).thenReturn(mockResult);
        when(participantRepository.findByMeetingId(meetingId)).thenReturn(new ArrayList<>());
        when(participantRepository.save(any(MeetingParticipant.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(segmentRepository.save(any(TranscriptSegment.class)))
                .thenAnswer(inv -> {
                    TranscriptSegment seg = inv.getArgument(0);
                    seg.setId(UUID.randomUUID());
                    return seg;
                });

        byte[] audioBytes = new byte[]{1, 2, 3, 4};
        List<TranscriptSegment> result = transcriptionService.processAudio(meetingId, audioBytes, "audio/webm", true);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Olá time, iniciando transcrição.", result.get(0).getOriginalText());
        assertEquals(0L, result.get(0).getStartTimeMs());
        assertEquals(3000L, result.get(0).getEndTimeMs());

        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/meetings/" + meetingId + "/transcripts"), any(TranscriptSegment.class));
    }

    @Test
    @DisplayName("Deve permitir correção manual mantendo o originalText preservado")
    void shouldCorrectSegmentPreservingOriginalText() {
        UUID segmentId = UUID.randomUUID();
        TranscriptSegment segment = TranscriptSegment.builder()
                .id(segmentId)
                .originalText("Texto com erro de grafia")
                .build();

        when(segmentRepository.findById(segmentId)).thenReturn(Optional.of(segment));
        when(segmentRepository.save(any(TranscriptSegment.class))).thenAnswer(inv -> inv.getArgument(0));

        TranscriptSegment updated = transcriptionService.correctSegment(segmentId, "Texto corrigido com precisão");

        assertEquals("Texto com erro de grafia", updated.getOriginalText());
        assertEquals("Texto corrigido com precisão", updated.getCorrectedText());
    }
}

