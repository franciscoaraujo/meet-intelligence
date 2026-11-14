package com.meetingintelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.dto.GoogleAuthRequest;
import com.meetingintelligence.application.service.AuthService;
import com.meetingintelligence.presentation.controller.AuthController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private com.meetingintelligence.infrastructure.security.JwtService jwtService;

    @Test
    @DisplayName("Deve aceitar requisição de autenticação Google com credencial válida")
    void shouldAcceptGoogleAuthRequest() throws Exception {
        GoogleAuthRequest request = GoogleAuthRequest.builder()
                .credential("test.credential.jwt")
                .organizationName("Tech Enterprise")
                .build();

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Deve rejeitar requisição de autenticação sem credencial")
    void shouldRejectEmptyGoogleAuthRequest() throws Exception {
        GoogleAuthRequest request = GoogleAuthRequest.builder()
                .credential("")
                .build();

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
