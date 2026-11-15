package com.meetingintelligence.infrastructure.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.ai.AiModels;
import com.meetingintelligence.domain.speech.AudioTranscriber;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.List;

/**
 * OpenAI /v1/audio/transcriptions com gpt-transcribe (substituto do whisper-1). Usada com a chave
 * do servidor (gravações e áudio ao vivo) ou com a chave OpenAI legada da organização.
 * O modelo devolve só o texto (sem segmentos com horário).
 */
@Component
public class OpenAiTranscribeSttProvider implements AudioTranscriber {

    public static final String NAME = "OPENAI_TRANSCRIBE";
    private static final String URL = "https://api.openai.com/v1/audio/transcriptions";

    private final SttHttpClients http;
    private final ObjectMapper objectMapper;

    public OpenAiTranscribeSttProvider(SttHttpClients http, ObjectMapper objectMapper) {
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
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(credentials.apiKey().trim());
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("model", AiModels.OPENAI_TRANSCRIBE);
            // gpt-transcribe usa "languages[]" (dicas de idioma) no lugar de "language"
            body.add("languages[]", request.isoLanguage());
            final String fileName = fileNameFor(request);
            body.add("file", new ByteArrayResource(request.audio()) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            });

            ResponseEntity<String> response = http.forMode(request.mode())
                    .postForEntity(URL, new HttpEntity<>(body, headers), String.class);

            String text = objectMapper.readTree(response.getBody()).path("text").asText("").trim();
            if (text.isEmpty()) {
                return new TranscriptionOutcome.NoSpeech(NAME);
            }
            return new TranscriptionOutcome.Transcribed(NAME, List.of(TranscriptionSegmentDto.builder()
                    .text(text)
                    .startTimeMs(0L)
                    .endTimeMs(0L)
                    .confidence(BigDecimal.valueOf(0.95))
                    .language(request.languageOrDefault())
                    .build()));
        } catch (Exception e) {
            return SttFailures.from(NAME, e);
        }
    }

    static String fileNameFor(SttRequest request) {
        if (request.isWav()) return "audio.wav";
        String m = request.mimeType() == null ? "" : request.mimeType().toLowerCase();
        if (m.contains("mpeg") || m.contains("mp3")) return "audio.mp3";
        if (m.contains("mp4") || m.contains("m4a")) return "audio.m4a";
        if (m.contains("ogg")) return "audio.ogg";
        return "audio.webm";
    }
}
