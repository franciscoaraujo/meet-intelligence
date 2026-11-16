package com.meetingintelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.ai.AiModels;
import com.meetingintelligence.infrastructure.ai.GeminiLlmProvider;
import com.meetingintelligence.infrastructure.ai.GrokLlmProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelsTest {

    @Test
    @DisplayName("Grok: lê só o texto final da Responses API, ignorando itens de raciocínio")
    void grokExtractsOutputText() throws Exception {
        var root = new ObjectMapper().readTree("""
            {"status":"completed","output":[
              {"type":"reasoning","summary":[{"type":"summary_text","text":"pensando"}]},
              {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Resposta final"}]}
            ]}
            """);
        assertThat(GrokLlmProvider.extractOutputText(root)).isEqualTo("Resposta final");
    }

    @Test
    @DisplayName("Grok: remove cercas markdown de respostas JSON")
    void grokStripsMarkdownFences() {
        assertThat(GrokLlmProvider.stripMarkdownFences("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(GrokLlmProvider.stripMarkdownFences("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("Estimativa de custo usa o preço de cada modelo")
    void estimatesCostPerModel() {
        assertThat(AiModels.estimateCost("grok-4.7", 1_000_000, 1_000_000)).isEqualByComparingTo("8.000000");
        assertThat(AiModels.estimateCost("gemini-3.5-flash-lite", 1_000_000, 1_000_000)).isEqualByComparingTo("2.800000");
        assertThat(AiModels.estimateCost("gemini-4-hydrogen", 1_000_000, 0)).isEqualByComparingTo("1.500000");
    }

    @Test
    @DisplayName("Resposta do Gemini ignora partes de raciocínio e junta só o texto")
    void geminiSkipsThoughtParts() throws Exception {
        var root = new ObjectMapper().readTree("""
            {"candidates":[{"content":{"parts":[
              {"text":"pensando...","thought":true},
              {"text":"Resposta "},
              {"text":"final","thoughtSignature":"abc"}
            ]}}]}
            """);
        assertThat(GeminiLlmProvider.extractCandidateText(root)).isEqualTo("Resposta final");
    }
}
