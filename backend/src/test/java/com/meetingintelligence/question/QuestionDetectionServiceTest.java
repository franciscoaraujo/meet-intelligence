package com.meetingintelligence.question;

import com.meetingintelligence.application.service.question.InterviewCopilot;
import com.meetingintelligence.application.service.question.QuestionDetectionService;
import com.meetingintelligence.application.service.question.SegmentEvent;
import com.meetingintelligence.domain.entity.InterviewQuestion;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.domain.enums.InterviewRole;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QuestionDetectionServiceTest {

    private final UUID meetingId = UUID.randomUUID();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<Runnable> delayed = new ArrayList<>();
    private final List<InterviewQuestion> stored = new CopyOnWriteArrayList<>();
    private final InterviewQuestionRepository questions = mock(InterviewQuestionRepository.class);
    private final TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
    private final InterviewCopilot copilot = mock(InterviewCopilot.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private QuestionDetectionService service;

    @BeforeEach
    void setUp() {
        when(questions.save(any(InterviewQuestion.class))).thenAnswer(inv -> {
            InterviewQuestion q = inv.getArgument(0);
            if (q.getId() == null) {
                q.setId(UUID.randomUUID());
                q.setCreatedAt(OffsetDateTime.ofInstant(Instant.ofEpochMilli(now.get()), ZoneOffset.UTC));
                stored.add(q);
            }
            return q;
        });
        when(questions.findTop20ByMeetingIdOrderByCreatedAtDesc(any())).thenAnswer(inv -> {
            List<InterviewQuestion> copy = new ArrayList<>(stored);
            copy.sort(Comparator.comparing(InterviewQuestion::getCreatedAt).reversed());
            return copy;
        });
        service = build(new MeetingSerialExecutor(Runnable::run));
    }

    private QuestionDetectionService build(MeetingSerialExecutor serial) {
        return new QuestionDetectionService(questions, segments, serial, copilot, mock(SimpMessagingTemplate.class),
                meters, (ms, task) -> delayed.add(task), now::get);
    }

    private void say(AudioSource source, String text) {
        SpeakerRole role = source == AudioSource.MIC ? SpeakerRole.SELF : SpeakerRole.REMOTE;
        service.onSegment(new SegmentEvent(meetingId, UUID.randomUUID(), text, source, role, InterviewRole.CANDIDATE, 0L, 1000L));
    }

    /** Avança o relógio e executa os fechamentos por silêncio agendados. */
    private void silence(long ms) {
        now.addAndGet(ms);
        List<Runnable> due = new ArrayList<>(delayed);
        delayed.clear();
        due.forEach(Runnable::run);
    }

    @Test
    @DisplayName("CA-01: duas perguntas seguidas do entrevistador viram duas perguntas e duas respostas")
    void twoQuestionsAreSeparated() {
        say(AudioSource.TAB, "explique o conceito de SOLID");
        silence(3000);
        say(AudioSource.TAB, "como você trataria a resiliência de microsserviços");
        silence(2000);

        assertThat(stored).extracting(InterviewQuestion::getQuestionText)
                .containsExactly("explique o conceito de SOLID", "como você trataria a resiliência de microsserviços");
        verify(copilot, times(2)).answer(any());
    }

    @Test
    @DisplayName("RF-04: fragmentos de uma mesma pergunta formam uma pergunta só")
    void fragmentsAreJoined() {
        say(AudioSource.TAB, "explique o conceito");
        now.addAndGet(800);
        say(AudioSource.TAB, "de SOLID com um exemplo");
        silence(2000);

        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getQuestionText()).isEqualTo("explique o conceito de SOLID com um exemplo");
        assertThat(stored.get(0).getSourceSegmentIds()).hasSize(2);
    }

    @Test
    @DisplayName("CA-02: menção técnica do entrevistador não gera pergunta nem chama o copiloto")
    void technicalMentionIgnored() {
        say(AudioSource.TAB, "aqui a gente usa Kafka e Spring");
        silence(2000);

        assertThat(stored).isEmpty();
        verifyNoInteractions(copilot);
    }

    @Test
    @DisplayName("CA-04: a mesma pergunta repetida em 30 s fica registrada uma vez e é respondida uma vez")
    void repeatedQuestionDeduplicated() {
        say(AudioSource.TAB, "Explique o SOLID");
        silence(2000);
        now.addAndGet(30_000);
        say(AudioSource.TAB, "Pode explicar o SOLID de novo?");
        silence(2000);

        assertThat(stored).hasSize(1);
        verify(copilot, times(1)).answer(any());
        assertThat(meters.counter("questions.deduplicated").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("CA-05: microfone repetindo a aba é eco: marcado no segmento e sem pergunta")
    void echoSuppressed() {
        UUID micSegment = UUID.randomUUID();
        TranscriptSegment seg = TranscriptSegment.builder().id(micSegment).build();
        when(segments.findById(micSegment)).thenReturn(Optional.of(seg));

        say(AudioSource.TAB, "qual a diferença entre Kafka e RabbitMQ");
        now.addAndGet(1500);
        service.onSegment(new SegmentEvent(meetingId, micSegment, "Qual a diferença entre Kafka e RabbitMQ?",
                AudioSource.MIC, SpeakerRole.SELF, InterviewRole.CANDIDATE, 0L, 1000L));

        assertThat(seg.isEchoSuspected()).isTrue();
        verify(segments).save(seg);
    }

    @Test
    @DisplayName("Resposta do candidato fecha a pergunta na hora (sem esperar o silêncio) e não vira pergunta")
    void candidateAnswerClosesQuestion() {
        say(AudioSource.TAB, "qual a diferença entre Kafka e RabbitMQ?");
        now.addAndGet(300);
        say(AudioSource.MIC, "Bom, o Kafka é um log distribuído");

        assertThat(stored).extracting(InterviewQuestion::getQuestionText)
                .containsExactly("qual a diferença entre Kafka e RabbitMQ?");
        silence(2000);
        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("RN-03: pontuação intermediária registra a pergunta sem resposta automática")
    void midScoreDetectedWithoutAutoAnswer() {
        service.onSegment(new SegmentEvent(meetingId, UUID.randomUUID(), "qual banco de dados", AudioSource.TAB,
                SpeakerRole.REMOTE, InterviewRole.INTERVIEWER, 0L, 1000L));
        silence(2000);

        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).isAutoAnswer()).isFalse();
        verifyNoInteractions(copilot);
    }

    @Test
    @DisplayName("RF-09: gatilho manual responde a fala remota em aberto mesmo sem cara de pergunta")
    void manualTrigger() {
        say(AudioSource.TAB, "a ideia é desenhar o fluxo de pagamentos");
        service.triggerManual(meetingId);

        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).isAutoAnswer()).isTrue();
        verify(copilot).answer(stored.get(0).getId());
    }

    @Test
    @DisplayName("Gravações enviadas (UPLOAD) não passam pela detecção em tempo real")
    void uploadIgnored() {
        service.onSegment(new SegmentEvent(meetingId, UUID.randomUUID(), "o que é SOLID?", AudioSource.UPLOAD,
                SpeakerRole.UNKNOWN, null, 0L, 1000L));
        silence(2000);
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("CA-08: falas simultâneas da mesma reunião são processadas em ordem, sem duplicar perguntas")
    void concurrentSegmentsProcessedInOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            service = build(new MeetingSerialExecutor(pool));
            List<String> texts = List.of("o que é polimorfismo?", "qual a diferença entre REST e gRPC?",
                    "como funciona o garbage collector?");
            ExecutorService callers = Executors.newFixedThreadPool(3);
            for (int i = 0; i < texts.size(); i++) {
                String t = texts.get(i);
                long at = now.addAndGet(3000);
                callers.submit(() -> service.onSegment(new SegmentEvent(meetingId, UUID.randomUUID(), t, AudioSource.TAB,
                        SpeakerRole.REMOTE, InterviewRole.CANDIDATE, at, at + 1000)));
                Thread.sleep(20);
            }
            callers.shutdown();
            assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            // Cada fala nova fecha a anterior (pausa > 2,5 s); a última fecha por silêncio
            service = build(new MeetingSerialExecutor(Runnable::run));
            assertThat(stored).extracting(InterviewQuestion::getQuestionText).containsExactly(texts.get(0), texts.get(1));
            assertThat(stored.stream().map(InterviewQuestion::getQuestionText).distinct().count()).isEqualTo(stored.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
