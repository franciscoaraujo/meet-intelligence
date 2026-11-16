package com.meetingintelligence.application.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.entity.*;
import com.meetingintelligence.domain.enums.InsightCategory;
import com.meetingintelligence.domain.enums.TaskPriority;
import com.meetingintelligence.domain.enums.TaskStatus;
import com.meetingintelligence.infrastructure.ai.AIOrchestrator;
import com.meetingintelligence.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIInsightExtractorService {

    private final AIOrchestrator aiOrchestrator;
    private final PromptManagerService promptManagerService;
    private final MeetingSummaryRepository summaryRepository;
    private final AIInsightRepository insightRepository;
    private final MeetingTaskRepository taskRepository;
    private final MeetingDecisionRepository decisionRepository;
    private final TranscriptChunkRepository chunkRepository;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final KnowledgeBaseService knowledgeBaseService;

    @Async
    @Transactional
    public void processChunkAsync(Meeting meeting, TranscriptChunk chunk) {
        log.info("Iniciando extração assíncrona de insights para reunião '{}' - Chunk #{}",
                meeting.getTitle(), chunk.getChunkIndex());

        // 1. Recupera ou inicializa o resumo dinâmico existente
        MeetingSummary summary = summaryRepository.findByMeetingId(meeting.getId())
                .orElseGet(() -> summaryRepository.save(MeetingSummary.builder()
                        .meeting(meeting)
                        .version(1)
                        .executiveSummary("Início da reunião registrado.")
                        .keyTopics("[]")
                        .currentContext("Abertura da sessão.")
                        .lastProcessedChunkIndex(0)
                        .build()));

        // 2. Monta os prompts
        String systemPrompt = promptManagerService.buildSystemPrompt(meeting);
        String knowledgeContext = buildKnowledgeContext(meeting, chunk);
        String userPrompt = promptManagerService.buildIncrementalAnalysisUserPrompt(
                chunk,
                summary.getExecutiveSummary(),
                summary.getCurrentContext(),
                knowledgeContext
        );

        LLMRequest request = LLMRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .temperature(0.2)
                .jsonMode(true)
                .build();

        try {
            // 3. Executa a chamada com o AI Orchestrator
            LLMResponse response = aiOrchestrator.execute(request, meeting, "INCREMENTAL_INSIGHTS");

            // 4. Interpreta a resposta estruturada em JSON
            boolean parsed = parseAndPersistInsights(meeting, chunk, response.getContent(), summary);

            // 5. Só marca o bloco como processado se a resposta foi interpretada; senão ele fica
            //    pendente e é reprocessado em "Consolidar com IA"/fim da reunião (P-13)
            if (parsed) {
                chunk.setProcessedByAi(true);
                chunkRepository.save(chunk);
            }

        } catch (Exception e) {
            log.error("Erro durante extração de insights por IA no chunk #{}: {}",
                    chunk.getChunkIndex(), e.getMessage(), e);
        }
    }

    private String buildKnowledgeContext(Meeting meeting, TranscriptChunk chunk) {
        if (knowledgeBaseService == null || meeting.getOrganization() == null) return "";
        try {
            return knowledgeBaseService.buildContext(meeting.getOrganization().getId(), chunk.getTextContent());
        } catch (Exception e) {
            log.warn("Falha ao montar contexto da Base de Conhecimento: {}", e.getMessage());
            return "";
        }
    }

    private boolean parseAndPersistInsights(
            Meeting meeting,
            TranscriptChunk chunk,
            String jsonContent,
            MeetingSummary summary) {

        try {
            JsonNode root = objectMapper.readTree(jsonContent);

            // Atualiza o resumo dinâmico
            String delta = root.path("summary_delta").asText("");
            if (!delta.isBlank()) {
                String currentExec = summary.getExecutiveSummary() != null ? summary.getExecutiveSummary() : "";
                summary.setExecutiveSummary(currentExec + " " + delta);
                summary.setCurrentContext(delta);
                summary.setVersion(summary.getVersion() + 1);
                summary.setLastProcessedChunkIndex(chunk.getChunkIndex());
            }

            JsonNode topicsNode = root.path("key_topics");
            if (topicsNode.isArray()) {
                summary.setKeyTopics(topicsNode.toString());
            }

            MeetingSummary savedSummary = summaryRepository.save(summary);

            // Notifica frontend sobre atualização do resumo
            messagingTemplate.convertAndSend("/topic/meetings/" + meeting.getId() + "/summary", savedSummary);

            // Processa lista de insights
            JsonNode insightsNode = root.path("insights");
            if (insightsNode.isArray()) {
                for (JsonNode node : insightsNode) {
                    String catStr = node.path("category").asText("CONTENT_ANALYSIS");
                    InsightCategory category;
                    try {
                        category = InsightCategory.valueOf(catStr.toUpperCase());
                    } catch (Exception e) {
                        category = InsightCategory.CONTENT_ANALYSIS;
                    }

                    if (category == InsightCategory.QUESTION) {
                        // Perguntas são respondidas só pelo copiloto dedicado (spec 004, RF-10), evitando cards duplicados
                        continue;
                    }
                    String title = node.path("title").asText("Insight Identificado");
                    String desc = node.path("description").asText("");
                    String speakerRef = node.path("speaker_reference").asText(null);
                    double conf = node.path("confidence").asDouble(0.95);

                    // Verifica se já existe um insight recente na mesma reunião que era um fragmento incompleto desta mesma pergunta/fala
                    List<AIInsight> existingInsights = insightRepository.findByMeetingIdOrderByCreatedAtAsc(meeting.getId());
                    AIInsight insightToSave = null;
                    int checkStart = Math.max(0, existingInsights.size() - 4);
                    for (int i = existingInsights.size() - 1; i >= checkStart; i--) {
                        AIInsight prev = existingInsights.get(i);
                        if (prev.getCategory() == category && prev.getTitle() != null) {
                            String prevClean = prev.getTitle().replaceAll("[\"❓?]", "").trim().toLowerCase();
                            String newClean = title.replaceAll("[\"❓?]", "").trim().toLowerCase();
                            int commonCheck = Math.min(24, Math.min(prevClean.length(), newClean.length()));
                            if (newClean.startsWith(prevClean)
                                    || prevClean.startsWith(newClean)
                                    || (commonCheck >= 18 && prevClean.substring(0, commonCheck).equals(newClean.substring(0, commonCheck)))) {
                                prev.setTitle(title.length() >= prev.getTitle().length() ? title : prev.getTitle());
                                prev.setDescription(desc);
                                prev.setEndTimeMs(chunk.getEndTimeMs());
                                prev.setConfidence(BigDecimal.valueOf(conf));
                                insightToSave = prev;
                                break;
                            }
                        }
                    }

                    if (insightToSave == null) {
                        insightToSave = AIInsight.builder()
                                .meeting(meeting)
                                .category(category)
                                .title(title)
                                .description(desc)
                                .speakerReference(speakerRef)
                                .startTimeMs(chunk.getStartTimeMs())
                                .endTimeMs(chunk.getEndTimeMs())
                                .confidence(BigDecimal.valueOf(conf))
                                .chunkId(chunk.getId())
                                .build();
                    }

                    AIInsight savedInsight = insightRepository.save(insightToSave);

                    // Se for TAREFA (Action Item)
                    if (category == InsightCategory.TASK) {
                        String assignee = node.path("assignee_name").asText(speakerRef);
                        String priorityStr = node.path("priority").asText("MEDIUM");
                        TaskPriority priority;
                        try {
                            priority = TaskPriority.valueOf(priorityStr.toUpperCase());
                        } catch (Exception e) {
                            priority = TaskPriority.MEDIUM;
                        }

                        taskRepository.save(MeetingTask.builder()
                                .meeting(meeting)
                                .description(desc.isBlank() ? title : desc)
                                .assigneeName(assignee)
                                .dueDateRaw(node.path("due_date_raw").asText(null))
                                .priority(priority)
                                .status(TaskStatus.PENDING)
                                .confidence(BigDecimal.valueOf(conf))
                                .build());
                    }

                    // Se for DECISÃO
                    if (category == InsightCategory.DECISION) {
                        decisionRepository.save(MeetingDecision.builder()
                                .meeting(meeting)
                                .description(title)
                                .justification(desc)
                                .responsibleParties(speakerRef)
                                .timestampMs(chunk.getStartTimeMs())
                                .build());
                    }

                    // Notifica frontend sobre o novo insight em tempo real
                    messagingTemplate.convertAndSend("/topic/meetings/" + meeting.getId() + "/insights", savedInsight);
                }
            }

            return true;
        } catch (Exception e) {
            log.error("Falha ao interpretar o JSON retornado pelo LLM no bloco {}: {}", chunk.getChunkIndex(),
                    e.getClass().getSimpleName());
            return false;
        }
    }
}

