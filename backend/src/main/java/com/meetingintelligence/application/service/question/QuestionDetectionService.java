package com.meetingintelligence.application.service.question;

import com.meetingintelligence.domain.entity.InterviewQuestion;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.domain.enums.InterviewRole;
import com.meetingintelligence.domain.language.InterviewLanguage;
import com.meetingintelligence.domain.question.EchoGuard;
import com.meetingintelligence.domain.question.FollowUpDetector;
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
        InterviewLanguage language = languages.getOrDefault(u.meetingId(), InterviewLanguage.PT_BR);
        Classification c = UtteranceClassifier.classify(u.text(), u.role(), role, language);
        meters.counter("questions.classified", "type", c.type().name()).increment();

        if (!forced) {
            boolean question = c.type().isQuestion() && c.score() >= UtteranceClassifier.REVIEW_THRESHOLD;
            if (!question) {
                // Spec 016: "e se o Redis cair" sem "?" não parece pergunta, mas pode completar a anterior
                if (u.role() != SpeakerRole.SELF && FollowUpDetector.startsWithMarker(u.text(), language)) {
                    List<InterviewQuestion> recent = questions.findTop20ByMeetingIdOrderByCreatedAtDesc(u.meetingId());
                    if (mergeIntoPrevious(u, c, false, recent, leadIn(u, recent), language)) return;
                }
                if (c.type() == UtteranceType.TECHNICAL_DISCUSSION && c.score() >= 0.8) {
                    meters.counter("questions.technical_context").increment();
                }
                return;
            }
        }
        UtteranceType type = forced ? UtteranceType.MANUAL_TRIGGER : c.type();
        boolean autoAnswer = forced || c.autoAnswer();

        long now = clock.getAsLong();
        List<InterviewQuestion> recent = questions.findTop20ByMeetingIdOrderByCreatedAtDesc(u.meetingId());
        List<QuestionDeduplicator.Candidate> candidates = recent.stream()
                .filter(q -> q.getStatus() != InterviewQuestion.Status.DISMISSED)
                .map(q -> new QuestionDeduplicator.Candidate(q.getId(), q.getQuestionText(),
                        q.getCreatedAt() != null ? q.getCreatedAt().toInstant().toEpochMilli() : now, q.getContextText()))
                .toList();
        // Spec 015: a pergunta manual já contém toda a fala desde a anterior (RN-02)
        LeadIn leadIn = forced ? LeadIn.NONE : leadIn(u, recent);
        String context = leadIn.context();
        QuestionDeduplicator.Decision decision = dedup.decide(u.text(), context, now, candidates);

        // Spec 016: complemento ("e mais uma coisa…") entra no card da pergunta anterior
        if (decision.kind() == QuestionDeduplicator.Kind.NEW && !forced
                && mergeIntoPrevious(u, c, true, recent, leadIn, language)) {
            return;
        }

        if (decision.kind() == QuestionDeduplicator.Kind.NEW) {
            InterviewQuestion q = questions.save(InterviewQuestion.builder()
                    .meetingId(u.meetingId())
                    .status(InterviewQuestion.Status.DETECTED)
                    .questionText(u.text())
                    .contextText(context)
                    .utteranceType(type)
                    .score(BigDecimal.valueOf(c.score()))
                    .autoAnswer(autoAnswer)
                    .sourceSegmentIds(u.segmentIds().toArray(UUID[]::new))
                    .captureStartMs(u.captureStartMs())
                    .captureEndMs(u.captureEndMs())
                    .build());
            meters.counter("questions.detected", "type", type.name()).increment();
            log.info("Pergunta detectada: id={} type={} score={} auto={} contexto_chars={}", q.getId(), type, c.score(),
                    autoAnswer, context == null ? 0 : context.length());
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
        if (existing.getContextText() == null && context != null) existing.setContextText(context);
        boolean reanswer = decision.kind() == QuestionDeduplicator.Kind.SAME_QUESTION_GREW
                && (existing.isAutoAnswer() || autoAnswer);
        if (autoAnswer) existing.setAutoAnswer(true);
        InterviewQuestion saved = questions.save(existing);
        log.info("Pergunta repetida/continuada: id={} similaridade={} novaResposta={}",
                saved.getId(), String.format("%.2f", decision.similarity()), reanswer);
        publish(saved);
        // Spec 016, RN-03: complemento repetido não gera nova resposta
        if (saved.getStatus() == InterviewQuestion.Status.MERGED) return;
        boolean busy = saved.getStatus() == InterviewQuestion.Status.ANSWERING;
        if (!busy && (reanswer || (forced && saved.getStatus() != InterviewQuestion.Status.ANSWERED))) {
            requestAnswer(saved);
        }
    }

    /**
     * Spec 016 (RF-01..RF-03): se a fala completa a última pergunta, grava-a como complemento (MERGED),
     * soma os trechos à principal e pede a resposta de novo com todas as partes.
     */
    private boolean mergeIntoPrevious(Utterance u, Classification c, boolean classifiedAsQuestion,
                                      List<InterviewQuestion> recent, LeadIn leadIn, InterviewLanguage language) {
        InterviewQuestion lastPart = recent.stream()
                .filter(q -> q.getStatus() != InterviewQuestion.Status.DISMISSED && q.getCreatedAt() != null)
                .findFirst().orElse(null);
        if (lastPart == null) return false;
        UUID principalId = lastPart.getStatus() == InterviewQuestion.Status.MERGED && lastPart.getMergedIntoId() != null
                ? lastPart.getMergedIntoId() : lastPart.getId();
        InterviewQuestion principal = recent.stream().filter(q -> q.getId().equals(principalId)).findFirst()
                .orElseGet(() -> questions.findById(principalId).orElse(null));
        if (principal == null || principal.getStatus() == InterviewQuestion.Status.DISMISSED
                || principal.getStatus() == InterviewQuestion.Status.MERGED
                || principal.getUtteranceType() == UtteranceType.MANUAL_TRIGGER) {
            return false;
        }
        List<InterviewQuestion> parts = recent.stream()
                .filter(q -> q.getStatus() == InterviewQuestion.Status.MERGED && principalId.equals(q.getMergedIntoId()))
                .toList();
        StringBuilder known = new StringBuilder(principal.getQuestionText());
        parts.forEach(p -> known.append(' ').append(p.getQuestionText()));
        boolean newTopic = QuestionDeduplicator.introducesNewTopic(leadIn.context(),
                new QuestionDeduplicator.Candidate(principalId, known.toString(), 0, principal.getContextText()));
        long gapMs = clock.getAsLong() - lastPart.getCreatedAt().toInstant().toEpochMilli();

        boolean followUp = FollowUpDetector.isFollowUp(new FollowUpDetector.Situation(u.text(), language,
                classifiedAsQuestion, gapMs, 1 + parts.size(), leadIn.candidateSpoke(), newTopic));
        if (!followUp) return false;

        InterviewQuestion complement = questions.save(InterviewQuestion.builder()
                .meetingId(u.meetingId())
                .status(InterviewQuestion.Status.MERGED)
                .mergedIntoId(principalId)
                .questionText(u.text())
                .contextText(leadIn.context())
                .utteranceType(c.type())
                .score(BigDecimal.valueOf(c.score()))
                .autoAnswer(false)
                .sourceSegmentIds(u.segmentIds().toArray(UUID[]::new))
                .captureStartMs(u.captureStartMs())
                .captureEndMs(u.captureEndMs())
                .build());
        LinkedHashSet<UUID> ids = new LinkedHashSet<>(Arrays.asList(principal.getSourceSegmentIds()));
        ids.addAll(u.segmentIds());
        principal.setSourceSegmentIds(ids.toArray(UUID[]::new));
        if (u.captureEndMs() != null) principal.setCaptureEndMs(u.captureEndMs());
        InterviewQuestion savedPrincipal = questions.save(principal);
        meters.counter("questions.merged").increment();
        log.info("Complemento juntado: id={} principal={} partes={} gap_ms={}", complement.getId(), principalId,
                2 + parts.size(), gapMs);
        publish(complement);
        publish(savedPrincipal);
        // RF-03: pontuação intermediária sem resposta automática continua esperando o pedido do usuário
        boolean waitingForUser = savedPrincipal.getStatus() == InterviewQuestion.Status.DETECTED && !savedPrincipal.isAutoAnswer();
        if (!waitingForUser) requestAnswer(savedPrincipal);
        return true;
    }

    /**
     * Spec 016, RF-06: "Separar" desfaz a junção. O complemento volta a ser pergunta própria e as duas
     * são respondidas de novo, cada uma com as suas partes.
     *
     * @throws IllegalStateException se a pergunta não for um complemento
     */
    public InterviewQuestion split(UUID meetingId, UUID complementId) {
        InterviewQuestion complement = questions.findById(complementId)
                .filter(q -> q.getMeetingId().equals(meetingId))
                .orElseThrow(() -> new IllegalArgumentException("Pergunta não encontrada: " + complementId));
        if (complement.getStatus() != InterviewQuestion.Status.MERGED || complement.getMergedIntoId() == null) {
            throw new IllegalStateException("A pergunta não é um complemento");
        }
        UUID principalId = complement.getMergedIntoId();
        complement.setStatus(InterviewQuestion.Status.DETECTED);
        complement.setMergedIntoId(null);
        complement.setAutoAnswer(true);
        InterviewQuestion savedComplement = questions.save(complement);

        InterviewQuestion principal = questions.findById(principalId).orElse(null);
        if (principal != null) {
            List<UUID> removed = Arrays.asList(savedComplement.getSourceSegmentIds());
            UUID[] remaining = Arrays.stream(principal.getSourceSegmentIds()).filter(id -> !removed.contains(id)).toArray(UUID[]::new);
            principal.setSourceSegmentIds(remaining);
            Long end = segments.findAllById(Arrays.asList(remaining)).stream()
                    .map(TranscriptSegment::getCaptureEndMs).filter(java.util.Objects::nonNull)
                    .max(Long::compare).orElse(principal.getCaptureEndMs());
            principal.setCaptureEndMs(end);
            principal = questions.save(principal);
            publish(principal);
        }
        publish(savedComplement);
        meters.counter("questions.split").increment();
        log.info("Junção desfeita: complemento={} principal={}", complementId, principalId);
        requestAnswer(savedComplement);
        if (principal != null && principal.getStatus() != InterviewQuestion.Status.DISMISSED
                && !(principal.getStatus() == InterviewQuestion.Status.DETECTED && !principal.isAutoAnswer())) {
            requestAnswer(principal);
        }
        return savedComplement;
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

    /** Contexto da pergunta (spec 015, RF-01): janela, número de trechos e tamanho máximos. */
    static final long LEAD_IN_WINDOW_MS = 90_000;
    static final int LEAD_IN_MAX_SEGMENTS = 8;
    static final int LEAD_IN_MAX_CHARS = 1000;

    /**
     * Fala do entrevistador logo antes da pergunta: trechos remotos sem eco, depois da pergunta anterior
     * não descartada e da última fala do candidato, nos últimos 90 s, e antes do primeiro trecho da
     * própria pergunta. Mantém o fim (o mais perto da pergunta) dentro dos limites. Nulo se não houver.
     */
    /** Contexto antes da pergunta (spec 015) e se o candidato falou desde a última pergunta (spec 016). */
    record LeadIn(String context, boolean candidateSpoke) {
        static final LeadIn NONE = new LeadIn(null, false);
    }

    LeadIn leadIn(Utterance u, List<InterviewQuestion> recent) {
        OffsetDateTime boundary = recent.stream()
                .filter(q -> q.getStatus() != InterviewQuestion.Status.DISMISSED && q.getCreatedAt() != null)
                .map(InterviewQuestion::getCreatedAt)
                .findFirst().orElse(null);
        OffsetDateTime windowStart = OffsetDateTime.ofInstant(Instant.ofEpochMilli(clock.getAsLong() - LEAD_IN_WINDOW_MS),
                java.time.ZoneOffset.UTC);
        OffsetDateTime from = boundary == null || boundary.isBefore(windowStart) ? windowStart : boundary;

        List<TranscriptSegment> before = new ArrayList<>();
        boolean reachedQuestion = false;
        boolean candidateSpoke = false;
        for (TranscriptSegment s : segments.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(u.meetingId())) {
            if (u.segmentIds().contains(s.getId())) {
                reachedQuestion = true;
                break;
            }
            if (s.isEchoSuspected() || s.getCreatedAt() == null || !s.getCreatedAt().isAfter(from)) continue;
            if (s.getSpeakerRole() == SpeakerRole.SELF) {
                before.clear(); // o candidato falou: o cenário anterior acabou
                candidateSpoke = true;
            }
            else if (s.getSpeakerRole() == SpeakerRole.REMOTE) before.add(s);
        }
        if (!reachedQuestion) return LeadIn.NONE;
        if (before.isEmpty()) return new LeadIn(null, candidateSpoke);

        java.util.Deque<String> parts = new java.util.ArrayDeque<>();
        int chars = 0;
        for (int i = before.size() - 1; i >= 0 && parts.size() < LEAD_IN_MAX_SEGMENTS; i--) {
            TranscriptSegment s = before.get(i);
            String text = (s.getCorrectedText() != null ? s.getCorrectedText() : s.getOriginalText());
            if (text == null || text.isBlank()) continue;
            text = text.trim();
            if (chars + text.length() > LEAD_IN_MAX_CHARS) {
                if (parts.isEmpty()) parts.addFirst("…" + text.substring(text.length() - LEAD_IN_MAX_CHARS + 1));
                break;
            }
            parts.addFirst(text);
            chars += text.length() + 1;
        }
        return new LeadIn(parts.isEmpty() ? null : String.join(" ", parts), candidateSpoke);
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
