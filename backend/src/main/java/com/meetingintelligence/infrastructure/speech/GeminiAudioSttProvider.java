package com.meetingintelligence.infrastructure.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.ai.AiModels;
import com.meetingintelligence.domain.speech.AudioTranscriber;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.ai.GeminiLlmProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Transcrição multimodal com Gemini (generateContent com áudio inline), usando a chave e o modelo
 * da organização. Extraído de TranscriptionService.transcribeLiveTabAudioWithOrgAi.
 */
@Component
public class GeminiAudioSttProvider implements AudioTranscriber {

    public static final String NAME = "GEMINI";
    static final String NO_SPEECH_MARKER = "VAZIO";
    /** Prompt de transcrição no idioma da reunião (spec 011, RF-04). */
    static String prompt(String languageTag) {
        var language = com.meetingintelligence.domain.language.InterviewLanguage.fromTag(languageTag);
        return "Transcreva fielmente em " + language.namePt() + " (" + language.tag() + ") apenas o que foi falado neste "
                + "trecho de áudio de reunião, no idioma em que foi falado. Não traduza, não adicione comentários nem "
                + "explicações. Se houver apenas silêncio ou ruído sem fala inteligível, responda exatamente "
                + NO_SPEECH_MARKER + ".";
    }

    private final SttHttpClients http;
    private final ObjectMapper objectMapper;

    public GeminiAudioSttProvider(SttHttpClients http, ObjectMapper objectMapper) {
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
            String model = (credentials.model() != null && !credentials.model().isBlank())
                    ? credentials.model() : AiModels.GEMINI_DEFAULT;
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";

            String mime = request.mimeType() == null ? "audio/webm" : request.mimeType().split(";")[0].trim();
            Map<String, Object> body = Map.of("contents", List.of(Map.of("parts", List.of(
                    Map.of("inline_data", Map.of("mime_type", mime,
                            "data", Base64.getEncoder().encodeToString(request.audio()))),
                    Map.of("text", prompt(request.languageOrDefault()))))));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // Chave no cabeçalho (não na URL), para não aparecer em logs de requisição
            headers.set("x-goog-api-key", credentials.apiKey().trim());

            ResponseEntity<String> response = http.forMode(request.mode())
                    .postForEntity(url, new HttpEntity<>(body, headers), String.class);

            String text = GeminiLlmProvider.extractCandidateText(objectMapper.readTree(response.getBody()));
            if (text.isBlank() || text.toUpperCase().contains(NO_SPEECH_MARKER)) {
                return new TranscriptionOutcome.NoSpeech(NAME);
            }
            return new TranscriptionOutcome.Transcribed(NAME, List.of(TranscriptionSegmentDto.builder()
                    .text(text)
                    .startTimeMs(0L)
                    .endTimeMs(0L)
                    .confidence(BigDecimal.valueOf(0.9))
                    .language(request.languageOrDefault())
                    .build()));
        } catch (Exception e) {
            return SttFailures.from(NAME, e);
        }
    }
}
