package com.meetingintelligence.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.speech.AudioTranscriber.SttCredentials;
import com.meetingintelligence.domain.speech.FailureReason;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import com.meetingintelligence.infrastructure.speech.DeepgramSpeechToTextProvider;
import com.meetingintelligence.infrastructure.speech.GeminiAudioSttProvider;
import com.meetingintelligence.infrastructure.speech.OpenAiTranscribeSttProvider;
import com.meetingintelligence.infrastructure.speech.SttHttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.net.SocketTimeoutException;

import static com.meetingintelligence.speech.WavFixtures.wav;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

/** Provedores de STT contra respostas HTTP simuladas: nunca devolvem texto inventado. */
class SttProvidersTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SttHttpClients http;
    private MockRestServiceServer server;
    private final SttRequest request = new SttRequest(wav(2000), "audio/wav", "pt-BR", SttRequest.Mode.LIVE);
    private final SttCredentials creds = new SttCredentials("test-key", null);

    @BeforeEach
    void setUp() {
        http = new SttHttpClients(1000, 1000, 1000);
        server = MockRestServiceServer.bindTo(http.forMode(SttRequest.Mode.LIVE)).build();
    }

    // ---------------------------------------------------------------- Deepgram

    @Test
    @DisplayName("CA-04: Deepgram com erro HTTP 500 devolve falha, não transcrição simulada")
    void deepgramServerErrorIsFailure() {
        server.expect(requestTo(startsWith("https://api.deepgram.com/v1/listen")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        TranscriptionOutcome outcome = new DeepgramSpeechToTextProvider(http, mapper).transcribe(request, creds);

        assertThat(outcome).isInstanceOf(TranscriptionOutcome.Failed.class);
        assertThat(((TranscriptionOutcome.Failed) outcome).reason()).isEqualTo(FailureReason.HTTP_5XX);
    }

    @Test
    @DisplayName("Deepgram com transcrição vazia é 'sem fala'")
    void deepgramEmptyTranscriptIsNoSpeech() {
        server.expect(requestTo(startsWith("https://api.deepgram.com")))
                .andRespond(withSuccess("""
                        {"results":{"channels":[{"alternatives":[{"transcript":"","confidence":0}]}]}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(new DeepgramSpeechToTextProvider(http, mapper).transcribe(request, creds))
                .isInstanceOf(TranscriptionOutcome.NoSpeech.class);
    }

    @Test
    @DisplayName("Deepgram com diarização devolve um segmento por parágrafo, com falante e horário")
    void deepgramDiarizedParagraphs() {
        server.expect(requestTo(startsWith("https://api.deepgram.com")))
                .andExpect(header("Authorization", "Token test-key"))
                .andRespond(withSuccess("""
                        {"results":{"channels":[{"alternatives":[{"transcript":"Olá. Tudo bem?","confidence":0.97,
                          "paragraphs":{"paragraphs":[
                            {"speaker":0,"start":0.5,"end":1.2,"sentences":[{"text":"Olá."}]},
                            {"speaker":1,"start":1.5,"end":2.4,"sentences":[{"text":"Tudo bem?"}]}]}}]}]}}
                        """, MediaType.APPLICATION_JSON));

        var outcome = (TranscriptionOutcome.Transcribed) new DeepgramSpeechToTextProvider(http, mapper).transcribe(request, creds);

        assertThat(outcome.segments()).hasSize(2);
        assertThat(outcome.segments().get(1).getSpeakerTag()).isEqualTo(2);
        assertThat(outcome.segments().get(1).getStartTimeMs()).isEqualTo(1500L);
        assertThat(outcome.segments().get(1).getText()).isEqualTo("Tudo bem?");
    }

    // ------------------------------------------------------------------ OpenAI

    @Test
    @DisplayName("CA-07: OpenAI 401 vira falha HTTP_4XX (sem retry na cadeia)")
    void openAiUnauthorizedIsClientError() {
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        var outcome = (TranscriptionOutcome.Failed) new OpenAiTranscribeSttProvider(http, mapper).transcribe(request, creds);

        assertThat(outcome.reason()).isEqualTo(FailureReason.HTTP_4XX);
        assertThat(outcome.retryable()).isFalse();
    }

    @Test
    @DisplayName("OpenAI 429 é falha transitória (com retry na cadeia)")
    void openAiRateLimitIsRetryable() {
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        var outcome = (TranscriptionOutcome.Failed) new OpenAiTranscribeSttProvider(http, mapper).transcribe(request, creds);

        assertThat(outcome.reason()).isEqualTo(FailureReason.HTTP_429);
        assertThat(outcome.retryable()).isTrue();
    }

    @Test
    @DisplayName("OpenAI com texto vazio é 'sem fala'; com texto é transcrição")
    void openAiTextAndEmpty() {
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
                .andRespond(withSuccess("{\"text\":\"\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
                .andRespond(withSuccess("{\"text\":\"Como você garante consistência?\"}", MediaType.APPLICATION_JSON));
        var provider = new OpenAiTranscribeSttProvider(http, mapper);

        assertThat(provider.transcribe(request, creds)).isInstanceOf(TranscriptionOutcome.NoSpeech.class);
        var ok = (TranscriptionOutcome.Transcribed) provider.transcribe(request, creds);
        assertThat(ok.segments().get(0).getText()).isEqualTo("Como você garante consistência?");
    }

    @Test
    @DisplayName("CA-05: timeout de leitura vira falha TIMEOUT (transitória)")
    void timeoutIsRetryableFailure() {
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        var outcome = (TranscriptionOutcome.Failed) new OpenAiTranscribeSttProvider(http, mapper).transcribe(request, creds);

        assertThat(outcome.reason()).isEqualTo(FailureReason.TIMEOUT);
        assertThat(outcome.retryable()).isTrue();
    }

    // ------------------------------------------------------------------ Gemini

    @Test
    @DisplayName("Gemini: marcador VAZIO é 'sem fala'; chave vai no cabeçalho, não na URL")
    void geminiNoSpeechMarker() {
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-x:generateContent"))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"VAZIO"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        var outcome = new GeminiAudioSttProvider(http, mapper).transcribe(request, new SttCredentials("test-key", "gemini-x"));

        assertThat(outcome).isInstanceOf(TranscriptionOutcome.NoSpeech.class);
    }

    @Test
    @DisplayName("Gemini: transcrição ignora partes de raciocínio")
    void geminiTranscription() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com")))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"pensando","thought":true},{"text":"Explique SOLID"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        var outcome = (TranscriptionOutcome.Transcribed) new GeminiAudioSttProvider(http, mapper)
                .transcribe(request, new SttCredentials("k", null));

        assertThat(outcome.segments().get(0).getText()).isEqualTo("Explique SOLID");
    }

    @Test
    @DisplayName("Gemini: resposta não-JSON vira PARSE_ERROR")
    void geminiParseError() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com")))
                .andRespond(withSuccess("<html>erro</html>", MediaType.TEXT_HTML));

        var outcome = (TranscriptionOutcome.Failed) new GeminiAudioSttProvider(http, mapper)
                .transcribe(request, new SttCredentials("k", null));

        assertThat(outcome.reason()).isEqualTo(FailureReason.PARSE_ERROR);
    }
}
