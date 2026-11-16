package com.meetingintelligence.question;

import com.meetingintelligence.application.service.question.InterviewCopilot;
import com.meetingintelligence.application.service.question.QuestionDetectionService;
import com.meetingintelligence.application.service.question.SegmentEvent;
import com.meetingintelligence.domain.entity.InterviewQuestion;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.domain.enums.InterviewRole;
import com.meetingintelligence.domain.question.UtteranceType;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Spec 016: complementos ("e mais uma coisa…") entram no card da pergunta anterior. */
class MultiPartQuestionTest {

    private static final String MAIN = "Como você faria o cache desse serviço?";
    private static final String FOLLOW_UP = "E mais uma coisa, como você invalidaria esse cache?";

    private final UUID meetingId = UUID.randomUUID();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<Runnable> delayed = new ArrayList<>();
    private final List<InterviewQuestion> stored = new CopyOnWriteArrayList<>();
    private final List<TranscriptSegment> db = new CopyOnWriteArrayList<>();
    private final InterviewQuestionRepository questions = mock(InterviewQuestionRepository.class);
    private final TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
    private final InterviewCopilot copilot = mock(InterviewCopilot.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private QuestionDetectionService service;
    private long captureMs = 0;

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
        when(questions.findById(any())).thenAnswer(inv ->
                stored.stream().filter(q -> q.getId().equals(inv.getArgument(0))).findFirst());
        when(questions.findTop20ByMeetingIdOrderByCreatedAtDesc(any())).thenAnswer(inv -> {
            List<InterviewQuestion> copy = new ArrayList<>(stored);
            copy.sort(Comparator.comparing(InterviewQuestion::getCreatedAt).reversed());
            return copy;
        });
        when(segments.findByMeetingIdOrderByCreatedAtAscStartTimeMsAsc(meetingId)).thenAnswer(inv -> List.copyOf(db));
        when(segments.findAllById(any())).thenAnswer(inv -> {
            Collection<UUID> ids = new ArrayList<>();
            ((Iterable<UUID>) inv.getArgument(0)).forEach(ids::add);
            return db.stream().filter(s -> ids.contains(s.getId())).toList();
        });
        service = new QuestionDetectionService(questions, segments, new MeetingSerialExecutor(Runnable::run), copilot,
                mock(SimpMessagingTemplate.class), meters, (ms, task) -> delayed.add(task), now::get);
    }

