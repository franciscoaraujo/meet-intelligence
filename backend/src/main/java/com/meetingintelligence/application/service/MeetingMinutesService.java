package com.meetingintelligence.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.dto.MeetingMinutesDto;
import com.meetingintelligence.domain.entity.*;
import com.meetingintelligence.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class MeetingMinutesService {

    private final MeetingRepository meetingRepository;
    private final MeetingParticipantRepository participantRepository;
    private final MeetingSummaryRepository summaryRepository;
    private final MeetingDecisionRepository decisionRepository;
    private final MeetingTaskRepository taskRepository;
    private final TranscriptSegmentRepository transcriptRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public MeetingMinutesDto generateMinutes(UUID meetingId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Reunião não encontrada: " + meetingId));

        List<MeetingParticipant> participants = participantRepository.findByMeetingId(meetingId);
        Optional<MeetingSummary> summaryOpt = summaryRepository.findByMeetingId(meetingId);
        List<MeetingDecision> decisions = decisionRepository.findByMeetingIdOrderByTimestampMsAsc(meetingId);
        List<MeetingTask> tasks = taskRepository.findByMeetingIdOrderByCreatedAtAsc(meetingId);
        List<TranscriptSegment> segments = transcriptRepository.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(meetingId);

        List<String> participantNames = participants.stream()
                .map(p -> p.getName() + (p.getRole() != null ? " (" + p.getRole() + ")" : ""))
                .toList();

        List<String> keyTopicsList = new ArrayList<>();
        if (summaryOpt.isPresent() && summaryOpt.get().getKeyTopics() != null) {
            try {
                keyTopicsList = objectMapper.readValue(summaryOpt.get().getKeyTopics(), new TypeReference<List<String>>() {});
            } catch (Exception ignored) {}
        }

        List<MeetingMinutesDto.DecisionItemDto> decisionDtos = decisions.stream()
                .map(d -> MeetingMinutesDto.DecisionItemDto.builder()
                        .description(d.getDescription())
                        .justification(d.getJustification())
                        .responsible(d.getResponsibleParties())
                        .timeFormatted(formatMs(d.getTimestampMs()))
                        .build())
                .toList();

        List<MeetingMinutesDto.TaskItemDto> taskDtos = tasks.stream()
                .map(t -> MeetingMinutesDto.TaskItemDto.builder()
                        .description(t.getDescription())
                        .assignee(t.getAssigneeName() != null ? t.getAssigneeName() : "Não atribuído")
                        .dueDate(t.getDueDateRaw() != null ? t.getDueDateRaw() : "Não informado")
                        .priority(t.getPriority().name())
                        .status(t.getStatus().name())
                        .build())
                .toList();

        StringBuilder fullTranscript = new StringBuilder();
        for (TranscriptSegment s : segments) {
            String text = s.getCorrectedText() != null ? s.getCorrectedText() : s.getOriginalText();
            fullTranscript.append(String.format("[%s] %s: %s\n",
                    formatMs(s.getStartTimeMs()),
                    s.getSpeakerLabel() != null ? s.getSpeakerLabel() : "Participante",
                    text));
        }

        return MeetingMinutesDto.builder()
                .meetingId(meeting.getId())
                .title(meeting.getTitle())
                .organizationName(meeting.getOrganization().getName())
                .meetingType(meeting.getAiProfile() != null ? meeting.getAiProfile().getMeetingType().name() : "GERAL")
                .date(meeting.getCreatedAt())
                .durationFormatted("45 min")
                .participants(participantNames)
                .executiveSummary(summaryOpt.map(MeetingSummary::getExecutiveSummary).orElse("Resumo em processamento."))
                .keyTopics(keyTopicsList)
                .decisions(decisionDtos)
                .tasks(taskDtos)
                .fullTranscriptText(fullTranscript.toString())
                .build();
    }

    public byte[] exportAsTxt(UUID meetingId) {
        MeetingMinutesDto minutes = generateMinutes(meetingId);
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

        StringBuilder sb = new StringBuilder();
        sb.append("================================================================================\n");
        sb.append("               ATA DE REUNIÃO — MEETING INTELLIGENCE SAAS                       \n");
        sb.append("================================================================================\n\n");
        sb.append("REUNIÃO: ").append(minutes.getTitle()).append("\n");
        sb.append("ORGANIZAÇÃO: ").append(minutes.getOrganizationName()).append("\n");
        sb.append("DATA: ").append(minutes.getDate().format(dtf)).append("\n");
        sb.append("TIPO: ").append(minutes.getMeetingType()).append("\n\n");

        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("1. PARTICIPANTES PRESENTES\n");
        sb.append("--------------------------------------------------------------------------------\n");
        for (String p : minutes.getParticipants()) {
            sb.append(" - ").append(p).append("\n");
        }
        sb.append("\n");

        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("2. RESUMO EXECUTIVO\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(minutes.getExecutiveSummary()).append("\n\n");

        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("3. DECISÕES TOMADAS\n");
        sb.append("--------------------------------------------------------------------------------\n");
        if (minutes.getDecisions().isEmpty()) {
            sb.append("Nenhuma decisão formal registrada.\n");
        } else {
            for (MeetingMinutesDto.DecisionItemDto d : minutes.getDecisions()) {
                sb.append(String.format(" - [%s] %s (Responsável: %s)\n",
                        d.getTimeFormatted(), d.getDescription(), d.getResponsible() != null ? d.getResponsible() : "Equipe"));
                if (d.getJustification() != null) {
                    sb.append("   Justificativa: ").append(d.getJustification()).append("\n");
                }
            }
        }
        sb.append("\n");

        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("4. TAREFAS E PLANO DE AÇÃO (ACTION ITEMS)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        if (minutes.getTasks().isEmpty()) {
            sb.append("Nenhuma tarefa pendente registrada.\n");
        } else {
            for (MeetingMinutesDto.TaskItemDto t : minutes.getTasks()) {
                sb.append(String.format(" - [Prioridade: %s | Status: %s] %s\n   Responsável: %s | Prazo: %s\n",
                        t.getPriority(), t.getStatus(), t.getDescription(), t.getAssignee(), t.getDueDate()));
            }
        }
        sb.append("\n");

        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("5. TRANSCRIÇÃO COMPLETA COM TIMESTAMPS\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(minutes.getFullTranscriptText());

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] exportAsDocxHtml(UUID meetingId) {
        MeetingMinutesDto minutes = generateMinutes(meetingId);
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<style>");
        html.append("body { font-family: 'Segoe UI', Arial, sans-serif; line-height: 1.6; color: #1e293b; padding: 40px; }");
        html.append("h1 { color: #4338ca; border-bottom: 2px solid #6366f1; padding-bottom: 8px; }");
        html.append("h2 { color: #1e1b4b; border-bottom: 1px solid #cbd5e1; padding-bottom: 4px; margin-top: 24px; }");
        html.append(".meta { background: #f8fafc; padding: 12px; border-radius: 8px; border: 1px solid #e2e8f0; margin-bottom: 20px; }");
        html.append(".tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; background: #e0e7ff; color: #4338ca; }");
        html.append("ul { padding-left: 20px; }");
        html.append(".transcript { font-family: monospace; font-size: 12px; background: #f1f5f9; padding: 15px; border-radius: 8px; white-space: pre-wrap; }");
        html.append("</style></head><body>");

        html.append("<h1>Ata de Reunião — ").append(minutes.getTitle()).append("</h1>");
        html.append("<div class='meta'>");
        html.append("<p><strong>Organização:</strong> ").append(minutes.getOrganizationName()).append("<br>");
        html.append("<strong>Data:</strong> ").append(minutes.getDate().format(dtf)).append("<br>");
        html.append("<strong>Perfil de IA:</strong> <span class='tag'>").append(minutes.getMeetingType()).append("</span></p>");
        html.append("</div>");

        html.append("<h2>1. Participantes</h2><ul>");
        for (String p : minutes.getParticipants()) {
            html.append("<li>").append(p).append("</li>");
        }
        html.append("</ul>");

        html.append("<h2>2. Resumo Executivo</h2>");
        html.append("<p>").append(minutes.getExecutiveSummary()).append("</p>");

        html.append("<h2>3. Decisões Tomadas</h2><ul>");
        for (MeetingMinutesDto.DecisionItemDto d : minutes.getDecisions()) {
            html.append("<li><strong>[").append(d.getTimeFormatted()).append("] ").append(d.getDescription()).append("</strong>");
            if (d.getJustification() != null) html.append("<br><em>Justificativa:</em> ").append(d.getJustification());
            html.append("</li>");
        }
        html.append("</ul>");

        html.append("<h2>4. Tarefas e Próximos Passos</h2><ul>");
        for (MeetingMinutesDto.TaskItemDto t : minutes.getTasks()) {
            html.append("<li><strong>").append(t.getDescription()).append("</strong><br>");
            html.append("Responsável: ").append(t.getAssignee()).append(" | Prazo: ").append(t.getDueDate());
            html.append(" | Prioridade: <span class='tag'>").append(t.getPriority()).append("</span></li>");
        }
        html.append("</ul>");

        html.append("<h2>5. Transcrição na Íntegra</h2>");
        html.append("<div class='transcript'>").append(minutes.getFullTranscriptText()).append("</div>");

        html.append("</body></html>");

        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String formatMs(Long ms) {
        if (ms == null) return "00:00";
        long totalSecs = ms / 1000;
        long mins = totalSecs / 60;
        long secs = totalSecs % 60;
        return String.format("%02d:%02d", mins, secs);
    }
}

