package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.AuthResponse;
import com.meetingintelligence.application.dto.GoogleAuthRequest;
import com.meetingintelligence.application.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Autenticação", description = "Endpoints de autenticação e integração Google OAuth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/google")
    @Operation(summary = "Autenticar via Google OAuth", description = "Recebe a credencial/token do Google e retorna o JWT da aplicação")
    public ResponseEntity<AuthResponse> loginWithGoogle(@Valid @RequestBody GoogleAuthRequest request) {
        AuthResponse response = authService.authenticateWithGoogle(request);
        return ResponseEntity.ok(response);
    }
}

