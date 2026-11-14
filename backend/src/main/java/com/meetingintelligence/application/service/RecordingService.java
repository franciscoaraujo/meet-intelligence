package com.meetingintelligence.application.service;

import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.Recording;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.persistence.repository.RecordingRepository;
import com.meetingintelligence.infrastructure.storage.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingService {

    private final MeetingRepository meetingRepository;
    private final RecordingRepository recordingRepository;
    private final S3StorageService s3StorageService;
    private final TranscriptionService transcriptionService;

    @Transactional
    public Recording uploadAndProcessRecording(UUID meetingId, MultipartFile file) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Reunião não encontrada: " + meetingId));

        log.info("Recebendo upload de gravação para a reunião {}: {} (Tamanho: {} bytes)",
                meetingId, file.getOriginalFilename(), file.getSize());

        // 1. Armazena no MinIO / S3
        String storagePath = s3StorageService.uploadFile(meetingId, file);

        // 2. Persiste registro da gravação
        Recording recording = Recording.builder()
                .meeting(meeting)
                .storagePath(storagePath)
                .mimeType(file.getContentType() != null ? file.getContentType() : "audio/webm")
                .fileSizeBytes(file.getSize())
                .status("PROCESSING")
                .build();
        Recording saved = recordingRepository.save(recording);

        // 3. Processa transcrição do áudio
        try {
            byte[] bytes = file.getBytes();
            transcriptionService.processAudio(meetingId, bytes, file.getContentType(), false);
            saved.setStatus("READY");
            recordingRepository.save(saved);
        } catch (Exception e) {
            log.error("Erro ao transcrever arquivo de gravação: {}", e.getMessage(), e);
            saved.setStatus("ERROR");
            recordingRepository.save(saved);
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public List<Recording> getRecordingsByMeeting(UUID meetingId) {
        return recordingRepository.findByMeetingId(meetingId);
    }

    public String getPlaybackUrl(UUID recordingId) {
        Recording recording = recordingRepository.findById(recordingId)
                .orElseThrow(() -> new IllegalArgumentException("Gravação não encontrada: " + recordingId));
        return s3StorageService.generatePresignedUrl(recording.getStoragePath());
    }
}

