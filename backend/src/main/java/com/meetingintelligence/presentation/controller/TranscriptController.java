package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.AudioChunkCommand;
import com.meetingintelligence.application.dto.ChunkTranscriptionResponse;
import com.meetingintelligence.application.service.TranscriptionService;
import com.meetingintelligence.domain.speech.AudioSource;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/transcripts")
@RequiredArgsConstructor
@Tag(name = "Transcrições", description = "Endpoints de transcrição, streaming e correção de falas")
public class TranscriptController {

    private final TranscriptionService transcriptionService;

    @GetMapping
    @Operation(summary = "Obter transcrições da reunião", description = "Lista todos os segmentos transcritos com falantes e timestamps")
    public ResponseEntity<List<TranscriptSegment>> getTranscripts(@PathVariable UUID meetingId) {
        List<TranscriptSegment> segments = transcriptionService.getTranscriptsByMeeting(meetingId);
        return ResponseEntity.ok(segments);
    }

    /** Limite do áudio em base64 (~9 MB de áudio), bem acima de um trecho de 12 s em WAV 16 kHz (~384 KB). */
    static final int MAX_BASE64_CHARS = 12 * 1024 * 1024;

    @PostMapping("/chunk")
    @Operation(summary = "Enviar trecho de áudio ao vivo",
            description = "Transcreve um trecho de áudio (aba do Meet ou microfone) pela cadeia de provedores. "
                    + "Responde TRANSCRIBED, NO_SPEECH ou FAILED; reenvios com o mesmo clientSegmentId são idempotentes.")
    public ResponseEntity<ChunkTranscriptionResponse> processAudioChunk(
            @PathVariable UUID meetingId,
            @RequestBody AudioChunkRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        if (request.getBase64Audio() == null || request.getBase64Audio().isBlank()) {
            throw new IllegalArgumentException("base64Audio é obrigatório.");
        }
        if (request.getBase64Audio().length() > MAX_BASE64_CHARS) {
            throw new IllegalArgumentException("Trecho de áudio excede o tamanho máximo.");
        }
        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(request.getBase64Audio());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("base64Audio inválido.");
        }

        AudioChunkCommand command = new AudioChunkCommand(
                meetingId,
                audioBytes,
                request.getMimeType(),
                request.getClientSegmentId(),
                request.getAudioSource(),
                request.getCaptureStartMs(),
                request.getCaptureEndMs(),
                request.getSpeakerLabel());
        return ResponseEntity.ok(transcriptionService.processAudioChunk(command, currentUser.getOrganizationId()));
    }

    @PostMapping("/live-text")
    @Operation(summary = "Registrar fala capturada ao vivo no navegador", description = "Recebe fala transcrita em tempo real (Web Speech API / Microfone) e aciona o pipeline de IA")
    public ResponseEntity<TranscriptSegment> processLiveText(
            @PathVariable UUID meetingId,
            @RequestBody LiveTextRequest request) {
        if (request.getText() == null || request.getText().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        TranscriptSegment segment = transcriptionService.processLiveTextSegment(
                meetingId,
                request.getSpeakerLabel(),
                request.getText(),
                request.getStartTimeMs(),
                request.getEndTimeMs()
        );
        return ResponseEntity.ok(segment);
    }

    @PatchMapping("/{segmentId}")
    @Operation(summary = "Corrigir texto da transcrição", description = "Permite ajuste manual de palavras incorretas preservando o original")
    public ResponseEntity<TranscriptSegment> correctSegment(
            @PathVariable UUID meetingId,
            @PathVariable UUID segmentId,
            @RequestBody Map<String, String> body) {
        String newText = body.get("correctedText");
        TranscriptSegment segment = transcriptionService.correctSegment(segmentId, newText);
        return ResponseEntity.ok(segment);
    }

    @Data
    public static class AudioChunkRequest {
        private String base64Audio;
        private String mimeType = "audio/webm";
        /** Opcionais (spec 002/001): idempotência, origem e horário de captura. */
        private UUID clientSegmentId;
        private AudioSource audioSource;
        private Long captureStartMs;
        private Long captureEndMs;
        private String speakerLabel;
    }

    @Data
    public static class LiveTextRequest {
        private String speakerLabel;
        private String text;
        private long startTimeMs;
        private long endTimeMs;
    }
}

