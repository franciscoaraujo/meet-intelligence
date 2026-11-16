package com.meetingintelligence.question;

import com.meetingintelligence.domain.enums.InterviewRole;
import com.meetingintelligence.domain.language.InterviewLanguage;
import com.meetingintelligence.domain.question.UtteranceClassifier;
import com.meetingintelligence.domain.question.UtteranceClassifier.Classification;
import com.meetingintelligence.domain.speech.SpeakerRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 011 (CA-03): detecção de perguntas em inglês e francês com as mesmas metas do corpus em português. */
class MultilingualClassifierTest {

    record Row(String text, SpeakerRole speaker, boolean expectedQuestion) {}

    private static List<Row> corpus(String resource) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(MultilingualClassifierTest.class.getResourceAsStream(resource)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\\|");
                rows.add(new Row(p[0], SpeakerRole.valueOf(p[1]), "QUESTION".equals(p[2])));
            }
        }
        return rows;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"/question-corpus-en.csv, EN_US", "/question-corpus-fr.csv, FR_FR"})
    @DisplayName("CA-03: ≥ 90% das perguntas detectadas e ≤ 10% de falsos positivos")
    void corpusMeetsTargets(String resource, InterviewLanguage language) throws Exception {
        int questions = 0, detected = 0, nonQuestions = 0, falsePositives = 0;
        List<String> errors = new ArrayList<>();
        for (Row row : corpus(resource)) {
            Classification c = UtteranceClassifier.classify(row.text(), row.speaker(), InterviewRole.CANDIDATE, language);
            if (row.expectedQuestion()) {
                questions++;
                if (c.autoAnswer()) detected++; else errors.add("NÃO DETECTADA: " + row.text() + " -> " + c);
            } else {
                nonQuestions++;
                if (c.autoAnswer()) { falsePositives++; errors.add("FALSO POSITIVO: " + row.text() + " -> " + c); }
            }
        }
        System.out.printf("CORPUS %s: perguntas %d/%d | falsos positivos %d/%d%n", language, detected, questions, falsePositives, nonQuestions);
        assertThat((double) detected / questions).as("recall; erros: %s", errors).isGreaterThanOrEqualTo(0.90);
        assertThat((double) falsePositives / nonQuestions).as("falsos positivos; erros: %s", errors).isLessThanOrEqualTo(0.10);
    }

    @Test
    @DisplayName("As regras de um idioma não vazam para outro: 'comment' em inglês não é interrogativo francês")
    void rulesAreIsolatedPerLanguage() {
        assertThat(UtteranceClassifier.classify("comment on the deployment schedule", SpeakerRole.REMOTE,
                InterviewRole.CANDIDATE, InterviewLanguage.EN_US).autoAnswer()).isFalse();
        assertThat(UtteranceClassifier.classify("comment testeriez vous ce module", SpeakerRole.REMOTE,
                InterviewRole.CANDIDATE, InterviewLanguage.FR_FR).autoAnswer()).isTrue();
        // Sem idioma: português, como antes da spec 011
        assertThat(UtteranceClassifier.classify("how would you design it", SpeakerRole.REMOTE, InterviewRole.CANDIDATE).autoAnswer())
                .isFalse();
    }
}
