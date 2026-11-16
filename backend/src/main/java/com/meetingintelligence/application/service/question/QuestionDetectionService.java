package com.meetingintelligence.application.service.question;

import com.meetingintelligence.domain.entity.InterviewQuestion;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.domain.enums.InterviewRole;
import com.meetingintelligence.domain.language.InterviewLanguage;
import com.meetingintelligence.domain.question.EchoGuard;
import com.meetingintelligence.domain.question.QuestionDeduplicator;
import com.meetingintelligence.domain.question.UtteranceAssembler;
import com.meetingintelligence.domain.question.UtteranceAssembler.Fragment;
import com.meetingintelligence.domain.question.UtteranceAssembler.Utterance;
import com.meetingintelligence.domain.question.UtteranceClassifier;
import com.meetingintelligence.domain.question.UtteranceClassifier.Classification;
import com.meetingintelligence.domain.question.UtteranceType;
import com.meetingintelligence.domain.speech.AudioSource;
import com.meetingintelligence.domain.speech.SpeakerRole;
import com.meetingintelligence.infrastructure.async.MeetingSerialExecutor;
import com.meetingintelligence.infrastructure.persistence.repository.InterviewQuestionRepository;
import com.meetingintelligence.infrastructure.persistence.repository.TranscriptSegmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Detecção de perguntas em tempo real (spec 005). Para cada fala nova, na fila serial da reunião:
 * eco provável (MIC ≈ TAB) → montagem do enunciado por origem → classificação local → deduplicação
 * → registro da pergunta → copiloto (spec 004) só para perguntas acima do limiar.
 * Logs e métricas sem o texto falado.
 */
@Service
@Slf4j
public class QuestionDetectionService {

    /** Agendamento do fechamento por silêncio; substituível nos testes. */
    @FunctionalInterface
    public interface DelayedRunner {
        void runAfter(long delayMs, Runnable task);
    }

    private final InterviewQuestionRepository questions;
    private final TranscriptSegmentRepository segments;
    private final MeetingSerialExecutor serial;
    private final InterviewCopilot copilot;
    private final SimpMessagingTemplate messaging;
    private final MeterRegistry meters;
    private final DelayedRunner delays;
    private final LongSupplier clock;

    private final UtteranceAssembler assembler = UtteranceAssembler.withSpecDefaults();
    private final EchoGuard echoGuard = EchoGuard.withSpecDefaults();
    private final QuestionDeduplicator dedup = QuestionDeduplicator.withSpecDefaults();
    private final Map<UUID, InterviewRole> interviewRoles = new ConcurrentHashMap<>();
    /** Idioma da reunião (spec 011): escolhe as regras do classificador. */
    private final Map<UUID, InterviewLanguage> languages = new ConcurrentHashMap<>();

    @Autowired
    public QuestionDetectionService(InterviewQuestionRepository questions, TranscriptSegmentRepository segments,
                                    MeetingSerialExecutor serial, @Lazy InterviewCopilot copilot,
                                    SimpMessagingTemplate messaging, MeterRegistry meters, TaskScheduler scheduler) {
        this(questions, segments, serial, copilot, messaging, meters,
                (ms, task) -> scheduler.schedule(task, Instant.now().plusMillis(ms)), System::currentTimeMillis);
    }

    public QuestionDetectionService(InterviewQuestionRepository questions, TranscriptSegmentRepository segments,
                                    MeetingSerialExecutor serial, InterviewCopilot copilot,
                                    SimpMessagingTemplate messaging, MeterRegistry meters,
                                    DelayedRunner delays, LongSupplier clock) {
        this.questions = questions;
        this.segments = segments;
        this.serial = serial;
        this.copilot = copilot;
        this.messaging = messaging;
        this.meters = meters;
        this.delays = delays;
        this.clock = clock;
    }

    /** Fala nova gravada (chamada após o commit da transcrição). */
    public void onSegment(SegmentEvent event) {
        if (event.text() == null || event.text().isBlank() || event.audioSource() == AudioSource.UPLOAD) return;
        long receivedAt = clock.getAsLong();
        if (event.interviewRole() != null) interviewRoles.put(event.meetingId(), event.interviewRole());
        if (event.language() != null) languages.put(event.meetingId(), InterviewLanguage.fromTag(event.language()));
        serial.submit(event.meetingId(), () -> handle(event, receivedAt));
    }

    /** Janela máxima da fala considerada no botão "Pergunta terminou". */
    static final long MANUAL_WINDOW_MS = 120_000;
    static final int MANUAL_MAX_SEGMENTS = 10;

