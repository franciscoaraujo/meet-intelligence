package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.service.TranscriptionService;
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

    @PostMapping("/chunk")
    @Operation(summary = "Enviar chunk de áudio ao vivo", description = "Recebe bloco de áudio gravado no navegador para transcrição imediata")
    public ResponseEntity<List<TranscriptSegment>> processAudioChunk(
            @PathVariable UUID meetingId,
            @RequestBody AudioChunkRequest request) {

        byte[] audioBytes = Base64.getDecoder().decode(request.getBase64Audio());
        List<TranscriptSegment> segments = transcriptionService.processAudio(
                meetingId,
                audioBytes,
                request.getMimeType(),
                true
        );
        return ResponseEntity.ok(segments);
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
    }

    @Data
    public static class LiveTextRequest {
        private String speakerLabel;
        private String text;
        private long startTimeMs;
        private long endTimeMs;
    }
}

