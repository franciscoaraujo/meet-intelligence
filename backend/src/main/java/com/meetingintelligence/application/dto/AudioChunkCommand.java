package com.meetingintelligence.application.dto;

import com.meetingintelligence.domain.speech.AudioSource;

import java.util.UUID;

/**
 * Trecho de áudio ao vivo para transcrição.
 *
 * @param clientSegmentId id gerado no cliente; reenvios com o mesmo id são idempotentes (opcional na transição)
 * @param audioSource     TAB ou MIC (opcional; padrão TAB)
 * @param captureStartMs  início no relógio da sessão de captura (opcional)
 * @param captureEndMs    fim no relógio da sessão de captura (opcional)
 * @param speakerLabel    rótulo sugerido pelo cliente para áudio do microfone (opcional)
 */
public record AudioChunkCommand(UUID meetingId, byte[] audio, String mimeType, UUID clientSegmentId,
                                AudioSource audioSource, Long captureStartMs, Long captureEndMs,
                                String speakerLabel) {}
