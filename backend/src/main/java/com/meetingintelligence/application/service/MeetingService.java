package com.meetingintelligence.application.service;

import com.meetingintelligence.application.dto.CreateMeetingRequest;
import com.meetingintelligence.application.dto.MeetingResponse;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.MeetingParticipant;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.entity.User;
import com.meetingintelligence.domain.enums.MeetingStatus;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingParticipantRepository;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationRepository;
import com.meetingintelligence.infrastructure.persistence.repository.UserRepository;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MeetingService {

    private final MeetingRepository meetingRepository;
    private final MeetingParticipantRepository participantRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    @Transactional
    public MeetingResponse createMeeting(CreateMeetingRequest request, UserPrincipal currentUser) {
        log.info("Criando nova reunião: {} para o usuário: {}", request.getTitle(), currentUser.getId());

        Organization org = organizationRepository.findById(currentUser.getOrganizationId())
                .orElseThrow(() -> new IllegalArgumentException("Organização não encontrada"));

        if (request.getGoogleCalendarEventId() != null && !request.getGoogleCalendarEventId().isBlank()) {
            var existingOpt = meetingRepository.findByOrganizationIdAndGoogleCalendarEventId(
                    org.getId(), request.getGoogleCalendarEventId().trim()
            );
            if (existingOpt.isPresent()) {
                log.info("Reunião agendada já existente para o evento {}: retornando reunião {}",
                        request.getGoogleCalendarEventId(), existingOpt.get().getId());
                return mapToResponse(existingOpt.get());
            }
        }

        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));

        Meeting meeting = Meeting.builder()
                .organization(org)
                .createdBy(user)
                .title(request.getTitle())
                .description(request.getDescription())
                .scheduledStart(request.getScheduledStart())
                .scheduledEnd(request.getScheduledEnd())
                // Spec 010: captura avulsa não tem link; texto em branco é gravado como ausente
                .googleMeetLink(request.getGoogleMeetLink() == null || request.getGoogleMeetLink().isBlank()
                        ? null : request.getGoogleMeetLink().trim())
                .googleCalendarEventId(request.getGoogleCalendarEventId())
                .captureMechanism(request.getCaptureMechanism())
                .language(request.getLanguage() != null ? request.getLanguage() : "pt-BR")
                .answerStyle(request.getAnswerStyle() != null ? request.getAnswerStyle()
                        : com.meetingintelligence.domain.enums.AnswerStyle.NEUTRAL)
                .status(MeetingStatus.SCHEDULED)
                .build();

        Meeting saved = meetingRepository.save(meeting);

        // Adiciona criador como participante principal
        participantRepository.save(MeetingParticipant.builder()
                .meeting(saved)
                .name(user.getFullName())
                .email(user.getEmail())
                .speakerTag(1)
                .role("HOST")
                .joinedAt(OffsetDateTime.now())
                .build());

        // Adiciona participantes adicionais
        if (request.getParticipantEmails() != null) {
            int tag = 2;
            for (String email : request.getParticipantEmails()) {
                if (!email.equalsIgnoreCase(user.getEmail())) {
                    participantRepository.save(MeetingParticipant.builder()
                            .meeting(saved)
                            .name(email.split("@")[0])
                            .email(email)
                            .speakerTag(tag++)
                            .role("PARTICIPANT")
                            .build());
                }
            }
        }

        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<MeetingResponse> listMeetings(UUID organizationId, Pageable pageable) {
        return listMeetings(organizationId, false, pageable);
    }

    /** Spec 020 RF-09: por padrão só as ativas; archived=true traz as arquivadas. */
    @Transactional(readOnly = true)
    public Page<MeetingResponse> listMeetings(UUID organizationId, boolean archived, Pageable pageable) {
        Page<Meeting> page = archived
                ? meetingRepository.findByOrganizationIdAndArchivedAtIsNotNull(organizationId, pageable)
                : meetingRepository.findByOrganizationIdAndArchivedAtIsNull(organizationId, pageable);
        return page.map(this::mapToResponse);
    }

    /** Spec 020 RF-08: arquivar só esconde da lista; nenhum dado é apagado. */
    @Transactional
    public MeetingResponse setArchived(UUID meetingId, UUID organizationId, boolean archived) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .filter(m -> m.getOrganization() != null && m.getOrganization().getId().equals(organizationId))
                .orElseThrow(() -> new com.meetingintelligence.application.exception.ResourceNotFoundException(
                        "Reunião não encontrada: " + meetingId));
        meeting.setArchivedAt(archived ? (meeting.getArchivedAt() != null ? meeting.getArchivedAt() : OffsetDateTime.now()) : null);
        return mapToResponse(meetingRepository.save(meeting));
    }

    @Transactional(readOnly = true)
    public MeetingResponse getMeetingById(UUID meetingId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Reunião não encontrada: " + meetingId));
        return mapToResponse(meeting);
    }

    @Transactional
    public MeetingResponse updateMeetingStatus(UUID meetingId, MeetingStatus status) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Reunião não encontrada: " + meetingId));

        // Spec 020 RN-02: uma reunião que já começou nunca volta a "agendada"
        if (status == MeetingStatus.SCHEDULED && meeting.getStatus() != MeetingStatus.SCHEDULED) {
            throw new IllegalArgumentException("Uma reunião que já começou não volta a ficar agendada.");
        }
        MeetingStatus previous = meeting.getStatus();
        meeting.setStatus(status);
        if (status == MeetingStatus.IN_PROGRESS) {
            if (meeting.getActualStart() == null) meeting.setActualStart(OffsetDateTime.now());
            // "Retomar" uma concluída: o fim volta a ser marcado no próximo "Encerrar"
            if (previous == MeetingStatus.COMPLETED) meeting.setActualEnd(null);
        } else if (status == MeetingStatus.COMPLETED && meeting.getActualEnd() == null) {
            meeting.setActualEnd(OffsetDateTime.now());
        }

        return mapToResponse(meetingRepository.save(meeting));
    }

    /** Papel do usuário na entrevista (spec 003, RF-04): define quem é candidato/entrevistador. */
    @Transactional
    public MeetingResponse updateInterviewRole(UUID meetingId, UUID organizationId,
                                               com.meetingintelligence.domain.enums.InterviewRole role) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .filter(m -> m.getOrganization() != null && m.getOrganization().getId().equals(organizationId))
                .orElseThrow(() -> new com.meetingintelligence.application.exception.ResourceNotFoundException(
                        "Reunião não encontrada: " + meetingId));
        meeting.setUserInterviewRole(role);
        return mapToResponse(meetingRepository.save(meeting));
    }

    /** Idioma da entrevista (spec 011): transcrição, detecção de perguntas e respostas do copiloto. */
    @Transactional
    public MeetingResponse updateLanguage(UUID meetingId, UUID organizationId, String language) {
        var parsed = com.meetingintelligence.domain.language.InterviewLanguage.parseStrict(language);
        Meeting meeting = meetingRepository.findById(meetingId)
                .filter(m -> m.getOrganization() != null && m.getOrganization().getId().equals(organizationId))
                .orElseThrow(() -> new com.meetingintelligence.application.exception.ResourceNotFoundException(
                        "Reunião não encontrada: " + meetingId));
        meeting.setLanguage(parsed.tag());
        return mapToResponse(meetingRepository.save(meeting));
    }

    /** Tom das respostas do copiloto (spec 014). */
    @Transactional
    public MeetingResponse updateAnswerStyle(UUID meetingId, UUID organizationId, String style) {
        var parsed = com.meetingintelligence.domain.enums.AnswerStyle.parseStrict(style);
        Meeting meeting = meetingRepository.findById(meetingId)
                .filter(m -> m.getOrganization() != null && m.getOrganization().getId().equals(organizationId))
                .orElseThrow(() -> new com.meetingintelligence.application.exception.ResourceNotFoundException(
                        "Reunião não encontrada: " + meetingId));
        meeting.setAnswerStyle(parsed);
        return mapToResponse(meetingRepository.save(meeting));
    }

    @Transactional
    public void deleteMeeting(UUID meetingId) {
        if (!meetingRepository.existsById(meetingId)) {
            throw new IllegalArgumentException("Reunião não encontrada: " + meetingId);
        }
        meetingRepository.deleteById(meetingId);
    }

    private MeetingResponse mapToResponse(Meeting m) {
        return MeetingResponse.builder()
                .id(m.getId())
                .organizationId(m.getOrganization().getId())
                .title(m.getTitle())
                .description(m.getDescription())
                .scheduledStart(m.getScheduledStart())
                .scheduledEnd(m.getScheduledEnd())
                .actualStart(m.getActualStart())
                .actualEnd(m.getActualEnd())
                .archivedAt(m.getArchivedAt())
                .status(m.getStatus())
                .googleMeetLink(m.getGoogleMeetLink())
                .googleCalendarEventId(m.getGoogleCalendarEventId())
                .captureMechanism(m.getCaptureMechanism())
                .language(m.getLanguage())
                .userInterviewRole(m.getUserInterviewRole() != null ? m.getUserInterviewRole() : com.meetingintelligence.domain.enums.InterviewRole.CANDIDATE)
                .answerStyle(m.getAnswerStyle() != null ? m.getAnswerStyle() : com.meetingintelligence.domain.enums.AnswerStyle.NEUTRAL)
                .createdAt(m.getCreatedAt())
                .build();
    }
}

