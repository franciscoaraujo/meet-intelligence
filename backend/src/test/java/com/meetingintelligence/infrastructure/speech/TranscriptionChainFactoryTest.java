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
        return factory(serverOpenAi, serverDeepgram, "mock-key", localEnabled, simulationEnabled);
    }

    private TranscriptionChainFactory factory(String serverOpenAi, String serverDeepgram, String serverXai,
                                              boolean localEnabled, boolean simulationEnabled) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (localEnabled) beans.addBean("local", new LocalGoogleSttProvider());
        if (simulationEnabled) beans.addBean("sim", new SimulatedSttProvider());
        ObjectProvider<LocalGoogleSttProvider> local = beans.getBeanProvider(LocalGoogleSttProvider.class);
        ObjectProvider<SimulatedSttProvider> sim = beans.getBeanProvider(SimulatedSttProvider.class);
        return new TranscriptionChainFactory(new GeminiAudioSttProvider(http, mapper),
                new OpenAiTranscribeSttProvider(http, mapper), new DeepgramSpeechToTextProvider(http, mapper),
                new GrokSttProvider(http, mapper), local, sim, serverOpenAi, serverDeepgram, serverXai);
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

    // ------------------------------------------------------------------ spec 017

    private static OrganizationAiConfig orgWith(String gemini, String openAi, String grok) {
        OrganizationAiConfig cfg = orgWith(gemini, openAi);
        cfg.setGrokApiKey(grok);
        return cfg;
    }

    @Test
    @DisplayName("Spec 017 RF-03: ordem ao vivo completa com o Grok da org e do servidor")
    void liveOrderWithGrok() {
        var steps = factory("sk-server", "dg-server", "xai-server", true, true)
                .build(orgWith("AIza-org", "sk-org", "xai-org"), SttRequest.Mode.LIVE);

        assertThat(names(steps)).containsExactly("GEMINI", "OPENAI_TRANSCRIBE", "GROK_STT", "DEEPGRAM",
                "OPENAI_TRANSCRIBE", "GROK_STT", "LOCAL_GOOGLE_STT", "SIMULATION");
        assertThat(steps.get(2).credentials().apiKey()).isEqualTo("xai-org");
        assertThat(steps.get(5).credentials().apiKey()).isEqualTo("xai-server");
    }

    @Test
    @DisplayName("Spec 017 CA-05: com Gemini e xAI na org, o Gemini vem antes do Grok")
    void geminiBeforeGrok() {
        var steps = factory("mock-key", "mock-key", false, false)
                .build(orgWith("AIza-org", null, "xai-org"), SttRequest.Mode.LIVE);

        assertThat(names(steps)).containsExactly("GEMINI", "GROK_STT");
    }

    @Test
    @DisplayName("Spec 017 CA-05/CA-01: só xAI na org e Deepgram no servidor: Grok antes do Deepgram, antes do STT local")
    void onlyGrokOrgKeyComesFirst() {
        var steps = factory("mock-key", "dg-server", true, false)
                .build(orgWith(null, null, "xai-org"), SttRequest.Mode.LIVE);

        assertThat(names(steps)).containsExactly("GROK_STT", "DEEPGRAM", "LOCAL_GOOGLE_STT");
    }

    @Test
    @DisplayName("Spec 017 RF-05: useOrganizationKeys=false ignora também a chave xAI da org")
    void grokOrgKeyRespectsFlag() {
        OrganizationAiConfig cfg = orgWith(null, null, "xai-org");
        cfg.setUseOrganizationKeys(false);

        assertThat(factory("mock-key", "mock-key", false, false).build(cfg, SttRequest.Mode.LIVE)).isEmpty();
    }

    @Test
    @DisplayName("Spec 017 RF-04/CA-06: gravação enviada usa o Grok do servidor, depois de OpenAI e Deepgram")
    void batchOrderWithGrok() {
        assertThat(names(factory("sk-server", "dg-server", "xai-server", false, false)
                .build(orgWith(null, null, "xai-org"), SttRequest.Mode.BATCH)))
                .containsExactly("OPENAI_TRANSCRIBE", "DEEPGRAM", "GROK_STT");
        assertThat(names(factory("mock-key", "mock-key", "xai-server", false, false)
                .build(null, SttRequest.Mode.BATCH)))
                .containsExactly("GROK_STT");
    }

    // ------------------------------------------------------------------ spec 018

    private TranscriptionChainFactory serverFirst(String serverOpenAi, String serverDeepgram, String serverXai,
                                                  boolean localEnabled, boolean simulationEnabled) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (localEnabled) beans.addBean("local", new LocalGoogleSttProvider());
        if (simulationEnabled) beans.addBean("sim", new SimulatedSttProvider());
        return new TranscriptionChainFactory(new GeminiAudioSttProvider(http, mapper),
                new OpenAiTranscribeSttProvider(http, mapper), new DeepgramSpeechToTextProvider(http, mapper),
                new GrokSttProvider(http, mapper), beans.getBeanProvider(LocalGoogleSttProvider.class),
                beans.getBeanProvider(SimulatedSttProvider.class), serverOpenAi, serverDeepgram, serverXai, true);
    }

    @Test
    @DisplayName("Spec 018 CA-01: chaves do servidor primeiro (Deepgram), as da organização como reserva")
    void serverKeysFirstLive() {
        var steps = serverFirst("sk-server", "dg-server", "xai-server", true, true)
                .build(orgWith("AIza-org", "sk-org", "xai-org"), SttRequest.Mode.LIVE);

        assertThat(names(steps)).containsExactly("DEEPGRAM", "OPENAI_TRANSCRIBE", "GROK_STT",
                "GEMINI", "OPENAI_TRANSCRIBE", "GROK_STT", "LOCAL_GOOGLE_STT", "SIMULATION");
        assertThat(steps.get(0).credentials().apiKey()).isEqualTo("dg-server");
        assertThat(steps.get(5).credentials().apiKey()).isEqualTo("xai-org");
    }

    @Test
    @DisplayName("Spec 018 CA-01: o caso real (Deepgram no servidor, só xAI na org) usa o Deepgram antes do Grok da org")
    void deepgramBeforeOrganizationGrok() {
        var steps = serverFirst("mock-key", "dg-server", "mock-key", true, false)
                .build(orgWith(null, null, "xai-org"), SttRequest.Mode.LIVE);

        assertThat(names(steps)).containsExactly("DEEPGRAM", "GROK_STT", "LOCAL_GOOGLE_STT");
    }

    @Test
    @DisplayName("Spec 018 CA-03: gravação enviada com o Deepgram primeiro")
    void serverKeysFirstBatch() {
        assertThat(names(serverFirst("sk-server", "dg-server", "xai-server", false, false)
                .build(orgWith("AIza-org", null, "xai-org"), SttRequest.Mode.BATCH)))
                .containsExactly("DEEPGRAM", "OPENAI_TRANSCRIBE", "GROK_STT");
    }

    @Test
    @DisplayName("Spec 018: sem chave no servidor, as da organização continuam funcionando")
    void serverKeysFirstWithoutServerKeys() {
        assertThat(names(serverFirst("mock-key", "mock-key", "mock-key", false, false)
                .build(orgWith("AIza-org", null, "xai-org"), SttRequest.Mode.LIVE)))
                .containsExactly("GEMINI", "GROK_STT");
    }

    @Test
    @DisplayName("Spec 018 CA-05: valores de exemplo do .env.example ('sua-chave-...') não contam como chave")
    void exampleValuesAreNotKeys() {
        assertThat(TranscriptionChainFactory.isUsableKey("sua-chave-deepgram")).isFalse();
        assertThat(TranscriptionChainFactory.isUsableKey(" sua-chave-xai ")).isFalse();
        assertThat(TranscriptionChainFactory.isUsableKey("dg-real-key")).isTrue();
        assertThat(names(serverFirst("sua-chave-openai", "sua-chave-deepgram", "sua-chave-xai", false, false)
                .build(null, SttRequest.Mode.LIVE))).isEmpty();
    }
}
