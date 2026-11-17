package com.meetingintelligence.application.service;

import com.meetingintelligence.application.dto.AuthResponse;
import com.meetingintelligence.application.exception.AuthenticationFailedException;
import com.meetingintelligence.application.dto.GoogleAuthRequest;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.entity.OrganizationMember;
import com.meetingintelligence.domain.entity.User;
import com.meetingintelligence.domain.enums.OrganizationRole;
import com.meetingintelligence.domain.enums.PlanType;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationMemberRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationRepository;
import com.meetingintelligence.infrastructure.persistence.repository.UserRepository;
import com.meetingintelligence.infrastructure.security.GoogleTokenVerifier;
import com.meetingintelligence.infrastructure.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Login com Google (spec 007): o token só vale se o Google confirmar que é legítimo, emitido para o
 * nosso Client ID e com e-mail verificado ({@link GoogleTokenVerifier}). O login de demonstração só
 * existe com {@code app.auth.demo-login-enabled=true} e só para o e-mail fixo.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final JwtService jwtService;
    private final GoogleTokenVerifier googleTokens;

    @Value("${app.auth.demo-login-enabled:false}")
    private boolean demoLoginEnabled;

    @Value("${app.auth.demo-email:demo.user@meetingintelligence.ai}")
    private String demoEmail;

    @Value("${spring.security.oauth2.client.registration.google.client-id:}")
    private String configuredGoogleClientId;

    private volatile String runtimeGoogleClientId;

    public String getGoogleClientId() {
        if (runtimeGoogleClientId != null && !runtimeGoogleClientId.isBlank()) {
            return runtimeGoogleClientId;
        }
        return configuredGoogleClientId;
    }

    public void setGoogleClientId(String clientId) {
        this.runtimeGoogleClientId = clientId;
    }

    /** Spec 007 RF-02: login de demonstração, desligado por padrão e só para o e-mail fixo. */
    public boolean isDemoLoginEnabled() {
        return demoLoginEnabled;
    }

    @Transactional
    public AuthResponse authenticateWithGoogle(GoogleAuthRequest request) {
        String credential = request.getCredential() != null ? request.getCredential().trim() : "";
        String oauthAccessToken = request.getAccessToken();
        if ((oauthAccessToken == null || oauthAccessToken.isBlank()) && credential.startsWith("ya29.")) {
            oauthAccessToken = credential;
        }

        String email;
        String fullName;
        String avatarUrl = null;
        String googleId = null;

        if (oauthAccessToken != null && !oauthAccessToken.isBlank()) {
            // RF-04: Access Token conferido no Google (aud/azp) antes de ler o perfil
            GoogleTokenVerifier.GoogleIdentity id = googleTokens.verifyAccessToken(oauthAccessToken.trim(), getGoogleClientId());
            email = id.email();
            fullName = id.name();
            avatarUrl = id.picture();
            googleId = id.subject();
        } else if (demoLoginEnabled && credential.equalsIgnoreCase(demoEmail)) {
            // RF-02: demonstração só com a configuração ligada e só para o e-mail fixo. Vem antes do
            // ID Token porque o e-mail de demonstração também tem três partes separadas por ponto.
            email = demoEmail;
            fullName = "Demonstração Corporativa";
            log.warn("Login de demonstração usado (app.auth.demo-login-enabled=true)");
        } else if (!credential.contains("@") && credential.split("\\.").length == 3) {
            // RF-03: ID Token (JWT) só vale se o Google confirmar; sem decodificação local
            GoogleTokenVerifier.GoogleIdentity id = googleTokens.verifyIdToken(credential, getGoogleClientId());
            email = id.email();
            fullName = id.name();
            avatarUrl = id.picture();
            googleId = id.subject();
        } else {
            // RF-01: um e-mail qualquer não é credencial
            throw new AuthenticationFailedException("Credencial do Google inválida. Entre com sua conta Google.");
        }

        if (email == null || email.isBlank()) {
            throw new AuthenticationFailedException("Não foi possível obter o e-mail da sua conta Google.");
        }

        final String finalEmail = email;
        final String finalFullName = fullName != null ? fullName : email.split("@")[0];
        final String finalAvatarUrl = avatarUrl;
        final String finalGoogleId = googleId;
        final String finalAccessToken = oauthAccessToken;

        // 1. Busca ou cria o Usuário com os dados reais da conta Google
        User user = userRepository.findByEmail(finalEmail)
                .map(existing -> {
                    if (finalFullName != null) existing.setFullName(finalFullName);
                    if (finalAvatarUrl != null) existing.setAvatarUrl(finalAvatarUrl);
                    if (finalGoogleId != null) existing.setGoogleId(finalGoogleId);
                    if (finalAccessToken != null && !finalAccessToken.isBlank()) {
                        existing.setGoogleRefreshToken(finalAccessToken);
                    }
                    return userRepository.save(existing);
                })
                .orElseGet(() -> userRepository.save(User.builder()
                        .email(finalEmail)
                        .fullName(finalFullName)
                        .avatarUrl(finalAvatarUrl)
                        .googleId(finalGoogleId)
                        .googleRefreshToken(finalAccessToken)
                        .build()));

        // 2. Busca ou cria a Organização primária do usuário
        OrganizationMember membership = organizationMemberRepository.findByUserId(user.getId())
                .stream()
                .findFirst()
                .orElseGet(() -> {
                    String orgName = request.getOrganizationName() != null && !request.getOrganizationName().isBlank()
                            ? request.getOrganizationName()
                            : user.getFullName() + " Workspace";

                    String slug = orgName.toLowerCase().replaceAll("[^a-z0-9]", "-") + "-" + UUID.randomUUID().toString().substring(0, 8);

                    Organization newOrg = organizationRepository.save(Organization.builder()
                            .name(orgName)
                            .slug(slug)
                            .planType(PlanType.FREE)
                            .build());

                    return organizationMemberRepository.save(OrganizationMember.builder()
                            .organization(newOrg)
                            .user(user)
                            .role(OrganizationRole.OWNER)
                            .build());
                });

        Organization org = membership.getOrganization();

        // 3. Emite o JWT da aplicação
        String jwtToken = jwtService.generateToken(
                user.getId(),
                user.getEmail(),
                org.getId(),
                membership.getRole().name()
        );

        return AuthResponse.builder()
                .token(jwtToken)
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .avatarUrl(user.getAvatarUrl())
                .organizationId(org.getId())
                .organizationName(org.getName())
                .role(membership.getRole().name())
                .build();
    }
}
