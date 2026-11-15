package com.meetingintelligence.infrastructure.speech;

import com.meetingintelligence.domain.speech.FailureReason;
import com.meetingintelligence.domain.speech.TranscriptionOutcome;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

/** Converte exceções de chamadas HTTP em {@link TranscriptionOutcome.Failed}, sem expor conteúdo da resposta. */
final class SttFailures {

    private SttFailures() {}

    static TranscriptionOutcome.Failed from(String provider, Exception e) {
        if (e instanceof HttpStatusCodeException http) {
            int status = http.getStatusCode().value();
            FailureReason reason = status == 429 ? FailureReason.HTTP_429
                    : status >= 500 ? FailureReason.HTTP_5XX
                    : FailureReason.HTTP_4XX;
            return new TranscriptionOutcome.Failed(provider, reason, "HTTP " + status);
        }
        if (e instanceof ResourceAccessException rae) {
            boolean timeout = rae.getCause() instanceof SocketTimeoutException
                    || (rae.getMessage() != null && rae.getMessage().toLowerCase().contains("timed out"));
            return new TranscriptionOutcome.Failed(provider, timeout ? FailureReason.TIMEOUT : FailureReason.NETWORK,
                    rae.getCause() != null ? rae.getCause().getClass().getSimpleName() : "I/O");
        }
        if (e instanceof com.fasterxml.jackson.core.JsonProcessingException) {
            return new TranscriptionOutcome.Failed(provider, FailureReason.PARSE_ERROR, e.getClass().getSimpleName());
        }
        return new TranscriptionOutcome.Failed(provider, FailureReason.UNEXPECTED, e.getClass().getSimpleName());
    }
}
