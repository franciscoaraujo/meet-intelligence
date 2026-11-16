package com.meetingintelligence.infrastructure.speech;

import com.meetingintelligence.domain.speech.SttRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Clientes HTTP dos provedores de STT com timeouts explícitos (antes: new RestTemplate() sem timeout).
 * LIVE usa leitura curta (trechos de poucos segundos); BATCH, leitura longa (arquivos enviados).
 */
@Component
public class SttHttpClients {

    private final RestTemplate live;
    private final RestTemplate batch;

    public SttHttpClients(
            @Value("${app.speech.timeout.connect-ms:3000}") int connectMs,
            @Value("${app.speech.timeout.live-read-ms:15000}") int liveReadMs,
            @Value("${app.speech.timeout.batch-read-ms:120000}") int batchReadMs) {
        this.live = build(connectMs, liveReadMs);
        this.batch = build(connectMs, batchReadMs);
    }

    private static RestTemplate build(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return new RestTemplate(factory);
    }

    public RestTemplate forMode(SttRequest.Mode mode) {
        return mode == SttRequest.Mode.BATCH ? batch : live;
    }
}
