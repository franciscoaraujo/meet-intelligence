package com.meetingintelligence.infrastructure.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.ai.AiModels;
import com.meetingintelligence.domain.speech.AudioTranscriber;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Deepgram pré-gravado (/v1/listen) com diarização. Sem simulação: falha vira
 * {@link TranscriptionOutcome.Failed} e o roteador tenta o próximo provedor.
 */
@Component
public class DeepgramSpeechToTextProvider implements AudioTranscriber {

    public static final String NAME = "DEEPGRAM";

    private final SttHttpClients http;
    private final ObjectMapper objectMapper;

    public DeepgramSpeechToTextProvider(SttHttpClients http, ObjectMapper objectMapper) {
        this.http = http;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return NAME;
    }

    @Override
    public TranscriptionOutcome transcribe(SttRequest request, SttCredentials credentials) {
        try {
            String url = "https://api.deepgram.com/v1/listen?model=" + AiModels.DEEPGRAM_STT
                    + "&language=" + request.languageOrDefault()
                    + "&smart_format=true&diarize=true&punctuate=true&paragraphs=true";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Token " + credentials.apiKey().trim());
            headers.setContentType(MediaType.parseMediaType(request.mimeType() != null ? request.mimeType() : "audio/webm"));

            ResponseEntity<String> response = http.forMode(request.mode())
                    .postForEntity(url, new HttpEntity<>(request.audio(), headers), String.class);
            return parse(response.getBody(), request.languageOrDefault());
        } catch (Exception e) {
            return SttFailures.from(NAME, e);
        }
    }

    TranscriptionOutcome parse(String body, String language) throws Exception {
        JsonNode alternative = objectMapper.readTree(body)
                .path("results").path("channels").path(0).path("alternatives").path(0);
        if (alternative.isMissingNode()) {
            return new TranscriptionOutcome.Failed(NAME, com.meetingintelligence.domain.speech.FailureReason.PARSE_ERROR,
                    "resposta sem alternatives");
        }
        String transcript = alternative.path("transcript").asText("").trim();
        if (transcript.isEmpty()) {
            return new TranscriptionOutcome.NoSpeech(NAME);
        }

        double confidence = alternative.path("confidence").asDouble(0.9);
        List<TranscriptionSegmentDto> segments = new ArrayList<>();
        for (JsonNode p : alternative.path("paragraphs").path("paragraphs")) {
            StringBuilder text = new StringBuilder();
            for (JsonNode s : p.path("sentences")) {
                text.append(s.path("text").asText()).append(' ');
            }
            if (text.toString().isBlank()) continue;
            int speaker = p.path("speaker").asInt(0);
            segments.add(TranscriptionSegmentDto.builder()
                    .speakerLabel("Participante " + (speaker + 1))
                    .speakerTag(speaker + 1)
                    .text(text.toString().trim())
                    .startTimeMs((long) (p.path("start").asDouble(0) * 1000))
                    .endTimeMs((long) (p.path("end").asDouble(0) * 1000))
                    .confidence(BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP))
                    .language(language)
                    .build());
        }
        if (segments.isEmpty()) {
            // Sem parágrafos (áudio curto): usa a transcrição inteira como um trecho
            segments.add(TranscriptionSegmentDto.builder()
                    .text(transcript)
                    .startTimeMs(0L)
                    .endTimeMs(0L)
                    .confidence(BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP))
                    .language(language)
                    .build());
        }
        return new TranscriptionOutcome.Transcribed(NAME, segments);
    }
}
