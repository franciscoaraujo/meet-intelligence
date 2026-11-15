package com.meetingintelligence.application.dto;

import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.domain.speech.TranscriptionChainResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Resposta do POST /transcripts/chunk (spec 002).
 * status: TRANSCRIBED | NO_SPEECH | FAILED | PROCESSING (mesmo trecho ainda em processamento por outra requisição).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkTranscriptionResponse {

    public static final String PROCESSING = "PROCESSING";

    private String status;
    private String provider;
    @Builder.Default
    private List<TranscriptSegment> segments = List.of();
    @Builder.Default
    private List<TranscriptionChainResult.Attempt> attempts = List.of();
    /** Motivo da falha final (ex.: NO_PROVIDER_CONFIGURED, TIMEOUT, HTTP_5XX); null se não falhou. */
    private String failureReason;
    /** true quando o trecho (ou o mesmo texto) já tinha sido recebido e nada novo foi gravado. */
    private boolean duplicate;
}
