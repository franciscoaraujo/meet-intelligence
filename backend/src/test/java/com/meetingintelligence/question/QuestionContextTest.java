package com.meetingintelligence.question;

import com.meetingintelligence.application.service.question.CopilotPromptBuilder;
import com.meetingintelligence.application.service.question.InterviewCopilot;
import com.meetingintelligence.application.service.question.QuestionDetectionService;
import com.meetingintelligence.application.service.question.SegmentEvent;
import com.meetingintelligence.domain.entity.InterviewQuestion;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.domain.enums.InterviewRole;
import com.meetingintelligence.domain.question.QuestionDeduplicator;
import com.meetingintelligence.domain.speech.AudioSource;
import com.meetingintelligence.domain.speech.SpeakerRole;
import com.meetingintelligence.infrastructure.async.MeetingSerialExecutor;
import com.meetingintelligence.infrastructure.persistence.repository.InterviewQuestionRepository;
import com.meetingintelligence.infrastructure.persistence.repository.TranscriptSegmentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Spec 015: a fala do entrevistador antes da pergunta acompanha a pergunta até o copiloto. */
class QuestionContextTest {

    private static final String SCENARIO = "aqui a gente tem um sistema de pagamentos com Kafka e às vezes o consumidor "
            + "processa a mesma mensagem duas vezes gerando cobrança duplicada";
    private static final String QUESTION = "Como você resolveria isso?";

    private final UUID meetingId = UUID.randomUUID();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<Runnable> delayed = new ArrayList<>();
    private final List<InterviewQuestion> stored = new CopyOnWriteArrayList<>();
    /** "Banco" de falas já transcritas, na ordem de gravação. */
    private final List<TranscriptSegment> db = new CopyOnWriteArrayList<>();
    private final InterviewQuestionRepository questions = mock(InterviewQuestionRepository.class);
    private final TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
    private final InterviewCopilot copilot = mock(InterviewCopilot.class);
    private QuestionDetectionService service;

    @BeforeEach
    void setUp() {
        when(questions.save(any(InterviewQuestion.class))).thenAnswer(inv -> {
            InterviewQuestion q = inv.getArgument(0);
            if (q.getId() == null) {
                q.setId(UUID.randomUUID());
                q.setCreatedAt(at(now.get()));
                stored.add(q);
            }
            return q;
        });
        when(questions.findTop20ByMeetingIdOrderByCreatedAtDesc(any())).thenAnswer(inv -> {
            List<InterviewQuestion> copy = new ArrayList<>(stored);
            copy.sort(Comparator.comparing(InterviewQuestion::getCreatedAt).reversed());
            return copy;
        });
        when(segments.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(meetingId)).thenAnswer(inv -> List.copyOf(db));
        service = new QuestionDetectionService(questions, segments, new MeetingSerialExecutor(Runnable::run), copilot,
                mock(SimpMessagingTemplate.class), new SimpleMeterRegistry(), (ms, task) -> delayed.add(task), now::get);
    }

