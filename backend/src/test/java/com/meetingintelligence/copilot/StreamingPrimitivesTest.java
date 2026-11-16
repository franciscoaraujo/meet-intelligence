package com.meetingintelligence.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.service.question.DeltaThrottler;
import com.meetingintelligence.application.service.question.SpokenAnswerExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 008: extração incremental da spoken_answer (T-06) e agrupamento de deltas (T-07). */
class StreamingPrimitivesTest {

    private static final String JSON = """
            ```json
            {"is_question_for_candidate": true,
             "spoken_answer": "Eu uso \\"Outbox\\" com RabbitMQ.\\nNa prática: ação → evento, café ☕ e 😀 \\u00e9 \\u00E7.",
             "depth": "INTERMEDIATE",
             "sections": {"direct_answer": "nada de \\"spoken_answer\\": aqui", "spoken_answer": "aninhado não conta"},
             "assumptions": [], "confidence": 0.8}
            ```""";

    private static String expectedSpoken() throws Exception {
        return new ObjectMapper().readTree(JSON.substring(JSON.indexOf('{'), JSON.lastIndexOf('}') + 1))
                .path("spoken_answer").asText();
    }

    private static String feedInParts(String json, List<Integer> cuts) {
        SpokenAnswerExtractor extractor = new SpokenAnswerExtractor();
        StringBuilder out = new StringBuilder();
        int prev = 0;
        for (int cut : cuts) {
            out.append(extractor.feed(json.substring(prev, cut)));
            prev = cut;
        }
        out.append(extractor.feed(json.substring(prev)));
        return out.toString();
    }

    @Test
    @DisplayName("CA-05: texto liberado é idêntico ao valor decodificado do campo, com o JSON inteiro de uma vez")
    void wholeJson() throws Exception {
        assertThat(feedInParts(JSON, List.of())).isEqualTo(expectedSpoken());
    }

    @Test
    @DisplayName("CA-05: escapes, \\uXXXX e emojis partidos em qualquer ponto (todas as divisões em 2 partes)")
    void everySingleCut() throws Exception {
        String expected = expectedSpoken();
        for (int cut = 1; cut < JSON.length(); cut++) {
            assertThat(feedInParts(JSON, List.of(cut))).as("corte em %d", cut).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("CA-05: 300 partições aleatórias, inclusive caractere a caractere")
    void randomPartitions() throws Exception {
        String expected = expectedSpoken();
        Random random = new Random(8);
        for (int run = 0; run < 300; run++) {
            List<Integer> cuts = new ArrayList<>();
            int pos = 0;
            while (true) {
                pos += 1 + random.nextInt(run % 3 == 0 ? 2 : 12);
                if (pos >= JSON.length()) break;
                cuts.add(pos);
            }
            assertThat(feedInParts(JSON, cuts)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("Nunca libera meio par surrogate (emoji) entre duas chamadas")
    void neverEmitsLoneSurrogate() {
        String json = "{\"spoken_answer\": \"ok 😀 fim\"}";
        int emoji = json.indexOf("😀");
        SpokenAnswerExtractor extractor = new SpokenAnswerExtractor();
        String first = extractor.feed(json.substring(0, emoji + 1));
        assertThat(first).isEqualTo("ok ");
        assertThat(extractor.feed(json.substring(emoji + 1))).isEqualTo("😀 fim");
    }

    @Test
    @DisplayName("Campo fora da primeira posição ainda é extraído; campo ausente ou não textual não libera nada")
    void outOfOrderOrMissing() {
        assertThat(feedInParts("{\"depth\":\"BASIC\",\"sections\":{},\"spoken_answer\":\"tarde\"}", List.of(5))).isEqualTo("tarde");
        assertThat(feedInParts("{\"is_question_for_candidate\": false}", List.of())).isEmpty();
        assertThat(feedInParts("{\"spoken_answer\": null, \"x\": \"y\"}", List.of())).isEmpty();
        SpokenAnswerExtractor extractor = new SpokenAnswerExtractor();
        extractor.feed("{\"spoken_answer\": \"abc\"");
        assertThat(extractor.started()).isTrue();
        assertThat(extractor.finished()).isTrue();
        assertThat(extractor.feed(", \"other\": \"zzz\"}")).isEmpty();
    }

    @Test
    @DisplayName("CA-06: 200 deltas em 1 s viram no máximo 12 eventos, sem perda de texto")
    void throttlerGroupsDeltas() {
        AtomicLong now = new AtomicLong(0);
        List<String> events = new ArrayList<>();
        DeltaThrottler throttler = new DeltaThrottler(TimeUnit.MILLISECONDS.toNanos(100), now::get, events::add);
        StringBuilder sent = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            now.set(TimeUnit.MILLISECONDS.toNanos(i * 5L));
            String piece = "p" + i + " ";
            sent.append(piece);
            throttler.offer(piece);
        }
        throttler.flush();

        assertThat(events.size()).isBetween(2, 12);
        assertThat(String.join("", events)).isEqualTo(sent.toString());
        assertThat(events.get(0)).isEqualTo("p0 "); // o primeiro trecho sai na hora
    }

    @Test
    @DisplayName("discard() descarta o pendente; flush() sem pendente não publica")
    void throttlerDiscard() {
        AtomicLong now = new AtomicLong(0);
        List<String> events = new ArrayList<>();
        DeltaThrottler throttler = new DeltaThrottler(TimeUnit.MILLISECONDS.toNanos(100), now::get, events::add);
        throttler.offer("a");
        throttler.offer("b");
        throttler.discard();
        throttler.flush();
        assertThat(events).containsExactly("a");
    }
}
