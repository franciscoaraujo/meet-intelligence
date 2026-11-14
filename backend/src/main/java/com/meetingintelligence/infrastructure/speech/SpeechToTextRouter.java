package com.meetingintelligence.infrastructure.speech;

import com.meetingintelligence.domain.speech.SpeechToTextProvider;
import com.meetingintelligence.domain.speech.TranscriptionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SpeechToTextRouter {

    private final DeepgramSpeechToTextProvider deepgramProvider;
    private final OpenAiWhisperSpeechToTextProvider whisperProvider;

    public TranscriptionResult transcribeAudio(byte[] audioBytes, String mimeType, String language, boolean isLiveStream) {
        SpeechToTextProvider primary = isLiveStream ? deepgramProvider : whisperProvider;
        SpeechToTextProvider fallback = isLiveStream ? whisperProvider : deepgramProvider;

        try {
            log.info("Iniciando transcrição de áudio com provedor primário: {}", primary.getProviderName());
            return primary.transcribeAudio(audioBytes, mimeType, language);
        } catch (Exception e) {
            log.warn("Falha no provedor primário {}: {}. Acionando fallback: {}",
                    primary.getProviderName(), e.getMessage(), fallback.getProviderName());
            return fallback.transcribeAudio(audioBytes, mimeType, language);
        }
    }

    public TranscriptionResult transcribeStream(InputStream audioStream, String mimeType, String language) {
        return deepgramProvider.transcribeStream(audioStream, mimeType, language);
    }
}

