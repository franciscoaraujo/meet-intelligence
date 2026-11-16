package com.meetingintelligence.application.service;

import com.meetingintelligence.application.dto.AIProfileDto;
import com.meetingintelligence.domain.entity.AIProfile;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.enums.MeetingType;
import com.meetingintelligence.infrastructure.persistence.repository.AIProfileRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIProfileService {

    private final AIProfileRepository profileRepository;
    private final OrganizationRepository organizationRepository;

    @Transactional(readOnly = true)
    public List<AIProfileDto> listProfiles(UUID organizationId) {
        List<AIProfile> profiles = profileRepository.findByOrganizationId(organizationId);
        if (profiles.isEmpty()) {
            return getDefaultSystemProfiles();
        }
        return profiles.stream().map(this::mapToDto).toList();
    }

    @Transactional
    public AIProfileDto createProfile(UUID organizationId, AIProfileDto dto) {
        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Organização não encontrada: " + organizationId));

        AIProfile profile = AIProfile.builder()
                .organization(org)
                .name(dto.getName())
                .meetingType(dto.getMeetingType())
                .systemPromptOverride(dto.getSystemPromptOverride())
                .focusCategories(dto.getFocusCategories() != null ? dto.getFocusCategories() : "[\"DECISION\",\"TASK\",\"QUESTION\"]")
                .isDefault(dto.isDefault())
                .build();

        return mapToDto(profileRepository.save(profile));
    }

    @Transactional
    /** Spec 007 RF-13: só exclui perfil da própria organização; de outra → 404. */
    public void deleteProfile(UUID profileId, UUID organizationId) {
        var profile = profileRepository.findById(profileId)
                .filter(p -> p.getOrganization() != null && p.getOrganization().getId().equals(organizationId))
                .orElseThrow(() -> new com.meetingintelligence.application.exception.ResourceNotFoundException(
                        "Perfil não encontrado: " + profileId));
        profileRepository.delete(profile);
    }

    private List<AIProfileDto> getDefaultSystemProfiles() {
        List<AIProfileDto> defaults = new ArrayList<>();
        defaults.add(AIProfileDto.builder()
                .name("Geral / Alinhamento")
                .meetingType(MeetingType.GENERAL)
                .focusCategories("[\"DECISION\",\"TASK\",\"QUESTION\",\"ALERT\"]")
                .isDefault(true)
                .build());

        defaults.add(AIProfileDto.builder()
                .name("Entrevista Técnica / Processo Seletivo")
                .meetingType(MeetingType.INTERVIEW)
                .systemPromptOverride("Avaliar competências técnicas, profundidade de respostas, tecnologias citadas e pontos de atenção.")
                .focusCategories("[\"QUESTION\",\"CONTENT_ANALYSIS\",\"ALERT\"]")
                .isDefault(false)
                .build());

        defaults.add(AIProfileDto.builder()
                .name("Reunião Técnica & Arquitetura")
                .meetingType(MeetingType.TECHNICAL)
                .systemPromptOverride("Focar em decisões arquiteturais, pgvector, resiliência, riscos técnicos e prazos de implementação.")
                .focusCategories("[\"DECISION\",\"TASK\",\"CONTENT_ANALYSIS\"]")
                .isDefault(false)
                .build());

        defaults.add(AIProfileDto.builder()
                .name("Reunião Comercial & Vendas")
                .meetingType(MeetingType.SALES)
                .systemPromptOverride("Focar em dores do cliente, objeções levantadas, propostas de valor, negociações e próximos passos.")
                .focusCategories("[\"DECISION\",\"TASK\",\"CONTENT_ANALYSIS\"]")
                .isDefault(false)
                .build());

        defaults.add(AIProfileDto.builder()
                .name("Diretoria & Governança")
                .meetingType(MeetingType.CORPORATE)
                .systemPromptOverride("Foco rigoroso em deliberações executivas, orçamentos, donos de tarefas e impactos estratégicos.")
                .focusCategories("[\"DECISION\",\"TASK\"]")
                .isDefault(false)
                .build());

        return defaults;
    }

    private AIProfileDto mapToDto(AIProfile p) {
        return AIProfileDto.builder()
                .id(p.getId())
                .organizationId(p.getOrganization() != null ? p.getOrganization().getId() : null)
                .name(p.getName())
                .meetingType(p.getMeetingType())
                .systemPromptOverride(p.getSystemPromptOverride())
                .focusCategories(p.getFocusCategories())
                .isDefault(p.isDefault())
                .build();
    }
}

