package com.meetingintelligence.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long expirationHours;

    public JwtService(
            @Value("${app.jwt.secret:}") String secret,
            @Value("${app.jwt.expiration-hours:24}") long expirationHours) {
        this.secretKey = Keys.hmacShaKeyFor(secretBytes(secret));
        this.expirationHours = expirationHours;
    }

    /**
     * Spec 007 RF-06: sem segredo configurado (ou curto demais), usa um aleatório gerado agora. Os tokens
     * deixam de valer a cada reinício, mas nenhum segredo conhecido (do código ou do Git) assina tokens.
     */
    static byte[] secretBytes(String secret) {
        byte[] configured = secret == null ? new byte[0] : secret.trim().getBytes(StandardCharsets.UTF_8);
        if (configured.length >= 32) return configured;
        org.slf4j.LoggerFactory.getLogger(JwtService.class).warn(
                "JWT_SECRET ausente ou com menos de 32 bytes: usando um segredo aleatório. Os logins deixam de "
                        + "valer quando o backend reiniciar. Defina JWT_SECRET (no backend/.env em desenvolvimento).");
        byte[] random = new byte[64];
        new java.security.SecureRandom().nextBytes(random);
        return random;
    }

    public String generateToken(UUID userId, String email, UUID organizationId, String role) {
        Instant now = Instant.now();
        Instant expiry = now.plus(expirationHours, ChronoUnit.HOURS);

        return Jwts.builder()
                .subject(userId.toString())
                .claims(Map.of(
                        "email", email,
                        "orgId", organizationId != null ? organizationId.toString() : "",
                        "role", role != null ? role : "MEMBER"
                ))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Token válido → usuário autenticado; inválido, expirado ou sem organização → null (spec 007, RN-03).
     * Usado pelo filtro HTTP e pelo CONNECT do WebSocket.
     */
    public UserPrincipal toPrincipal(String token) {
        if (token == null || token.isBlank() || !isTokenValid(token)) return null;
        try {
            Claims claims = extractClaims(token);
            String orgId = claims.get("orgId", String.class);
            if (orgId == null || orgId.isBlank()) return null;
            String role = claims.get("role", String.class);
            return UserPrincipal.builder()
                    .id(UUID.fromString(claims.getSubject()))
                    .email(claims.get("email", String.class))
                    .organizationId(UUID.fromString(orgId))
                    .role(role != null ? role : "MEMBER")
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }
}

