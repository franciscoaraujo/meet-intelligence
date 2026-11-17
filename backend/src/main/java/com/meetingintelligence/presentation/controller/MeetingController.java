package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.CreateMeetingRequest;
import com.meetingintelligence.application.dto.MeetingResponse;
import com.meetingintelligence.application.service.MeetingService;
import com.meetingintelligence.domain.enums.MeetingStatus;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
@Tag(name = "Reuniões", description = "Gerenciamento e ciclo de vida das reuniões")
public class MeetingController {

    private final MeetingService meetingService;

    @PostMapping
    @Operation(summary = "Criar reunião", description = "Registra uma nova reunião vinculada à organização do usuário")
    public ResponseEntity<MeetingResponse> createMeeting(
            @Valid @RequestBody CreateMeetingRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        MeetingResponse response = meetingService.createMeeting(request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "Listar reuniões da organização",
            description = "Retorna reuniões paginadas; por padrão só as ativas, archived=true traz as arquivadas (spec 020)")
    public ResponseEntity<Page<MeetingResponse>> listMeetings(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam(defaultValue = "false") boolean archived,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<MeetingResponse> page = meetingService.listMeetings(currentUser.getOrganizationId(), archived, pageable);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obter reunião por ID", description = "Retorna os detalhes de uma reunião específica")
    public ResponseEntity<MeetingResponse> getMeetingById(@PathVariable UUID id) {
        MeetingResponse response = meetingService.getMeetingById(id);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Atualizar status da reunião", description = "Modifica o status (ex: IN_PROGRESS, COMPLETED)")
    public ResponseEntity<MeetingResponse> updateStatus(
            @PathVariable UUID id,
            @RequestParam MeetingStatus status) {
        MeetingResponse response = meetingService.updateMeetingStatus(id, status);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/interview-role")
    @Operation(summary = "Definir seu papel na entrevista", description = "CANDIDATE (padrão) ou INTERVIEWER")
    public ResponseEntity<MeetingResponse> updateInterviewRole(
            @PathVariable UUID id,
            @RequestParam com.meetingintelligence.domain.enums.InterviewRole role,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.meetingintelligence.infrastructure.security.UserPrincipal currentUser) {
        return ResponseEntity.ok(meetingService.updateInterviewRole(id, currentUser.getOrganizationId(), role));
    }

    @PatchMapping("/{id}/language")
    @Operation(summary = "Definir o idioma da entrevista", description = "pt-BR, en-US ou fr-FR (spec 011)")
    public ResponseEntity<MeetingResponse> updateLanguage(
            @PathVariable UUID id,
            @RequestParam String language,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.meetingintelligence.infrastructure.security.UserPrincipal currentUser) {
        return ResponseEntity.ok(meetingService.updateLanguage(id, currentUser.getOrganizationId(), language));
    }

    @PatchMapping("/{id}/answer-style")
    @Operation(summary = "Definir o estilo das respostas do copiloto", description = "NEUTRAL, INFORMAL, FORMAL ou TECHNICAL (spec 014)")
    public ResponseEntity<MeetingResponse> updateAnswerStyle(
            @PathVariable UUID id,
            @RequestParam String style,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.meetingintelligence.infrastructure.security.UserPrincipal currentUser) {
        return ResponseEntity.ok(meetingService.updateAnswerStyle(id, currentUser.getOrganizationId(), style));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Arquivar reunião", description = "Spec 020: sai da lista principal; nenhum dado é apagado")
    public ResponseEntity<MeetingResponse> archive(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(meetingService.setArchived(id, currentUser.getOrganizationId(), true));
    }

    @PostMapping("/{id}/unarchive")
    @Operation(summary = "Restaurar reunião arquivada", description = "Spec 020: volta para a lista principal")
    public ResponseEntity<MeetingResponse> unarchive(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(meetingService.setArchived(id, currentUser.getOrganizationId(), false));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Excluir reunião definitivamente (LGPD)", description = "Exclusão em cascata de gravações, transcrições e análises")
    public ResponseEntity<Void> deleteMeeting(@PathVariable UUID id) {
        meetingService.deleteMeeting(id);
        return ResponseEntity.noContent().build();
    }
}

