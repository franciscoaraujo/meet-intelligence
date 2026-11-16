package com.meetingintelligence.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.speech.AudioTranscriber;
import com.meetingintelligence.domain.speech.AudioTranscriber.SttCredentials;
import com.meetingintelligence.domain.speech.FailureReason;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionChainResult;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.speech.GrokSttProvider;
import com.meetingintelligence.infrastructure.speech.SpeechToTextRouterTestAccess;
import com.meetingintelligence.infrastructure.speech.SttHttpClients;
import com.meetingintelligence.infrastructure.speech.TranscriptionChainFactory.ChainStep;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

import static com.meetingintelligence.speech.WavFixtures.wav;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Spec 017: transcrição com o Grok (xAI) contra respostas HTTP simuladas. */
class GrokSttProviderTest {

    private static final String URL = "https://api.x.ai/v1/stt";
    private static final String KEY = "xai-secret-test-key";

    private final ObjectMapper mapper = new ObjectMapper();
    private SttHttpClients http;
    private MockRestServiceServer server;
    private GrokSttProvider grok;
    private final SttRequest request = new SttRequest(wav(2000), "audio/wav", "pt-BR", SttRequest.Mode.LIVE);
    private final SttCredentials creds = new SttCredentials(KEY, null);

    @BeforeEach
    void setUp() {
        http = new SttHttpClients(1000, 1000, 1000);
        server = MockRestServiceServer.bindTo(http.forMode(SttRequest.Mode.LIVE)).build();
        grok = new GrokSttProvider(http, mapper);
    }

    private static TranscriptionOutcome.Failed failed(TranscriptionOutcome outcome) {
        assertThat(outcome).isInstanceOf(TranscriptionOutcome.Failed.class);
        return (TranscriptionOutcome.Failed) outcome;
    }

    @Test
    @DisplayName("CA-02: multipart com model, language (ISO) e file por último, com Bearer")
    void requestShape() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andExpect(req -> {
                    String body = ((MockClientHttpRequest) req).getBodyAsString(StandardCharsets.ISO_8859_1);
                    int model = body.indexOf("name=\"model\"");
                    int language = body.indexOf("name=\"language\"");
                    int file = body.indexOf("name=\"file\"");
                    assertThat(model).isNotNegative();
                    assertThat(language).isGreaterThan(model);
                    assertThat(file).isGreaterThan(language);
                    assertThat(body).contains("grok-voice-transcribe-2.0");
                    assertThat(body.substring(language, file)).contains("\r\n\r\npt\r\n");
                    assertThat(body).contains("filename=\"audio.wav\"");
                })
                .andRespond(withSuccess("{\"text\":\"Olá\",\"language\":\"pt\",\"duration\":2.0}", MediaType.APPLICATION_JSON));

