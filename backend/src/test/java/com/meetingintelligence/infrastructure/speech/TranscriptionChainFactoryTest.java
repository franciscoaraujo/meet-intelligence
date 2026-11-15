package com.meetingintelligence.infrastructure.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.entity.OrganizationAiConfig;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.infrastructure.speech.TranscriptionChainFactory.ChainStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptionChainFactoryTest {

    private final SttHttpClients http = new SttHttpClients(1000, 1000, 1000);
    private final ObjectMapper mapper = new ObjectMapper();

    private TranscriptionChainFactory factory(String serverOpenAi, String serverDeepgram,
                                              boolean localEnabled, boolean simulationEnabled) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (localEnabled) beans.addBean("local", new LocalGoogleSttProvider());
        if (simulationEnabled) beans.addBean("sim", new SimulatedSttProvider());
        ObjectProvider<LocalGoogleSttProvider> local = beans.getBeanProvider(LocalGoogleSttProvider.class);
        ObjectProvider<SimulatedSttProvider> sim = beans.getBeanProvider(SimulatedSttProvider.class);
        return new TranscriptionChainFactory(new GeminiAudioSttProvider(http, mapper),
                new OpenAiTranscribeSttProvider(http, mapper), new DeepgramSpeechToTextProvider(http, mapper),
                local, sim, serverOpenAi, serverDeepgram);
    }

    private static List<String> names(List<ChainStep> steps) {
        return steps.stream().map(s -> s.transcriber().providerName()).toList();
    }

    private static OrganizationAiConfig orgWith(String gemini, String openAi) {
        return OrganizationAiConfig.builder().geminiApiKey(gemini).openAiApiKey(openAi)
                .geminiModel("gemini-3.5-flash-lite").useOrganizationKeys(true).build();
    }

    @Test
    @DisplayName("RF-03: ordem ao vivo — Gemini (org) → OpenAI (org) → Deepgram → OpenAI (servidor) → local")
    void liveOrder() {
        var steps = factory("sk-server", "dg-server", true, false)
                .build(orgWith("AIza-org", "sk-org"), SttRequest.Mode.LIVE);

        assertThat(names(steps)).containsExactly("GEMINI", "OPENAI_TRANSCRIBE", "DEEPGRAM", "OPENAI_TRANSCRIBE", "LOCAL_GOOGLE_STT");
        assertThat(steps.get(0).credentials().model()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(steps.get(1).credentials().apiKey()).isEqualTo("sk-org");
        assertThat(steps.get(3).credentials().apiKey()).isEqualTo("sk-server");
    }

    @Test
    @DisplayName("RN-03/CA-04: provedores sem chave ('mock-key', vazia, ID de workspace) ficam fora da cadeia")
    void unavailableProvidersExcluded() {
        var steps = factory("mock-key", "", false, false)
                .build(orgWith("wrkspc_abc123", null), SttRequest.Mode.LIVE);

        assertThat(steps).isEmpty();
    }

    @Test
    @DisplayName("useOrganizationKeys=false ignora as chaves da organização")
    void organizationKeysDisabled() {
        OrganizationAiConfig cfg = orgWith("AIza-org", "sk-org");
        cfg.setUseOrganizationKeys(false);

        var steps = factory("mock-key", "dg-server", false, false).build(cfg, SttRequest.Mode.LIVE);

        assertThat(names(steps)).containsExactly("DEEPGRAM");
    }

    @Test
    @DisplayName("RF-04: ordem de gravação enviada — OpenAI (servidor) → Deepgram; sem chaves da org")
    void batchOrder() {
        var steps = factory("sk-server", "dg-server", true, false)
                .build(orgWith("AIza-org", "sk-org"), SttRequest.Mode.BATCH);

        assertThat(names(steps)).containsExactly("OPENAI_TRANSCRIBE", "DEEPGRAM");
    }

    @Test
    @DisplayName("RF-09: simulação só entra na cadeia quando habilitada, e por último")
    void simulationOnlyWhenEnabled() {
        assertThat(names(factory("mock-key", "mock-key", false, false).build(null, SttRequest.Mode.LIVE))).isEmpty();
        assertThat(names(factory("sk", "mock-key", false, true).build(null, SttRequest.Mode.LIVE)))
                .containsExactly("OPENAI_TRANSCRIBE", "SIMULATION");
    }
}
