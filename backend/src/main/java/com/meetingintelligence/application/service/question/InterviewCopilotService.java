package com.meetingintelligence.application.service.question;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.service.ai.KnowledgeBaseService;
import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.copilot.CopilotAnswer;
import com.meetingintelligence.domain.copilot.CopilotAnswerValidator;
import com.meetingintelligence.domain.entity.AIInsight;
import com.meetingintelligence.domain.entity.InterviewQuestion;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.MeetingSummary;
import com.meetingintelligence.domain.enums.InsightCategory;
import com.meetingintelligence.infrastructure.ai.AIOrchestrator;
import com.meetingintelligence.infrastructure.ai.GrokLlmProvider;
import com.meetingintelligence.infrastructure.persistence.repository.AIInsightRepository;
import com.meetingintelligence.infrastructure.persistence.repository.InterviewQuestionRepository;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingSummaryRepository;
import com.meetingintelligence.infrastructure.persistence.repository.TranscriptSegmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Copiloto de entrevistas (spec 004): para uma pergunta detectada, gera UMA sugestão de resposta
 * falável, fiel ao currículo, e a publica como card vinculado à pergunta. Roda fora da fila serial
 * da reunião (@Async) para não atrasar a detecção das próximas perguntas.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewCopilotService implements InterviewCopilot {

    static final String OPERATION = "INTERVIEW_ANSWER";
    static final int MAX_ATTEMPTS = 2;

    private final InterviewQuestionRepository questions;
    private final MeetingRepository meetings;
    private final TranscriptSegmentRepository segments;
    private final MeetingSummaryRepository summaries;
    private final AIInsightRepository insights;
    private final KnowledgeBaseService knowledgeBase;
    private final AIOrchestrator orchestrator;
    private final CopilotPromptBuilder prompts;
    private final QuestionDetectionService detection;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    @Async
    @Override
    public void answer(UUID questionId) {
        answerNow(questionId);
    }

    /** Execução síncrona (usada pelo @Async e pelos testes). */
    public void answerNow(UUID questionId) {
        InterviewQuestion question = questions.findById(questionId).orElse(null);
        if (question == null || question.getStatus() == InterviewQuestion.Status.DISMISSED) return;
        MDC.put("meetingId", question.getMeetingId().toString());
        MDC.put("questionId", questionId.toString());
        long start = System.nanoTime();
        try {
            Meeting meeting = meetings.findById(question.getMeetingId()).orElseThrow();
            updateStatus(question, InterviewQuestion.Status.ANSWERING, null);

            String kb = meeting.getOrganization() != null
                    ? knowledgeBase.buildContext(meeting.getOrganization().getId(), question.getQuestionText()) : "";
            String summary = summaries.findByMeetingId(meeting.getId()).map(MeetingSummary::getExecutiveSummary).orElse("");
            var recent = segments.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(meeting.getId());

            String correction = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                LLMRequest request = LLMRequest.builder()
                        .systemPrompt(prompts.systemPrompt())
                        .userPrompt(prompts.userPrompt(question.getQuestionText(), question.getUtteranceType().name(),
                                question.getScore().doubleValue(), recent, meeting.getUserInterviewRole(), summary, kb, correction))
                        .maxTokens(2048)
                        .jsonMode(true)
                        .build();

                LLMResponse response;
                try {
                    response = orchestrator.execute(request, meeting, OPERATION);
                } catch (RuntimeException e) {
                    fail(question, "LLM_UNAVAILABLE");
                    return;
                }

                CopilotAnswer answer;
                try {
                    answer = objectMapper.readValue(GrokLlmProvider.stripMarkdownFences(response.getContent()), CopilotAnswer.class);
                } catch (Exception e) {
                    correction = "o conteúdo não era um JSON válido no formato pedido";
                    meters.counter("copilot.validation.rejected", "rule", "PARSE_ERROR").increment();
                    continue;
                }

                if (!answer.questionForCandidate()) {
                    updateStatus(question, InterviewQuestion.Status.DISMISSED, "NOT_A_QUESTION");
                    meters.counter("copilot.outcome", "status", "DISMISSED").increment();
                    log.info("Copiloto: o modelo indicou que não era pergunta ao candidato");
                    return;
                }

                List<String> problems = CopilotAnswerValidator.validate(answer, question.getQuestionText(), kb);
                if (!problems.isEmpty()) {
                    correction = String.join("; ", problems);
                    problems.forEach(p -> meters.counter("copilot.validation.rejected", "rule", ruleOf(p)).increment());
                    log.info("Copiloto: resposta rejeitada na tentativa {}: {}", attempt, problems.size());
                    continue;
                }

                publishAnswer(meeting, question, answer, response);
                meters.counter("copilot.outcome", "status", "ANSWERED").increment();
                Timer.builder("copilot.latency").tag("provider", String.valueOf(response.getProviderName()))
                        .register(meters).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                return;
            }
            fail(question, correction != null && correction.contains("JSON") ? "PARSE_ERROR" : "VALIDATION_FAILED");
        } finally {
            MDC.remove("meetingId");
            MDC.remove("questionId");
        }
    }

    private void publishAnswer(Meeting meeting, InterviewQuestion question, CopilotAnswer answer, LLMResponse response) {
        String metadata;
        try {
            metadata = objectMapper.writeValueAsString(java.util.Map.of(
                    "copilotAnswer", answer,
                    "simulated", answer.simulated(),
                    "provider", String.valueOf(response.getProviderName()),
                    "model", String.valueOf(response.getModelName())));
        } catch (Exception e) {
            metadata = null;
        }

        AIInsight insight = question.getAnswerInsightId() != null
                ? insights.findById(question.getAnswerInsightId()).orElse(null) : null;
        if (insight == null) {
            insight = AIInsight.builder()
                    .meeting(meeting)
                    .category(InsightCategory.QUESTION)
                    .questionId(question.getId())
                    .speakerReference("Entrevistador")
                    .build();
        }
        String title = "❓ " + question.getQuestionText();
        insight.setTitle(title.length() > 480 ? title.substring(0, 480) + "…" : title);
        insight.setDescription(answer.toDisplayText());
        insight.setMetadata(metadata);
        insight.setSourceSegmentIds(question.getSourceSegmentIds());
        insight.setStartTimeMs(question.getCaptureStartMs());
        insight.setEndTimeMs(question.getCaptureEndMs());
        insight.setConfidence(BigDecimal.valueOf(answer.confidence() != null ? Math.min(1, Math.max(0, answer.confidence())) : 0.8));
        AIInsight saved = insights.save(insight);

        question.setAnswerInsightId(saved.getId());
        question.setAnswerVersion(question.getAnswerVersion() + 1);
        updateStatus(question, InterviewQuestion.Status.ANSWERED, null);
        try {
            messaging.convertAndSend("/topic/meetings/" + meeting.getId() + "/insights", saved);
        } catch (Exception e) {
            log.warn("Erro ao publicar a resposta do copiloto: {}", e.getClass().getSimpleName());
        }
        log.info("Copiloto respondeu: versão={} simulado={} provedor={}", question.getAnswerVersion(),
                answer.simulated(), response.getProviderName());
    }

    private void fail(InterviewQuestion question, String reason) {
        updateStatus(question, InterviewQuestion.Status.FAILED, reason);
        meters.counter("copilot.outcome", "status", "FAILED").increment();
        log.warn("Copiloto falhou: reason={}", reason);
    }

    private void updateStatus(InterviewQuestion question, InterviewQuestion.Status status, String reason) {
        question.setStatus(status);
        question.setFailureReason(reason);
        detection.publish(questions.save(question));
    }

    private static String ruleOf(String problem) {
        if (problem.startsWith("experiência")) return "INVENTED_EXPERIENCE";
        if (problem.startsWith("pergunta técnica")) return "MISSING_SECTIONS";
        if (problem.startsWith("spoken_answer")) return "SPOKEN_LENGTH";
        return "OTHER";
    }
}
