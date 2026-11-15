package com.meetingintelligence.domain.speech;

/** Motivo de falha de transcrição e se ele justifica uma nova tentativa no mesmo provedor. */
public enum FailureReason {
    TIMEOUT(true),
    HTTP_429(true),
    HTTP_5XX(true),
    NETWORK(true),
    HTTP_4XX(false),
    PARSE_ERROR(false),
    /** O áudio não pode ser decodificado: os demais provedores também falhariam. */
    INVALID_AUDIO(false),
    /** Orçamento de tempo total da cadeia esgotado. */
    TIMEOUT_BUDGET(false),
    /** Nenhum provedor disponível (sem chave configurada). */
    NO_PROVIDER_CONFIGURED(false),
    UNEXPECTED(false);

    private final boolean retryable;

    FailureReason(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
