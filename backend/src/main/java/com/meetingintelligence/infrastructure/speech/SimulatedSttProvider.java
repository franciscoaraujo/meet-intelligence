package com.meetingintelligence.infrastructure.speech;

import com.meetingintelligence.domain.speech.AudioTranscriber;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Transcrição simulada para desenvolvimento sem chaves. Existe <b>somente</b> com
 * app.speech.simulation-enabled=true e grava source=SIMULATION, para nunca se passar por fala real.
 */
@Component
@ConditionalOnProperty(name = "app.speech.simulation-enabled", havingValue = "true")
public class SimulatedSttProvider implements AudioTranscriber {

    public static final String NAME = "SIMULATION";

    @Override
    public String providerName() {
        return NAME;
    }

    @Override
    public TranscriptionOutcome transcribe(SttRequest request, SttCredentials credentials) {
        String lang = request.languageOrDefault();
        return new TranscriptionOutcome.Transcribed(NAME, List.of(
                TranscriptionSegmentDto.builder()
                        .speakerLabel("Falante simulado 1").speakerTag(1)
                        .text("[Simulação] Iniciamos a reunião para avaliar os requisitos de arquitetura.")
                        .startTimeMs(0L).endTimeMs(4500L)
                        .confidence(BigDecimal.ONE).language(lang).build(),
                TranscriptionSegmentDto.builder()
                        .speakerLabel("Falante simulado 2").speakerTag(2)
                        .text("[Simulação] Avaliamos PostgreSQL com pgvector para busca semântica.")
                        .startTimeMs(5000L).endTimeMs(9500L)
                        .confidence(BigDecimal.ONE).language(lang).build()));
    }
}
