package com.meetingintelligence.infrastructure.websocket;

import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.security.JwtService;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Segurança do tempo real (spec 007, RF-07..RF-09):
 * <ul>
 *   <li>CONNECT exige {@code Authorization: Bearer <token>} válido;</li>
 *   <li>SUBSCRIBE só em {@code /topic/meetings/{id}/<canal>} de reunião da organização do token;</li>
 *   <li>SEND do cliente é sempre recusado: os eventos saem só do servidor.</li>
 * </ul>
 * Qualquer recusa vira um frame ERROR do STOMP e encerra a sessão.
 */
@Component
@Slf4j
public class StompSecurityInterceptor implements ChannelInterceptor {

    static final Pattern MEETING_TOPIC = Pattern.compile(
            "^/topic/meetings/([0-9a-fA-F-]{36})/(transcripts|insights|questions|copilot|summary)$");

    private final JwtService jwt;
    private final MeetingRepository meetings;

    public StompSecurityInterceptor(JwtService jwt, MeetingRepository meetings) {
        this.jwt = jwt;
        this.meetings = meetings;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;

        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> authenticate(accessor);
            case SUBSCRIBE -> authorizeSubscription(accessor);
            case SEND -> throw deny("Clientes não publicam mensagens");
            default -> { /* DISCONNECT, UNSUBSCRIBE, ACK, NACK: sem dado sensível */ }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
        UserPrincipal principal = jwt.toPrincipal(token);
        if (principal == null) {
            throw deny("Login necessário");
        }
        accessor.setUser(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        UserPrincipal user = principalOf(accessor.getUser());
        if (user == null) {
            throw deny("Login necessário");
        }
        String destination = accessor.getDestination();
        Matcher m = destination == null ? null : MEETING_TOPIC.matcher(destination);
        if (m == null || !m.matches()) {
            throw deny("Destino não permitido");
        }
        UUID meetingId = UUID.fromString(m.group(1));
        if (!meetings.existsByIdAndOrganization_Id(meetingId, user.getOrganizationId())) {
            // RN-02: não diz se a reunião existe em outra organização
            throw deny("Reunião não encontrada");
        }
    }

    private static UserPrincipal principalOf(Principal p) {
        if (p instanceof UsernamePasswordAuthenticationToken auth && auth.getPrincipal() instanceof UserPrincipal up) {
            return up;
        }
        return null;
    }

    private static MessageDeliveryException deny(String reason) {
        log.warn("WebSocket recusado: {}", reason);
        return new MessageDeliveryException(reason);
    }
}