    /**
     * Botão "Pergunta terminou" (RF-09, revisto em 2026-11-15): o candidato sinaliza que o entrevistador
     * terminou de perguntar. O texto é toda a fala remota desde a pergunta anterior (até 120 s); o
     * copiloto identifica a pergunta no fim do trecho e sempre responde.
     */
    public void triggerManual(UUID meetingId) {
        serial.submit(meetingId, () -> {
            Optional<Utterance> open = assembler.closeNow(meetingId, AudioSource.TAB);
            Utterance utterance = remoteSpeechSinceLastQuestion(meetingId, open.orElse(null));
            if (utterance == null) utterance = open.orElseGet(() -> recentRemoteSpeech(meetingId));
            if (utterance == null) {
                log.info("Gatilho manual sem fala remota recente para a reunião {}", meetingId);
                return;
            }
            process(utterance, true);
        });
    }

    private void handle(SegmentEvent e, long receivedAt) {
        MDC.put("meetingId", e.meetingId().toString());
        MDC.put("segmentId", String.valueOf(e.segmentId()));
        try {
            if (e.audioSource() == AudioSource.MIC && echoGuard.isEcho(e.meetingId(), e.text(), receivedAt)) {
                segments.findById(e.segmentId()).ifPresent(s -> {
                    s.setEchoSuspected(true);
                    segments.save(s);
                });
                meters.counter("questions.echo_suppressed").increment();
                log.info("Fala do microfone marcada como eco provável");
                return;
            }
            if (e.audioSource() == AudioSource.TAB) {
                echoGuard.recordTab(e.meetingId(), e.text(), receivedAt);
            }

            Fragment fragment = new Fragment(e.segmentId(), e.text(), e.audioSource(), e.speakerRole(),
                    e.captureStartMs(), e.captureEndMs(), receivedAt);
            for (Utterance closed : assembler.append(e.meetingId(), fragment)) {
                process(closed, false);
            }
            UUID meetingId = e.meetingId();
            delays.runAfter(assembler.closeSilenceMs() + 50, () -> serial.submit(meetingId, () -> closeDue(meetingId)));
        } finally {
            MDC.remove("meetingId");
            MDC.remove("segmentId");
        }
    }

    private void closeDue(UUID meetingId) {
        for (Utterance closed : assembler.closeDue(meetingId, clock.getAsLong())) {
            process(closed, false);
        }
    }

    private void process(Utterance u, boolean forced) {
        InterviewRole role = interviewRoles.get(u.meetingId());
        Classification c = UtteranceClassifier.classify(u.text(), u.role(), role,
                languages.getOrDefault(u.meetingId(), InterviewLanguage.PT_BR));
        meters.counter("questions.classified", "type", c.type().name()).increment();

        if (!forced) {
            if (!c.type().isQuestion()) {
                if (c.type() == UtteranceType.TECHNICAL_DISCUSSION && c.score() >= 0.8) {
                    meters.counter("questions.technical_context").increment();
                }
                return;
            }
            if (c.score() < UtteranceClassifier.REVIEW_THRESHOLD) return;
        }
        UtteranceType type = forced ? UtteranceType.MANUAL_TRIGGER : c.type();
        boolean autoAnswer = forced || c.autoAnswer();

        long now = clock.getAsLong();
        List<InterviewQuestion> recent = questions.findTop20ByMeetingIdOrderByCreatedAtDesc(u.meetingId());
        List<QuestionDeduplicator.Candidate> candidates = recent.stream()
                .filter(q -> q.getStatus() != InterviewQuestion.Status.DISMISSED)
                .map(q -> new QuestionDeduplicator.Candidate(q.getId(), q.getQuestionText(),
                        q.getCreatedAt() != null ? q.getCreatedAt().toInstant().toEpochMilli() : now))
                .toList();
        QuestionDeduplicator.Decision decision = dedup.decide(u.text(), now, candidates);

        if (decision.kind() == QuestionDeduplicator.Kind.NEW) {
            InterviewQuestion q = questions.save(InterviewQuestion.builder()
                    .meetingId(u.meetingId())
                    .status(InterviewQuestion.Status.DETECTED)
                    .questionText(u.text())
                    .utteranceType(type)
                    .score(BigDecimal.valueOf(c.score()))
                    .autoAnswer(autoAnswer)
                    .sourceSegmentIds(u.segmentIds().toArray(UUID[]::new))
                    .captureStartMs(u.captureStartMs())
                    .captureEndMs(u.captureEndMs())
                    .build());
            meters.counter("questions.detected", "type", type.name()).increment();
            log.info("Pergunta detectada: id={} type={} score={} auto={}", q.getId(), type, c.score(), autoAnswer);
            publish(q);
            if (autoAnswer) requestAnswer(q);
            return;
        }

        InterviewQuestion existing = recent.stream().filter(q -> q.getId().equals(decision.existingId())).findFirst().orElseThrow();
        meters.counter("questions.deduplicated").increment();
        if (u.text().length() > existing.getQuestionText().length()) existing.setQuestionText(u.text());
        LinkedHashSet<UUID> ids = new LinkedHashSet<>(Arrays.asList(existing.getSourceSegmentIds()));
        ids.addAll(u.segmentIds());
        existing.setSourceSegmentIds(ids.toArray(UUID[]::new));
        if (u.captureEndMs() != null) existing.setCaptureEndMs(u.captureEndMs());
        boolean reanswer = decision.kind() == QuestionDeduplicator.Kind.SAME_QUESTION_GREW
                && (existing.isAutoAnswer() || autoAnswer);
        if (autoAnswer) existing.setAutoAnswer(true);
        InterviewQuestion saved = questions.save(existing);
        log.info("Pergunta repetida/continuada: id={} similaridade={} novaResposta={}",
                saved.getId(), String.format("%.2f", decision.similarity()), reanswer);
        publish(saved);
        boolean busy = saved.getStatus() == InterviewQuestion.Status.ANSWERING;
        if (!busy && (reanswer || (forced && saved.getStatus() != InterviewQuestion.Status.ANSWERED))) {
            requestAnswer(saved);
        }
    }

