package com.meetingintelligence.infrastructure.speech;

import com.meetingintelligence.domain.speech.AudioTranscriber;
import com.meetingintelligence.domain.speech.FailureReason;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * STT local via Python + biblioteca speech_recognition ({@code recognize_google}).
 *
 * <b>Desligado por padrão</b> (app.speech.local-google-stt.enabled=false): usa um endpoint não oficial
 * do Google, sem SLA, e envia o áudio a ele; serve apenas para desenvolvimento sem chaves.
 * Lê somente stdout (stderr separado) e usa marcadores explícitos para sem fala e erro.
 */
@Component
@ConditionalOnProperty(name = "app.speech.local-google-stt.enabled", havingValue = "true")
public class LocalGoogleSttProvider implements AudioTranscriber {

    public static final String NAME = "LOCAL_GOOGLE_STT";
    static final String NO_SPEECH = "__NO_SPEECH__";
    static final String ERROR = "__ERROR__";

    private static final String SCRIPT = String.join("\n",
            "import sys",
            "sys.stdout.reconfigure(encoding='utf-8')",
            "try:",
            "    import speech_recognition as sr",
            "    r = sr.Recognizer()",
            "    with sr.AudioFile(sys.argv[1]) as source:",
            "        audio = r.record(source)",
            "    print(r.recognize_google(audio, language=sys.argv[2]))",
            "except Exception as e:",
            "    if type(e).__name__ == 'UnknownValueError':",
            "        print('" + NO_SPEECH + "')",
            "    else:",
            "        print('" + ERROR + "' + type(e).__name__)",
            "");

    @Override
    public String providerName() {
        return NAME;
    }

    @Override
    public TranscriptionOutcome transcribe(SttRequest request, SttCredentials credentials) {
        if (!request.isWav()) {
            return new TranscriptionOutcome.Failed(NAME, FailureReason.INVALID_AUDIO, "somente WAV");
        }
        Path wav = null;
        try {
            wav = Files.createTempFile("meet_stt_", ".wav");
            Files.write(wav, request.audio());
            Process proc = new ProcessBuilder("python", "-c", SCRIPT, wav.toAbsolutePath().toString(),
                    request.languageOrDefault())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return new TranscriptionOutcome.Failed(NAME, FailureReason.TIMEOUT, "processo python");
            }
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return interpret(out);
        } catch (Exception e) {
            return new TranscriptionOutcome.Failed(NAME, FailureReason.UNEXPECTED, e.getClass().getSimpleName());
        } finally {
            if (wav != null) {
                try {
                    Files.deleteIfExists(wav);
                } catch (Exception ignored) {
                    // arquivo temporário
                }
            }
        }
    }

    static TranscriptionOutcome interpret(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return new TranscriptionOutcome.Failed(NAME, FailureReason.UNEXPECTED, "saída vazia");
        }
        if (stdout.startsWith(NO_SPEECH)) {
            return new TranscriptionOutcome.NoSpeech(NAME);
        }
        if (stdout.startsWith(ERROR)) {
            String type = stdout.substring(ERROR.length());
            FailureReason reason = type.contains("RequestError") ? FailureReason.NETWORK : FailureReason.UNEXPECTED;
            return new TranscriptionOutcome.Failed(NAME, reason, type);
        }
        return new TranscriptionOutcome.Transcribed(NAME, List.of(TranscriptionSegmentDto.builder()
                .text(stdout)
                .startTimeMs(0L)
                .endTimeMs(0L)
                .confidence(BigDecimal.valueOf(0.8))
                .build()));
    }
}
