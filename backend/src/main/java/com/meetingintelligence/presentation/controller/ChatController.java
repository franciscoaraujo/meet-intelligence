package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.ChatMessageRequest;
import com.meetingintelligence.application.dto.ChatMessageResponse;
import com.meetingintelligence.application.service.ai.RagService;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/chat")
@RequiredArgsConstructor
@Tag(name = "Chat RAG Interativo", description = "Assistente conversacional contextualizado na reunião via RAG com pgvector")
public class ChatController {

    private final RagService ragService;

    @PostMapping
    @Operation(summary = "Conversar com a reunião (RAG)", description = "Envia uma pergunta do usuário e recebe a resposta fundamentada com citações")
    public ResponseEntity<ChatMessageResponse> askQuestion(
            @PathVariable UUID meetingId,
            @Valid @RequestBody ChatMessageRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        ChatMessageResponse response = ragService.processUserMessage(meetingId, request, currentUser);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(summary = "Obter histórico do chat da reunião", description = "Recupera todas as mensagens trocadas com o assistente na reunião")
    public ResponseEntity<List<ChatMessageResponse>> getChatHistory(@PathVariable UUID meetingId) {
        List<ChatMessageResponse> history = ragService.getConversationHistory(meetingId);
        return ResponseEntity.ok(history);
    }
}

