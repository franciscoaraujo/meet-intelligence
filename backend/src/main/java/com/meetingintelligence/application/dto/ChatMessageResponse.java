package com.meetingintelligence.application.dto;

import com.meetingintelligence.domain.enums.SenderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageResponse {
    private UUID id;
    private SenderType senderType;
    private String content;
    private List<CitationDto> citations;
    private OffsetDateTime createdAt;
}

