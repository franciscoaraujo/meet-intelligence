package com.meetingintelligence.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.service.ai.KnowledgeBaseService;
import com.meetingintelligence.application.service.question.CopilotPromptBuilder;
import com.meetingintelligence.application.service.question.CopilotStreamPublisher;
import com.meetingintelligence.application.service.question.InterviewCopilotService;
import com.meetingintelligence.application.service.question.QuestionDetectionService;
import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.ai.LlmStreamAbortedException;
import com.meetingintelligence.domain.ai.LlmStreamListener;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewCopilotServiceTest {

    private static final String RESUME = "<knowledge_base>\n<document tipo=\"CURRÍCULO DO CANDIDATO (o usuário)\" arquivo=\"cv.pdf\">\n"
            + "Engenheiro Java e Spring Boot. RabbitMQ com Retry/DLQ. Garantia de consistência distribuída com Transactional Outbox no OrionPay.\n"
            + "</document>\n</knowledge_base>";

    private static final String SPOKEN = "Para garantir consistência entre serviços eu evito transação distribuída e uso eventos. Gravo o dado e o evento na mesma transação com o padrão Transactional Outbox e um processo separado publica no broker. No OrionPay usei isso com RabbitMQ, retry e DLQ, e os consumidores são idempotentes para lidar com mensagens \\\"repetidas\\\".";

    private static final String VALID_JSON = """
            {"is_question_for_candidate": true,
             "spoken_answer": "%s",
             "depth": "ADVANCED",
             "sections": {"direct_answer": "Consistência eventual com Outbox e Saga.",
                          "technical_explanation": "Outbox grava evento e dado juntos; Saga coordena compensações.",
                          "practical_example": "OrionPay com RabbitMQ e DLQ.",
                          "trade_offs": "Mais complexidade operacional em troca de resiliência."},
             "resume_facts_used": ["Transactional Outbox no OrionPay"], "assumptions": [], "confidence": 0.86}
            """.formatted(SPOKEN);

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
    private final List<CopilotStreamPublisher.Event> streamEvents = new ArrayList<>();
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
        doAnswer(inv -> {
            streamEvents.add(inv.getArgument(1));
            return null;
        }).when(messaging).convertAndSend(endsWith("/copilot"), any(CopilotStreamPublisher.Event.class));

        service = new InterviewCopilotService(questions, meetings, segments, summaries, insights, knowledgeBase,
                orchestrator, new CopilotPromptBuilder(), detection, messaging, new ObjectMapper(), new SimpleMeterRegistry(),
                new CopilotStreamPublisher(messaging));
    }

    private static LLMResponse llm(String content) {
        return LLMResponse.builder().providerName("CLAUDE").modelName("claude-opus-5-5").content(content).build();
    }

    /** Cada chamada ao orquestrador entrega o próximo conteúdo em 20 partes, como um provedor em streaming. */
    private void streamResponses(String... contents) {
        Deque<String> queue = new ArrayDeque<>(Arrays.asList(contents));
        when(orchestrator.executeStreaming(any(), any(), any(), any())).thenAnswer(inv -> {
            String content = queue.size() > 1 ? queue.poll() : queue.peek();
            LlmStreamListener listener = inv.getArgument(3);
            int step = Math.max(1, content.length() / 20);
            for (int i = 0; i < content.length(); i += step) {
                listener.onDelta(content.substring(i, Math.min(content.length(), i + step)));
            }
            return llm(content);
        });
    }

    private List<CopilotStreamPublisher.Type> eventTypes() {
        return streamEvents.stream().map(CopilotStreamPublisher.Event::type).toList();
    }

    private String draftOfAttempt(int attempt) {
        return streamEvents.stream()
                .filter(e -> e.type() == CopilotStreamPublisher.Type.DELTA && e.attempt() == attempt)
                .map(CopilotStreamPublisher.Event::text).collect(Collectors.joining());
    }

    @Test
    @DisplayName("Resposta válida vira card QUESTION vinculado à pergunta, com a versão falada e as seções")
    void validAnswerPublished() {
        streamResponses(VALID_JSON);

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
    @DisplayName("008 CA-01: STARTED, deltas com exatamente a spoken_answer (sem JSON) e COMPLETED, nessa ordem")
    void streamingEventsInOrder() throws Exception {
        streamResponses(VALID_JSON);

        service.answerNow(question.getId());

        assertThat(eventTypes().get(0)).isEqualTo(CopilotStreamPublisher.Type.STARTED);
        assertThat(eventTypes().get(eventTypes().size() - 1)).isEqualTo(CopilotStreamPublisher.Type.COMPLETED);
        assertThat(eventTypes().subList(1, eventTypes().size() - 1)).isNotEmpty()
                .allMatch(t -> t == CopilotStreamPublisher.Type.DELTA);
        String expected = new ObjectMapper().readValue(VALID_JSON, CopilotAnswer.class).spokenAnswer();
        assertThat(draftOfAttempt(1)).isEqualTo(expected).doesNotContain("{").doesNotContain("spoken_answer");
        assertThat(streamEvents).extracting(CopilotStreamPublisher.Event::seq).isSorted().doesNotHaveDuplicates();
        assertThat(streamEvents.get(streamEvents.size() - 1).insightId()).isEqualTo(savedInsights.get(0).getId());
    }

    @Test
    @DisplayName("O prompt v2 inclui currículo, papéis da conversa, a pergunta, as regras e a ordem dos campos")
    void promptContents() {
        streamResponses(VALID_JSON);
        ArgumentCaptor<LLMRequest> req = ArgumentCaptor.forClass(LLMRequest.class);

        service.answerNow(question.getId());

        verify(orchestrator).executeStreaming(req.capture(), any(), eq("INTERVIEW_ANSWER"), any());
        assertThat(req.getValue().getSystemPrompt()).startsWith("INTERVIEW_COPILOT_V2").contains("Nunca invente empresas")
                .contains("\"spoken_answer\" logo depois de \"is_question_for_candidate\"");
        assertThat(req.getValue().getUserPrompt()).contains("<knowledge_base>").contains("<question type=\"DIRECT_QUESTION\"")
                .contains("Como você garante consistência");
        assertThat(req.getValue().isJsonMode()).isTrue();
    }

    @Test
    @DisplayName("CA-03: JSON inválido e depois válido → 2 chamadas, REJECTED entre elas e card publicado")
    void retriesAfterInvalidJson() {
        streamResponses("isto não é json", "```json\n" + VALID_JSON + "\n```");
        ArgumentCaptor<LLMRequest> req = ArgumentCaptor.forClass(LLMRequest.class);

        service.answerNow(question.getId());

        verify(orchestrator, times(2)).executeStreaming(req.capture(), any(), any(), any());
        assertThat(req.getAllValues().get(1).getUserPrompt()).contains("foi rejeitada");
        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.ANSWERED);
        assertThat(streamEvents).anyMatch(e -> e.type() == CopilotStreamPublisher.Type.REJECTED && "PARSE_ERROR".equals(e.reason()));
    }

    @Test
    @DisplayName("008 CA-02: tentativa que inventa experiência → REJECTED; a segunda recomeça do zero e só ela é gravada")
    void rejectedDraftReplacedByNextAttempt() {
        String invented = VALID_JSON.replace("No OrionPay usei isso com RabbitMQ", "Na minha experiência trabalhei com Kafka");
        streamResponses(invented, VALID_JSON);

        service.answerNow(question.getId());

        assertThat(eventTypes()).containsSubsequence(CopilotStreamPublisher.Type.STARTED, CopilotStreamPublisher.Type.REJECTED,
                CopilotStreamPublisher.Type.STARTED, CopilotStreamPublisher.Type.COMPLETED);
        assertThat(streamEvents).filteredOn(e -> e.type() == CopilotStreamPublisher.Type.REJECTED)
                .singleElement().extracting(CopilotStreamPublisher.Event::reason).isEqualTo("VALIDATION");
        assertThat(draftOfAttempt(1)).contains("Kafka");
        assertThat(draftOfAttempt(2)).doesNotContain("Kafka").startsWith("Para garantir");
        assertThat(savedInsights).hasSize(1);
        assertThat(savedInsights.get(0).getDescription()).doesNotContain("Kafka");
    }

    @Test
    @DisplayName("CA-01 (004): experiência inventada é rejeitada; persistindo, a pergunta falha sem publicar card")
    void inventedExperienceFails() {
        String invented = VALID_JSON.replace("No OrionPay usei isso com RabbitMQ", "Na minha experiência trabalhei com Kafka");
        streamResponses(invented);

        service.answerNow(question.getId());

        verify(orchestrator, times(2)).executeStreaming(any(), any(), any(), any());
        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.FAILED);
        assertThat(question.getFailureReason()).isEqualTo("VALIDATION_FAILED");
        assertThat(savedInsights).isEmpty();
        assertThat(streamEvents.get(streamEvents.size() - 1).type()).isEqualTo(CopilotStreamPublisher.Type.FAILED);
    }

    @Test
    @DisplayName("008 CA-04: provedor cai depois de alguns trechos → tentativa descartada (STREAM_ABORTED) e nova tentativa")
    void abortedStreamIsDiscardedAndRetried() {
        when(orchestrator.executeStreaming(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    LlmStreamListener listener = inv.getArgument(3);
                    listener.onDelta("{\"is_question_for_candidate\": true, \"spoken_answer\": \"Eu usei");
                    throw new LlmStreamAbortedException("conexão caiu", new RuntimeException());
                })
                .thenAnswer(inv -> {
                    LlmStreamListener listener = inv.getArgument(3);
                    listener.onDelta(VALID_JSON);
                    return llm(VALID_JSON);
                });

        service.answerNow(question.getId());

        assertThat(streamEvents).anyMatch(e -> e.type() == CopilotStreamPublisher.Type.REJECTED && "STREAM_ABORTED".equals(e.reason()));
        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.ANSWERED);
        assertThat(savedInsights).hasSize(1);
    }

    @Test
    @DisplayName("008 CA-04: streaming interrompido em todas as tentativas → FAILED (STREAM_ABORTED), nada gravado")
    void abortedTwiceFails() {
        when(orchestrator.executeStreaming(any(), any(), any(), any()))
                .thenThrow(new LlmStreamAbortedException("conexão caiu", new RuntimeException()));

        service.answerNow(question.getId());

        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.FAILED);
        assertThat(question.getFailureReason()).isEqualTo("STREAM_ABORTED");
        assertThat(savedInsights).isEmpty();
    }

    @Test
    @DisplayName("CA-04: o modelo indica que não era pergunta → DISMISSED, sem card")
    void notAQuestionDismissed() {
        streamResponses("{\"is_question_for_candidate\": false}");

        service.answerNow(question.getId());

        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.DISMISSED);
        assertThat(savedInsights).isEmpty();
        verify(messaging, never()).convertAndSend(contains("/insights"), any(Object.class));
        assertThat(eventTypes()).doesNotContain(CopilotStreamPublisher.Type.DELTA);
    }

    @Test
    @DisplayName("Spec 015 CA-04: o cenário vai para o prompt e para a busca na base; o título vira a pergunta completa")
    void questionContextReachesPromptAndKnowledgeBase() {
        String scenario = "aqui temos um sistema de pagamentos com microsserviços e às vezes o saldo fica diferente entre eles";
        question.setQuestionText("Como você resolveria isso?");
        question.setContextText(scenario);
        String full = "Como você garantiria consistência de saldo entre microsserviços de pagamento?";
        streamResponses(VALID_JSON.replaceFirst("\\{", "{\"question\": \"" + full + "\", "));
        ArgumentCaptor<LLMRequest> req = ArgumentCaptor.forClass(LLMRequest.class);

        service.answerNow(question.getId());

        verify(orchestrator).executeStreaming(req.capture(), any(), any(), any());
        assertThat(req.getValue().getUserPrompt()).contains("<question_context>\n" + scenario).contains("<context_instruction>");
        verify(knowledgeBase).buildContext(any(), eq(scenario + " Como você resolveria isso?"));
        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.ANSWERED);
        // o que foi falado continua guardado para a deduplicação (RN-03); o card mostra a pergunta completa
        assertThat(question.getQuestionText()).isEqualTo("Como você resolveria isso?");
        assertThat(savedInsights.get(0).getTitle()).isEqualTo("❓ " + full);
    }

    @Test
    @DisplayName("Spec 015 RF-03: pergunta curta com cenário técnico exige as seções técnicas")
    void technicalScenarioRequiresSections() {
        question.setQuestionText("Como você resolveria isso?");
        question.setContextText("o consumidor do Kafka processa a mesma mensagem duas vezes");
        String noSections = VALID_JSON.replaceAll("\"sections\": \\{[^}]*\\}", "\"sections\": {}");
        streamResponses(noSections, VALID_JSON);
        ArgumentCaptor<LLMRequest> req = ArgumentCaptor.forClass(LLMRequest.class);

        service.answerNow(question.getId());

        verify(orchestrator, times(2)).executeStreaming(req.capture(), any(), any(), any());
        assertThat(req.getAllValues().get(1).getUserPrompt()).contains("pergunta técnica sem direct_answer");
        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.ANSWERED);
    }

    @Test
    @DisplayName("Pergunta terminou: o modelo não pode descartar; o título vira a pergunta entendida")
    void manualTriggerNeverDismissed() {
        question.setUtteranceType(UtteranceType.MANUAL_TRIGGER);
        question.setQuestionText("isso mesmo não pode ser zero no experimento de dupla fenda por que o padrão some");
        String withQuestion = VALID_JSON.replaceFirst("\\{", "{\"question\": \"Como você garante consistência entre microsserviços?\", ");
        streamResponses("{\"is_question_for_candidate\": false}", withQuestion);
        ArgumentCaptor<LLMRequest> req = ArgumentCaptor.forClass(LLMRequest.class);

        service.answerNow(question.getId());

        verify(orchestrator, times(2)).executeStreaming(req.capture(), any(), any(), any());
        assertThat(req.getAllValues().get(0).getUserPrompt()).contains("<manual_trigger>").contains("Pergunta terminou");
        assertThat(req.getAllValues().get(1).getUserPrompt()).contains("confirmou que a pergunta terminou");
        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.ANSWERED);
        assertThat(question.getQuestionText()).isEqualTo("Como você garante consistência entre microsserviços?");
        assertThat(savedInsights.get(0).getTitle()).contains("Como você garante consistência");
    }

    @Test
    @DisplayName("Pergunta terminou: se o modelo insistir que não é pergunta, fica FAILED (com 'Tentar novamente'), não some")
    void manualTriggerFailsInsteadOfDismissing() {
        question.setUtteranceType(UtteranceType.MANUAL_TRIGGER);
        streamResponses("{\"is_question_for_candidate\": false}");

        service.answerNow(question.getId());

        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.FAILED);
        assertThat(question.getFailureReason()).isEqualTo("NOT_A_QUESTION");
    }

    @Test
    @DisplayName("CA-05: gerar de novo atualiza o MESMO card, com answer_version 2")
    void regenerateUpdatesSameCard() {
        streamResponses(VALID_JSON);

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
        when(orchestrator.executeStreaming(any(), any(), any(), any())).thenThrow(new RuntimeException("indisponível"));

        service.answerNow(question.getId());

        assertThat(question.getStatus()).isEqualTo(InterviewQuestion.Status.FAILED);
        assertThat(question.getFailureReason()).isEqualTo("LLM_UNAVAILABLE");
        assertThat(streamEvents.get(streamEvents.size() - 1).reason()).isEqualTo("LLM_UNAVAILABLE");
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

    @Test
    @DisplayName("008 RN-03: a simulação sem chave também sai em partes, com spoken_answer logo no início")
    void simulationStreamsInParts() {
        GeminiLlmProvider gemini = new GeminiLlmProvider("mock-key", "gemini-3.5-flash-lite", new ObjectMapper());
        CopilotPromptBuilder builder = new CopilotPromptBuilder();
        String userPrompt = builder.userPrompt("Explique o conceito de SOLID", "DIRECT_QUESTION", 0.9,
                List.of(), null, "", RESUME, null);
        List<String> parts = new ArrayList<>();

        LLMResponse response = gemini.stream(LLMRequest.builder()
                .systemPrompt(builder.systemPrompt()).userPrompt(userPrompt).jsonMode(true).build(), null, null, parts::add);

        assertThat(parts.size()).isGreaterThan(5);
        assertThat(String.join("", parts)).isEqualTo(response.getContent());
        assertThat(response.getContent().indexOf("\"spoken_answer\"")).isLessThan(response.getContent().indexOf("\"depth\""));
    }
}
