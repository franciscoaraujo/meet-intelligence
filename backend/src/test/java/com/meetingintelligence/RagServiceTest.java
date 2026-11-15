package com.meetingintelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.dto.ChatMessageRequest;
import com.meetingintelligence.application.dto.ChatMessageResponse;
import com.meetingintelligence.application.service.ai.RagService;
import com.meetingintelligence.domain.ai.EmbeddingProvider;
import com.meetingintelligence.domain.ai.LLMResponse;
import com.meetingintelligence.domain.entity.*;
import com.meetingintelligence.domain.enums.SenderType;
import com.meetingintelligence.infrastructure.ai.AIOrchestrator;
import com.meetingintelligence.infrastructure.persistence.repository.*;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import com.pgvector.PGvector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock
    private MeetingRepository meetingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AIConversationRepository conversationRepository;

    @Mock
    private AIMessageRepository messageRepository;

    @Mock
    private MeetingSummaryRepository summaryRepository;

    @Mock
    private MeetingEmbeddingRepository embeddingRepository;

    @Mock
    private EmbeddingProvider embeddingProvider;

    @Mock
    private AIOrchestrator aiOrchestrator;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private com.meetingintelligence.infrastructure.persistence.repository.TranscriptSegmentRepository segmentRepository;

    @Mock
    private com.meetingintelligence.application.service.ai.KnowledgeBaseService knowledgeBaseService;

    @InjectMocks
    private RagService ragService;

    @Test
    @DisplayName("Deve processar pergunta com RAG, recuperar chunks relevantes via pgvector e retornar resposta com citações")
    void shouldProcessUserMessageWithRag() {
        UUID meetingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        Organization org = Organization.builder().id(orgId).name("SaaS Corp").build();
        User user = User.builder().id(userId).email("user@saas.com").fullName("User").build();
        Meeting meeting = Meeting.builder().id(meetingId).title("Alinhamento Técnico").organization(org).build();

        UserPrincipal principal = UserPrincipal.builder()
                .id(userId)
                .email("user@saas.com")
                .organizationId(orgId)
                .role("OWNER")
                .build();

        when(meetingRepository.findById(meetingId)).thenReturn(Optional.of(meeting));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        AIConversation conversation = AIConversation.builder()
                .id(UUID.randomUUID())
                .meeting(meeting)
                .user(user)
                .build();
        when(conversationRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(conversation));

        float[] mockVector = new float[1536];
        when(embeddingProvider.generateEmbedding("Quais decisões foram tomadas?")).thenReturn(mockVector);

        MeetingEmbedding mockEmbedding = MeetingEmbedding.builder()
                .id(UUID.randomUUID())
                .meeting(meeting)
                .embedding(new PGvector(mockVector).toString())
                .textPreview("Foi decidido utilizar o PostgreSQL com pgvector para RAG.")
                .startTimeMs(5000L)
                .endTimeMs(12000L)
                .build();

        when(embeddingRepository.findSimilarChunks(eq(meetingId), any(), eq(4))).thenReturn(List.of(mockEmbedding));

        LLMResponse mockLlmResponse = LLMResponse.builder()
                .providerName("GEMINI")
                .modelName("gemini-3.5-flash-lite")
                .content("Foi decidido utilizar o PostgreSQL com pgvector para implementar a busca semântica do RAG.")
                .promptTokens(180)
                .completionTokens(60)
                .totalTokens(240)
                .estimatedCostUsd(BigDecimal.valueOf(0.00003))
                .latencyMs(250L)
                .build();

        when(aiOrchestrator.execute(any(), eq(meeting), eq("RAG_CHAT"))).thenReturn(mockLlmResponse);

        when(messageRepository.save(any(AIMessage.class))).thenAnswer(inv -> {
            AIMessage msg = inv.getArgument(0);
            msg.setId(UUID.randomUUID());
            return msg;
        });

        ChatMessageRequest request = ChatMessageRequest.builder()
                .message("Quais decisões foram tomadas?")
                .build();

        ChatMessageResponse response = ragService.processUserMessage(meetingId, request, principal);

        assertNotNull(response);
        assertEquals(SenderType.ASSISTANT, response.getSenderType());
        assertTrue(response.getContent().contains("pgvector"));
        assertEquals(1, response.getCitations().size());
        assertEquals(5000L, response.getCitations().get(0).getStartTimeMs());
        assertEquals(12000L, response.getCitations().get(0).getEndTimeMs());

        verify(messageRepository, times(2)).save(any(AIMessage.class)); // 1 do usuário, 1 do assistente
    }
}

