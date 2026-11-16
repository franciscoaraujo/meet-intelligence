package com.meetingintelligence.security;

import com.meetingintelligence.application.dto.GoogleAuthRequest;
import com.meetingintelligence.application.exception.AuthenticationFailedException;
import com.meetingintelligence.application.service.AuthService;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.entity.OrganizationMember;
import com.meetingintelligence.domain.entity.User;
import com.meetingintelligence.domain.enums.OrganizationRole;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationMemberRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationRepository;
import com.meetingintelligence.infrastructure.persistence.repository.UserRepository;
import com.meetingintelligence.infrastructure.security.GoogleTokenVerifier;
import com.meetingintelligence.infrastructure.security.JwtService;
import com.meetingintelligence.infrastructure.security.SecretCipher;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 007: login (RF-01/RF-02), segredo do token (RF-06) e criptografia das chaves (RF-14). */
class AuthAndSecretsTest {

    @Nested
    @DisplayName("Login")
    class Login {
        private final UserRepository users = mock(UserRepository.class);
        private final OrganizationRepository orgs = mock(OrganizationRepository.class);
        private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
        private final GoogleTokenVerifier google = mock(GoogleTokenVerifier.class);
        private final JwtService jwt = new JwtService("segredo-de-teste-com-mais-de-32-bytes-0123456789", 1);

        private AuthService service(boolean demoEnabled) {
            AuthService s = new AuthService(users, orgs, members, jwt, google);
            ReflectionTestUtils.setField(s, "demoLoginEnabled", demoEnabled);
            ReflectionTestUtils.setField(s, "demoEmail", "demo.user@meetingintelligence.ai");
            ReflectionTestUtils.setField(s, "configuredGoogleClientId", "nosso-app");
            when(users.findByEmail(anyString())).thenReturn(Optional.empty());
            when(users.save(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                if (u.getId() == null) u.setId(UUID.randomUUID());
                return u;
            });
            Organization org = Organization.builder().id(UUID.randomUUID()).name("Org").build();
            when(members.findByUserId(any())).thenReturn(List.of(
                    OrganizationMember.builder().organization(org).role(OrganizationRole.OWNER).build()));
            return s;
        }

        private static GoogleAuthRequest cred(String c) {
            GoogleAuthRequest r = new GoogleAuthRequest();
            r.setCredential(c);
            return r;
        }

