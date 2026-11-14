package com.meetingintelligence.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoogleAuthRequest {
    @NotBlank(message = "O ID token ou auth code do Google é obrigatório")
    private String credential; // Google ID Token ou Access Token
    private String accessToken; // OAuth2 Access Token com escopo calendar.readonly
    private String organizationName;
}
