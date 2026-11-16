package com.meetingintelligence.infrastructure.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.ai.AiModels;
import com.meetingintelligence.domain.speech.AudioTranscriber;
import com.meetingintelligence.domain.speech.FailureReason;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * xAI Speech to Text (spec 017): {@code POST https://api.x.ai/v1/stt}, com a mesma chave do Grok
 * usada nas respostas. O texto vem num só segmento; o horário e a confiança saem de {@code words}.
 */
@Component
public class GrokSttProvider implements AudioTranscriber {

    public static final String NAME = "GROK_STT";
    static final String URL = "https://api.x.ai/v1/stt";

    private final SttHttpClients http;
    private final ObjectMapper objectMapper;
    private final String model;

    @Autowired
    public GrokSttProvider(SttHttpClients http, ObjectMapper objectMapper,
                           @Value("${app.ai.grok.stt-model:}") String model) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.model = model == null || model.isBlank() ? AiModels.GROK_STT : model.trim();
    }

    public GrokSttProvider(SttHttpClients http, ObjectMapper objectMapper) {
        this(http, objectMapper, null);
    }

    @Override
    public String providerName() {
        return NAME;
    }

    /** Modelo de STT em uso (RF-02). */
    public String model() {
        return model;
    }

    @Override
    public TranscriptionOutcome transcribe(SttRequest request, SttCredentials credentials) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(credentials.apiKey().trim());
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            // A ordem importa: a API exige "file" como último campo do formulário
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("model", model);
            body.add("language", request.isoLanguage());
            final String fileName = OpenAiTranscribeSttProvider.fileNameFor(request);
            body.add("file", new ByteArrayResource(request.audio()) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            });

            ResponseEntity<String> response = http.forMode(request.mode())
                    .postForEntity(URL, new HttpEntity<>(body, headers), String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            String text = root.path("text").asText("").trim();
            if (text.isEmpty()) {
                return new TranscriptionOutcome.NoSpeech(NAME);
            }
            return new TranscriptionOutcome.Transcribed(NAME, List.of(segment(text, root.path("words"), request)));
        } catch (HttpStatusCodeException http) {
            // 413: áudio grande demais; os demais provedores também o recusariam
            if (http.getStatusCode().value() == 413) {
                return new TranscriptionOutcome.Failed(NAME, FailureReason.INVALID_AUDIO, "HTTP 413");
            }
            return SttFailures.from(NAME, http);
        } catch (Exception e) {
            return SttFailures.from(NAME, e);
        }
    }

    private static TranscriptionSegmentDto segment(String text, JsonNode words, SttRequest request) {
        long start = 0L;
        long end = 0L;
        BigDecimal confidence = BigDecimal.valueOf(0.95);
        if (words.isArray() && !words.isEmpty()) {
            start = Math.round(words.get(0).path("start").asDouble(0) * 1000);
            end = Math.round(words.get(words.size() - 1).path("end").asDouble(0) * 1000);
            double sum = 0;
            int count = 0;
            for (JsonNode w : words) {
                if (w.has("confidence")) {
                    sum += w.path("confidence").asDouble();
                    count++;
                }
            }
            if (count > 0) {
                confidence = BigDecimal.valueOf(sum / count).setScale(3, RoundingMode.HALF_UP);
            }
        }
        return TranscriptionSegmentDto.builder()
                .text(text)
                .startTimeMs(start)
                .endTimeMs(end)
                .confidence(confidence)
                .language(request.languageOrDefault())
                .build();
    }
}