    private static OffsetDateTime at(long ms) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
    }

    /** Fala gravada no banco sem passar pela detecção (contexto já fechado antes). */
    private TranscriptSegment stored(SpeakerRole role, String text) {
        TranscriptSegment s = TranscriptSegment.builder().id(UUID.randomUUID()).originalText(text)
                .speakerRole(role).createdAt(at(now.get())).build();
        db.add(s);
        return s;
    }

    /** Fala do entrevistador que passa pela detecção (gravada e depois enviada ao serviço). */
    private void interviewerSays(String text) {
        TranscriptSegment s = stored(SpeakerRole.REMOTE, text);
        service.onSegment(new SegmentEvent(meetingId, s.getId(), text, AudioSource.TAB, SpeakerRole.REMOTE,
                InterviewRole.CANDIDATE, 0L, 1000L));
    }

    private void silence(long ms) {
        now.addAndGet(ms);
        List<Runnable> due = new ArrayList<>(delayed);
        delayed.clear();
        due.forEach(Runnable::run);
    }

    @Test
    @DisplayName("CA-01: cenário + pausa + pergunta curta → uma pergunta com o cenário como contexto, respondida")
    void scenarioBecomesContext() {
        stored(SpeakerRole.REMOTE, SCENARIO);
        now.addAndGet(3000);
        interviewerSays(QUESTION);
        silence(2000);

        assertThat(stored).singleElement().satisfies(q -> {
            assertThat(q.getQuestionText()).isEqualTo(QUESTION);
            assertThat(q.getContextText()).isEqualTo(SCENARIO);
        });
        verify(copilot).answer(stored.get(0).getId());
    }

    @Test
    @DisplayName("CA-02: o contexto ignora a fala de antes da pergunta anterior, o que veio antes da fala do candidato e as falas posteriores")
    void contextBoundaries() {
        stored(SpeakerRole.REMOTE, "qual a diferença entre processo e thread");
        now.addAndGet(1000);
        InterviewQuestion previous = InterviewQuestion.builder().id(UUID.randomUUID()).meetingId(meetingId)
                .status(InterviewQuestion.Status.ANSWERED).questionText("qual a diferença entre processo e thread")
                .utteranceType(com.meetingintelligence.domain.question.UtteranceType.DIRECT_QUESTION)
                .score(java.math.BigDecimal.ONE).sourceSegmentIds(new UUID[0]).build();
        previous.setCreatedAt(at(now.get()));
        stored.add(previous);
        now.addAndGet(5000);
        stored(SpeakerRole.REMOTE, "perfeito era isso mesmo");
        now.addAndGet(1000);
        stored(SpeakerRole.SELF, "obrigado");
        now.addAndGet(1000);
        stored(SpeakerRole.REMOTE, SCENARIO);
        TranscriptSegment echo = stored(SpeakerRole.REMOTE, "eco qualquer");
        echo.setEchoSuspected(true);
        now.addAndGet(3000);
        interviewerSays(QUESTION);
        stored(SpeakerRole.REMOTE, "fala que veio depois da pergunta");
        silence(2000);

        InterviewQuestion q = stored.get(stored.size() - 1);
        assertThat(q.getQuestionText()).isEqualTo(QUESTION);
        assertThat(q.getContextText()).isEqualTo(SCENARIO);
    }

    @Test
    @DisplayName("RF-01: fala longa demais → o contexto guarda o fim (o mais perto da pergunta), até 1.000 caracteres")
    void longContextKeepsTheEnd() {
        for (int i = 0; i < 12; i++) {
            stored(SpeakerRole.REMOTE, "trecho " + i + " " + "x".repeat(150));
            now.addAndGet(1000);
        }
        interviewerSays(QUESTION);
        silence(2000);

        String context = stored.get(0).getContextText();
        assertThat(context.length()).isLessThanOrEqualTo(1000);
        assertThat(context).endsWith("trecho 11 " + "x".repeat(150)).doesNotContain("trecho 0 ");
    }

    @Test
    @DisplayName("CA-03: a mesma pergunta curta depois de dois cenários diferentes → duas perguntas e duas respostas")
    void sameShortQuestionAfterNewScenarioIsNew() {
        stored(SpeakerRole.REMOTE, SCENARIO);
        now.addAndGet(3000);
        interviewerSays(QUESTION);
        silence(2000);

        now.addAndGet(30_000);
        stored(SpeakerRole.REMOTE, "agora outro cenário a busca de produtos no catálogo está lenta com milhões de itens no Postgres");
        now.addAndGet(3000);
        interviewerSays(QUESTION);
        silence(2000);

        assertThat(stored).hasSize(2);
        assertThat(stored.get(1).getContextText()).contains("catálogo");
        verify(copilot, times(2)).answer(any());
    }

    @Test
    @DisplayName("CA-03: a mesma pergunta repetida sem cenário novo continua deduplicada")
    void repeatWithoutNewScenarioIsSame() {
        stored(SpeakerRole.REMOTE, SCENARIO);
        now.addAndGet(3000);
        interviewerSays(QUESTION);
        silence(2000);

        now.addAndGet(20_000);
        interviewerSays("Como você resolveria isso?");
        silence(2000);

        assertThat(stored).hasSize(1);
        verify(copilot, times(1)).answer(any());
    }

    @Test
    @DisplayName("RF-04: deduplicação — contexto novo com assunto diferente separa; contexto curto ou do mesmo assunto não")
    void deduplicatorUsesContext() {
        QuestionDeduplicator dedup = QuestionDeduplicator.withSpecDefaults();
        var previous = List.of(new QuestionDeduplicator.Candidate(UUID.randomUUID(), QUESTION, 1_000, SCENARIO));

        assertThat(dedup.decide(QUESTION, "a busca no catálogo de produtos está lenta no Postgres", 2_000, previous).kind())
                .isEqualTo(QuestionDeduplicator.Kind.NEW);
        assertThat(dedup.decide(QUESTION, "ok", 2_000, previous).kind())
                .isEqualTo(QuestionDeduplicator.Kind.SAME_QUESTION);
        assertThat(dedup.decide(QUESTION, "de novo o consumidor do Kafka processa a mensagem duas vezes", 2_000, previous).kind())
                .isEqualTo(QuestionDeduplicator.Kind.SAME_QUESTION);
        assertThat(dedup.decide(QUESTION, null, 2_000, previous).kind())
                .isEqualTo(QuestionDeduplicator.Kind.SAME_QUESTION);
    }

    @Test
    @DisplayName("CA-04: com contexto, o prompt traz <question_context> antes da pergunta e a instrução; sem contexto, nada muda")
    void promptWithContext() {
        CopilotPromptBuilder builder = new CopilotPromptBuilder();
        String with = builder.userPrompt(QUESTION, SCENARIO, "DIRECT_QUESTION", 0.9, List.of(), InterviewRole.CANDIDATE,
                "", "", null, false, "pt-BR", null);
        String without = builder.userPrompt(QUESTION, null, "DIRECT_QUESTION", 0.9, List.of(), InterviewRole.CANDIDATE,
                "", "", null, false, "pt-BR", null);

        assertThat(with).contains("<question_context>\n" + SCENARIO + "\n</question_context>")
                .contains("<context_instruction>").contains("\"question\"");
        assertThat(with.indexOf("<question_context>")).isLessThan(with.indexOf("<question type="));
        assertThat(without).doesNotContain("question_context").doesNotContain("<context_instruction>")
                .isEqualTo(builder.userPrompt(QUESTION, "DIRECT_QUESTION", 0.9, List.of(), InterviewRole.CANDIDATE,
                        "", "", null, false, "pt-BR", null));
    }
}
