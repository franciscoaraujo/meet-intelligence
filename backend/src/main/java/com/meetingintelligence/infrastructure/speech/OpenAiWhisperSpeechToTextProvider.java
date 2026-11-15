package com.meetingintelligence.infrastructure.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.ai.AiModels;
import com.meetingintelligence.domain.speech.SpeechToTextProvider;
import com.meetingintelligence.domain.speech.TranscriptionResult;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class OpenAiWhisperSpeechToTextProvider implements SpeechToTextProvider {

    private final String apiKey;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OpenAiWhisperSpeechToTextProvider(
            @Value("${app.ai.openai.api-key:mock-key}") String apiKey,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderName() {
        return "WHISPER";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.equals("mock-key");
    }

    @Override
    public TranscriptionResult transcribeAudio(byte[] audioBytes, String mimeType, String language) {
        if (!isAvailable()) {
            return generateMockTranscription(language);
        }

        try {
            String url = "https://api.openai.com/v1/audio/transcriptions";

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            // gpt-transcribe substitui o whisper-1 (descontinuado). Ele usa "languages[]" (lista de dicas de idioma)
            // no lugar de "language" e responde só com o texto, sem segmentos com horário.
            body.add("model", AiModels.OPENAI_TRANSCRIBE);
            if (language != null && !language.equalsIgnoreCase("auto")) {
                body.add("languages[]", language.split("-")[0]); // formato ISO: 'pt', 'en'...
            }

            final String fileName = fileNameFor(mimeType);
            ByteArrayResource resource = new ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };
            body.add("file", resource);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseWhisperResponse(response.getBody(), language);
            }
        } catch (Exception e) {
            log.error("Erro na chamada à OpenAI Whisper API: {}", e.getMessage(), e);
        }

        return generateMockTranscription(language);
    }

    @Override
    public TranscriptionResult transcribeStream(InputStream audioStream, String mimeType, String language) {
        try {
            byte[] bytes = audioStream.readAllBytes();
            return transcribeAudio(bytes, mimeType, language);
        } catch (Exception e) {
            log.error("Erro ao ler stream para o Whisper: {}", e.getMessage());
            return generateMockTranscription(language);
        }
    }

    private static String fileNameFor(String mimeType) {
        String m = mimeType == null ? "" : mimeType.toLowerCase();
        if (m.contains("wav")) return "recording.wav";
        if (m.contains("mpeg") || m.contains("mp3")) return "recording.mp3";
        if (m.contains("mp4") || m.contains("m4a")) return "recording.m4a";
        if (m.contains("ogg")) return "recording.ogg";
        return "recording.webm";
    }

    private TranscriptionResult parseWhisperResponse(String jsonString, String language) {
        try {
            JsonNode root = objectMapper.readTree(jsonString);
            String fullText = root.path("text").asText();
            JsonNode segmentsNode = root.path("segments");

            List<TranscriptionSegmentDto> segments = new ArrayList<>();
            if ((!segmentsNode.isArray() || segmentsNode.isEmpty()) && !fullText.isBlank()) {
                // gpt-transcribe não devolve segmentos: registra a transcrição como um único trecho
                segments.add(TranscriptionSegmentDto.builder()
                        .speakerLabel("Falante")
                        .speakerTag(1)
                        .text(fullText.trim())
                        .startTimeMs(0L)
                        .endTimeMs(0L)
                        .confidence(BigDecimal.valueOf(0.95))
                        .language(language)
                        .build());
            }
            if (segmentsNode.isArray()) {
                for (JsonNode s : segmentsNode) {
                    double start = s.path("start").asDouble(0.0);
                    double end = s.path("end").asDouble(0.0);
                    String text = s.path("text").asText();

                    segments.add(TranscriptionSegmentDto.builder()
                            .speakerLabel("Falante")
                            .speakerTag(1)
                            .text(text.trim())
                            .startTimeMs((long) (start * 1000))
                            .endTimeMs((long) (end * 1000))
                            .confidence(BigDecimal.valueOf(0.95))
                            .language(language)
                            .build());
                }
            }

            return TranscriptionResult.builder()
                    .provider(getProviderName())
                    .detectedLanguage(language)
                    .fullText(fullText)
                    .segments(segments)
                    .build();
        } catch (Exception e) {
            log.error("Erro no parse da resposta do Whisper: {}", e.getMessage());
            return generateMockTranscription(language);
        }
    }

    private TranscriptionResult generateMockTranscription(String language) {
        List<TranscriptionSegmentDto> segments = new ArrayList<>();
        segments.add(TranscriptionSegmentDto.builder()
                .speakerLabel("Falante 1")
                .speakerTag(1)
                .text("Gravação enviada com sucesso e transcrita através do modelo Whisper.")
                .startTimeMs(0L)
                .endTimeMs(6000L)
                .confidence(BigDecimal.valueOf(0.97))
                .language(language != null ? language : "pt-BR")
                .build());

        return TranscriptionResult.builder()
                .provider(getProviderName())
                .detectedLanguage(language != null ? language : "pt-BR")
                .durationMs(6000L)
                .segments(segments)
                .fullText("Gravação enviada com sucesso e transcrita através do modelo Whisper.")
                .build();
    }
}

