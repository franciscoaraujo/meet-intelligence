package com.meetingintelligence.question;

import com.meetingintelligence.domain.enums.InterviewRole;
import com.meetingintelligence.domain.question.UtteranceClassifier;
import com.meetingintelligence.domain.question.UtteranceClassifier.Classification;
import com.meetingintelligence.domain.question.UtteranceType;
import com.meetingintelligence.domain.speech.SpeakerRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class UtteranceClassifierTest {

    private record Row(String text, SpeakerRole speaker, boolean expectedQuestion) {}

    private static List<Row> corpus() throws Exception {
        List<Row> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(UtteranceClassifierTest.class.getResourceAsStream("/question-corpus.csv")),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\\|");
                rows.add(new Row(p[0], SpeakerRole.valueOf(p[1]), "QUESTION".equals(p[2])));
            }
        }
        return rows;
    }

    @Test
    @DisplayName("CA-06 / RNF-02: no corpus, ≥ 90% das perguntas reais detectadas e ≤ 10% de falsos positivos")
    void corpusMeetsPrecisionTargets() throws Exception {
        List<Row> rows = corpus();
        int questions = 0, detected = 0, nonQuestions = 0, falsePositives = 0;
        List<String> misses = new ArrayList<>();
        for (Row row : rows) {
            Classification c = UtteranceClassifier.classify(row.text(), row.speaker(), InterviewRole.CANDIDATE);
            if (row.expectedQuestion()) {
                questions++;
                if (c.autoAnswer()) detected++; else misses.add("NÃO DETECTADA: " + row.text() + " -> " + c);
            } else {
                nonQuestions++;
                if (c.autoAnswer()) { falsePositives++; misses.add("FALSO POSITIVO: " + row.text() + " -> " + c); }
            }
        }
        double recall = (double) detected / questions;
        double fpr = (double) falsePositives / nonQuestions;

        System.out.printf("CORPUS: %d frases | perguntas detectadas %d/%d (%.0f%%) | falsos positivos %d/%d (%.0f%%)%n",
                rows.size(), detected, questions, recall * 100, falsePositives, nonQuestions, fpr * 100);
        assertThat(rows).hasSizeGreaterThanOrEqualTo(40);
        assertThat(recall).as("recall; erros: %s", misses).isGreaterThanOrEqualTo(0.90);
        assertThat(fpr).as("falsos positivos; erros: %s", misses).isLessThanOrEqualTo(0.10);
    }

    @Test
    @DisplayName("CA-02 / RN-01: menção técnica isolada não é pergunta")
    void technicalMentionIsNotQuestion() {
        Classification c = UtteranceClassifier.classify("aqui a gente usa Kafka e Spring", SpeakerRole.REMOTE, InterviewRole.CANDIDATE);
        assertThat(c.autoAnswer()).isFalse();
        assertThat(c.type()).isEqualTo(UtteranceType.TECHNICAL_DISCUSSION);
    }

    @Test
    @DisplayName("CA-03: pedido sem '?' é pergunta indireta com pontuação ≥ 0,6")
    void indirectQuestionWithoutQuestionMark() {
        Classification c = UtteranceClassifier.classify("me fale um pouco sobre sua experiência com mensageria",
                SpeakerRole.REMOTE, InterviewRole.CANDIDATE);
        assertThat(c.type()).isEqualTo(UtteranceType.INDIRECT_QUESTION);
        assertThat(c.score()).isGreaterThanOrEqualTo(0.6);
        assertThat(c.signals()).contains("pedido/imperativo");
    }

    @Test
    @DisplayName("RN-02: fala do candidato nunca é pergunta a responder; se o usuário é o entrevistador, a fala dele pode ser")
    void candidateSpeech() {
        assertThat(UtteranceClassifier.classify("Qual o volume de mensagens?", SpeakerRole.SELF, InterviewRole.CANDIDATE).type())
                .isEqualTo(UtteranceType.CANDIDATE_ANSWER);
        assertThat(UtteranceClassifier.classify("Qual o volume de mensagens?", SpeakerRole.SELF, InterviewRole.INTERVIEWER).type())
                .isEqualTo(UtteranceType.DIRECT_QUESTION);
    }

    @Test
    @DisplayName("Afirmação que começa com 'como eu disse' não é pergunta")
    void falseInterrogative() {
        assertThat(UtteranceClassifier.classify("como eu disse antes, usamos microsserviços", SpeakerRole.REMOTE,
                InterviewRole.CANDIDATE).autoAnswer()).isFalse();
    }
}
