package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.exception.ResourceNotFoundException;
import com.meetingintelligence.application.service.question.AnswerTranslationService;
import com.meetingintelligence.application.service.question.InterviewCopilot;
import com.meetingintelligence.application.service.question.QuestionDetectionService;
import com.meetingintelligence.domain.entity.InterviewQuestion;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.infrastructure.persistence.repository.InterviewQuestionRepository;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Perguntas detectadas na entrevista (spec 005) e ações sobre elas. */
@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/questions")
@RequiredArgsConstructor
@Tag(name = "Perguntas da entrevista", description = "Perguntas detectadas, resposta sob demanda e descarte")
public class QuestionController {

    private final InterviewQuestionRepository questions;
    private final MeetingRepository meetings;
    private final QuestionDetectionService detection;
    private final InterviewCopilot copilot;
    private final AnswerTranslationService translations;

    @GetMapping
    @Operation(summary = "Listar perguntas detectadas")
    public ResponseEntity<List<InterviewQuestion>> list(@PathVariable UUID meetingId,
                                                        @AuthenticationPrincipal UserPrincipal user) {
        requireMeeting(meetingId, user);
        return ResponseEntity.ok(questions.findByMeetingIdOrderByCreatedAtAsc(meetingId));
    }

    @PostMapping("/answer-latest")
    @Operation(summary = "Responder a última fala do entrevistador",
            description = "Gatilho manual (RF-09): trata a fala remota em aberto (ou as últimas) como pergunta e aciona o copiloto")
    public ResponseEntity<Void> answerLatest(@PathVariable UUID meetingId, @AuthenticationPrincipal UserPrincipal user) {
        requireMeeting(meetingId, user);
        detection.triggerManual(meetingId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{questionId}/answer")
    @Operation(summary = "Gerar (de novo) a resposta de uma pergunta")
    public ResponseEntity<Void> answer(@PathVariable UUID meetingId, @PathVariable UUID questionId,
                                       @AuthenticationPrincipal UserPrincipal user) {
        requireQuestion(meetingId, questionId, user);
        copilot.answer(questionId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{questionId}/translate")
    @Operation(summary = "Traduzir o card do copiloto", description = "pt-BR, en-US ou fr-FR (spec 012); 503 se a IA estiver indisponível")
    public ResponseEntity<?> translate(@PathVariable UUID meetingId, @PathVariable UUID questionId,
                                       @RequestParam String language, @AuthenticationPrincipal UserPrincipal user) {
        requireQuestion(meetingId, questionId, user);
        try {
            return ResponseEntity.ok(translations.translate(meetingId, questionId, language));
        } catch (AnswerTranslationService.TranslationUnavailableException e) {
            return ResponseEntity.status(503).body(java.util.Map.of("message", "Não foi possível traduzir agora."));
        }
    }

    @PostMapping("/{questionId}/dismiss")
    @Operation(summary = "Marcar que não era uma pergunta")
    public ResponseEntity<InterviewQuestion> dismiss(@PathVariable UUID meetingId, @PathVariable UUID questionId,
                                                     @AuthenticationPrincipal UserPrincipal user) {
        InterviewQuestion q = requireQuestion(meetingId, questionId, user);
        q.setStatus(InterviewQuestion.Status.DISMISSED);
        InterviewQuestion saved = questions.save(q);
        detection.publish(saved);
        return ResponseEntity.ok(saved);
    }

    private Meeting requireMeeting(UUID meetingId, UserPrincipal user) {
        return meetings.findById(meetingId)
                .filter(m -> m.getOrganization() != null && m.getOrganization().getId().equals(user.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException("Reunião não encontrada: " + meetingId));
    }

    private InterviewQuestion requireQuestion(UUID meetingId, UUID questionId, UserPrincipal user) {
        requireMeeting(meetingId, user);
        return questions.findById(questionId)
                .filter(q -> q.getMeetingId().equals(meetingId))
                .orElseThrow(() -> new ResourceNotFoundException("Pergunta não encontrada: " + questionId));
    }
}
