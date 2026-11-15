package com.meetingintelligence.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.service.ai.KnowledgeBaseService;
import com.meetingintelligence.application.service.question.CopilotPromptBuilder;
import com.meetingintelligence.application.service.question.InterviewCopilotService;
import com.meetingintelligence.application.service.question.QuestionDetectionService;
import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.copilot.CopilotAnswer;
import com.meetingintelligence.domain.copilot.CopilotAnswerValidator;
import com.meetingintelligence.domain.entity.AIInsight;
import com.meetingintelligence.domain.entity.InterviewQuestion;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.enums.InsightCategory;
import com.meetingintelligence.domain.question.UtteranceType;
import com.meetingintelligence.infrastructure.ai.AIOrchestrator;
import com.meetingintelligence.infrastructure.ai.GeminiLlmProvider;
import com.meetingintelligence.infrastructure.persistence.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewCopilotServiceTest {

    private static final String RESUME = "<knowledge_base>\n<document tipo=\"CURRÍCULO DO CANDIDATO (o usuário)\" arquivo=\"cv.pdf\">\n"
            + "Engenheiro Java e Spring Boot. RabbitMQ com Retry/DLQ. Garantia de consistência distribuída com Transactional Outbox no OrionPay.\n"
            + "</document>\n</knowledge_base>";

    private static final String VALID_JSON = """
            {"is_question_for_candidate": true, "depth": "ADVANCED",
             "spoken_answer": "Para garantir consistência entre serviços eu evito transação distribuída e uso eventos. Gravo o dado e o evento na mesma transação com o padrão Transactional Outbox e um processo separado publica no broker. No OrionPay usei isso com RabbitMQ, retry e DLQ, e os consumidores são idempotentes para lidar com mensagens repetidas.",
             "sections": {"direct_answer": "Consistência eventual com Outbox e Saga.",
                          "technical_explanation": "Outbox grava evento e dado juntos; Saga coordena compensações.",
                          "practical_example": "OrionPay com RabbitMQ e DLQ.",
                          "trade_offs": "Mais complexidade operacional em troca de resiliência."},
             "resume_facts_used": ["Transactional Outbox no OrionPay"], "assumptions": [], "confidence": 0.86}
            """;

    private final InterviewQuestionRepository questions = mock(InterviewQuestionRepository.class);
    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
    private final MeetingSummaryRepository summaries = mock(MeetingSummaryRepository.class);
    private final AIInsightRepository insights = mock(AIInsightRepository.class);
    private final KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
    private final AIOrchestrator orchestrator = mock(AIOrchestrator.class);
    private final QuestionDetectionService detection = mock(QuestionDetectionService.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final List<AIInsight> savedInsights = new ArrayList<>();
    private InterviewCopilotService service;
    private InterviewQuestion question;

    @BeforeEach
    void setUp() {
        UUID orgId = UUID.randomUUID();
        Meeting meeting = Meeting.builder().id(UUID.randomUUID()).organization(Organization.builder().id(orgId).build()).build();
        question = InterviewQuestion.builder().id(UUID.randomUUID()).meetingId(meeting.getId())
                .status(InterviewQuestion.Status.DETECTED).questionText("Como você garante consistência entre microsserviços?")
                .utteranceType(UtteranceType.DIRECT_QUESTION).score(BigDecimal.valueOf(0.9)).autoAnswer(true)
                .sourceSegmentIds(new UUID[]{UUID.randomUUID()}).captureStartMs(120_000L).build();

        when(questions.findById(question.getId())).thenReturn(Optional.of(question));
        when(questions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(meetings.findById(meeting.getId())).thenReturn(Optional.of(meeting));
        when(segments.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(any())).thenReturn(List.of());
        when(summaries.findByMeetingId(any())).thenReturn(Optional.empty());
        when(knowledgeBase.buildContext(eq(orgId), anyString())).thenReturn(RESUME);
        when(insights.save(any(AIInsight.class))).thenAnswer(inv -> {
            AIInsight i = inv.getArgument(0);
            if (i.getId() == null) {
                i.setId(UUID.randomUUID());
                savedInsights.add(i);
            }
            return i;
        });
        when(insights.findById(any())).thenAnswer(inv ->
                savedInsights.stream().filter(i -> i.getId().equals(inv.getArgument(0))).findFirst());

        service = new InterviewCopilotService(questions, meetings, segments, summaries, insights, knowledgeBase,
                orchestrator, new CopilotPromptBuilder(), detection, messaging, new ObjectMapper(), new SimpleMeterRegistry());
    }

    private static LLMResponse llm(String content) {
        return LLMResponse.builder().providerName("CLAUDE").modelName("claude-opus-5-5").content(content).build();
    }

    @Test
    @DisplayName("Resposta válida vira card QUESTION vinculado à pergunta, com a versão falada e as seções")
    void validAnswerPublished() {
        when(orchestrator.execute(any(), any(), eq("INTERVIEW_ANSWER"))).thenReturn(llm(VALID_JSON));

        service.answerNow(question.getId());

        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.ANSWERED);
        assertThat(savedInsights).hasSize(1);
        AIInsight card = savedInsights.get(0);
        assertThat(card.getCategory()).isEqualTo(InsightCategory.QUESTION);
        assertThat(card.getQuestionId()).isEqualTo(question.getId());
        assertThat(card.getDescription()).startsWith("Para garantir consistência").contains("**Trade-offs:**");
        assertThat(card.getMetadata()).contains("\"simulated\":false");
        assertThat(card.getSourceSegmentIds()).containsExactly(question.getSourceSegmentIds());
        verify(messaging).convertAndSend(contains("/insights"), any(AIInsight.class));
    }

    @Test
    @DisplayName("O prompt inclui currículo, papéis da conversa, a pergunta e as regras do copiloto")
    void promptContents() {
        when(orchestrator.execute(any(), any(), any())).thenReturn(llm(VALID_JSON));
        ArgumentCaptor<LLMRequest> req = ArgumentCaptor.forClass(LLMRequest.class);

        service.answerNow(question.getId());

        verify(orchestrator).execute(req.capture(), any(), any());
        assertThat(req.getValue().getSystemPrompt()).startsWith("INTERVIEW_COPILOT_V1").contains("Nunca invente empresas");
        assertThat(req.getValue().getUserPrompt()).contains("<knowledge_base>").contains("<question type=\"DIRECT_QUESTION\"")
                .contains("Como você garante consistência");
        assertThat(req.getValue().isJsonMode()).isTrue();
    }

    @Test
    @DisplayName("CA-03: JSON inválido e depois válido → 2 chamadas e card publicado")
    void retriesAfterInvalidJson() {
        when(orchestrator.execute(any(), any(), any())).thenReturn(llm("isto não é json"), llm("```json\n" + VALID_JSON + "\n```"));
        ArgumentCaptor<LLMRequest> req = ArgumentCaptor.forClass(LLMRequest.class);

        service.answerNow(question.getId());

        verify(orchestrator, times(2)).execute(req.capture(), any(), any());
        assertThat(req.getAllValues().get(1).getUserPrompt()).contains("foi rejeitada");
        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.ANSWERED);
    }

    @Test
    @DisplayName("CA-01: experiência inventada é rejeitada; persistindo, a pergunta falha sem publicar card")
    void inventedExperienceFails() {
        String invented = VALID_JSON.replace("No OrionPay usei isso com RabbitMQ", "Na minha experiência trabalhei com Kafka");
        when(orchestrator.execute(any(), any(), any())).thenReturn(llm(invented));

        service.answerNow(question.getId());

        verify(orchestrator, times(2)).execute(any(), any(), any());
        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.FAILED);
        assertThat(question.getFailureReason()).isEqualTo("VALIDATION_FAILED");
        assertThat(savedInsights).isEmpty();
    }

    @Test
    @DisplayName("CA-04: o modelo indica que não era pergunta → DISMISSED, sem card")
    void notAQuestionDismissed() {
        when(orchestrator.execute(any(), any(), any())).thenReturn(llm("{\"is_question_for_candidate\": false}"));

        service.answerNow(question.getId());

        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.DISMISSED);
        assertThat(savedInsights).isEmpty();
        verify(messaging, never()).convertAndSend(contains("/insights"), any(Object.class));
    }

    @Test
    @DisplayName("CA-05: gerar de novo atualiza o MESMO card, com answer_version 2")
    void regenerateUpdatesSameCard() {
        when(orchestrator.execute(any(), any(), any())).thenReturn(llm(VALID_JSON));

        service.answerNow(question.getId());
        UUID firstCard = question.getAnswerInsightId();
        service.answerNow(question.getId());

        assertThat(savedInsights).hasSize(1);
        assertThat(question.getAnswerInsightId()).isEqualTo(firstCard);
        assertThat(question.getAnswerVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("Todos os provedores indisponíveis → FAILED (LLM_UNAVAILABLE), sem afetar a transcrição")
    void llmUnavailable() {
        when(orchestrator.execute(any(), any(), any())).thenThrow(new RuntimeException("indisponível"));

        service.answerNow(question.getId());

        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.FAILED);
        assertThat(question.getFailureReason()).isEqualTo("LLM_UNAVAILABLE");
    }

    @Test
    @DisplayName("Pergunta descartada pelo usuário não é respondida")
    void dismissedQuestionSkipped() {
        question.setStatus(InterviewQuestion.Status.DISMISSED);
        service.answerNow(question.getId());
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("CA-06: simulação sem chave produz o contrato do copiloto, marcada como simulada e válida")
    void simulationProducesValidSimulatedAnswer() throws Exception {
        GeminiLlmProvider gemini = new GeminiLlmProvider("mock-key", "gemini-3.5-flash-lite", new ObjectMapper());
        CopilotPromptBuilder builder = new CopilotPromptBuilder();
        String userPrompt = builder.userPrompt("Qual a diferença entre Kafka e RabbitMQ?", "DIRECT_QUESTION", 0.9,
                List.of(), null, "", RESUME, null);

        LLMResponse response = gemini.generateCompletion(LLMRequest.builder()
                .systemPrompt(builder.systemPrompt()).userPrompt(userPrompt).jsonMode(true).build());
        CopilotAnswer answer = new ObjectMapper().readValue(response.getContent(), CopilotAnswer.class);

        assertThat(answer.simulated()).isTrue();
        assertThat(answer.questionForCandidate()).isTrue();
        assertThat(CopilotAnswerValidator.validate(answer, "Qual a diferença entre Kafka e RabbitMQ?", RESUME)).isEmpty();
        assertThat(response.getModelName()).isEqualTo("simulacao-local");
    }
}
