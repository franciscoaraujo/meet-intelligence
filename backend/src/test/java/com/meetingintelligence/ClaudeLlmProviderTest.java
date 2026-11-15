package com.meetingintelligence;

import com.meetingintelligence.domain.ai.LLMRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeLlmProviderTest {

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = com.meetingintelligence.infrastructure.ai.ClaudeLlmProvider.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    @Test
    @DisplayName("Modelos atuais não recebem temperature; modelos legados continuam recebendo")
    void samplingParamsOnlyForLegacyModels() throws Exception {
        Class<?>[] t = {String.class};
        assertThat((boolean) call("acceptsSamplingParams", t, "claude-opus-5-5")).isFalse();
        assertThat((boolean) call("acceptsSamplingParams", t, "claude-sonnet-5-5")).isFalse();
        assertThat((boolean) call("acceptsSamplingParams", t, "claude-haiku-5-5")).isFalse();
        assertThat((boolean) call("acceptsSamplingParams", t, "claude-3-5-sonnet-20241022")).isTrue();
    }

    @Test
    @DisplayName("Limite de tokens mínimo para modelos com raciocínio sempre ativo")
    void maxTokensHasFloorForThinkingModels() throws Exception {
        Class<?>[] t = {LLMRequest.class, String.class};
        LLMRequest tiny = LLMRequest.builder().userPrompt("ping").maxTokens(40).build();
        assertThat((int) call("resolveMaxTokens", t, tiny, "claude-opus-5-5")).isEqualTo(4096);
        assertThat((int) call("resolveMaxTokens", t, tiny, "claude-3-haiku-20240307")).isEqualTo(40);
    }

    @Test
    @DisplayName("Fallback no servidor só nos modelos que o suportam e custo pelo preço do modelo")
    void fallbackAndCost() throws Exception {
        Class<?>[] t = {String.class};
        assertThat((boolean) call("supportsServerSideFallback", t, "claude-opus-5-5")).isTrue();
        assertThat((boolean) call("supportsServerSideFallback", t, "claude-haiku-5-5")).isFalse();

        BigDecimal cost = (BigDecimal) call("estimateCost", new Class<?>[]{String.class, int.class, int.class},
                "claude-opus-5-5", 1_000_000, 1_000_000);
        assertThat(cost).isEqualByComparingTo("24.000000");
    }
}
