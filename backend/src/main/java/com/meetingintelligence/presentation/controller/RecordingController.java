package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.service.RecordingService;
import com.meetingintelligence.domain.entity.Recording;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/recordings")
@RequiredArgsConstructor
@Tag(name = "Gravações", description = "Upload e gerenciamento de arquivos de áudio gravados")
public class RecordingController {

    private final RecordingService recordingService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload de gravação de áudio/vídeo", description = "Recebe arquivo de mídia (Mecanismo D), salva no S3 e inicia transcrição")
    public ResponseEntity<Recording> uploadRecording(
            @PathVariable UUID meetingId,
            @RequestParam("file") MultipartFile file) {
        Recording recording = recordingService.uploadAndProcessRecording(meetingId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(recording);
    }

    @GetMapping
    @Operation(summary = "Listar gravações da reunião", description = "Retorna todas as gravações vinculadas à reunião")
    public ResponseEntity<List<Recording>> getRecordings(@PathVariable UUID meetingId) {
        List<Recording> recordings = recordingService.getRecordingsByMeeting(meetingId);
        return ResponseEntity.ok(recordings);
    }

    @GetMapping("/{recordingId}/playback-url")
    @Operation(summary = "Obter URL de reprodução", description = "Gera uma Presigned URL temporária para audição do áudio")
    public ResponseEntity<Map<String, String>> getPlaybackUrl(
            @PathVariable UUID meetingId,
            @PathVariable UUID recordingId) {
        String url = recordingService.getPlaybackUrl(recordingId);
        return ResponseEntity.ok(Map.of("playbackUrl", url));
    }
}

