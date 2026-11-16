package com.meetingintelligence.application.service.question;

import com.meetingintelligence.application.service.ai.PromptManagerService;
import com.meetingintelligence.domain.entity.TranscriptSegment;
import com.meetingintelligence.domain.enums.AnswerStyle;
import com.meetingintelligence.domain.enums.InterviewRole;
import com.meetingintelligence.domain.language.InterviewLanguage;
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

    static final String PROMPT_RESOURCE = "prompts/interview-answer.v2.md";
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
        return userPrompt(question, utteranceType, score, recentSegments, interviewRole, summary, knowledgeBase, correction, false, null);
    }

    public String userPrompt(String question, String utteranceType, double score, List<TranscriptSegment> recentSegments,
                             InterviewRole interviewRole, String summary, String knowledgeBase, String correction,
                             boolean manualTrigger) {
        return userPrompt(question, utteranceType, score, recentSegments, interviewRole, summary, knowledgeBase, correction,
                manualTrigger, null);
    }

    public String userPrompt(String question, String utteranceType, double score, List<TranscriptSegment> recentSegments,
                             InterviewRole interviewRole, String summary, String knowledgeBase, String correction,
                             boolean manualTrigger, String languageTag) {
        return userPrompt(question, utteranceType, score, recentSegments, interviewRole, summary, knowledgeBase, correction,
                manualTrigger, languageTag, null);
    }

    /** Spec 014: o estilo muda o tom, nunca os fatos nem o tamanho da resposta falada. */
    static String styleInstruction(AnswerStyle style) {
        return "<answer_style>\n" + style.instruction() + " O estilo muda só o tom: os fatos continuam restritos ao "
                + "<knowledge_base>, e \"spoken_answer\" continua com 60 a 140 palavras.\n</answer_style>";
    }

    /** Spec 011: em inglês/francês, a resposta inteira sai no idioma da entrevista. */
    static String languageInstruction(InterviewLanguage language) {
        return "<answer_language>\nA entrevista é em " + language.namePt() + " (" + language.tag() + "). Ignore a instrução "
                + "de escrever em português: escreva \"spoken_answer\", todas as \"sections\", \"question\" e \"assumptions\" "
                + "em " + language.namePt() + ", com naturalidade de quem fala o idioma. Mantenha as chaves do JSON e os "
                + "valores de \"depth\" como estão.\n</answer_language>";
    }

    /** Instrução do botão "Pergunta terminou": o trecho pode ter comentários antes da pergunta. */
    static final String MANUAL_TRIGGER_INSTRUCTION = "<manual_trigger>\nO candidato apertou o botão \"Pergunta terminou\": "
            + "o trecho em <question> é tudo o que o entrevistador disse desde a pergunta anterior e pode começar com "
            + "comentários, elogios ou contexto. Identifique a pergunta feita por último (normalmente no fim do trecho), "
            + "escreva-a corrigida e completa no campo \"question\" e responda a ela. Neste caso "
            + "\"is_question_for_candidate\" deve ser true.\n</manual_trigger>";

    public String userPrompt(String question, String utteranceType, double score, List<TranscriptSegment> recentSegments,
                             InterviewRole interviewRole, String summary, String knowledgeBase, String correction,
                             boolean manualTrigger, String languageTag, AnswerStyle style) {
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
        if (manualTrigger) {
            sb.append("\n\n").append(MANUAL_TRIGGER_INSTRUCTION);
        }
        if (style != null && style.instruction() != null) {
            sb.append("\n\n").append(styleInstruction(style));
        }
        InterviewLanguage language = InterviewLanguage.fromTag(languageTag);
        if (language != InterviewLanguage.PT_BR) {
            sb.append("\n\n").append(languageInstruction(language));
        }
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
