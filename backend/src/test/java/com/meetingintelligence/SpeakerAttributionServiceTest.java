package com.meetingintelligence;

import com.meetingintelligence.application.service.SpeakerAttributionService;
import com.meetingintelligence.application.service.SpeakerAttributionService.SpeakerAttribution;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.MeetingParticipant;
import com.meetingintelligence.domain.entity.User;
import com.meetingintelligence.domain.speech.AudioSource;
import com.meetingintelligence.domain.speech.SpeakerRole;
import com.meetingintelligence.domain.speech.TranscriptionSegmentDto;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingParticipantRepository;
import com.meetingintelligence.infrastructure.persistence.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Uma regra por linha da tabela RF-03 da spec 003. */
class SpeakerAttributionServiceTest {

    private final MeetingParticipantRepository participants = mock(MeetingParticipantRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SpeakerAttributionService service = new SpeakerAttributionService(participants, users, meters);

    private final UUID userId = UUID.randomUUID();
    private final Meeting meeting = Meeting.builder().id(UUID.randomUUID()).build();
    private final List<MeetingParticipant> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("Francisco Araújo").email("francisco@example.com").build()));
        when(participants.findByMeetingId(meeting.getId())).thenAnswer(inv -> new ArrayList<>(stored));
        when(participants.save(any(MeetingParticipant.class))).thenAnswer(inv -> {
            MeetingParticipant p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            stored.add(p);
            return p;
        });
    }

    @Test
    @DisplayName("CA-02: MIC → SELF, nome do usuário logado, participante do usuário (criado na primeira fala)")
    void micIsSelf() {
        SpeakerAttribution first = service.forLiveAudio(meeting, AudioSource.MIC, userId);
        SpeakerAttribution second = service.forLiveAudio(meeting, AudioSource.MIC, userId);

        assertThat(first.role()).isEqualTo(SpeakerRole.SELF);
        assertThat(first.label()).isEqualTo("Francisco Araújo");
        assertThat(first.confidence()).isEqualByComparingTo("0.9");
        assertThat(first.participant().getEmail()).isEqualTo("francisco@example.com");
        assertThat(second.participant()).isSameAs(first.participant());
        verify(participants, times(1)).save(any());
    }

    @Test
    @DisplayName("CA-01: TAB → REMOTE, rótulo genérico, sem participante e sem o nome do usuário")
    void tabIsRemoteWithoutIdentity() {
        SpeakerAttribution result = service.forLiveAudio(meeting, AudioSource.TAB, userId);

        assertThat(result.role()).isEqualTo(SpeakerRole.REMOTE);
        assertThat(result.label()).isEqualTo("Participante remoto (Meet)").doesNotContain("Francisco");
        assertThat(result.participant()).isNull();
        verify(participants, never()).save(any());
    }

    @Test
    @DisplayName("CA-04: upload diarizado → 'Falante N' com a confiança do provedor e participante por tag")
    void uploadWithDiarization() {
        SpeakerAttribution a = service.forUpload(meeting, dto(1, 0.97));
        SpeakerAttribution b = service.forUpload(meeting, dto(2, 0.91));
        SpeakerAttribution a2 = service.forUpload(meeting, dto(1, 0.95));

        assertThat(a.label()).isEqualTo("Falante 1");
        assertThat(b.label()).isEqualTo("Falante 2");
        assertThat(a.role()).isEqualTo(SpeakerRole.UNKNOWN);
        assertThat(a.confidence()).isEqualByComparingTo("0.97");
        assertThat(a2.participant()).isSameAs(a.participant());
    }

    @Test
    @DisplayName("Upload sem diarização ou com confiança < 0,5 → Desconhecido, confiança 0")
    void uploadWithoutEvidence() {
        assertThat(service.forUpload(meeting, dto(null, 0.99)).label()).isEqualTo("Desconhecido");
        SpeakerAttribution low = service.forUpload(meeting, dto(1, 0.3));
        assertThat(low.label()).isEqualTo("Desconhecido");
        assertThat(low.confidence()).isEqualByComparingTo("0");
        assertThat(low.participant()).isNull();
    }

    @Test
    @DisplayName("MANUAL com falante escolhido → rótulo e papel escolhidos, confiança 1,0")
    void manualChosen() {
        SpeakerAttribution remote = service.forBrowserText(meeting, AudioSource.MANUAL, "Entrevistador", SpeakerRole.REMOTE, userId);
        SpeakerAttribution self = service.forBrowserText(meeting, AudioSource.MANUAL, "Francisco", SpeakerRole.SELF, userId);

        assertThat(remote.role()).isEqualTo(SpeakerRole.REMOTE);
        assertThat(remote.confidence()).isEqualByComparingTo("1");
        assertThat(remote.participant()).isNull();
        assertThat(self.participant()).isNotNull();
    }

    @Test
    @DisplayName("MANUAL sem rótulo → Desconhecido; Web Speech (MIC) → SELF")
    void manualUnknownAndWebSpeech() {
        assertThat(service.forBrowserText(meeting, AudioSource.MANUAL, " ", null, userId).role()).isEqualTo(SpeakerRole.UNKNOWN);
        assertThat(service.forBrowserText(meeting, AudioSource.MIC, "qualquer", SpeakerRole.REMOTE, userId).role())
                .isEqualTo(SpeakerRole.SELF);
    }

    @Test
    @DisplayName("Usuário sem nome cadastrado → 'Você'")
    void selfFallbackLabel() {
        UUID other = UUID.randomUUID();
        when(users.findById(other)).thenReturn(Optional.empty());
        assertThat(service.forLiveAudio(meeting, AudioSource.MIC, other).label()).isEqualTo("Você");
    }

    @Test
    @DisplayName("Métrica segments.attributed por origem e papel")
    void metrics() {
        service.forLiveAudio(meeting, AudioSource.TAB, userId);
        assertThat(meters.counter("segments.attributed", "audioSource", "TAB", "role", "REMOTE").count()).isEqualTo(1.0);
    }

    private static TranscriptionSegmentDto dto(Integer tag, double confidence) {
        return TranscriptionSegmentDto.builder().text("x").speakerTag(tag).confidence(BigDecimal.valueOf(confidence)).build();
    }
}
