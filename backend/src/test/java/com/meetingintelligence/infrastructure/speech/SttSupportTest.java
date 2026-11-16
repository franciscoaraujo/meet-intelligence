package com.meetingintelligence.infrastructure.speech;

import com.meetingintelligence.domain.speech.FailureReason;
import com.meetingintelligence.domain.speech.SttRequest;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static com.meetingintelligence.speech.WavFixtures.wav;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SttSupportTest {

    @Test
    @DisplayName("WAV: duração calculada pelo cabeçalho; arquivo inválido devolve -1")
    void wavDuration() {
        assertThat(WavAudioInspector.durationMs(wav(1500))).isEqualTo(1500);
        assertThat(WavAudioInspector.durationMs(wav(0))).isZero();
        assertThat(WavAudioInspector.durationMs("not a wav at all, definitely not RIFF data....".getBytes())).isEqualTo(-1);
        assertThat(WavAudioInspector.durationMs(new byte[10])).isEqualTo(-1);
    }

    @Test
    @DisplayName("STT local: saída do Python mapeada para sem fala, erro ou texto")
    void localGoogleSttInterpretation() {
        assertThat(LocalGoogleSttProvider.interpret("__NO_SPEECH__")).isInstanceOf(TranscriptionOutcome.NoSpeech.class);
        var err = (TranscriptionOutcome.Failed) LocalGoogleSttProvider.interpret("__ERROR__RequestError");
        assertThat(err.reason()).isEqualTo(FailureReason.NETWORK);
        assertThat(LocalGoogleSttProvider.interpret("")).isInstanceOf(TranscriptionOutcome.Failed.class);
        var ok = (TranscriptionOutcome.Transcribed) LocalGoogleSttProvider.interpret("explique o conceito de solid");
        assertThat(ok.segments().get(0).getText()).isEqualTo("explique o conceito de solid");
    }

    @Test
    @DisplayName("Transcribed sem segmentos é rejeitado: lista vazia nunca representa sucesso")
    void transcribedRequiresSegments() {
        assertThatThrownBy(() -> new TranscriptionOutcome.Transcribed("X", java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RNF-01: cliente HTTP do STT respeita o timeout de leitura configurado (servidor lento real)")
    void httpClientHonoursReadTimeout() throws Exception {
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/", exchange -> {
            try {
                Thread.sleep(2000);
                exchange.sendResponseHeaders(200, -1);
            } catch (Exception ignored) {
                // conexão encerrada pelo cliente
            } finally {
                exchange.close();
            }
        });
        slow.start();
        try {
            SttHttpClients clients = new SttHttpClients(500, 300, 300);
            long start = System.currentTimeMillis();
            Exception error = null;
            try {
                clients.forMode(SttRequest.Mode.LIVE)
                        .getForObject("http://127.0.0.1:" + slow.getAddress().getPort() + "/", String.class);
            } catch (Exception e) {
                error = e;
            }
            long elapsed = System.currentTimeMillis() - start;

            assertThat(error).isNotNull();
            assertThat(SttFailures.from("X", error).reason()).isEqualTo(FailureReason.TIMEOUT);
            assertThat(elapsed).isLessThan(1500);
        } finally {
            slow.stop(0);
        }
    }
}
