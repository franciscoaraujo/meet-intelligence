package com.meetingintelligence.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationDto {
    private UUID chunkId;
    private Long startTimeMs;
    private Long endTimeMs;
    private String textSnippet;
}

