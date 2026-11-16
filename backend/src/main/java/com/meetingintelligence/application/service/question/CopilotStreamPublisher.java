package com.meetingintelligence.application.service.question;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publica os eventos de streaming do copiloto em {@code /topic/meetings/{id}/copilot} (spec 008, RF-03).
 * O texto das respostas nunca vai para o log; só tipo, tentativa e tamanho.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CopilotStreamPublisher {

    public enum Type { STARTED, DELTA, REJECTED, COMPLETED, FAILED }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(Type type, UUID questionId, int attempt, long seq, String text, String reason, UUID insightId) {}

    private final SimpMessagingTemplate messaging;

    public Session open(UUID meetingId, UUID questionId) {
        return new Session(meetingId, questionId);
    }

    /** Eventos de uma execução do copiloto para uma pergunta; {@code seq} cresce em todas as tentativas. */
    public final class Session {
        private final String destination;
        private final UUID questionId;
        private long seq;
        private int attempt;

        private Session(UUID meetingId, UUID questionId) {
            this.destination = "/topic/meetings/" + meetingId + "/copilot";
            this.questionId = questionId;
        }

        public void started(int attempt) {
            this.attempt = attempt;
            send(Type.STARTED, null, null, null);
        }

        public void delta(String text) {
            send(Type.DELTA, text, null, null);
        }

        public void rejected(String reason) {
            send(Type.REJECTED, null, reason, null);
        }

        public void completed(UUID insightId) {
            send(Type.COMPLETED, null, null, insightId);
        }

        public void failed(String reason) {
            send(Type.FAILED, null, reason, null);
        }

        private void send(Type type, String text, String reason, UUID insightId) {
            Event event = new Event(type, questionId, attempt, seq++, text, reason, insightId);
            try {
                messaging.convertAndSend(destination, event);
            } catch (Exception e) {
                // Falha no WebSocket não pode interromper a geração; o estado final chega por /questions e REST
                log.warn("Erro ao publicar evento {} do copiloto: {}", type, e.getClass().getSimpleName());
            }
        }
    }
}
