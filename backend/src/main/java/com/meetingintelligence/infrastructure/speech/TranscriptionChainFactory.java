package com.meetingintelligence.infrastructure.speech;

import com.meetingintelligence.domain.entity.OrganizationAiConfig;
import com.meetingintelligence.domain.speech.AudioTranscriber;
import com.meetingintelligence.domain.speech.AudioTranscriber.SttCredentials;
import com.meetingintelligence.domain.speech.SttRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Monta a ordem dos provedores de STT a partir da configuração existente, incluindo só os
 * disponíveis (com chave).
 * <p>
 * Padrão do produto (spec 018, {@code app.speech.server-keys-first=true}): as chaves do servidor,
 * pagas pelo produto, vêm primeiro e as da organização ficam de reserva.
 * <pre>
 * LIVE : Deepgram → gpt-transcribe → Grok (servidor)
 *        → Gemini → gpt-transcribe → Grok (organização) → STT local (flag) → simulação (flag)
 * BATCH: Deepgram → gpt-transcribe → Grok (servidor) → simulação (flag)
 * </pre>
 * Com {@code false} (plano em que o cliente traz a chave), vale a ordem das specs 002 e 017:
 * <pre>
 * LIVE : Gemini (org) → gpt-transcribe (org) → Grok (org)
 *        → Deepgram (servidor) → gpt-transcribe (servidor) → Grok (servidor)
 *        → STT local (flag) → simulação (flag)
 * BATCH: gpt-transcribe (servidor) → Deepgram (servidor) → Grok (servidor) → simulação (flag)
 * </pre>
 */
