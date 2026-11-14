package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.service.ai.TranscriptChunkAggregatorService;
import com.meetingintelligence.domain.entity.*;
import com.meetingintelligence.domain.enums.InsightCategory;
import com.meetingintelligence.infrastructure.persistence.repository.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}")
@RequiredArgsConstructor
@Tag(name = "AI Insights & Resumos", description = "Endpoints para recuperação de análises automáticas, resumo e tarefas")
public class InsightController {

    private final AIInsightRepository insightRepository;
    private final MeetingSummaryRepository summaryRepository;
    private final MeetingTaskRepository taskRepository;
    private final MeetingDecisionRepository decisionRepository;
    private final MeetingRepository meetingRepository;
    private final TranscriptChunkAggregatorService chunkAggregatorService;

    @GetMapping("/insights")
    @Operation(summary = "Listar insights da reunião", description = "Recupera decisões, tarefas, perguntas e análises de conteúdo")
    public ResponseEntity<List<AIInsight>> getInsights(
            @PathVariable UUID meetingId,
            @RequestParam(required = false) InsightCategory category) {

        List<AIInsight> insights = category != null
                ? insightRepository.findByMeetingIdAndCategory(meetingId, category)
                : insightRepository.findByMeetingIdOrderByCreatedAtAsc(meetingId);

        return ResponseEntity.ok(insights);
    }

    @GetMapping("/summary")
    @Operation(summary = "Obter resumo dinâmico", description = "Retorna a versão mais recente do resumo executivo acumulado")
    public ResponseEntity<MeetingSummary> getSummary(@PathVariable UUID meetingId) {
        Optional<MeetingSummary> summary = summaryRepository.findByMeetingId(meetingId);
        return summary.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/tasks")
    @Operation(summary = "Listar tarefas da reunião", description = "Retorna os action items detectados pela IA")
    public ResponseEntity<List<MeetingTask>> getTasks(@PathVariable UUID meetingId) {
        List<MeetingTask> tasks = taskRepository.findByMeetingIdOrderByCreatedAtAsc(meetingId);
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/decisions")
    @Operation(summary = "Listar decisões da reunião", description = "Retorna decisões tomadas e acordadas")
    public ResponseEntity<List<MeetingDecision>> getDecisions(@PathVariable UUID meetingId) {
        List<MeetingDecision> decisions = decisionRepository.findByMeetingIdOrderByTimestampMsAsc(meetingId);
        return ResponseEntity.ok(decisions);
    }

    @PostMapping("/insights/trigger")
    @Operation(summary = "Forçar análise dos blocos restantes", description = "Dispara consolidação de IA para os blocos acumulados")
    public ResponseEntity<Void> triggerAnalysis(@PathVariable UUID meetingId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Reunião não encontrada: " + meetingId));

        chunkAggregatorService.flushRemaining(meeting);
        return ResponseEntity.accepted().build();
    }
}

