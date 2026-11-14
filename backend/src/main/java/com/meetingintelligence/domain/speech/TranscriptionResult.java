package com.meetingintelligence.domain.speech;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscriptionResult {
    private String provider;
    private String detectedLanguage;
    private Long durationMs;
    private List<TranscriptionSegmentDto> segments;
    private String fullText;
}

