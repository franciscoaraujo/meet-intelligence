package com.meetingintelligence.application.service;

import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.MeetingParticipant;
import com.meetingintelligence.domain.entity.User;
import com.meetingintelligence.domain.speech.AudioSource;
import com.meetingintelligence.domain.speech.SpeakerRole;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingParticipantRepository;
import com.meetingintelligence.infrastructure.persistence.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Decide quem falou num segmento a partir de evidência (spec 003), nunca por atividade de outra fonte:
 * <ul>
 *   <li>MIC → o próprio usuário (SELF), vinculado ao participante dele;</li>
 *   <li>TAB → participante remoto (REMOTE), sem participante específico; a diarização por trecho
 *       ao vivo não é usada como identidade, pois o "falante 0" de um trecho não é o mesmo do seguinte;</li>
 *   <li>UPLOAD → "Falante N" quando o provedor diariza o arquivo inteiro; senão desconhecido;</li>
 *   <li>MANUAL → o rótulo escolhido pelo usuário (confiança 1,0) ou desconhecido.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpeakerAttributionService {

    public static final String REMOTE_LABEL = "Participante remoto (Meet)";
    public static final String UNKNOWN_LABEL = "Desconhecido";
    public static final String SELF_FALLBACK_LABEL = "Você";
    static final double MIN_DIARIZATION_CONFIDENCE = 0.5;

    private final MeetingParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final MeterRegistry meters;

    /** Resultado da atribuição. {@code participant} é nulo quando não há evidência de quem é. */
    public record SpeakerAttribution(SpeakerRole role, String label, BigDecimal confidence, MeetingParticipant participant) {}

    public SpeakerAttribution forLiveAudio(Meeting meeting, AudioSource source, UUID userId) {
        SpeakerAttribution result = source == AudioSource.MIC
                ? self(meeting, userId, BigDecimal.valueOf(0.9))
                : new SpeakerAttribution(SpeakerRole.REMOTE, REMOTE_LABEL, BigDecimal.valueOf(0.8), null);
        return count(source, result);
    }

    /** Segmento de gravação enviada: usa a diarização do provedor, que cobre o arquivo inteiro. */
    public SpeakerAttribution forUpload(Meeting meeting, TranscriptionSegmentDto dto) {
        Integer tag = dto.getSpeakerTag();
        double confidence = dto.getConfidence() != null ? dto.getConfidence().doubleValue() : 0;
        if (tag == null || confidence < MIN_DIARIZATION_CONFIDENCE) {
            return count(AudioSource.UPLOAD, new SpeakerAttribution(SpeakerRole.UNKNOWN, UNKNOWN_LABEL, BigDecimal.ZERO, null));
        }
        String label = "Falante " + tag;
        MeetingParticipant participant = participantRepository.findByMeetingId(meeting.getId()).stream()
                .filter(p -> tag.equals(p.getSpeakerTag()))
                .findFirst()
                .orElseGet(() -> participantRepository.save(MeetingParticipant.builder()
                        .meeting(meeting).name(label).speakerTag(tag).role("PARTICIPANT").build()));
        return count(AudioSource.UPLOAD, new SpeakerAttribution(SpeakerRole.UNKNOWN, label,
                BigDecimal.valueOf(confidence).setScale(3, java.math.RoundingMode.HALF_UP), participant));
    }

    /**
     * Texto vindo do navegador: Web Speech (audioSource=MIC) ou digitado (MANUAL) com o falante
     * escolhido pelo usuário.
     */
    public SpeakerAttribution forBrowserText(Meeting meeting, AudioSource source, String chosenLabel,
                                             SpeakerRole chosenRole, UUID userId) {
        if (source == AudioSource.MIC) {
            return count(source, self(meeting, userId, BigDecimal.valueOf(0.9)));
        }
        if (chosenLabel == null || chosenLabel.isBlank()) {
            return count(AudioSource.MANUAL, new SpeakerAttribution(SpeakerRole.UNKNOWN, UNKNOWN_LABEL, BigDecimal.ZERO, null));
        }
        SpeakerRole role = chosenRole != null ? chosenRole : SpeakerRole.UNKNOWN;
        MeetingParticipant participant = role == SpeakerRole.SELF ? selfParticipant(meeting, userId) : null;
        return count(AudioSource.MANUAL, new SpeakerAttribution(role, chosenLabel.trim(), BigDecimal.ONE, participant));
    }

    private SpeakerAttribution self(Meeting meeting, UUID userId, BigDecimal confidence) {
        User user = userId != null ? userRepository.findById(userId).orElse(null) : null;
        String label = user != null && user.getFullName() != null && !user.getFullName().isBlank()
                ? user.getFullName() : SELF_FALLBACK_LABEL;
        return new SpeakerAttribution(SpeakerRole.SELF, label, confidence, selfParticipant(meeting, user));
    }

    private MeetingParticipant selfParticipant(Meeting meeting, UUID userId) {
        return selfParticipant(meeting, userId != null ? userRepository.findById(userId).orElse(null) : null);
    }

    /** Participante do usuário na reunião (por e-mail); criado na primeira fala se não existir. */
    private MeetingParticipant selfParticipant(Meeting meeting, User user) {
        if (user == null) return null;
        return participantRepository.findByMeetingId(meeting.getId()).stream()
                .filter(p -> p.getEmail() != null && p.getEmail().equalsIgnoreCase(user.getEmail()))
                .findFirst()
                .orElseGet(() -> participantRepository.save(MeetingParticipant.builder()
                        .meeting(meeting).name(user.getFullName()).email(user.getEmail()).role("HOST").build()));
    }

    private SpeakerAttribution count(AudioSource source, SpeakerAttribution result) {
        meters.counter("segments.attributed", "audioSource", source.name(), "role", result.role().name()).increment();
        log.debug("Falante atribuído: source={} role={} confidence={}", source, result.role(), result.confidence());
        return result;
    }
}
