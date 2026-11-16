package com.meetingintelligence;

import com.meetingintelligence.application.service.MeetingService;
import com.meetingintelligence.application.service.question.CopilotPromptBuilder;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.enums.AnswerStyle;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingParticipantRepository;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationRepository;
import com.meetingintelligence.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Spec 014: estilo das respostas do copiloto. */
class AnswerStyleTest {

    @Test
    @DisplayName("CA-01: PATCH /answer-style grava o estilo; valor inválido → 400 sem gravar")
    void updateAnswerStyle() {
        UUID orgId = UUID.randomUUID();
        Meeting meeting = Meeting.builder().id(UUID.randomUUID()).organization(Organization.builder().id(orgId).build()).title("x").build();
        assertThat(meeting.getAnswerStyle()).isEqualTo(AnswerStyle.NEUTRAL);
        MeetingRepository meetings = mock(MeetingRepository.class);
        when(meetings.findById(meeting.getId())).thenReturn(Optional.of(meeting));
        when(meetings.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));
        MeetingService service = new MeetingService(meetings, mock(MeetingParticipantRepository.class),
                mock(OrganizationRepository.class), mock(UserRepository.class));

        assertThat(service.updateAnswerStyle(meeting.getId(), orgId, "formal").getAnswerStyle()).isEqualTo(AnswerStyle.FORMAL);
        assertThatThrownBy(() -> service.updateAnswerStyle(meeting.getId(), orgId, "XYZ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(meeting.getAnswerStyle()).isEqualTo(AnswerStyle.FORMAL);
        verify(meetings, times(1)).save(any());
    }

    private static String prompt(AnswerStyle style, String language) {
        return new CopilotPromptBuilder().userPrompt("how would you design a rate limiter", "DIRECT_QUESTION", 0.9, List.of(),
                null, "", "<knowledge_base>cv</knowledge_base>", null, false, language, style);
    }

    @Test
    @DisplayName("CA-02: técnico/formal/informal entram no prompt com a regra de fidelidade; o padrão não muda o prompt")
    void promptStyle() {
        assertThat(prompt(AnswerStyle.TECHNICAL, "pt-BR")).contains("<answer_style>").contains("Tom técnico")
                .contains("os fatos continuam restritos ao <knowledge_base>").contains("60 a 140 palavras");
        assertThat(prompt(AnswerStyle.FORMAL, "pt-BR")).contains("Tom formal");
        assertThat(prompt(AnswerStyle.INFORMAL, "pt-BR")).contains("Tom informal");
        assertThat(prompt(AnswerStyle.NEUTRAL, "pt-BR")).doesNotContain("<answer_style>");
        assertThat(prompt(null, "pt-BR")).doesNotContain("<answer_style>");
        // RN-03: combina com o idioma
        assertThat(prompt(AnswerStyle.FORMAL, "en-US")).contains("<answer_style>").contains("<answer_language>");
    }
}
