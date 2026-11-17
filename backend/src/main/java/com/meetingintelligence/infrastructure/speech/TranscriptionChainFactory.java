package com.meetingintelligence.infrastructure.speech;

import com.meetingintelligence.domain.entity.OrganizationAiConfig;
import com.meetingintelligence.domain.speech.AudioTranscriber;
import com.meetingintelligence.domain.speech.AudioTranscriber.SttCredentials;
import com.meetingintelligence.domain.speech.SttRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Monta a ordem dos provedores de STT a partir da configuração existente, incluindo só os
 * disponíveis (com chave). Ordem (spec 002, RF-03/RF-04):
 * <pre>
 * LIVE : Gemini (org) → gpt-transcribe (chave OpenAI legada da org) → Grok (org, spec 017)
 *        → Deepgram (servidor) → gpt-transcribe (servidor) → Grok (servidor, spec 017)
 *        → STT local (flag) → simulação (flag)
 * BATCH: gpt-transcribe (servidor) → Deepgram (servidor) → Grok (servidor) → simulação (flag)
 * </pre>
 * O Grok entra depois dos provedores do mesmo nível: quem já transcreve com outra chave não muda
 * (spec 017, RN-01).
 */
@Component
public class TranscriptionChainFactory {

    /** Um passo da cadeia: provedor + credenciais a usar. */
    public record ChainStep(AudioTranscriber transcriber, SttCredentials credentials) {}

    private final GeminiAudioSttProvider gemini;
    private final OpenAiTranscribeSttProvider openAi;
    private final DeepgramSpeechToTextProvider deepgram;
    private final GrokSttProvider grok;
    private final ObjectProvider<LocalGoogleSttProvider> localGoogle;
    private final ObjectProvider<SimulatedSttProvider> simulated;
    private final String serverOpenAiKey;
    private final String serverDeepgramKey;
    private final String serverXaiKey;

    public TranscriptionChainFactory(
            GeminiAudioSttProvider gemini,
            OpenAiTranscribeSttProvider openAi,
            DeepgramSpeechToTextProvider deepgram,
            GrokSttProvider grok,
            ObjectProvider<LocalGoogleSttProvider> localGoogle,
            ObjectProvider<SimulatedSttProvider> simulated,
            @Value("${app.ai.openai.api-key:mock-key}") String serverOpenAiKey,
            @Value("${app.ai.deepgram.api-key:mock-key}") String serverDeepgramKey,
            @Value("${app.ai.grok.api-key:mock-key}") String serverXaiKey) {
        this.gemini = gemini;
        this.openAi = openAi;
        this.deepgram = deepgram;
        this.grok = grok;
        this.localGoogle = localGoogle;
        this.simulated = simulated;
        this.serverOpenAiKey = serverOpenAiKey;
        this.serverDeepgramKey = serverDeepgramKey;
        this.serverXaiKey = serverXaiKey;
    }

    /** @param orgConfig configuração BYOK da organização (pode ser null) */
    public List<ChainStep> build(OrganizationAiConfig orgConfig, SttRequest.Mode mode) {
        List<ChainStep> steps = new ArrayList<>();
        boolean orgKeysEnabled = orgConfig != null && !Boolean.FALSE.equals(orgConfig.getUseOrganizationKeys());

        if (mode == SttRequest.Mode.LIVE) {
            if (orgKeysEnabled && isUsableKey(orgConfig.getGeminiApiKey())) {
                steps.add(new ChainStep(gemini, new SttCredentials(orgConfig.getGeminiApiKey(), orgConfig.getGeminiModel())));
            }
            if (orgKeysEnabled && isUsableKey(orgConfig.getOpenAiApiKey())) {
                steps.add(new ChainStep(openAi, new SttCredentials(orgConfig.getOpenAiApiKey(), null)));
            }
            if (orgKeysEnabled && isUsableKey(orgConfig.getGrokApiKey())) {
                steps.add(new ChainStep(grok, new SttCredentials(orgConfig.getGrokApiKey(), null)));
            }
            if (isUsableKey(serverDeepgramKey)) {
                steps.add(new ChainStep(deepgram, new SttCredentials(serverDeepgramKey, null)));
            }
            if (isUsableKey(serverOpenAiKey)) {
                steps.add(new ChainStep(openAi, new SttCredentials(serverOpenAiKey, null)));
            }
            if (isUsableKey(serverXaiKey)) {
                steps.add(new ChainStep(grok, new SttCredentials(serverXaiKey, null)));
            }
            localGoogle.ifAvailable(p -> steps.add(new ChainStep(p, SttCredentials.none())));
        } else {
            if (isUsableKey(serverOpenAiKey)) {
                steps.add(new ChainStep(openAi, new SttCredentials(serverOpenAiKey, null)));
            }
            if (isUsableKey(serverDeepgramKey)) {
                steps.add(new ChainStep(deepgram, new SttCredentials(serverDeepgramKey, null)));
            }
            if (isUsableKey(serverXaiKey)) {
                steps.add(new ChainStep(grok, new SttCredentials(serverXaiKey, null)));
            }
        }
        simulated.ifAvailable(p -> steps.add(new ChainStep(p, SttCredentials.none())));
        return steps;
    }

    /**
     * Chave utilizável: preenchida, diferente do placeholder "mock-key" e não um ID de workspace
     * da Anthropic ("wrkspc_..."), que já foi colado por engano no lugar de chaves.
     */
    static boolean isUsableKey(String key) {
        return key != null && !key.isBlank() && !"mock-key".equals(key.trim()) && !key.trim().startsWith("wrkspc_");
    }
}
