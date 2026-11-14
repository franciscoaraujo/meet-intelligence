package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.CalendarEventDto;
import com.meetingintelligence.application.service.CalendarService;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/calendar")
@RequiredArgsConstructor
@Tag(name = "Google Calendar & Google Meet", description = "Sincronização estrita de reuniões agendadas no Google Meet")
public class CalendarController {

    private final CalendarService calendarService;

    @GetMapping("/events")
    @Operation(summary = "Listar reuniões agendadas no Google Meet", description = "Retorna exclusivamente eventos reais agendados que possuem link do Google Meet")
    public ResponseEntity<List<CalendarEventDto>> getUpcomingEvents(@AuthenticationPrincipal UserPrincipal currentUser) {
        List<CalendarEventDto> events = calendarService.listUpcomingEvents(
                currentUser.getId(),
                currentUser.getOrganizationId()
        );
        return ResponseEntity.ok(events);
    }

    @GetMapping("/status")
    @Operation(summary = "Status da conexão com Google Calendar", description = "Verifica se o usuário possui token OAuth do Google Calendar conectado")
    public ResponseEntity<Map<String, Boolean>> getConnectionStatus(@AuthenticationPrincipal UserPrincipal currentUser) {
        boolean connected = calendarService.isGoogleCalendarConnected(currentUser.getId());
        return ResponseEntity.ok(Map.of("connected", connected));
    }

    @PostMapping("/connect")
    @Operation(summary = "Conectar token OAuth do Google Calendar", description = "Salva o Access Token OAuth2 do Google e sincroniza as reuniões agendadas no Google Meet")
    public ResponseEntity<List<CalendarEventDto>> connectGoogleCalendar(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestBody ConnectCalendarRequest request) {
        List<CalendarEventDto> events = calendarService.connectAndSyncGoogleCalendar(
                currentUser.getId(),
                currentUser.getOrganizationId(),
                request.getAccessToken()
        );
        return ResponseEntity.ok(events);
    }

    @Data
    public static class ConnectCalendarRequest {
        private String accessToken;
    }
}
