package com.meetingintelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.dto.MeetingMinutesDto;
import com.meetingintelligence.application.service.MeetingMinutesService;
import com.meetingintelligence.domain.entity.*;
import com.meetingintelligence.domain.enums.MeetingType;
import com.meetingintelligence.domain.enums.TaskPriority;
import com.meetingintelligence.domain.enums.TaskStatus;
import com.meetingintelligence.infrastructure.persistence.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingMinutesServiceTest {

    @Mock
    private MeetingRepository meetingRepository;

    @Mock
    private MeetingParticipantRepository participantRepository;

    @Mock
    private MeetingSummaryRepository summaryRepository;

    @Mock
    private MeetingDecisionRepository decisionRepository;

    @Mock
    private MeetingTaskRepository taskRepository;

    @Mock
    private TranscriptSegmentRepository transcriptRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MeetingMinutesService minutesService;

    @Test
    @DisplayName("Deve consolidar ata de reunião e exportar arquivo TXT estruturado")
    void shouldGenerateMinutesAndExportTxt() {
        UUID meetingId = UUID.randomUUID();
        Organization org = Organization.builder().id(UUID.randomUUID()).name("Empresa SaaS").build();
        AIProfile profile = AIProfile.builder().meetingType(MeetingType.TECHNICAL).build();

        Meeting meeting = Meeting.builder()
                .id(meetingId)
                .organization(org)
                .title("Alinhamento de Arquitetura")
                .aiProfile(profile)
                .createdAt(OffsetDateTime.now())
                .build();

        when(meetingRepository.findById(meetingId)).thenReturn(Optional.of(meeting));

        List<MeetingParticipant> participants = List.of(
                MeetingParticipant.builder().name("Carlos").role("HOST").build(),
                MeetingParticipant.builder().name("Mariana").role("ENGINEER").build()
        );
        when(participantRepository.findByMeetingId(meetingId)).thenReturn(participants);

        MeetingSummary summary = MeetingSummary.builder()
                .executiveSummary("A equipe alinhou a integração de pgvector e LLM.")
                .keyTopics("[\"pgvector\", \"LLM\"]")
                .build();
        when(summaryRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(summary));

        List<MeetingDecision> decisions = List.of(
                MeetingDecision.builder()
                        .description("Adoção do pgvector")
                        .justification("Menor custo e nativo no Postgres")
                        .responsibleParties("Mariana")
                        .timestampMs(5000L)
                        .build()
        );
        when(decisionRepository.findByMeetingIdOrderByTimestampMsAsc(meetingId)).thenReturn(decisions);

        List<MeetingTask> tasks = List.of(
                MeetingTask.builder()
                        .description("Subir migration Flyway")
                        .assigneeName("Mariana")
                        .dueDateRaw("Sexta-feira")
                        .priority(TaskPriority.HIGH)
                        .status(TaskStatus.PENDING)
                        .build()
        );
        when(taskRepository.findByMeetingIdOrderByCreatedAtAsc(meetingId)).thenReturn(tasks);

        List<TranscriptSegment> segments = List.of(
                TranscriptSegment.builder()
                        .speakerLabel("Carlos")
                        .originalText("Iniciando gravação.")
                        .startTimeMs(1000L)
                        .endTimeMs(3000L)
                        .build()
        );
        when(transcriptRepository.findByMeetingIdOrderByStartTimeMsAsc(meetingId)).thenReturn(segments);

        // Executa geração da ata
        MeetingMinutesDto minutes = minutesService.generateMinutes(meetingId);

        assertNotNull(minutes);
        assertEquals("Alinhamento de Arquitetura", minutes.getTitle());
        assertEquals("Empresa SaaS", minutes.getOrganizationName());
        assertEquals(2, minutes.getParticipants().size());
        assertEquals(1, minutes.getDecisions().size());
        assertEquals(1, minutes.getTasks().size());

        // Valida exportação em TXT
        byte[] txtBytes = minutesService.exportAsTxt(meetingId);
        assertNotNull(txtBytes);
        String txtContent = new String(txtBytes);
        assertTrue(txtContent.contains("ATA DE REUNIÃO"));
        assertTrue(txtContent.contains("Adoção do pgvector"));
        assertTrue(txtContent.contains("Subir migration Flyway"));
    }
}

