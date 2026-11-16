package com.meetingintelligence.infrastructure.ai;

import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.ai.LlmStreamAbortedException;
import com.meetingintelligence.domain.ai.LlmStreamListener;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.entity.OrganizationAiConfig;
import com.meetingintelligence.infrastructure.persistence.repository.LLMUsageRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationAiConfigRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec 008, T-08 e T-11: fallback no streaming e modelo próprio do copiloto. */
class AIOrchestratorStreamingTest {

    private final GeminiLlmProvider gemini = mock(GeminiLlmProvider.class);
    private final GrokLlmProvider grok = mock(GrokLlmProvider.class);
    private final ClaudeLlmProvider claude = mock(ClaudeLlmProvider.class);
    private final OrganizationAiConfigRepository configs = mock(OrganizationAiConfigRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private Meeting meeting;

    @BeforeEach
    void setUp() {
        UUID orgId = UUID.randomUUID();
        meeting = Meeting.builder().id(UUID.randomUUID()).organization(Organization.builder().id(orgId).build()).build();
        OrganizationAiConfig cfg = new OrganizationAiConfig();
        cfg.setActiveProvider("GROK");
        cfg.setUseOrganizationKeys(true);
        cfg.setGrokApiKey("xai-org");
        cfg.setGrokModel("grok-4.7");
        when(configs.findByOrganizationId(orgId)).thenReturn(Optional.of(cfg));
        when(grok.getProviderName()).thenReturn("GROK");
        when(gemini.getProviderName()).thenReturn("GEMINI");
    }

    private AIOrchestrator orchestrator(String grokCopilotModel, String geminiCopilotModel) {
        return new AIOrchestrator(gemini, grok, claude, mock(LLMUsageRepository.class), configs, meters,
                "GEMINI", grokCopilotModel, "", geminiCopilotModel);
    }

    private static LLMResponse ok(String provider) {
        return LLMResponse.builder().providerName(provider).modelName("m").content("{}").build();
    }

    private static LLMRequest request() {
        return LLMRequest.builder().userPrompt("p").build();
    }

    @Test
    @DisplayName("CA-03: Grok falha antes do primeiro trecho → streaming do Gemini e contador copilot.stream.fallback")
    void fallbackBeforeFirstDelta() {
        when(grok.stream(any(), any(), any(), any())).thenThrow(new IllegalStateException("HTTP 503"));
        when(gemini.stream(any(), any(), any(), any())).thenAnswer(inv -> {
            ((LlmStreamListener) inv.getArgument(3)).onDelta("texto do gemini");
            return ok("GEMINI");
        });
        List<String> parts = new ArrayList<>();

        LLMResponse response = orchestrator("", "").executeStreaming(request(), meeting, AIOrchestrator.INTERVIEW_ANSWER, parts::add);

        assertThat(response.getProviderName()).isEqualTo("GEMINI");
        assertThat(parts).containsExactly("texto do gemini");
        assertThat(meters.counter("copilot.stream.fallback", "from", "GROK", "to", "GEMINI").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Falha depois do primeiro trecho → LlmStreamAbortedException, sem fallback")
    void abortAfterDelta() {
        when(grok.stream(any(), any(), any(), any())).thenAnswer(inv -> {
            ((LlmStreamListener) inv.getArgument(3)).onDelta("Eu");
            throw new IllegalStateException("conexão caiu");
        });

        assertThatThrownBy(() -> orchestrator("", "").executeStreaming(request(), meeting, AIOrchestrator.INTERVIEW_ANSWER, t -> { }))
                .isInstanceOf(LlmStreamAbortedException.class);
        verify(gemini, never()).stream(any(), any(), any(), any());
        assertThat(meters.counter("copilot.stream.aborted", "provider", "GROK").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Todos falham antes de qualquer trecho → indisponível (RuntimeException comum)")
    void allFailBeforeDelta() {
        when(grok.stream(any(), any(), any(), any())).thenThrow(new IllegalStateException("401"));
        when(gemini.stream(any(), any(), any(), any())).thenThrow(new IllegalStateException("sem chave"));

        assertThatThrownBy(() -> orchestrator("", "").executeStreaming(request(), meeting, AIOrchestrator.INTERVIEW_ANSWER, t -> { }))
                .isNotInstanceOf(LlmStreamAbortedException.class).hasMessageContaining("indisponível");
    }

    @Test
    @DisplayName("RF-09: o modelo do copiloto substitui o da organização só na operação do copiloto; vazio mantém o atual")
    void copilotModelOverride() {
        when(grok.stream(any(), any(), any(), any())).thenReturn(ok("GROK"));
        when(grok.generateCompletion(any(), any(), any())).thenReturn(ok("GROK"));

        orchestrator("grok-4.20-0309-non-reasoning", "").executeStreaming(request(), meeting, AIOrchestrator.INTERVIEW_ANSWER, t -> { });
        verify(grok).stream(any(), eq("xai-org"), eq("grok-4.20-0309-non-reasoning"), any());

        orchestrator("grok-4.20-0309-non-reasoning", "").execute(request(), meeting, "INCREMENTAL_ANALYSIS");
        verify(grok).generateCompletion(any(), eq("xai-org"), eq("grok-4.7"));

        orchestrator("", "").executeStreaming(request(), meeting, AIOrchestrator.INTERVIEW_ANSWER, t -> { });
        verify(grok).stream(any(), eq("xai-org"), eq("grok-4.7"), any());
    }

    @Test
    @DisplayName("No fallback do copiloto, o Gemini usa o modelo do copiloto do Gemini, se configurado")
    void fallbackUsesGeminiCopilotModel() {
        when(grok.stream(any(), any(), any(), any())).thenThrow(new IllegalStateException("HTTP 503"));
        when(gemini.stream(any(), any(), any(), any())).thenReturn(ok("GEMINI"));

        orchestrator("", "gemini-4-hydrogen").executeStreaming(request(), meeting, AIOrchestrator.INTERVIEW_ANSWER, t -> { });

        verify(gemini).stream(any(), isNull(), eq("gemini-4-hydrogen"), any());
    }
}
