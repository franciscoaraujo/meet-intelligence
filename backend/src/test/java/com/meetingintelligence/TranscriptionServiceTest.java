package com.meetingintelligence;

import com.meetingintelligence.application.dto.AudioChunkCommand;
import com.meetingintelligence.application.dto.ChunkTranscriptionResponse;
import com.meetingintelligence.application.exception.ResourceNotFoundException;
import com.meetingintelligence.application.service.TranscriptionService;
import com.meetingintelligence.application.service.ai.TranscriptChunkAggregatorService;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.entity.TranscriptIngestion;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.domain.speech.AudioSource;
import com.meetingintelligence.domain.speech.FailureReason;
import com.meetingintelligence.domain.speech.TranscriptionChainResult;
import com.meetingintelligence.domain.speech.TranscriptionChainResult.Attempt;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.persistence.repository.*;
import com.meetingintelligence.infrastructure.speech.SpeechToTextRouter;
import com.meetingintelligence.infrastructure.speech.TranscriptionChainFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TranscriptionServiceTest {

    @Mock private MeetingRepository meetingRepository;
    @Mock private TranscriptSegmentRepository segmentRepository;
    @Mock private OrganizationAiConfigRepository aiConfigRepository;
    @Mock private TranscriptIngestionRepository ingestionRepository;
    @Mock private SpeechToTextRouter speechToTextRouter;
    @Mock private TranscriptionChainFactory chainFactory;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private TranscriptChunkAggregatorService chunkAggregatorService;
    @Mock private com.meetingintelligence.application.service.question.QuestionDetectionService questionDetection;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private com.meetingintelligence.application.service.SpeakerAttributionService speakerAttribution;

    @InjectMocks
    private TranscriptionService transcriptionService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID meetingId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private Meeting meeting;
    private final List<TranscriptIngestion> ingestions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        meeting = Meeting.builder()
                .id(meetingId)
                .title("Entrevista")
                .language("pt-BR")
                .organization(Organization.builder().id(orgId).build())
                .build();
        when(meetingRepository.findById(meetingId)).thenReturn(Optional.of(meeting));
        when(aiConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.empty());
        when(chainFactory.build(any(), any())).thenReturn(List.of());
        when(segmentRepository.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(meetingId)).thenReturn(List.of());
        when(speakerAttribution.forLiveAudio(any(), any(), any())).thenAnswer(inv ->
                new com.meetingintelligence.application.service.SpeakerAttributionService.SpeakerAttribution(
                        inv.getArgument(1) == AudioSource.MIC ? com.meetingintelligence.domain.speech.SpeakerRole.SELF
                                : com.meetingintelligence.domain.speech.SpeakerRole.REMOTE,
                        inv.getArgument(1) == AudioSource.MIC ? "Francisco" : "Participante remoto (Meet)",
                        java.math.BigDecimal.valueOf(0.8), null));
        when(segmentRepository.save(any(TranscriptSegment.class))).thenAnswer(inv -> {
            TranscriptSegment s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });

        // Repositório de ingestões em memória
        when(ingestionRepository.saveAndFlush(any(TranscriptIngestion.class))).thenAnswer(inv -> {
            TranscriptIngestion ing = inv.getArgument(0);
            ing.setId(UUID.randomUUID());
            ingestions.add(ing);
            return ing;
        });
        when(ingestionRepository.save(any(TranscriptIngestion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ingestionRepository.findById(any())).thenAnswer(inv ->
                ingestions.stream().filter(i -> i.getId().equals(inv.getArgument(0))).findFirst());
        when(ingestionRepository.findByMeetingIdAndClientSegmentId(eq(meetingId), any())).thenAnswer(inv ->
                ingestions.stream().filter(i -> i.getClientSegmentId().equals(inv.getArgument(1))).findFirst());
    }

    private AudioChunkCommand chunk(UUID clientSegmentId) {
        return new AudioChunkCommand(meetingId, new byte[]{1, 2, 3}, "audio/wav", clientSegmentId,
                AudioSource.TAB, 125_000L, 131_400L, null);
    }

    private static TranscriptionChainResult transcribed(String provider, String text) {
        return new TranscriptionChainResult(TranscriptionChainResult.Status.TRANSCRIBED, provider,
                List.of(TranscriptionSegmentDto.builder().text(text).startTimeMs(0L).endTimeMs(0L).build()),
                List.of(new Attempt("GEMINI", "FAILED", FailureReason.HTTP_5XX, "HTTP 500", 10),
                        new Attempt(provider, "TRANSCRIBED", null, null, 20)));
    }

    @Test
    @DisplayName("CA-01: trecho ao vivo transcrito por fallback é gravado com o provedor real, origem e horário de captura")
    void transcribedChunkIsPersistedAndPublished() {
        when(speechToTextRouter.transcribe(any(), any())).thenReturn(transcribed("DEEPGRAM", "Explique o conceito de SOLID"));

        ChunkTranscriptionResponse response = transcriptionService.processAudioChunk(chunk(UUID.randomUUID()), orgId, userId);

        assertThat(response.getStatus()).isEqualTo("TRANSCRIBED");
        assertThat(response.getProvider()).isEqualTo("DEEPGRAM");
        assertThat(response.getSegments()).hasSize(1);
        TranscriptSegment seg = response.getSegments().get(0);
        assertThat(seg.getOriginalText()).isEqualTo("Explique o conceito de SOLID");
        assertThat(seg.getSource()).isEqualTo("DEEPGRAM");
        assertThat(seg.getAudioSource()).isEqualTo(AudioSource.TAB);
        assertThat(seg.getSpeakerRole()).isEqualTo(com.meetingintelligence.domain.speech.SpeakerRole.REMOTE);
        assertThat(seg.getSpeakerLabel()).isEqualTo("Participante remoto (Meet)");
        assertThat(seg.getStartTimeMs()).isEqualTo(125_000L);
        assertThat(seg.getCaptureEndMs()).isEqualTo(131_400L);
        assertThat(ingestions.get(0).getStatus()).isEqualTo(TranscriptIngestion.Status.TRANSCRIBED);
        assertThat(ingestions.get(0).getSegmentId()).isEqualTo(seg.getId());
        verify(messagingTemplate).convertAndSend(eq("/topic/meetings/" + meetingId + "/transcripts"), any(TranscriptSegment.class));
        verify(chunkAggregatorService).onNewSegment(any(), any(), eq("Explique o conceito de SOLID"));
        verify(questionDetection).onSegment(any());
    }

    @Test
    @DisplayName("CA-02 / P-01: falha de todos os provedores não é sucesso; nada é gravado nem publicado")
    void failedChainPersistsNothing() {
        when(speechToTextRouter.transcribe(any(), any())).thenReturn(new TranscriptionChainResult(
                TranscriptionChainResult.Status.FAILED, null, List.of(),
                List.of(new Attempt("GEMINI", "FAILED", FailureReason.HTTP_5XX, "HTTP 500", 10))));

        ChunkTranscriptionResponse response = transcriptionService.processAudioChunk(chunk(UUID.randomUUID()), orgId, userId);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getFailureReason()).isEqualTo("HTTP_5XX");
        assertThat(response.getSegments()).isEmpty();
        verify(segmentRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
        assertThat(ingestions.get(0).getStatus()).isEqualTo(TranscriptIngestion.Status.FAILED);
    }

    @Test
    @DisplayName("CA-03: sem fala é sucesso sem conteúdo; nada é gravado")
    void noSpeechPersistsNothing() {
        when(speechToTextRouter.transcribe(any(), any())).thenReturn(new TranscriptionChainResult(
                TranscriptionChainResult.Status.NO_SPEECH, "GEMINI", List.of(), List.of()));

        ChunkTranscriptionResponse response = transcriptionService.processAudioChunk(chunk(UUID.randomUUID()), orgId, userId);

        assertThat(response.getStatus()).isEqualTo("NO_SPEECH");
        verify(segmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("CA-06: o mesmo clientSegmentId devolve o resultado anterior sem nova transcrição")
    void sameClientSegmentIdIsIdempotent() {
        when(speechToTextRouter.transcribe(any(), any())).thenReturn(transcribed("DEEPGRAM", "Pergunta um"));
        UUID clientId = UUID.randomUUID();
        ChunkTranscriptionResponse first = transcriptionService.processAudioChunk(chunk(clientId), orgId, userId);
        when(segmentRepository.findById(first.getSegments().get(0).getId())).thenReturn(Optional.of(first.getSegments().get(0)));

        ChunkTranscriptionResponse second = transcriptionService.processAudioChunk(chunk(clientId), orgId, userId);

        assertThat(second.isDuplicate()).isTrue();
        assertThat(second.getStatus()).isEqualTo("TRANSCRIBED");
        assertThat(second.getSegments()).extracting(TranscriptSegment::getId)
                .containsExactly(first.getSegments().get(0).getId());
        verify(speechToTextRouter, times(1)).transcribe(any(), any());
        verify(segmentRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("Reenvio de trecho que falhou é processado de novo (falha não fica 'presa')")
    void failedChunkCanBeRetried() {
        UUID clientId = UUID.randomUUID();
        when(speechToTextRouter.transcribe(any(), any()))
                .thenReturn(new TranscriptionChainResult(TranscriptionChainResult.Status.FAILED, null, List.of(), List.of()))
                .thenReturn(transcribed("DEEPGRAM", "agora foi"));

        transcriptionService.processAudioChunk(chunk(clientId), orgId, userId);
        ChunkTranscriptionResponse retry = transcriptionService.processAudioChunk(chunk(clientId), orgId, userId);

        assertThat(retry.getStatus()).isEqualTo("TRANSCRIBED");
        assertThat(retry.isDuplicate()).isFalse();
        verify(speechToTextRouter, times(2)).transcribe(any(), any());
    }

    @Test
    @DisplayName("Reunião de outra organização responde como inexistente (404), sem transcrever")
    void otherOrganizationIsNotFound() {
        assertThatThrownBy(() -> transcriptionService.processAudioChunk(chunk(UUID.randomUUID()), UUID.randomUUID(), userId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(speechToTextRouter, never()).transcribe(any(), any());
    }

    @Test
    @DisplayName("Texto idêntico a um dos últimos 3 segmentos não é gravado de novo (comportamento preservado)")
    void repeatedTextIsNotPersistedAgain() {
        when(segmentRepository.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(meetingId)).thenReturn(List.of(
                TranscriptSegment.builder().originalText("Pergunta repetida").startTimeMs(0L).endTimeMs(1L).build()));
        when(speechToTextRouter.transcribe(any(), any())).thenReturn(transcribed("DEEPGRAM", "pergunta repetida"));

        ChunkTranscriptionResponse response = transcriptionService.processAudioChunk(chunk(UUID.randomUUID()), orgId, userId);

        assertThat(response.isDuplicate()).isTrue();
        verify(segmentRepository, never()).save(any());
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

        TranscriptSegment updated = transcriptionService.correctSegment(segmentId, "Texto corrigido com precisão");

        assertEquals("Texto com erro de grafia", updated.getOriginalText());
        assertEquals("Texto corrigido com precisão", updated.getCorrectedText());
    }
}
