package com.meetingintelligence;

import com.meetingintelligence.application.service.MeetingService;
import com.meetingintelligence.application.service.question.CopilotPromptBuilder;
import com.meetingintelligence.domain.copilot.CopilotAnswer;
import com.meetingintelligence.domain.copilot.CopilotAnswerValidator;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.language.InterviewLanguage;
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

/** Spec 011: idioma da entrevista. */
class InterviewLanguageTest {

    private static final String RESUME = "<knowledge_base>Engenheiro Java e Spring Boot. RabbitMQ com DLQ no OrionPay.</knowledge_base>";

    @Test
    @DisplayName("fromTag é tolerante (padrão português); parseStrict só aceita pt-BR, en-US e fr-FR")
    void parsing() {
        assertThat(InterviewLanguage.fromTag(null)).isEqualTo(InterviewLanguage.PT_BR);
        assertThat(InterviewLanguage.fromTag("en")).isEqualTo(InterviewLanguage.EN_US);
        assertThat(InterviewLanguage.fromTag("FR_FR")).isEqualTo(InterviewLanguage.FR_FR);
        assertThat(InterviewLanguage.fromTag("es-ES")).isEqualTo(InterviewLanguage.PT_BR);
        assertThat(InterviewLanguage.parseStrict("en-US")).isEqualTo(InterviewLanguage.EN_US);
        assertThatThrownBy(() -> InterviewLanguage.parseStrict("es-ES")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CA-01: PATCH /language grava o idioma; idioma não suportado → 400 (IllegalArgumentException) sem gravar")
    void updateLanguage() {
        UUID orgId = UUID.randomUUID();
        Meeting meeting = Meeting.builder().id(UUID.randomUUID()).organization(Organization.builder().id(orgId).build())
                .title("Captura").language("pt-BR").build();
        MeetingRepository meetings = mock(MeetingRepository.class);
        when(meetings.findById(meeting.getId())).thenReturn(Optional.of(meeting));
        when(meetings.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));
        MeetingService service = new MeetingService(meetings, mock(MeetingParticipantRepository.class),
                mock(OrganizationRepository.class), mock(UserRepository.class));

        service.updateLanguage(meeting.getId(), orgId, "en-US");
        assertThat(meeting.getLanguage()).isEqualTo("en-US");

        assertThatThrownBy(() -> service.updateLanguage(meeting.getId(), orgId, "es-ES")).isInstanceOf(IllegalArgumentException.class);
        assertThat(meeting.getLanguage()).isEqualTo("en-US");
        verify(meetings, times(1)).save(any());
    }

    @Test
    @DisplayName("CA-04: em inglês o prompt do copiloto pede a resposta em inglês; em português não muda")
    void copilotPromptLanguage() {
        CopilotPromptBuilder builder = new CopilotPromptBuilder();
        String en = builder.userPrompt("how would you design a rate limiter", "DIRECT_QUESTION", 0.9, List.of(), null, "",
                RESUME, null, false, "en-US");
        String fr = builder.userPrompt("comment concevriez-vous un limiteur", "DIRECT_QUESTION", 0.9, List.of(), null, "",
                RESUME, null, false, "fr-FR");
        String pt = builder.userPrompt("como você projetaria um limitador", "DIRECT_QUESTION", 0.9, List.of(), null, "",
                RESUME, null, false, "pt-BR");

        assertThat(en).contains("<answer_language>").contains("em inglês (en-US)");
        assertThat(fr).contains("em francês (fr-FR)");
        assertThat(pt).doesNotContain("<answer_language>");
    }

    private static CopilotAnswer answer(String spoken) {
        return new CopilotAnswer(true, "INTERMEDIATE", spoken, null, List.of(), List.of(), 0.9, false);
    }

    @Test
    @DisplayName("CA-05: experiência inventada em inglês e em francês é rejeitada; fatos do currículo passam")
    void validatorEnglishAndFrench() {
        String filler = " This keeps the system reliable, easy to test and simple to evolve over time for the whole team.";
        assertThat(CopilotAnswerValidator.validate(answer("I worked with Kafka at Acme for three years." + filler),
                "tell me about messaging", RESUME)).anyMatch(p -> p.contains("kafka"));
        assertThat(CopilotAnswerValidator.validate(answer("J'ai utilisé Kafka chez Acme pendant trois ans." + filler),
                "parlez-moi de la messagerie", RESUME)).anyMatch(p -> p.contains("kafka"));
        assertThat(CopilotAnswerValidator.validate(answer("I used RabbitMQ with a dead letter queue at OrionPay." + filler),
                "tell me about messaging", RESUME)).noneMatch(p -> p.contains("experiência"));
    }
}
