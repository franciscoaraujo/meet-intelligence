package com.meetingintelligence.application.service.question;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.service.ai.KnowledgeBaseService;
import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.ai.LlmStreamAbortedException;
import com.meetingintelligence.domain.copilot.CopilotAnswer;
import com.meetingintelligence.domain.copilot.CopilotAnswerValidator;
import com.meetingintelligence.domain.entity.AIInsight;
import com.meetingintelligence.domain.entity.InterviewQuestion;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.MeetingSummary;
import com.meetingintelligence.domain.enums.InsightCategory;
import com.meetingintelligence.domain.question.UtteranceType;
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

    static final String OPERATION = AIOrchestrator.INTERVIEW_ANSWER;
    static final int MAX_ATTEMPTS = 2;
    /** No máximo um evento DELTA a cada 100 ms por pergunta (spec 008, RNF-03). */
    static final long DELTA_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

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
    private final CopilotStreamPublisher streams;

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

            CopilotStreamPublisher.Session events = streams.open(meeting.getId(), question.getId());
            // "Pergunta terminou": o candidato confirmou que houve pergunta; o modelo não pode descartá-la
            boolean manual = question.getUtteranceType() == UtteranceType.MANUAL_TRIGGER;
            String correction = null;
            String lastFailure = "VALIDATION_FAILED";
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                LLMRequest request = LLMRequest.builder()
                        .systemPrompt(prompts.systemPrompt())
                        .userPrompt(prompts.userPrompt(question.getQuestionText(), question.getUtteranceType().name(),
                                question.getScore().doubleValue(), recent, meeting.getUserInterviewRole(), summary, kb, correction,
                                manual, meeting.getLanguage()))
                        .maxTokens(2048)
                        .jsonMode(true)
                        .build();

                // Streaming (spec 008): só o texto da spoken_answer vai para a UI, agrupado a cada 100 ms
                events.started(attempt);
                long attemptStart = System.nanoTime();
                SpokenAnswerExtractor extractor = new SpokenAnswerExtractor();
                DeltaThrottler throttler = new DeltaThrottler(DELTA_INTERVAL_NANOS, System::nanoTime, events::delta);
                int[] deltas = {0};
                long[] firstTextAt = {0};

                LLMResponse response;
                try {
                    response = orchestrator.executeStreaming(request, meeting, OPERATION, delta -> {
                        deltas[0]++;
                        String text = extractor.feed(delta);
                        if (!text.isEmpty()) {
                            if (firstTextAt[0] == 0) firstTextAt[0] = System.nanoTime();
                            throttler.offer(text);
                        }
                    });
                } catch (LlmStreamAbortedException e) {
                    throttler.discard();
                    events.rejected("STREAM_ABORTED");
                    lastFailure = "STREAM_ABORTED";
                    log.info("Copiloto: streaming interrompido na tentativa {}", attempt);
                    continue;
                } catch (RuntimeException e) {
                    events.failed("LLM_UNAVAILABLE");
                    fail(question, "LLM_UNAVAILABLE");
                    return;
                }
                throttler.flush();
                recordStreamMetrics(response, attempt, attemptStart, firstTextAt[0], deltas[0], extractor);

                CopilotAnswer answer;
                try {
                    answer = objectMapper.readValue(GrokLlmProvider.stripMarkdownFences(response.getContent()), CopilotAnswer.class);
                } catch (Exception e) {
                    correction = "o conteúdo não era um JSON válido no formato pedido";
                    lastFailure = "PARSE_ERROR";
                    meters.counter("copilot.validation.rejected", "rule", "PARSE_ERROR").increment();
                    events.rejected("PARSE_ERROR");
                    continue;
                }

                if (!answer.questionForCandidate() && manual) {
                    correction = "o candidato confirmou que a pergunta terminou; identifique a última pergunta do trecho, "
                            + "preencha \"question\" e responda com is_question_for_candidate true";
                    lastFailure = "NOT_A_QUESTION";
                    events.rejected("VALIDATION");
                    continue;
                }
                if (!answer.questionForCandidate()) {
                    events.failed("NOT_A_QUESTION");
                    updateStatus(question, InterviewQuestion.Status.DISMISSED, "NOT_A_QUESTION");
                    meters.counter("copilot.outcome", "status", "DISMISSED").increment();
                    log.info("Copiloto: o modelo indicou que não era pergunta ao candidato");
                    return;
                }

                List<String> problems = CopilotAnswerValidator.validate(answer, question.getQuestionText(), kb);
                if (!problems.isEmpty()) {
                    correction = String.join("; ", problems);
                    lastFailure = "VALIDATION_FAILED";
                    problems.forEach(p -> meters.counter("copilot.validation.rejected", "rule", ruleOf(p)).increment());
                    events.rejected("VALIDATION");
                    log.info("Copiloto: resposta rejeitada na tentativa {}: {}", attempt, problems.size());
                    continue;
                }

                if (manual && answer.interpretedQuestion() != null && !answer.interpretedQuestion().isBlank()) {
                    // O título do card passa a ser a pergunta entendida, não o trecho inteiro com comentários
                    question.setQuestionText(answer.interpretedQuestion().trim());
                }
                AIInsight saved = publishAnswer(meeting, question, answer, response);
                events.completed(saved.getId());
                meters.counter("copilot.outcome", "status", "ANSWERED").increment();
                Timer.builder("copilot.latency").tag("provider", String.valueOf(response.getProviderName()))
                        .tag("model", String.valueOf(response.getModelName()))
                        .register(meters).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                return;
            }
            events.failed(lastFailure);
            fail(question, lastFailure);
        } finally {
            MDC.remove("meetingId");
            MDC.remove("questionId");
        }
    }

    /** Tempo até o primeiro texto (RNF-01) e um log por tentativa, sem o conteúdo da resposta. */
    private void recordStreamMetrics(LLMResponse response, int attempt, long attemptStart, long firstTextAt, int deltas,
                                     SpokenAnswerExtractor extractor) {
        String provider = String.valueOf(response.getProviderName());
        String model = String.valueOf(response.getModelName());
        long ttftMs = firstTextAt == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(firstTextAt - attemptStart);
        if (firstTextAt != 0) {
            Timer.builder("copilot.ttft").tag("provider", provider).tag("model", model)
                    .register(meters).record(firstTextAt - attemptStart, TimeUnit.NANOSECONDS);
        } else {
            // O modelo não escreveu spoken_answer (ou não era pergunta): nada foi mostrado durante a geração
            meters.counter("copilot.stream.no_spoken_first", "provider", provider).increment();
        }
        log.info("Copiloto: tentativa={} provedor={} modelo={} ttft_ms={} total_ms={} deltas={} spoken_completo={}",
                attempt, provider, model, ttftMs, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - attemptStart),
                deltas, extractor.finished());
    }

    private AIInsight publishAnswer(Meeting meeting, InterviewQuestion question, CopilotAnswer answer, LLMResponse response) {
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
        return saved;
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
