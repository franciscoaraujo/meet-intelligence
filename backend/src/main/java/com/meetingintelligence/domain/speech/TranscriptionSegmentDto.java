package com.meetingintelligence.domain.speech;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscriptionSegmentDto {
    private String speakerLabel;
    private Integer speakerTag;
    private String text;
    private Long startTimeMs;
    private Long endTimeMs;
    private BigDecimal confidence;
    private String language;
}

