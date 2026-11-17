package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.AuthResponse;
import com.meetingintelligence.application.dto.GoogleAuthRequest;
import com.meetingintelligence.application.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller responsável pela autenticação via Google OAuth 2.0 (ID Token e Access Token)
 * e pelo gerenciamento dinâmico do Google OAuth Client ID.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Autenticação", description = "Endpoints de autenticação real com Google OAuth 2.0")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/google")
    @Operation(summary = "Autenticar via Google OAuth 2.0", description = "Valida o Access Token ou ID Token real nos servidores do Google e emite o JWT da aplicação")
    public ResponseEntity<AuthResponse> loginWithGoogle(@Valid @RequestBody GoogleAuthRequest request) {
        AuthResponse response = authService.authenticateWithGoogle(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/google/config")
    @Operation(summary = "Obter configuração pública do Google OAuth Client ID")
    public ResponseEntity<Map<String, Object>> getGoogleConfig() {
        String clientId = authService.getGoogleClientId();
        boolean configured = clientId != null && !clientId.isBlank() && !clientId.equals("test-client-id");
        return ResponseEntity.ok(Map.of(
                "clientId", configured ? clientId : "",
                "configured", configured,
                // Spec 007 RF-02: a tela de login só mostra a demonstração quando ela está ligada
                "demoLoginEnabled", authService.isDemoLoginEnabled()
        ));
    }

    @PostMapping("/google/config")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Configurar dinamicamente o Google OAuth Client ID",
            description = "Spec 007 RF-05: exige login com papel OWNER ou ADMIN")
    public ResponseEntity<Map<String, Object>> updateGoogleConfig(@RequestBody Map<String, String> body) {
        String clientId = body.getOrDefault("clientId", "").trim();
        authService.setGoogleClientId(clientId);
        boolean configured = !clientId.isBlank() && !clientId.equals("test-client-id");
        return ResponseEntity.ok(Map.of(
                "clientId", clientId,
                "configured", configured
        ));
    }
}