    private void requestAnswer(InterviewQuestion q) {
        meters.counter("copilot.calls").increment();
        copilot.answer(q.getId());
    }

    /**
     * Fala remota (sem eco) registrada depois da última pergunta não descartada, nos últimos 120 s
     * (no máximo 10 trechos). Inclui o enunciado em aberto, se ainda não estiver no banco.
     */
    private Utterance remoteSpeechSinceLastQuestion(UUID meetingId, Utterance open) {
        OffsetDateTime boundary = questions.findTop20ByMeetingIdOrderByCreatedAtDesc(meetingId).stream()
                .filter(q -> q.getStatus() != InterviewQuestion.Status.DISMISSED && q.getCreatedAt() != null)
                .map(InterviewQuestion::getCreatedAt)
                .findFirst().orElse(null);
        OffsetDateTime windowStart = OffsetDateTime.ofInstant(Instant.ofEpochMilli(clock.getAsLong() - MANUAL_WINDOW_MS),
                java.time.ZoneOffset.UTC);
        OffsetDateTime from = boundary == null || boundary.isBefore(windowStart) ? windowStart : boundary;

        List<TranscriptSegment> remote = new ArrayList<>();
        for (TranscriptSegment s : segments.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(meetingId)) {
            if (s.getSpeakerRole() == SpeakerRole.REMOTE && !s.isEchoSuspected()
                    && s.getCreatedAt() != null && s.getCreatedAt().isAfter(from)) {
                remote.add(s);
            }
        }
        if (remote.size() > MANUAL_MAX_SEGMENTS) remote = remote.subList(remote.size() - MANUAL_MAX_SEGMENTS, remote.size());

        List<UUID> ids = new ArrayList<>(remote.stream().map(TranscriptSegment::getId).toList());
        StringBuilder text = new StringBuilder(remote.stream()
                .map(s -> s.getCorrectedText() != null ? s.getCorrectedText() : s.getOriginalText())
                .reduce((a, b) -> a + " " + b).orElse(""));
        if (open != null && !ids.containsAll(open.segmentIds())) {
            if (!text.isEmpty()) text.append(' ');
            text.append(open.text());
            open.segmentIds().stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        }
        if (text.isEmpty()) return null;
        Long start = !remote.isEmpty() ? remote.get(0).getCaptureStartMs() : open.captureStartMs();
        Long end = open != null ? open.captureEndMs() : remote.get(remote.size() - 1).getCaptureEndMs();
        return new Utterance(meetingId, AudioSource.TAB, SpeakerRole.REMOTE, text.toString(), ids, start, end, clock.getAsLong());
    }

    private Utterance recentRemoteSpeech(UUID meetingId) {
        List<TranscriptSegment> all = segments.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(meetingId);
        List<TranscriptSegment> remote = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && remote.size() < 3; i--) {
            TranscriptSegment s = all.get(i);
            if (s.getSpeakerRole() == SpeakerRole.REMOTE && !s.isEchoSuspected()) remote.add(0, s);
        }
        if (remote.isEmpty()) return null;
        String text = remote.stream()
                .map(s -> s.getCorrectedText() != null ? s.getCorrectedText() : s.getOriginalText())
                .reduce((a, b) -> a + " " + b).orElse("");
        return new Utterance(meetingId, AudioSource.TAB, SpeakerRole.REMOTE, text,
                remote.stream().map(TranscriptSegment::getId).toList(),
                remote.get(0).getCaptureStartMs(), remote.get(remote.size() - 1).getCaptureEndMs(), clock.getAsLong());
    }

    public void publish(InterviewQuestion q) {
        try {
            messaging.convertAndSend("/topic/meetings/" + q.getMeetingId() + "/questions", q);
        } catch (Exception e) {
            log.warn("Erro ao publicar pergunta no WebSocket: {}", e.getClass().getSimpleName());
        }
    }
}
