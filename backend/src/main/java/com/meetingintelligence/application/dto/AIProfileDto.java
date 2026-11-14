package com.meetingintelligence.application.dto;

import com.meetingintelligence.domain.enums.MeetingType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIProfileDto {
    private UUID id;
    private UUID organizationId;
    @NotBlank(message = "O nome do perfil é obrigatório")
    private String name;
    @NotNull(message = "O tipo de reunião é obrigatório")
    private MeetingType meetingType;
    private String systemPromptOverride;
    private String focusCategories;
    private boolean isDefault;
}