    private static OffsetDateTime at(long ms) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
    }

    private TranscriptSegment store(SpeakerRole role, String text) {
        long start = captureMs;
        captureMs += 3000;
        TranscriptSegment s = TranscriptSegment.builder().id(UUID.randomUUID()).originalText(text).speakerRole(role)
                .captureStartMs(start).captureEndMs(start + 2500).createdAt(at(now.get())).build();
        db.add(s);
        return s;
    }

    private void interviewerSays(String text) {
        TranscriptSegment s = store(SpeakerRole.REMOTE, text);
        service.onSegment(new SegmentEvent(meetingId, s.getId(), text, AudioSource.TAB, SpeakerRole.REMOTE,
                InterviewRole.CANDIDATE, s.getCaptureStartMs(), s.getCaptureEndMs()));
    }

    private void silence(long ms) {
        now.addAndGet(ms);
        List<Runnable> due = new ArrayList<>(delayed);
        delayed.clear();
        due.forEach(Runnable::run);
    }

    private void ask(String text) {
        interviewerSays(text);
        silence(2000);
    }

    private InterviewQuestion byText(String text) {
        return stored.stream().filter(q -> q.getQuestionText().equals(text)).findFirst().orElseThrow();
    }

    private List<InterviewQuestion> visible() {
        return stored.stream().filter(q -> q.getStatus() != InterviewQuestion.Status.MERGED).toList();
    }

    @Test
    @DisplayName("CA-01: pergunta + 5 s + \"e mais uma coisa…\" → um card só; o complemento fica MERGED e a principal é respondida de novo")
    void followUpIsMerged() {
        ask(MAIN);
        now.addAndGet(5000);
        ask(FOLLOW_UP);

        InterviewQuestion principal = byText(MAIN);
        InterviewQuestion complement = byText(FOLLOW_UP);
        assertThat(visible()).containsExactly(principal);
        assertThat(complement.getStatus()).isEqualTo(InterviewQuestion.Status.MERGED);
        assertThat(complement.getMergedIntoId()).isEqualTo(principal.getId());
        assertThat(principal.getQuestionText()).isEqualTo(MAIN);
        assertThat(principal.getSourceSegmentIds()).hasSize(2).contains(complement.getSourceSegmentIds());
        assertThat(principal.getCaptureEndMs()).isEqualTo(complement.getCaptureEndMs());
        verify(copilot, times(2)).answer(principal.getId());
        verify(copilot, never()).answer(complement.getId());
        assertThat(meters.counter("questions.merged").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("RF-01a: \"e se o Redis cair\" sem \"?\" também completa a pergunta anterior")
    void followUpWithoutQuestionShape() {
        ask(MAIN);
        now.addAndGet(4000);
        ask("e se o Redis cair");

        assertThat(visible()).hasSize(1);
        assertThat(byText("e se o Redis cair").getStatus()).isEqualTo(InterviewQuestion.Status.MERGED);
    }

    @Test
    @DisplayName("CA-03: 45 s depois, mesmo com marcador, é pergunta nova")
    void tooLateIsNew() {
        ask(MAIN);
        now.addAndGet(45_000);
        ask("E como foi no seu último emprego?");

        assertThat(visible()).hasSize(2);
        assertThat(stored).noneMatch(q -> q.getStatus() == InterviewQuestion.Status.MERGED);
    }

    @Test
    @DisplayName("CA-03: sem marcador de continuação é pergunta nova")
    void withoutMarkerIsNew() {
        ask(MAIN);
        now.addAndGet(5000);
        ask("Como você invalidaria esse cache?");

        assertThat(visible()).hasSize(2);
    }

    @Test
    @DisplayName("CA-03: o candidato falou entre as duas → pergunta nova")
    void candidateSpokeIsNew() {
        ask(MAIN);
        now.addAndGet(3000);
        store(SpeakerRole.SELF, "eu usaria um cache distribuído com Redis");
        now.addAndGet(3000);
        ask(FOLLOW_UP);

        assertThat(visible()).hasSize(2);
    }

    @Test
    @DisplayName("CA-03: cenário novo antes do complemento (spec 015) → pergunta nova")
    void newScenarioIsNew() {
        ask(MAIN);
        now.addAndGet(3000);
        store(SpeakerRole.REMOTE, "mudando de assunto nosso time de dados usa Spark e Airflow para pipelines noturnos");
        now.addAndGet(3000);
        ask("E como você monitoraria isso?");

        assertThat(visible()).hasSize(2);
    }

    @Test
    @DisplayName("CA-03: no máximo 3 partes; a quarta vira pergunta nova")
    void atMostThreeParts() {
        ask(MAIN);
        now.addAndGet(4000);
        ask(FOLLOW_UP);
        now.addAndGet(4000);
        ask("E se o tráfego dobrar na Black Friday?");
        now.addAndGet(4000);
        ask("E também, como você mediria a taxa de acerto?");

        assertThat(stored.stream().filter(q -> q.getStatus() == InterviewQuestion.Status.MERGED)).hasSize(2);
        assertThat(visible()).hasSize(2);
        assertThat(visible().get(1).getQuestionText()).isEqualTo("E também, como você mediria a taxa de acerto?");
    }

    @Test
    @DisplayName("RN-03: complemento repetido não gera nova resposta")
    void repeatedFollowUpNotReanswered() {
        ask(MAIN);
        now.addAndGet(4000);
        ask(FOLLOW_UP);
        now.addAndGet(4000);
        ask("E mais uma coisa, como você invalidaria esse cache?");

        assertThat(stored).hasSize(2);
        verify(copilot, times(2)).answer(any());
    }

    @Test
    @DisplayName("RF-03: principal com pontuação intermediária (sem resposta automática) continua esperando o usuário")
    void midScorePrincipalWaitsForUser() {
        InterviewQuestion principal = InterviewQuestion.builder().meetingId(meetingId).status(InterviewQuestion.Status.DETECTED)
                .questionText("qual banco de dados").utteranceType(UtteranceType.DIRECT_QUESTION).score(BigDecimal.valueOf(0.5))
                .autoAnswer(false).sourceSegmentIds(new UUID[0]).build();
        questions.save(principal);
        now.addAndGet(4000);
        ask("E também, por que você escolheria esse banco?");

        assertThat(byText("E também, por que você escolheria esse banco?").getMergedIntoId()).isEqualTo(principal.getId());
        verifyNoInteractions(copilot);
    }

    @Test
    @DisplayName("CA-04: Separar → o complemento vira pergunta própria e as duas são respondidas de novo")
    void splitRestoresBothQuestions() {
        ask(MAIN);
        long mainEnd = byText(MAIN).getCaptureEndMs();
        now.addAndGet(5000);
        ask(FOLLOW_UP);
        InterviewQuestion principal = byText(MAIN);
        InterviewQuestion complement = byText(FOLLOW_UP);
        clearInvocations(copilot);

        service.split(meetingId, complement.getId());

        assertThat(complement.getStatus()).isEqualTo(InterviewQuestion.Status.DETECTED);
        assertThat(complement.getMergedIntoId()).isNull();
        assertThat(principal.getSourceSegmentIds()).hasSize(1).doesNotContain(complement.getSourceSegmentIds());
        assertThat(principal.getCaptureEndMs()).isEqualTo(mainEnd);
        assertThat(visible()).hasSize(2);
        verify(copilot).answer(complement.getId());
        verify(copilot).answer(principal.getId());
        assertThat(meters.counter("questions.split").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("CA-04: Separar uma pergunta que não é complemento → erro (409 no controller)")
    void splitNonComplementFails() {
        ask(MAIN);
        UUID id = byText(MAIN).getId();

        assertThatThrownBy(() -> service.split(meetingId, id)).isInstanceOf(IllegalStateException.class);
        assertThat(byText(MAIN).getStatus()).isNotEqualTo(InterviewQuestion.Status.MERGED);
        assertThat(Optional.ofNullable(byText(MAIN).getMergedIntoId())).isEmpty();
    }
}