@Component
@Slf4j
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
    private final boolean serverKeysFirst;

    @Autowired
    public TranscriptionChainFactory(
            GeminiAudioSttProvider gemini,
            OpenAiTranscribeSttProvider openAi,
            DeepgramSpeechToTextProvider deepgram,
            GrokSttProvider grok,
            ObjectProvider<LocalGoogleSttProvider> localGoogle,
            ObjectProvider<SimulatedSttProvider> simulated,
            @Value("${app.ai.openai.api-key:mock-key}") String serverOpenAiKey,
            @Value("${app.ai.deepgram.api-key:mock-key}") String serverDeepgramKey,
            @Value("${app.ai.grok.api-key:mock-key}") String serverXaiKey,
            @Value("${app.speech.server-keys-first:true}") boolean serverKeysFirst) {
        this.gemini = gemini;
        this.openAi = openAi;
        this.deepgram = deepgram;
        this.grok = grok;
        this.localGoogle = localGoogle;
        this.simulated = simulated;
        this.serverOpenAiKey = serverOpenAiKey;
        this.serverDeepgramKey = serverDeepgramKey;
        this.serverXaiKey = serverXaiKey;
        this.serverKeysFirst = serverKeysFirst;
        logPrimaryProvider();
    }

    /** Ordem das specs 002/017 (organização primeiro), usada pelos testes existentes. */
    public TranscriptionChainFactory(
            GeminiAudioSttProvider gemini,
            OpenAiTranscribeSttProvider openAi,
            DeepgramSpeechToTextProvider deepgram,
            GrokSttProvider grok,
            ObjectProvider<LocalGoogleSttProvider> localGoogle,
            ObjectProvider<SimulatedSttProvider> simulated,
            String serverOpenAiKey,
            String serverDeepgramKey,
            String serverXaiKey) {
        this(gemini, openAi, deepgram, grok, localGoogle, simulated,
                serverOpenAiKey, serverDeepgramKey, serverXaiKey, false);
    }

    /** @param orgConfig configuração BYOK da organização (pode ser null) */
    public List<ChainStep> build(OrganizationAiConfig orgConfig, SttRequest.Mode mode) {
        List<ChainStep> steps = new ArrayList<>();

        if (mode == SttRequest.Mode.LIVE) {
            if (serverKeysFirst) {
                steps.addAll(serverSteps());
                steps.addAll(organizationSteps(orgConfig));
            } else {
                steps.addAll(organizationSteps(orgConfig));
                steps.addAll(serverSteps());
            }
            localGoogle.ifAvailable(p -> steps.add(new ChainStep(p, SttCredentials.none())));
        } else {
            // Gravação enviada: só chaves do servidor
            steps.addAll(serverKeysFirst ? serverSteps() : serverStepsBatchLegacy());
        }

        simulated.ifAvailable(p -> steps.add(new ChainStep(p, SttCredentials.none())));
        return steps;
    }

    /** Chaves cadastradas pela organização (respeitando {@code use_organization_keys}). */
    private List<ChainStep> organizationSteps(OrganizationAiConfig orgConfig) {
        List<ChainStep> steps = new ArrayList<>();
        boolean enabled = orgConfig != null && !Boolean.FALSE.equals(orgConfig.getUseOrganizationKeys());
        if (!enabled) return steps;
        if (isUsableKey(orgConfig.getGeminiApiKey())) {
            steps.add(new ChainStep(gemini, new SttCredentials(orgConfig.getGeminiApiKey(), orgConfig.getGeminiModel())));
        }
        if (isUsableKey(orgConfig.getOpenAiApiKey())) {
            steps.add(new ChainStep(openAi, new SttCredentials(orgConfig.getOpenAiApiKey(), null)));
        }
        if (isUsableKey(orgConfig.getGrokApiKey())) {
            steps.add(new ChainStep(grok, new SttCredentials(orgConfig.getGrokApiKey(), null)));
        }
        return steps;
    }

    /** Chaves do servidor: Deepgram → gpt-transcribe → Grok (a ordem de ambos os modos ao vivo). */
    private List<ChainStep> serverSteps() {
        List<ChainStep> steps = new ArrayList<>();
        if (isUsableKey(serverDeepgramKey)) {
            steps.add(new ChainStep(deepgram, new SttCredentials(serverDeepgramKey, null)));
        }
        if (isUsableKey(serverOpenAiKey)) {
            steps.add(new ChainStep(openAi, new SttCredentials(serverOpenAiKey, null)));
        }
        if (isUsableKey(serverXaiKey)) {
            steps.add(new ChainStep(grok, new SttCredentials(serverXaiKey, null)));
        }
        return steps;
    }

    /** Gravação enviada na ordem antiga (spec 002 RF-04): gpt-transcribe → Deepgram → Grok. */
    private List<ChainStep> serverStepsBatchLegacy() {
        List<ChainStep> steps = new ArrayList<>();
        if (isUsableKey(serverOpenAiKey)) {
            steps.add(new ChainStep(openAi, new SttCredentials(serverOpenAiKey, null)));
        }
        if (isUsableKey(serverDeepgramKey)) {
            steps.add(new ChainStep(deepgram, new SttCredentials(serverDeepgramKey, null)));
        }
        if (isUsableKey(serverXaiKey)) {
            steps.add(new ChainStep(grok, new SttCredentials(serverXaiKey, null)));
        }
        return steps;
    }

    /** Spec 018 RF-06: qual provedor do servidor vem primeiro, sem nunca mostrar a chave. */
    private void logPrimaryProvider() {
        List<ChainStep> server = serverSteps();
        String first = server.isEmpty() ? null : server.get(0).transcriber().providerName();
        if (first == null) {
            log.info("Transcrição ao vivo: nenhuma chave de STT no servidor; usa as chaves da organização");
        } else if (serverKeysFirst) {
            log.info("Transcrição ao vivo: {} primeiro (chave do servidor); chaves da organização como reserva", first);
        } else {
            log.info("Transcrição ao vivo: chaves da organização primeiro; {} (chave do servidor) como reserva", first);
        }
    }

    /**
     * Chave utilizável: preenchida, diferente do placeholder "mock-key", dos valores de exemplo do
     * .env.example ("sua-chave-...", spec 018) e de um ID de workspace da Anthropic ("wrkspc_..."),
     * que já foi colado por engano no lugar de chaves.
     */
    static boolean isUsableKey(String key) {
        if (key == null || key.isBlank()) return false;
        String k = key.trim();
        return !"mock-key".equals(k) && !k.startsWith("wrkspc_") && !k.startsWith("sua-chave");
    }
}
