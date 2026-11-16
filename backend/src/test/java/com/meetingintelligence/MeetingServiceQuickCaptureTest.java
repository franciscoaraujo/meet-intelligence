package com.meetingintelligence;

import com.meetingintelligence.application.dto.CreateMeetingRequest;
import com.meetingintelligence.application.service.MeetingService;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.entity.User;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingParticipantRepository;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationRepository;
import com.meetingintelligence.infrastructure.persistence.repository.UserRepository;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Spec 010: captura avulsa (reunião sem link do Google Meet). */
class MeetingServiceQuickCaptureTest {

    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private MeetingService service;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        principal = UserPrincipal.builder().id(userId).organizationId(orgId).email("ana@exemplo.com").role("USER").build();
        when(organizations.findById(orgId)).thenReturn(Optional.of(Organization.builder().id(orgId).build()));
        User user = new User();
        user.setId(userId);
        user.setEmail("ana@exemplo.com");
        user.setFullName("Ana");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(meetings.save(any(Meeting.class))).thenAnswer(inv -> {
            Meeting m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
        service = new MeetingService(meetings, mock(MeetingParticipantRepository.class), organizations, users);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("CA-05: sem link (nulo ou em branco) → reunião criada com googleMeetLink nulo")
    void blankLinkStoredAsNull(String link) {
        service.createMeeting(CreateMeetingRequest.builder().title("Captura de 15/11 21:30").googleMeetLink(link).build(), principal);

        ArgumentCaptor<Meeting> saved = ArgumentCaptor.forClass(Meeting.class);
        verify(meetings).save(saved.capture());
        assertThat(saved.getValue().getGoogleMeetLink()).isNull();
        assertThat(saved.getValue().getTitle()).isEqualTo("Captura de 15/11 21:30");
    }

    @ParameterizedTest
    @ValueSource(strings = {" https://meet.google.com/abc-defg-hij "})
    @DisplayName("Link do Meet informado continua gravado (sem espaços nas pontas)")
    void meetLinkKept(String link) {
        service.createMeeting(CreateMeetingRequest.builder().title("Reunião").googleMeetLink(link).build(), principal);

        ArgumentCaptor<Meeting> saved = ArgumentCaptor.forClass(Meeting.class);
        verify(meetings).save(saved.capture());
        assertThat(saved.getValue().getGoogleMeetLink()).isEqualTo("https://meet.google.com/abc-defg-hij");
    }
}
