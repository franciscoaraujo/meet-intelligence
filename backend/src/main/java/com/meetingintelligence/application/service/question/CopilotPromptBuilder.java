package com.meetingintelligence.application.service.question;

import com.meetingintelligence.application.service.ai.PromptManagerService;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.domain.enums.InterviewRole;
import com.meetingintelligence.domain.speech.SpeakerRole;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Monta os prompts do copiloto (spec 004): system versionado em arquivo + contexto rotulado por papel. */
@Component
public class CopilotPromptBuilder {

    static final String PROMPT_RESOURCE = "prompts/interview-answer.v1.md";
    static final int CONTEXT_SEGMENTS = 8;

    private final String systemPrompt;

    public CopilotPromptBuilder() {
        try {
            this.systemPrompt = new ClassPathResource(PROMPT_RESOURCE).getContentAsString(StandardCharsets.UTF_8)
                    + "\n" + PromptManagerService.KNOWLEDGE_BASE_DIRECTIVES;
        } catch (IOException e) {
            throw new UncheckedIOException("Prompt do copiloto não encontrado: " + PROMPT_RESOURCE, e);
        }
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public String userPrompt(String question, String utteranceType, double score, List<TranscriptSegment> recentSegments,
                             InterviewRole interviewRole, String summary, String knowledgeBase, String correction) {
        StringBuilder conversation = new StringBuilder();
        int start = Math.max(0, recentSegments.size() - CONTEXT_SEGMENTS);
        for (TranscriptSegment s : recentSegments.subList(start, recentSegments.size())) {
            if (s.isEchoSuspected()) continue;
            String text = s.getCorrectedText() != null ? s.getCorrectedText() : s.getOriginalText();
            conversation.append('[').append(roleLabel(s.getSpeakerRole(), interviewRole)).append("]: ")
                    .append(text).append('\n');
        }
        StringBuilder sb = new StringBuilder();
        if (knowledgeBase != null && !knowledgeBase.isBlank()) {
            sb.append(knowledgeBase).append("\n\n");
        }
        sb.append("<meeting_summary>\n").append(summary == null || summary.isBlank() ? "Sem resumo ainda." : summary)
                .append("\n</meeting_summary>\n\n");
        sb.append("<conversation>\n").append(conversation).append("</conversation>\n\n");
        sb.append("<question type=\"").append(utteranceType).append("\" score=\"").append(score).append("\">\n")
                .append(question).append("\n</question>");
        if (correction != null && !correction.isBlank()) {
            sb.append("\n\nSua resposta anterior foi rejeitada: ").append(correction)
                    .append(". Corrija e responda novamente somente com o JSON.");
        }
        return sb.toString();
    }

    /** SELF/REMOTE → candidato/entrevistador conforme o papel do usuário na entrevista (spec 003). */
    static String roleLabel(SpeakerRole role, InterviewRole interviewRole) {
        if (role == null || role == SpeakerRole.UNKNOWN) return "DESCONHECIDO";
        boolean userIsCandidate = interviewRole == null || interviewRole == InterviewRole.CANDIDATE;
        if (role == SpeakerRole.SELF) return userIsCandidate ? "CANDIDATO" : "ENTREVISTADOR";
        return userIsCandidate ? "ENTREVISTADOR" : "CANDIDATO";
    }
}
