package com.meetingintelligence.application.service;

import com.meetingintelligence.domain.ai.AiModels;
import com.meetingintelligence.domain.entity.LLMUsage;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionChainResult;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.persistence.repository.LLMUsageRepository;
import com.meetingintelligence.infrastructure.speech.GrokSttProvider;
import com.meetingintelligence.infrastructure.speech.WavAudioInspector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Registra em {@code llm_usage} o áudio transcrito pelo Grok (spec 017, RF-07): base para cotas e
 * cobrança por hora. A xAI cobra pelo áudio enviado, então "sem fala" também conta.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SttUsageRecorder {

    public static final String OPERATION_LIVE = "STT_LIVE";
    public static final String OPERATION_BATCH = "STT_BATCH";

    private final LLMUsageRepository usageRepository;
    private final GrokSttProvider grok;

    /** O resultado veio do Grok e o áudio foi processado (com ou sem fala)? */
    public static boolean applies(TranscriptionChainResult result) {
        return result != null && GrokSttProvider.NAME.equals(result.provider())
                && result.status() != TranscriptionChainResult.Status.FAILED;
    }

    public void record(Meeting meeting, SttRequest request, TranscriptionChainResult result) {
        if (meeting == null || meeting.getOrganization() == null || !applies(result)) return;

        try {
            long durationMs = audioDurationMs(request, result);
            usageRepository.save(LLMUsage.builder()
                    .organization(meeting.getOrganization())
                    .meeting(meeting)
                    .provider("GROK")
                    .modelName(grok.model())
                    .operationType(request.mode() == SttRequest.Mode.BATCH ? OPERATION_BATCH : OPERATION_LIVE)
                    .promptTokens(0)
                    .completionTokens(0)
                    .totalTokens(0)
                    .estimatedCostUsd(costUsd(durationMs))
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

    static BigDecimal costUsd(long durationMs) {
        return BigDecimal.valueOf(durationMs)
                .multiply(BigDecimal.valueOf(AiModels.GROK_STT_USD_PER_HOUR))
                .divide(BigDecimal.valueOf(3_600_000L), 6, RoundingMode.HALF_UP);
    }

    private static long latencyMs(TranscriptionChainResult result) {
        return result.attempts().stream()
                .filter(a -> GrokSttProvider.NAME.equals(a.provider()))
                .reduce((first, second) -> second)
                .map(TranscriptionChainResult.Attempt::latencyMs)
                .orElse(0L);
    }
}
