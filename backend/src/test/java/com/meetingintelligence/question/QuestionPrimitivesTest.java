package com.meetingintelligence.question;

import com.meetingintelligence.domain.question.EchoGuard;
import com.meetingintelligence.domain.question.QuestionDeduplicator;
import com.meetingintelligence.domain.question.QuestionDeduplicator.Candidate;
import com.meetingintelligence.domain.question.QuestionDeduplicator.Kind;
import com.meetingintelligence.domain.question.UtteranceAssembler;
import com.meetingintelligence.domain.question.UtteranceAssembler.Fragment;
import com.meetingintelligence.domain.question.UtteranceAssembler.Utterance;
import com.meetingintelligence.domain.speech.AudioSource;
import com.meetingintelligence.domain.speech.SpeakerRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionPrimitivesTest {

    private static final UUID MEETING = UUID.randomUUID();

    private static Fragment tab(String text, long at) {
        return new Fragment(UUID.randomUUID(), text, AudioSource.TAB, SpeakerRole.REMOTE, at, at + 1000, at);
    }

    private static Fragment mic(String text, long at) {
        return new Fragment(UUID.randomUUID(), text, AudioSource.MIC, SpeakerRole.SELF, at, at + 1000, at);
    }

    @Nested
    class Assembler {
        private final UtteranceAssembler assembler = UtteranceAssembler.withSpecDefaults();

        @Test
        @DisplayName("RF-04: fragmentos da mesma origem com pausa curta formam um enunciado; fecha após 1,5 s")
        void joinsContinuationAndClosesOnSilence() {
            assertThat(assembler.append(MEETING, tab("explique o conceito", 0))).isEmpty();
            assertThat(assembler.append(MEETING, tab("de SOLID", 1200))).isEmpty();
            assertThat(assembler.closeDue(MEETING, 2000)).isEmpty();

            List<Utterance> closed = assembler.closeDue(MEETING, 2700);
            assertThat(closed).hasSize(1);
            assertThat(closed.get(0).text()).isEqualTo("explique o conceito de SOLID");
            assertThat(closed.get(0).segmentIds()).hasSize(2);
        }

        @Test
        @DisplayName("CA-01: pausa maior que 2,5 s separa duas perguntas")
        void longGapSeparatesQuestions() {
            assembler.append(MEETING, tab("explique o conceito de SOLID", 0));
            List<Utterance> closed = assembler.append(MEETING, tab("como você trataria a resiliência de microsserviços", 3000));
            assertThat(closed).extracting(Utterance::text).containsExactly("explique o conceito de SOLID");
        }

        @Test
        @DisplayName("Fala do candidato fecha a pergunta aberta do entrevistador")
        void otherSourceClosesOpenUtterance() {
            assembler.append(MEETING, tab("qual a diferença entre Kafka e RabbitMQ", 0));
            List<Utterance> closed = assembler.append(MEETING, mic("então, o Kafka", 500));
            assertThat(closed).extracting(Utterance::source).containsExactly(AudioSource.TAB);
        }

        @Test
        @DisplayName("Limite de 40 palavras fecha o enunciado")
        void maxWordsClosesUtterance() {
            String longText = "palavra ".repeat(41).trim();
            assertThat(assembler.append(MEETING, tab(longText, 0))).hasSize(1);
        }

        @Test
        @DisplayName("Reuniões diferentes não se misturam")
        void meetingsAreIsolated() {
            UUID other = UUID.randomUUID();
            assembler.append(MEETING, tab("pergunta A", 0));
            assertThat(assembler.append(other, mic("resposta B", 100))).isEmpty();
        }
    }

    @Nested
    class Echo {
        @Test
        @DisplayName("CA-05: fala do microfone igual à da aba de 2 s antes é eco provável; após 5 s não é")
        void detectsEchoWithinWindow() {
            EchoGuard guard = EchoGuard.withSpecDefaults();
            guard.recordTab(MEETING, "qual a diferença entre Kafka e RabbitMQ", 1000);

            assertThat(guard.isEcho(MEETING, "Qual a diferença entre Kafka e RabbitMQ?", 3000)).isTrue();
            assertThat(guard.isEcho(MEETING, "o Kafka é um log distribuído", 3000)).isFalse();
            assertThat(guard.isEcho(MEETING, "qual a diferença entre Kafka e RabbitMQ", 7000)).isFalse();
        }
    }

    @Nested
    class Dedup {
        private final QuestionDeduplicator dedup = QuestionDeduplicator.withSpecDefaults();
        private final UUID existing = UUID.randomUUID();

        @Test
        @DisplayName("CA-04: a mesma pergunta repetida em 30 s é a mesma pergunta")
        void repeatedQuestion() {
            var d = dedup.decide("Pode explicar o SOLID de novo?", 30_000,
                    List.of(new Candidate(existing, "Explique o SOLID", 0)));
            assertThat(d.kind()).isEqualTo(Kind.SAME_QUESTION);
            assertThat(d.existingId()).isEqualTo(existing);
        }

        @Test
        @DisplayName("Pergunta diferente é nova; fora da janela de 120 s também é nova")
        void differentOrOldQuestion() {
            var candidates = List.of(new Candidate(existing, "explique o conceito de SOLID", 0));
            assertThat(dedup.decide("como você trataria a resiliência de microsserviços", 10_000, candidates).kind())
                    .isEqualTo(Kind.NEW);
            assertThat(dedup.decide("explique o conceito de SOLID", 200_000, candidates).kind()).isEqualTo(Kind.NEW);
        }

        @Test
        @DisplayName("Mesma pergunta que cresceu ≥ 30% pede nova resposta")
        void grownQuestion() {
            var d = dedup.decide("explique o conceito de SOLID com um exemplo prático em Java", 5_000,
                    List.of(new Candidate(existing, "explique o conceito de SOLID", 0)));
            assertThat(d.kind()).isEqualTo(Kind.SAME_QUESTION_GREW);
        }
    }
}
