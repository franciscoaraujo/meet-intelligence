package com.meetingintelligence.domain.speech;

import java.util.List;

/**
 * Resultado da cadeia de provedores de STT.
 *
 * @param status   TRANSCRIBED, NO_SPEECH ou FAILED
 * @param provider provedor que produziu o resultado final (null em FAILED)
 * @param segments segmentos transcritos (vazio fora de TRANSCRIBED)
 * @param attempts todas as tentativas, na ordem, para diagnóstico (sem conteúdo transcrito)
 */
public record TranscriptionChainResult(Status status, String provider, List<TranscriptionSegmentDto> segments,
                                       List<Attempt> attempts) {

    public enum Status { TRANSCRIBED, NO_SPEECH, FAILED }

    public record Attempt(String provider, String outcome, FailureReason reason, String detail, long latencyMs) {}

    public TranscriptionChainResult {
        segments = segments == null ? List.of() : List.copyOf(segments);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    /** Motivo da falha final (o da última tentativa), ou null se não falhou. */
    public FailureReason failureReason() {
        if (status != Status.FAILED || attempts.isEmpty()) return null;
        return attempts.get(attempts.size() - 1).reason();
    }
}
