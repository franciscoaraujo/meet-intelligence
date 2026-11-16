package com.meetingintelligence.presentation.security;

import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guarda central das rotas de reunião (spec 007, RF-11): toda requisição a
 * {@code /api/v1/meetings/{meetingId}} ou {@code /api/v1/meetings/{meetingId}/**} só passa se a reunião é
 * da organização do usuário. Reunião de outra organização ou inexistente → 404 (RN-02).
 * Vale para os endpoints atuais e para os que vierem, sem depender de cada controller lembrar.
 */
@Component
@Slf4j
public class MeetingAccessGuard implements HandlerInterceptor {

    static final Pattern MEETING_PATH = Pattern.compile("^/api/v1/meetings/([0-9a-fA-F-]{36})(/.*)?$");

    private final MeetingRepository meetings;

    public MeetingAccessGuard(MeetingRepository meetings) {
        this.meetings = meetings;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Matcher m = MEETING_PATH.matcher(path);
        if (!m.matches()) return true;

        UUID meetingId;
        try {
            meetingId = UUID.fromString(m.group(1));
        } catch (IllegalArgumentException e) {
            return notFound(response);
        }
        UUID orgId = organizationOfCurrentUser();
        if (orgId == null || !meetings.existsByIdAndOrganization_Id(meetingId, orgId)) {
            log.warn("Acesso negado à reunião {} (organização do usuário: {})", meetingId, orgId);
            return notFound(response);
        }
        return true;
    }

    private static UUID organizationOfCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal user) {
            return user.getOrganizationId();
        }
        return null;
    }

    private static boolean notFound(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":404,\"error\":\"Not Found\",\"message\":\"Reunião não encontrada.\"}");
        return false;
    }
}
