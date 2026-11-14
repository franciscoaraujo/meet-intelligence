package com.meetingintelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.service.ai.AIInsightExtractorService;
import com.meetingintelligence.application.service.ai.PromptManagerService;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.entity.*;
import com.meetingintelligence.domain.enums.InsightCategory;
import com.meetingintelligence.infrastructure.ai.AIOrchestrator;
import com.meetingintelligence.infrastructure.persistence.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AIInsightExtractorServiceTest {

    @Mock
    private AIOrchestrator aiOrchestrator;

    @Mock
    private PromptManagerService promptManagerService;

    @Mock
    private MeetingSummaryRepository summaryRepository;

    @Mock
    private AIInsightRepository insightRepository;

    @Mock
    private MeetingTaskRepository taskRepository;

    @Mock
    private MeetingDecisionRepository decisionRepository;

    @Mock
    private TranscriptChunkRepository chunkRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private com.meetingintelligence.application.service.ai.KnowledgeBaseService knowledgeBaseService;

    @InjectMocks
    private AIInsightExtractorService insightExtractorService;

    @Test
    @DisplayName("Deve extrair insights e tarefas e atualizar resumo dinâmico a partir do retorno da IA")
    void shouldExtractInsightsAndTasksFromLlmResponse() {
        UUID meetingId = UUID.randomUUID();
        Organization org = Organization.builder().id(UUID.randomUUID()).name("Tech").build();
        Meeting meeting = Meeting.builder().id(meetingId).title("Reunião Técnica").organization(org).build();

        TranscriptChunk chunk = TranscriptChunk.builder()
                .id(UUID.randomUUID())
                .meeting(meeting)
                .chunkIndex(1)
                .textContent("[Carlos]: Vamos aprovar a arquitetura do pgvector.")
                .startTimeMs(1000L)
                .endTimeMs(10000L)
                .build();

        MeetingSummary summary = MeetingSummary.builder()
                .meeting(meeting)
                .version(1)
                .executiveSummary("Início da reunião.")
                .build();

        when(summaryRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(summary));
        when(summaryRepository.save(any(MeetingSummary.class))).thenAnswer(inv -> inv.getArgument(0));
        when(insightRepository.save(any(AIInsight.class))).thenAnswer(inv -> inv.getArgument(0));

        String mockJsonResponse = """
        {
          "summary_delta": "Arquitetura com pgvector foi aprovada por unanimidade.",
          "key_topics": ["pgvector", "Arquitetura"],
          "insights": [
            {
              "category": "DECISION",
              "title": "Aprovação de pgvector",
              "description": "Utilizar PostgreSQL com pgvector para busca semântica.",
              "speaker_reference": "Carlos",
              "confidence": 0.98
            },
            {
              "category": "TASK",
              "title": "Criar migration Flyway para pgvector",
              "description": "Habilitar extensão vector e tabela de embeddings.",
              "assignee_name": "Mariana",
              "priority": "HIGH",
              "confidence": 0.95
            }
          ]
        }
        """;

        LLMResponse llmResponse = LLMResponse.builder()
                .providerName("GEMINI")
                .modelName("gemini-1.5-flash")
                .content(mockJsonResponse)
                .promptTokens(150)
                .completionTokens(120)
                .totalTokens(270)
                .estimatedCostUsd(BigDecimal.valueOf(0.00005))
                .latencyMs(200L)
                .build();

        when(aiOrchestrator.execute(any(), eq(meeting), eq("INCREMENTAL_INSIGHTS"))).thenReturn(llmResponse);

        // Executa extração
        insightExtractorService.processChunkAsync(meeting, chunk);

        // Valida que decisões e tarefas foram persistidas
        verify(insightRepository, atLeastOnce()).save(any(AIInsight.class));
        verify(decisionRepository, times(1)).save(any(MeetingDecision.class));
        verify(taskRepository, times(1)).save(any(MeetingTask.class));
        verify(chunkRepository, times(1)).save(any(TranscriptChunk.class));

        // Valida emissão de WebSocket para atualização da interface
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/meetings/" + meetingId + "/summary"), any(MeetingSummary.class));
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/meetings/" + meetingId + "/insights"), any(AIInsight.class));
    }
}
