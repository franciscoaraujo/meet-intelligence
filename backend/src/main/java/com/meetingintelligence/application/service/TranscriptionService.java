package com.meetingintelligence.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.MeetingParticipant;
import com.meetingintelligence.domain.entity.OrganizationAiConfig;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.domain.speech.TranscriptionResult;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingParticipantRepository;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationAiConfigRepository;
import com.meetingintelligence.infrastructure.persistence.repository.TranscriptSegmentRepository;
import com.meetingintelligence.infrastructure.speech.SpeechToTextRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptionService {

    private final MeetingRepository meetingRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final MeetingParticipantRepository participantRepository;
    private final OrganizationAiConfigRepository aiConfigRepository;
    private final SpeechToTextRouter speechToTextRouter;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final com.meetingintelligence.application.service.ai.TranscriptChunkAggregatorService chunkAggregatorService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Transactional
    public List<TranscriptSegment> processAudio(UUID meetingId, byte[] audioBytes, String mimeType, boolean isLiveStream) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Reunião não encontrada: " + meetingId));

        log.info("Processando áudio para transcrição da reunião '{}' (Tamanho: {} bytes, Live={})",
                meeting.getTitle(), audioBytes.length, isLiveStream);

        // Se for captura ao vivo da Guia do Google Meet (isLiveStream), tenta primeiro transcrever com a chave Gemini ou OpenAI configurada pela organização
        if (isLiveStream) {
            List<TranscriptSegment> liveSegments = transcribeLiveTabAudioWithOrgAi(meeting, audioBytes, mimeType);
            if (liveSegments != null) {
                return liveSegments;
            }
        }

        TranscriptionResult result = speechToTextRouter.transcribeAudio(
                audioBytes,
                mimeType,
                meeting.getLanguage(),
                isLiveStream
        );

        List<MeetingParticipant> existingParticipants = participantRepository.findByMeetingId(meetingId);
        List<TranscriptSegment> savedSegments = new ArrayList<>();

        for (TranscriptionSegmentDto dto : result.getSegments()) {
            // Localiza ou mapeia participante por tag de falante
            MeetingParticipant participant = findOrCreateParticipant(meeting, dto, existingParticipants);

            TranscriptSegment segment = TranscriptSegment.builder()
                    .meeting(meeting)
                    .participant(participant)
                    .speakerLabel(dto.getSpeakerLabel() != null ? dto.getSpeakerLabel() : "Participante")
                    .originalText(dto.getText())
                    .startTimeMs(dto.getStartTimeMs())
                    .endTimeMs(dto.getEndTimeMs())
                    .confidence(dto.getConfidence())
                    .language(dto.getLanguage() != null ? dto.getLanguage() : meeting.getLanguage())
                    .source(result.getProvider())
                    .build();

            TranscriptSegment saved = segmentRepository.save(segment);
            savedSegments.add(saved);

            // Envia para o agregador de blocos semânticos para análise automática de IA
            try {
                chunkAggregatorService.onNewSegment(meeting, saved);
            } catch (Exception e) {
                log.warn("Erro ao alimentar agregador semântico de IA: {}", e.getMessage());
            }

            // Notifica o frontend em tempo real via WebSocket
            try {
                messagingTemplate.convertAndSend("/topic/meetings/" + meetingId + "/transcripts", saved);
            } catch (Exception e) {
                log.warn("Erro ao emitir WebSocket para transcrição: {}", e.getMessage());
            }
        }

        return savedSegments;
    }

    private List<TranscriptSegment> transcribeLiveTabAudioWithOrgAi(Meeting meeting, byte[] audioBytes, String mimeType) {
        if (audioBytes == null || audioBytes.length < 1500) {
            return Collections.emptyList();
        }
        Optional<OrganizationAiConfig> cfgOpt = aiConfigRepository.findByOrganizationId(meeting.getOrganization().getId());
        OrganizationAiConfig cfg = cfgOpt.orElse(null);
        String transcribedText = null;

        // 1. Tenta com Google Gemini Multimodal Audio (se a chave Gemini estiver configurada)
        if (cfg != null && cfg.getGeminiApiKey() != null && !cfg.getGeminiApiKey().isBlank() && !cfg.getGeminiApiKey().startsWith("wrkspc_")) {
            try {
                String model = (cfg.getGeminiModel() != null && !cfg.getGeminiModel().isBlank())
                        ? cfg.getGeminiModel()
                        : "gemini-1.5-flash";
                String url = String.format(
                        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                        model, cfg.getGeminiApiKey().trim()
                );

                String base64Audio = Base64.getEncoder().encodeToString(audioBytes);
                String cleanMime = (mimeType != null && mimeType.contains(";"))
                        ? mimeType.split(";")[0].trim()
                        : (mimeType != null ? mimeType : "audio/webm");

                Map<String, Object> inlineData = Map.of(
                        "mime_type", cleanMime,
                        "data", base64Audio
                );
                Map<String, Object> promptPart = Map.of(
                        "text", "Transcreva fielmente em português do Brasil apenas o que foi falado neste trecho de áudio de reunião. Não adicione comentários nem explicações. Se houver apenas silêncio ou ruído sem fala inteligível, responda exatamente VAZIO."
                );
                Map<String, Object> body = Map.of(
                        "contents", List.of(Map.of("parts", List.of(inlineData, promptPart))),
                        "generationConfig", Map.of("temperature", 0.0)
                );

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                ResponseEntity<String> res = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
                if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                    JsonNode root = objectMapper.readTree(res.getBody());
                    JsonNode candidates = root.path("candidates");
                    if (candidates.isArray() && !candidates.isEmpty()) {
                        String text = candidates.get(0).path("content").path("parts").get(0).path("text").asText("").trim();
                        if (!text.isBlank() && !text.equalsIgnoreCase("VAZIO") && !text.contains("VAZIO")) {
                            transcribedText = text;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Falha ao transcrever chunk de áudio da guia via Gemini: {}", e.getMessage());
            }
        }

        // 2. Fallback para OpenAI Whisper (se a chave OpenAI estiver configurada)
        if (transcribedText == null && cfg != null && cfg.getOpenAiApiKey() != null && !cfg.getOpenAiApiKey().isBlank()) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(cfg.getOpenAiApiKey().trim());
                headers.setContentType(MediaType.MULTIPART_FORM_DATA);

                MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                body.add("model", "whisper-1");
                body.add("language", "pt");
                ByteArrayResource resource = new ByteArrayResource(audioBytes) {
                    @Override
                    public String getFilename() {
                        return (mimeType != null && mimeType.contains("wav")) ? "tab-audio.wav" : "tab-audio.webm";
                    }
                };
                body.add("file", resource);

                ResponseEntity<String> res = restTemplate.postForEntity(
                        "https://api.openai.com/v1/audio/transcriptions",
                        new HttpEntity<>(body, headers),
                        String.class
                );
                if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                    JsonNode root = objectMapper.readTree(res.getBody());
                    String text = root.path("text").asText("").trim();
                    if (!text.isBlank()) {
                        transcribedText = text;
                    }
                }
            } catch (Exception e) {
                log.warn("Falha ao transcrever chunk de áudio da guia via Whisper: {}", e.getMessage());
            }
        }

        // 3. Fallback nativo gratuito (PCM WAV da Guia do Google Meet via Google Speech Recognition sem necessidade de chave de API)
        boolean isWav = (mimeType != null && mimeType.toLowerCase().contains("wav"))
                || (audioBytes.length > 12 && audioBytes[0] == 'R' && audioBytes[1] == 'I' && audioBytes[2] == 'F' && audioBytes[3] == 'F');
        if (transcribedText == null && isWav) {
            transcribedText = transcribeWavViaLocalGoogleStt(audioBytes);
        }

        if (transcribedText != null && !transcribedText.isBlank()) {
            List<TranscriptSegment> existing = segmentRepository.findByMeetingIdOrderByStartTimeMsAsc(meeting.getId());
            String normalizedNew = transcribedText.trim().toLowerCase();
            int checkStart = Math.max(0, existing.size() - 3);
            for (int i = checkStart; i < existing.size(); i++) {
                String prevText = existing.get(i).getOriginalText();
                if (prevText != null && prevText.trim().equalsIgnoreCase(normalizedNew)) {
                    return Collections.emptyList();
                }
            }

            long nowMs = existing.size() * 5000L;
            log.info("Áudio do entrevistador (Guia Google Meet) transcrito com sucesso: '{}'", transcribedText);
            TranscriptSegment saved = processLiveTextSegment(
                    meeting.getId(),
                    "Entrevistador (Google Meet)",
                    transcribedText,
                    nowMs,
                    nowMs + 5000L
            );
            return List.of(saved);
        }

        return Collections.emptyList();
    }

    private String transcribeWavViaLocalGoogleStt(byte[] wavBytes) {
        java.nio.file.Path tempWav = null;
        try {
            tempWav = java.nio.file.Files.createTempFile("meet_tab_audio_", ".wav");
            java.nio.file.Files.write(tempWav, wavBytes);

            String pyScript = "import sys, speech_recognition as sr\n"
                    + "sys.stdout.reconfigure(encoding='utf-8')\n"
                    + "r = sr.Recognizer()\n"
                    + "with sr.AudioFile(sys.argv[1]) as source:\n"
                    + "    audio = r.record(source)\n"
                    + "try:\n"
                    + "    print(r.recognize_google(audio, language='pt-BR'))\n"
                    + "except Exception:\n"
                    + "    pass\n";

            ProcessBuilder pb = new ProcessBuilder("python", "-c", pyScript, tempWav.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return null;
            }
            String output = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!output.isBlank() && !output.contains("Traceback")) {
                return output;
            }
        } catch (Exception e) {
            log.warn("Erro na transcrição WAV da guia: {}", e.getMessage());
        } finally {
            if (tempWav != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tempWav);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    @Transactional
    public TranscriptSegment processLiveTextSegment(UUID meetingId, String speakerLabel, String text, long startTimeMs, long endTimeMs) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Reunião não encontrada: " + meetingId));

        String resolvedSpeaker = (speakerLabel != null && !speakerLabel.isBlank()) ? speakerLabel : "Participante (Voz ao Vivo)";
        String cleanText = text != null ? text.trim() : "";

        // Verifica se este trecho é continuação de uma fala imediatamente anterior do mesmo locutor (pausa curta na fala)
        List<TranscriptSegment> existingSegments = segmentRepository.findByMeetingIdOrderByStartTimeMsAsc(meetingId);
        if (!existingSegments.isEmpty() && !cleanText.isEmpty()) {
            TranscriptSegment last = existingSegments.get(existingSegments.size() - 1);
            String lastText = last.getOriginalText() != null ? last.getOriginalText().trim() : "";
            boolean sameSpeaker = Objects.equals(last.getSpeakerLabel(), resolvedSpeaker);
            long timeGapMs = Math.abs(startTimeMs - last.getStartTimeMs());

            if (sameSpeaker && shouldMergeWithPreviousSegment(lastText, cleanText, timeGapMs)) {
                String mergedText = mergeSpeechFragments(lastText, cleanText);
                last.setOriginalText(mergedText);
                last.setEndTimeMs(Math.max(last.getEndTimeMs(), endTimeMs > startTimeMs ? endTimeMs : startTimeMs + 3000L));
                TranscriptSegment updated = segmentRepository.save(last);

                try {
                    chunkAggregatorService.onNewSegment(meeting, updated);
                } catch (Exception e) {
                    log.warn("Erro ao alimentar agregador semântico de IA após fusão de segmento: {}", e.getMessage());
                }

                try {
                    messagingTemplate.convertAndSend("/topic/meetings/" + meetingId + "/transcripts", updated);
                } catch (Exception e) {
                    log.warn("Erro ao emitir WebSocket para transcrição atualizada: {}", e.getMessage());
                }
                return updated;
            }
        }

        List<MeetingParticipant> existingParticipants = participantRepository.findByMeetingId(meetingId);
        MeetingParticipant participant = existingParticipants.isEmpty() ? null : existingParticipants.get(0);

        TranscriptSegment segment = TranscriptSegment.builder()
                .meeting(meeting)
                .participant(participant)
                .speakerLabel(resolvedSpeaker)
                .originalText(cleanText)
                .startTimeMs(startTimeMs)
                .endTimeMs(endTimeMs > startTimeMs ? endTimeMs : startTimeMs + 3000L)
                .confidence(java.math.BigDecimal.valueOf(0.96))
                .language(meeting.getLanguage() != null ? meeting.getLanguage() : "pt-BR")
                .source("WEB_SPEECH_LIVE")
                .build();

        TranscriptSegment saved = segmentRepository.save(segment);

        // Alimenta o agregador semântico que dispara a IA (Insights, Resumo e pgvector)
        try {
            chunkAggregatorService.onNewSegment(meeting, saved);
        } catch (Exception e) {
            log.warn("Erro ao alimentar agregador semântico de IA: {}", e.getMessage());
        }

        // Notifica o frontend em tempo real via WebSocket STOMP
        try {
            messagingTemplate.convertAndSend("/topic/meetings/" + meetingId + "/transcripts", saved);
        } catch (Exception e) {
            log.warn("Erro ao emitir WebSocket para transcrição ao vivo: {}", e.getMessage());
        }

        return saved;
    }

    private boolean shouldMergeWithPreviousSegment(String prevText, String newText, long timeGapMs) {
        if (prevText.isEmpty() || newText.isEmpty()) return false;
        String prevLower = prevText.toLowerCase();
        String newLower = newText.toLowerCase();

        // Se o novo trecho já contém o texto anterior inteiro (ou compartilha o mesmo início longo de pergunta), substitui/funde
        if (newLower.startsWith(prevLower) || prevLower.startsWith(newLower)) {
            return true;
        }
        int minPrefix = Math.min(20, Math.min(prevLower.length(), newLower.length()));
        if (minPrefix >= 15 && prevLower.substring(0, minPrefix).equals(newLower.substring(0, minPrefix)) && timeGapMs <= 18000L) {
            return true;
        }

        // Se o novo trecho começa com preposição/conjunção ou tem 1-3 palavras de complemento (ex: "de ordenação", "ordenação", "em micro serviços")
        boolean startsWithContinuation = newLower.startsWith("de ")
                || newLower.startsWith("da ")
                || newLower.startsWith("do ")
                || newLower.startsWith("dos ")
                || newLower.startsWith("das ")
                || newLower.startsWith("e ")
                || newLower.startsWith("ou ")
                || newLower.startsWith("em ")
                || newLower.startsWith("no ")
                || newLower.startsWith("na ")
                || newLower.startsWith("nos ")
                || newLower.startsWith("nas ")
                || newLower.startsWith("para ")
                || newLower.startsWith("por ")
                || newLower.startsWith("com ")
                || newLower.startsWith("que ")
                || newLower.startsWith("entre ")
                || newLower.startsWith("sobre ")
                || newLower.startsWith("baseada ");

        boolean prevEndsIncomplete = prevLower.endsWith(" de")
                || prevLower.endsWith(" da")
                || prevLower.endsWith(" do")
                || prevLower.endsWith(" dos")
                || prevLower.endsWith(" das")
                || prevLower.endsWith(" e")
                || prevLower.endsWith(" ou")
                || prevLower.endsWith(" em")
                || prevLower.endsWith(" para")
                || prevLower.endsWith(" com")
                || prevLower.endsWith(" por")
                || prevLower.endsWith(" que")
                || prevLower.endsWith(" entre")
                || prevLower.endsWith(" sobre")
                || prevLower.endsWith(" algoritmos")
                || prevLower.endsWith(" arquitetura")
                || prevLower.endsWith(" conceito");

        int newWordCount = newText.split("\\s+").length;
        int prevWordCount = prevText.split("\\s+").length;

        if ((startsWithContinuation || prevEndsIncomplete || newWordCount <= 3) && timeGapMs <= 12000L) {
            return true;
        }

        // Se chegou em menos de 6 segundos e a frase anterior era curta e não tinha pontuação final
        return timeGapMs <= 6000L && prevWordCount <= 9 && !prevText.endsWith("?") && !prevText.endsWith(".");
    }

    private String mergeSpeechFragments(String prevText, String newText) {
        String prevLower = prevText.toLowerCase();
        String newLower = newText.toLowerCase();
        if (newLower.startsWith(prevLower)) {
            return newText;
        }
        if (prevLower.startsWith(newLower)) {
            return prevText;
        }
        int minPrefix = Math.min(20, Math.min(prevLower.length(), newLower.length()));
        if (minPrefix >= 15 && prevLower.substring(0, minPrefix).equals(newLower.substring(0, minPrefix))) {
            return newText.length() >= prevText.length() ? newText : prevText;
        }
        return prevText + " " + newText;
    }

    @Transactional(readOnly = true)
    public List<TranscriptSegment> getTranscriptsByMeeting(UUID meetingId) {
        return segmentRepository.findByMeetingIdOrderByStartTimeMsAsc(meetingId);
    }

    @Transactional
    public TranscriptSegment correctSegment(UUID segmentId, String newText) {
        TranscriptSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("Segmento de transcrição não encontrado: " + segmentId));

        // Preserva o texto original e grava a correção
        segment.setCorrectedText(newText);
        return segmentRepository.save(segment);
    }

    private MeetingParticipant findOrCreateParticipant(
            Meeting meeting,
            TranscriptionSegmentDto dto,
            List<MeetingParticipant> participants) {

        if (dto.getSpeakerTag() == null) {
            return participants.isEmpty() ? null : participants.get(0);
        }

        for (MeetingParticipant p : participants) {
            if (dto.getSpeakerTag().equals(p.getSpeakerTag())) {
                return p;
            }
        }

        // Se for um novo falante detectado pela diarização, cria participante
        MeetingParticipant newParticipant = participantRepository.save(MeetingParticipant.builder()
                .meeting(meeting)
                .name(dto.getSpeakerLabel() != null ? dto.getSpeakerLabel() : "Participante " + dto.getSpeakerTag())
                .speakerTag(dto.getSpeakerTag())
                .role("PARTICIPANT")
                .build());

        participants.add(newParticipant);
        return newParticipant;
    }
}

