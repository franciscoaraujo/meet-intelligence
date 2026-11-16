package com.meetingintelligence.question;

import com.meetingintelligence.domain.language.InterviewLanguage;
import com.meetingintelligence.domain.question.FollowUpDetector;
import com.meetingintelligence.domain.question.FollowUpDetector.Situation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 016, RF-01: marcadores de continuação por idioma e as condições de junção. */
class FollowUpDetectorTest {

    private static Situation ok(String text, InterviewLanguage language) {
        return new Situation(text, language, true, 5_000, 1, false, false);
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @CsvSource(delimiter = '|', textBlock = """
            PT_BR | E mais uma coisa, como você invalidaria esse cache?
            PT_BR | Mais uma coisa: qual seria o TTL?
            PT_BR | Além disso, como você testaria?
            PT_BR | E também, qual banco você usaria?
            PT_BR | Só complementando, e se o tráfego dobrar?
            PT_BR | E se o Redis cair?
            PT_BR | E como você monitoraria isso?
            PT_BR | E por que não usar Kafka?
            PT_BR | E o que muda com dez instâncias?
            EN_US | And also, how would you invalidate it?
            EN_US | One more thing, what about the TTL?
            EN_US | What if the cache goes down?
            EN_US | And how would you monitor it?
            EN_US | Also, which database would you pick?
            FR_FR | Et aussi, comment invalideriez-vous le cache ?
            FR_FR | Encore une chose : quel TTL ?
            FR_FR | Et si le Redis tombe ?
            FR_FR | De plus, comment le testeriez-vous ?
            """)
    @DisplayName("CA-06: complementos com marcador no idioma da entrevista juntam")
    void followUps(InterviewLanguage language, String text) {
        assertThat(FollowUpDetector.isFollowUp(ok(text, language))).isTrue();
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @CsvSource(delimiter = '|', textBlock = """
            PT_BR | Como você invalidaria esse cache?
            PT_BR | Qual banco de dados você usaria?
            PT_BR | Explique o padrão Saga
            PT_BR | Então, como foi no seu último emprego?
            PT_BR | Esse é um ponto importante
            PT_BR | and also how would you invalidate it?
            EN_US | How would you invalidate the cache?
            EN_US | E se o Redis cair?
            FR_FR | Comment invalideriez-vous le cache ?
            """)
    @DisplayName("RN-01: sem marcador (ou com marcador de outro idioma) não junta")
    void notFollowUps(InterviewLanguage language, String text) {
        assertThat(FollowUpDetector.isFollowUp(ok(text, language))).isFalse();
    }

    @Test
    @DisplayName("CA-03: passou de 30 s, o candidato falou, assunto novo ou já tem 3 partes → não junta")
    void conditions() {
        String text = "E mais uma coisa, como você invalidaria esse cache?";
        InterviewLanguage pt = InterviewLanguage.PT_BR;
        assertThat(FollowUpDetector.isFollowUp(new Situation(text, pt, true, 30_000, 1, false, false))).isTrue();
        assertThat(FollowUpDetector.isFollowUp(new Situation(text, pt, true, 45_000, 1, false, false))).isFalse();
        assertThat(FollowUpDetector.isFollowUp(new Situation(text, pt, true, 5_000, 1, true, false))).isFalse();
        assertThat(FollowUpDetector.isFollowUp(new Situation(text, pt, true, 5_000, 1, false, true))).isFalse();
        assertThat(FollowUpDetector.isFollowUp(new Situation(text, pt, true, 5_000, 2, false, false))).isTrue();
        assertThat(FollowUpDetector.isFollowUp(new Situation(text, pt, true, 5_000, 3, false, false))).isFalse();
    }

    @Test
    @DisplayName("RF-01a: sem cara de pergunta, só junta com ao menos 3 palavras depois do marcador")
    void withoutQuestionShape() {
        InterviewLanguage pt = InterviewLanguage.PT_BR;
        assertThat(FollowUpDetector.isFollowUp(new Situation("e se o Redis cair", pt, false, 5_000, 1, false, false))).isTrue();
        assertThat(FollowUpDetector.isFollowUp(new Situation("e mais uma coisa", pt, false, 5_000, 1, false, false))).isFalse();
        assertThat(FollowUpDetector.isFollowUp(new Situation("e também", pt, false, 5_000, 1, false, false))).isFalse();
    }
}