        @Test
        @DisplayName("CA-01: um e-mail qualquer não é credencial (antes entrava na conta)")
        void emailRejected() {
            assertThatThrownBy(() -> service(false).authenticateWithGoogle(cred("vitima@empresa.com")))
                    .isInstanceOf(AuthenticationFailedException.class);
            assertThatThrownBy(() -> service(true).authenticateWithGoogle(cred("vitima@empresa.com")))
                    .as("mesmo com a demonstração ligada").isInstanceOf(AuthenticationFailedException.class);
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("RF-02: demonstração desligada recusa até o e-mail de demonstração; ligada, aceita só ele")
        void demoLogin() {
            assertThatThrownBy(() -> service(false).authenticateWithGoogle(cred("demo.user@meetingintelligence.ai")))
                    .isInstanceOf(AuthenticationFailedException.class);
            assertThat(service(true).authenticateWithGoogle(cred("demo.user@meetingintelligence.ai")).getEmail())
                    .isEqualTo("demo.user@meetingintelligence.ai");
        }

        @Test
        @DisplayName("CA-02: token JWT só entra se o Google confirmar; recusa do Google derruba o login")
        void idTokenGoesThroughGoogle() {
            when(google.verifyIdToken("a.b.c", "nosso-app"))
                    .thenThrow(new AuthenticationFailedException("Credencial do Google inválida"));

            assertThatThrownBy(() -> service(false).authenticateWithGoogle(cred("a.b.c")))
                    .isInstanceOf(AuthenticationFailedException.class);
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("ID Token confirmado pelo Google emite o token da aplicação")
        void idTokenAccepted() {
            when(google.verifyIdToken("a.b.c", "nosso-app"))
                    .thenReturn(new GoogleTokenVerifier.GoogleIdentity("ana@empresa.com", "Ana", null, "123"));

            var res = service(false).authenticateWithGoogle(cred("a.b.c"));

            assertThat(res.getEmail()).isEqualTo("ana@empresa.com");
            assertThat(jwt.toPrincipal(res.getToken()).getEmail()).isEqualTo("ana@empresa.com");
        }
    }

    @Nested
    @DisplayName("Segredo do token")
    class JwtSecret {
        @Test
        @DisplayName("CA-05: sem JWT_SECRET, cada início usa um segredo diferente; token de um não vale no outro")
        void randomSecretWithoutConfig() {
            JwtService first = new JwtService("", 1);
            JwtService second = new JwtService("", 1);
            String token = first.generateToken(UUID.randomUUID(), "a@a.test", UUID.randomUUID(), "OWNER");

            assertThat(first.isTokenValid(token)).isTrue();
            assertThat(second.isTokenValid(token)).isFalse();
        }

        @Test
        @DisplayName("CA-05: segredo curto (< 32 bytes) também é trocado por um aleatório")
        void shortSecretIgnored() {
            String token = new JwtService("curto", 1).generateToken(UUID.randomUUID(), "a@a.test", UUID.randomUUID(), "OWNER");
            assertThat(new JwtService("curto", 1).isTokenValid(token)).isFalse();
        }

        @Test
        @DisplayName("CA-05: com JWT_SECRET, o token continua válido entre inícios")
        void configuredSecret() {
            String secret = "segredo-configurado-com-mais-de-32-bytes!!";
            String token = new JwtService(secret, 1).generateToken(UUID.randomUUID(), "a@a.test", UUID.randomUUID(), "OWNER");
            assertThat(new JwtService(secret, 1).isTokenValid(token)).isTrue();
        }

        @Test
        @DisplayName("RN-03: token sem organização não vira usuário")
        void tokenWithoutOrganization() {
            JwtService jwt = new JwtService("segredo-configurado-com-mais-de-32-bytes!!", 1);
            UserPrincipal ok = jwt.toPrincipal(jwt.generateToken(UUID.randomUUID(), "a@a.test", UUID.randomUUID(), "ADMIN"));
            assertThat(ok.getRole()).isEqualTo("ADMIN");
            assertThat(jwt.toPrincipal(jwt.generateToken(UUID.randomUUID(), "a@a.test", null, "OWNER"))).isNull();
            assertThat(jwt.toPrincipal("lixo")).isNull();
            assertThat(jwt.toPrincipal(null)).isNull();
        }
    }

    @Nested
    @DisplayName("Criptografia das chaves")
    class Cipher {
        private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
        private final SecretCipher cipher = new SecretCipher(KEY);

        @Test
        @DisplayName("CA-09: cifra com prefixo enc:v1:, IV aleatório, e volta igual")
        void roundTrip() {
            String a = cipher.encrypt("xai-chave-secreta");
            String b = cipher.encrypt("xai-chave-secreta");

            assertThat(a).startsWith("enc:v1:").doesNotContain("xai-chave-secreta").isNotEqualTo(b);
            assertThat(cipher.decrypt(a)).isEqualTo("xai-chave-secreta");
            assertThat(cipher.decrypt(b)).isEqualTo("xai-chave-secreta");
        }

        @Test
        @DisplayName("Valor legado em texto puro é lido como está; nulo e vazio passam direto; não cifra duas vezes")
        void legacyAndEdgeCases() {
            assertThat(cipher.decrypt("chave-antiga")).isEqualTo("chave-antiga");
            assertThat(cipher.encrypt(null)).isNull();
            assertThat(cipher.encrypt("")).isEmpty();
            String once = cipher.encrypt("x");
            assertThat(cipher.encrypt(once)).isEqualTo(once);
        }

        @Test
        @DisplayName("Chave errada ou valor adulterado → erro, nunca lixo")
        void wrongKeyOrTampered() {
            String stored = cipher.encrypt("segredo");
            SecretCipher other = new SecretCipher(Base64.getEncoder().encodeToString("ffffffffffffffffffffffffffffffff".getBytes()));

            assertThatThrownBy(() -> other.decrypt(stored)).isInstanceOf(IllegalStateException.class);
            String tampered = stored.substring(0, stored.length() - 4) + "AAAA";
            assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("CA-09: sem APP_ENCRYPTION_KEY (ou inválida) o backend não sobe")
        void keyRequired() {
            assertThatThrownBy(() -> new SecretCipher("")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("APP_ENCRYPTION_KEY");
            assertThatThrownBy(() -> new SecretCipher("não-é-base64!!")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString("curta".getBytes())))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        }
    }
}
