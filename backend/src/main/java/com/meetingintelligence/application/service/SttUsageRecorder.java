package com.meetingintelligence.application.service;

import com.meetingintelligence.domain.ai.AiModels;
import com.meetingintelligence.domain.entity.LLMUsage;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionChainResult;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.persistence.repository.LLMUsageRepository;
import com.meetingintelligence.infrastructure.speech.DeepgramSpeechToTextProvider;
import com.meetingintelligence.infrastructure.speech.GrokSttProvider;
import com.meetingintelligence.infrastructure.speech.WavAudioInspector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Registra em {@code llm_usage} o áudio transcrito pelos provedores com preço conhecido: Grok
 * (spec 017, RF-07) e Deepgram (spec 018, RF-02). É a base para cotas e cobrança por hora. Os
 * provedores cobram pelo áudio enviado, então "sem fala" também conta.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SttUsageRecorder {

    public static final String OPERATION_LIVE = "STT_LIVE";
    public static final String OPERATION_BATCH = "STT_BATCH";

    private final LLMUsageRepository usageRepository;
    private final GrokSttProvider grok;

    /** Preço por provedor da cadeia: nome em llm_usage e US$ por hora de áudio. */
    private record Price(String provider, double usdPerHour) {}

    private static Price priceOf(String chainProvider) {
        if (GrokSttProvider.NAME.equals(chainProvider)) return new Price("GROK", AiModels.GROK_STT_USD_PER_HOUR);
        if (DeepgramSpeechToTextProvider.NAME.equals(chainProvider)) {
            return new Price("DEEPGRAM", AiModels.DEEPGRAM_STT_USD_PER_HOUR);
        }
        return null;
    }

    /** O áudio foi processado (com ou sem fala) por um provedor com preço conhecido? */
    public static boolean applies(TranscriptionChainResult result) {
        return result != null && priceOf(result.provider()) != null
                && result.status() != TranscriptionChainResult.Status.FAILED;
    }

    public void record(Meeting meeting, SttRequest request, TranscriptionChainResult result) {
        if (meeting == null || meeting.getOrganization() == null || !applies(result)) return;

        try {
            long durationMs = audioDurationMs(request, result);
            Price price = priceOf(result.provider());
            String model = GrokSttProvider.NAME.equals(result.provider()) ? grok.model() : AiModels.DEEPGRAM_STT;
            usageRepository.save(LLMUsage.builder()
                    .organization(meeting.getOrganization())
                    .meeting(meeting)
                    .provider(price.provider())
                    .modelName(model)
                    .operationType(request.mode() == SttRequest.Mode.BATCH ? OPERATION_BATCH : OPERATION_LIVE)
                    .promptTokens(0)
                    .completionTokens(0)
                    .totalTokens(0)
                    .estimatedCostUsd(costUsd(durationMs, price.usdPerHour()))
                    .latencyMs(latencyMs(result))
                    .build());
        } catch (Exception e) {
            log.warn("Não foi possível registrar o uso de transcrição: {}", e.getClass().getSimpleName());
        }
    }

    /** Duração pelo cabeçalho WAV (áudio ao vivo); nas gravações, o fim da última palavra. */
    static long audioDurationMs(SttRequest request, TranscriptionChainResult result) {
        long wav = WavAudioInspector.durationMs(request.audio());
        if (wav > 0) return wav;
        return result.segments().stream()
                .map(TranscriptionSegmentDto::getEndTimeMs)
                .filter(v -> v != null && v > 0)
                .mapToLong(Long::longValue)
                .max().orElse(0L);
    }

    static BigDecimal costUsd(long durationMs, double usdPerHour) {
        return BigDecimal.valueOf(durationMs)
                .multiply(BigDecimal.valueOf(usdPerHour))
                .divide(BigDecimal.valueOf(3_600_000L), 6, RoundingMode.HALF_UP);
    }

    private static long latencyMs(TranscriptionChainResult result) {
        return result.attempts().stream()
                .filter(a -> a.provider() != null && a.provider().equals(result.provider()))
                .reduce((first, second) -> second)
                .map(TranscriptionChainResult.Attempt::latencyMs)
                .orElse(0L);
    }
}
