package com.meetingintelligence.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.exception.ResourceNotFoundException;
import com.meetingintelligence.application.service.question.AnswerTranslationService;
import com.meetingintelligence.domain.ai.LLMRequest;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.entity.AIInsight;
import com.meetingintelligence.domain.entity.InterviewQuestion;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.infrastructure.ai.AIOrchestrator;
import com.meetingintelligence.infrastructure.persistence.repository.AIInsightRepository;
import com.meetingintelligence.infrastructure.persistence.repository.InterviewQuestionRepository;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Spec 012: tradução do card do copiloto. */
class AnswerTranslationServiceTest {

    private static final String METADATA = """
            {"copilotAnswer": {"is_question_for_candidate": true,
              "spoken_answer": "I really like that idea. It feels like a helpful bandmate.",
              "sections": {"direct_answer": "Use short audio clips.", "trade_offs": "Needs a good microphone."}},
             "simulated": false, "provider": "GROK", "model": "grok-4.20-0309-non-reasoning"}
            """;
    private static final String TRANSLATED = """
            {"question": "Como a ferramenta ajudaria nos ensaios?",
             "spoken_answer": "Eu gosto muito dessa ideia. Parece um colega de banda prestativo.",
             "sections": {"direct_answer": "Use clipes curtos de áudio.", "trade_offs": "Precisa de um bom microfone."}}
            """;

    private final InterviewQuestionRepository questions = mock(InterviewQuestionRepository.class);
    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final AIInsightRepository insights = mock(AIInsightRepository.class);
    private final AIOrchestrator orchestrator = mock(AIOrchestrator.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private AnswerTranslationService service;
    private UUID meetingId;
    private InterviewQuestion question;
    private AIInsight insight;

    @BeforeEach
    void setUp() {
        meetingId = UUID.randomUUID();
        insight = AIInsight.builder().id(UUID.randomUUID()).metadata(METADATA).build();
        question = InterviewQuestion.builder().id(UUID.randomUUID()).meetingId(meetingId)
                .questionText("how would the tool help rehearsals").answerInsightId(insight.getId()).build();
        when(questions.findById(question.getId())).thenReturn(Optional.of(question));
        when(insights.findById(insight.getId())).thenReturn(Optional.of(insight));
        when(meetings.findById(meetingId)).thenReturn(Optional.of(Meeting.builder().id(meetingId).build()));
        when(insights.save(any(AIInsight.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AnswerTranslationService(questions, meetings, insights, orchestrator, mapper);
    }

    private void llmReturns(String content) {
        when(orchestrator.execute(any(), any(), eq(AIOrchestrator.ANSWER_TRANSLATION)))
                .thenReturn(LLMResponse.builder().providerName("GROK").content(content).build());
    }

    @Test
    @DisplayName("CA-01: traduz pergunta, resposta e seções, envia só o card (sem currículo) e grava nos metadados")
    void translatesAndCaches() throws Exception {
        llmReturns(TRANSLATED);
        ArgumentCaptor<LLMRequest> req = ArgumentCaptor.forClass(LLMRequest.class);

        AnswerTranslationService.Translation t = service.translate(meetingId, question.getId(), "pt-BR");

        assertThat(t.spokenAnswer()).startsWith("Eu gosto muito");
        assertThat(t.question()).isEqualTo("Como a ferramenta ajudaria nos ensaios?");
        assertThat(t.sections()).containsEntry("direct_answer", "Use clipes curtos de áudio.").hasSize(2);
        verify(orchestrator).execute(req.capture(), any(), eq(AIOrchestrator.ANSWER_TRANSLATION));
        assertThat(req.getValue().getSystemPrompt()).contains("português do Brasil (pt-BR)").contains("não acrescente nem remova fatos");
        assertThat(req.getValue().getUserPrompt()).contains("helpful bandmate").doesNotContain("knowledge_base");
        assertThat(mapper.readTree(insight.getMetadata()).path("translations").path("pt-BR").path("spokenAnswer").asText())
                .startsWith("Eu gosto muito");
        assertThat(mapper.readTree(insight.getMetadata()).path("copilotAnswer").path("spoken_answer").asText())
                .startsWith("I really like"); // o original continua
    }

    @Test
    @DisplayName("CA-02: segundo pedido no mesmo idioma não chama a IA")
    void cacheHit() {
        llmReturns(TRANSLATED);
        service.translate(meetingId, question.getId(), "pt-BR");
        AnswerTranslationService.Translation again = service.translate(meetingId, question.getId(), "pt-BR");

        assertThat(again.spokenAnswer()).startsWith("Eu gosto muito");
        verify(orchestrator, times(1)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("CA-03: pergunta sem resposta → 404; idioma inválido → 400")
    void notFoundAndInvalidLanguage() {
        question.setAnswerInsightId(null);
        assertThatThrownBy(() -> service.translate(meetingId, question.getId(), "pt-BR")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.translate(meetingId, question.getId(), "es-ES")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.translate(UUID.randomUUID(), question.getId(), "pt-BR")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("CA-04: resposta inválida ou IA indisponível → TranslationUnavailableException (503), nada gravado")
    void unavailable() {
        llmReturns("{\"summary_delta\": \"não é tradução\"}");
        assertThatThrownBy(() -> service.translate(meetingId, question.getId(), "fr-FR"))
                .isInstanceOf(AnswerTranslationService.TranslationUnavailableException.class);

        when(orchestrator.execute(any(), any(), any())).thenThrow(new RuntimeException("indisponível"));
        assertThatThrownBy(() -> service.translate(meetingId, question.getId(), "fr-FR"))
                .isInstanceOf(AnswerTranslationService.TranslationUnavailableException.class);
        assertThat(insight.getMetadata()).isEqualTo(METADATA);
        verify(insights, never()).save(any());
    }
}