        assertThat(grok.transcribe(request, creds)).isInstanceOf(TranscriptionOutcome.Transcribed.class);
        server.verify();
    }

    @Test
    @DisplayName("RF-02: modelo configurável; vazio usa o padrão")
    void configurableModel() {
        assertThat(new GrokSttProvider(http, mapper, "grok-voice-transcribe-1.0").model()).isEqualTo("grok-voice-transcribe-1.0");
        assertThat(new GrokSttProvider(http, mapper, " ").model()).isEqualTo("grok-voice-transcribe-2.0");
        assertThat(grok.providerName()).isEqualTo("GROK_STT");
    }

    @Test
    @DisplayName("RF-01: texto com words vira um segmento com início, fim e confiança média")
    void transcribedWithWords() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"text":" Como você faria o cache? ","language":"pt","duration":2.4,
                 "words":[{"text":"Como","start":0.32,"end":0.5,"confidence":0.9},
                          {"text":"você","start":0.5,"end":0.7,"confidence":0.8},
                          {"text":"cache?","start":1.9,"end":2.25}]}
                """, MediaType.APPLICATION_JSON));

        TranscriptionOutcome outcome = grok.transcribe(request, creds);

        assertThat(outcome).isInstanceOf(TranscriptionOutcome.Transcribed.class);
        TranscriptionSegmentDto seg = ((TranscriptionOutcome.Transcribed) outcome).segments().get(0);
        assertThat(seg.getText()).isEqualTo("Como você faria o cache?");
        assertThat(seg.getStartTimeMs()).isEqualTo(320L);
        assertThat(seg.getEndTimeMs()).isEqualTo(2250L);
        assertThat(seg.getConfidence()).isEqualByComparingTo(new BigDecimal("0.850"));
        assertThat(seg.getLanguage()).isEqualTo("pt-BR");
    }

    @Test
    @DisplayName("RF-01: sem words, horário 0 e confiança padrão 0,95")
    void transcribedWithoutWords() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"text\":\"Tudo bem?\",\"language\":\"pt\",\"duration\":1.0}",
                MediaType.APPLICATION_JSON));

        TranscriptionSegmentDto seg = ((TranscriptionOutcome.Transcribed) grok.transcribe(request, creds)).segments().get(0);

        assertThat(seg.getStartTimeMs()).isZero();
        assertThat(seg.getEndTimeMs()).isZero();
        assertThat(seg.getConfidence()).isEqualByComparingTo("0.95");
    }

    @Test
    @DisplayName("CA-03: texto vazio é 'sem fala'")
    void emptyTextIsNoSpeech() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"text\":\"  \",\"language\":\"pt\",\"duration\":3.0}",
                MediaType.APPLICATION_JSON));

        assertThat(grok.transcribe(request, creds)).isInstanceOf(TranscriptionOutcome.NoSpeech.class);
    }

    @Test
    @DisplayName("CA-04: 401 não repete; 429/503 repetem; 413 é áudio inválido; 400 passa ao próximo")
    void errorClassification() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.PAYLOAD_TOO_LARGE));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        TranscriptionOutcome.Failed unauthorized = failed(grok.transcribe(request, creds));
        assertThat(unauthorized.reason()).isEqualTo(FailureReason.HTTP_4XX);
        assertThat(unauthorized.retryable()).isFalse();
        assertThat(failed(grok.transcribe(request, creds)).reason()).isEqualTo(FailureReason.HTTP_429);
        assertThat(failed(grok.transcribe(request, creds)).reason()).isEqualTo(FailureReason.HTTP_5XX);
        assertThat(failed(grok.transcribe(request, creds)).reason()).isEqualTo(FailureReason.INVALID_AUDIO);
        assertThat(failed(grok.transcribe(request, creds)).reason()).isEqualTo(FailureReason.HTTP_4XX);
    }

    @Test
    @DisplayName("CA-08: a chave não aparece no motivo da falha, mesmo que o corpo do erro a repita")
    void keyNeverInFailureDetail() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid api key " + KEY + "\"}"));

        TranscriptionOutcome.Failed f = failed(grok.transcribe(request, creds));

        assertThat(f.detail()).isEqualTo("HTTP 401").doesNotContain(KEY);
    }

    // --------------------------------------------------------------- na cadeia (spec 002)

    /** Provedor seguinte da cadeia, sempre com o mesmo texto. */
    private static AudioTranscriber next(String text) {
        return new AudioTranscriber() {
            @Override
            public String providerName() {
                return "NEXT";
            }

            @Override
            public TranscriptionOutcome transcribe(SttRequest r, SttCredentials c) {
                return new TranscriptionOutcome.Transcribed("NEXT", List.of(TranscriptionSegmentDto.builder()
                        .text(text).startTimeMs(0L).endTimeMs(0L).confidence(BigDecimal.ONE).language("pt-BR").build()));
            }
        };
    }

    private TranscriptionChainResult chain(AudioTranscriber second) {
        return SpeechToTextRouterTestAccess.create(new SimpleMeterRegistry(), Clock.systemUTC(), ms -> {}, 1, 30_000, 300_000)
                .transcribe(request, List.of(new ChainStep(grok, creds),
                        new ChainStep(second, new SttCredentials("other", null))));
    }

    @Test
    @DisplayName("CA-01: Grok transcreve primeiro na cadeia e o resultado sai como GROK_STT")
    void grokTranscribesInChain() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"text\":\"Fale sobre você.\",\"language\":\"pt\",\"duration\":2.0}",
                MediaType.APPLICATION_JSON));

        TranscriptionChainResult result = chain(next("não deveria"));

        assertThat(result.status()).isEqualTo(TranscriptionChainResult.Status.TRANSCRIBED);
        assertThat(result.provider()).isEqualTo("GROK_STT");
        assertThat(result.segments().get(0).getText()).isEqualTo("Fale sobre você.");
    }

    @Test
    @DisplayName("CA-04: 401 passa direto ao próximo provedor; 503 repete uma vez no Grok")
    void chainFallbackAndRetry() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        TranscriptionChainResult unauthorized = chain(next("pelo próximo"));
        assertThat(unauthorized.provider()).isEqualTo("NEXT");
        assertThat(unauthorized.attempts()).hasSize(2);
        server.verify();

        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"text\":\"Segunda tentativa\",\"duration\":1.0}",
                MediaType.APPLICATION_JSON));
        TranscriptionChainResult retried = chain(next("não deveria"));
        assertThat(retried.provider()).isEqualTo("GROK_STT");
        assertThat(retried.segments().get(0).getText()).isEqualTo("Segunda tentativa");
        server.verify();
    }

    @Test
    @DisplayName("CA-04: 413 encerra a cadeia sem chamar o próximo provedor")
    void payloadTooLargeEndsChain() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.PAYLOAD_TOO_LARGE));

        TranscriptionChainResult result = chain(next("não deveria"));

        assertThat(result.status()).isEqualTo(TranscriptionChainResult.Status.FAILED);
        assertThat(result.failureReason()).isEqualTo(FailureReason.INVALID_AUDIO);
    }
}
