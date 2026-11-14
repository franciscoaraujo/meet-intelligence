package com.meetingintelligence.application.service;

import com.meetingintelligence.application.dto.OrganizationDto;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.entity.OrganizationMember;
import com.meetingintelligence.domain.entity.User;
import com.meetingintelligence.domain.enums.OrganizationRole;
import com.meetingintelligence.domain.enums.PlanType;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationMemberRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationRepository;
import com.meetingintelligence.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final MeetingRepository meetingRepository;

    @Transactional(readOnly = true)
    public OrganizationDto getOrganizationDetails(UUID organizationId) {
        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Organização não encontrada: " + organizationId));

        List<OrganizationMember> members = memberRepository.findByOrganizationId(organizationId);
        List<OrganizationDto.MemberItemDto> memberDtos = members.stream()
                .map(m -> OrganizationDto.MemberItemDto.builder()
                        .userId(m.getUser().getId())
                        .email(m.getUser().getEmail())
                        .fullName(m.getUser().getFullName())
                        .avatarUrl(m.getUser().getAvatarUrl())
                        .role(m.getRole())
                        .build())
                .toList();

        return OrganizationDto.builder()
                .id(org.getId())
                .name(org.getName())
                .slug(org.getSlug())
                .planType(org.getPlanType())
                .members(memberDtos)
                .createdAt(org.getCreatedAt())
                .build();
    }

    @Transactional
    public OrganizationDto updatePlan(UUID organizationId, PlanType newPlan) {
        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Organização não encontrada: " + organizationId));

        log.info("Atualizando plano da organização '{}' de {} para {}", org.getName(), org.getPlanType(), newPlan);
        org.setPlanType(newPlan);
        organizationRepository.save(org);
        return getOrganizationDetails(organizationId);
    }

    @Transactional
    public OrganizationDto.MemberItemDto inviteMember(UUID organizationId, String email, OrganizationRole role) {
        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Organização não encontrada: " + organizationId));

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(User.builder()
                        .email(email)
                        .fullName(email.split("@")[0])
                        .build()));

        OrganizationMember member = memberRepository.findByOrganizationIdAndUserId(organizationId, user.getId())
                .orElseGet(() -> memberRepository.save(OrganizationMember.builder()
                        .organization(org)
                        .user(user)
                        .role(role != null ? role : OrganizationRole.MEMBER)
                        .build()));

        return OrganizationDto.MemberItemDto.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(member.getRole())
                .build();
    }

    @Transactional
    public void deleteMeetingLgpd(UUID meetingId) {
        log.info("Executando exclusão definitiva de reunião (Direito ao Esquecimento LGPD): {}", meetingId);
        meetingRepository.deleteById(meetingId);
    }
}

