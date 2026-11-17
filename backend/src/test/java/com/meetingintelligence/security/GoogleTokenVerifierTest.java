package com.meetingintelligence.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.exception.AuthenticationFailedException;
import com.meetingintelligence.infrastructure.security.GoogleTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Spec 007 RF-03/RF-04: credenciais do Google só valem se o Google confirmar, para o nosso app. */
class GoogleTokenVerifierTest {

    private static final String CLIENT = "nosso-app.apps.googleusercontent.com";
    private MockRestServiceServer google;
    private GoogleTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        RestTemplate http = new RestTemplate();
        google = MockRestServiceServer.bindTo(http).build();
        verifier = new GoogleTokenVerifier(http, new ObjectMapper());
    }

    private static String tokenInfo(String aud, String email, String verified) {
        return "{\"aud\":\"" + aud + "\",\"azp\":\"" + aud + "\",\"email\":\"" + email + "\",\"email_verified\":\""
                + verified + "\",\"name\":\"Ana Souza\",\"sub\":\"123\",\"picture\":\"https://x/p.png\"}";
    }

    @Test
    @DisplayName("ID Token confirmado pelo Google, para o nosso app, com e-mail verificado → identidade")
    void validIdToken() {
        google.expect(requestTo(startsWith("https://oauth2.googleapis.com/tokeninfo?id_token=a.b.c")))
                .andRespond(withSuccess(tokenInfo(CLIENT, "ana@empresa.com", "true"), MediaType.APPLICATION_JSON));

        GoogleTokenVerifier.GoogleIdentity id = verifier.verifyIdToken("a.b.c", CLIENT);

        assertThat(id.email()).isEqualTo("ana@empresa.com");
        assertThat(id.name()).isEqualTo("Ana Souza");
        assertThat(id.subject()).isEqualTo("123");
    }

    @Test
    @DisplayName("CA-02: ID Token de outro app (aud diferente) → recusado")
    void idTokenForAnotherApp() {
        google.expect(requestTo(startsWith("https://oauth2.googleapis.com/tokeninfo")))
                .andRespond(withSuccess(tokenInfo("app-do-atacante", "ana@empresa.com", "true"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verifyIdToken("a.b.c", CLIENT))
                .isInstanceOf(AuthenticationFailedException.class).hasMessageContaining("este aplicativo");
    }

    @Test
    @DisplayName("CA-02: e-mail não verificado → recusado")
    void unverifiedEmail() {
        google.expect(requestTo(startsWith("https://oauth2.googleapis.com/tokeninfo")))
                .andRespond(withSuccess(tokenInfo(CLIENT, "ana@empresa.com", "false"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verifyIdToken("a.b.c", CLIENT)).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("CA-02: Google recusa o token → login falha; não há decodificação local (token forjado)")
    void googleRejectsForgedToken() {
        // Token "forjado": payload com e-mail da vítima, sem assinatura válida
        String forged = "eyJhbGciOiJub25lIn0.eyJlbWFpbCI6InZpdGltYUBlbXByZXNhLmNvbSJ9.x";
        google.expect(requestTo(startsWith("https://oauth2.googleapis.com/tokeninfo")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> verifier.verifyIdToken(forged, CLIENT)).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("RN-03: Google fora do ar → login falha")
    void googleDown() {
        google.expect(requestTo(startsWith("https://oauth2.googleapis.com/tokeninfo")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> verifier.verifyIdToken("a.b.c", CLIENT)).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("Sem Client ID configurado no servidor → nenhum token é aceito")
    void noClientConfigured() {
        google.expect(requestTo(startsWith("https://oauth2.googleapis.com/tokeninfo")))
                .andRespond(withSuccess(tokenInfo(CLIENT, "ana@empresa.com", "true"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verifyIdToken("a.b.c", "")).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("RF-04: Access Token conferido no tokeninfo (azp) antes do userinfo")
    void validAccessToken() {
        google.expect(requestTo("https://oauth2.googleapis.com/tokeninfo?access_token=ya29.ok"))
                .andRespond(withSuccess("{\"azp\":\"" + CLIENT + "\",\"aud\":\"" + CLIENT + "\",\"scope\":\"email\"}",
                        MediaType.APPLICATION_JSON));
        google.expect(requestTo("https://www.googleapis.com/oauth2/v3/userinfo"))
                .andExpect(header("Authorization", "Bearer ya29.ok"))
                .andRespond(withSuccess("{\"email\":\"ana@empresa.com\",\"email_verified\":true,\"name\":\"Ana\",\"sub\":\"9\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(verifier.verifyAccessToken("ya29.ok", CLIENT).email()).isEqualTo("ana@empresa.com");
        google.verify();
    }

    @Test
    @DisplayName("CA-03: Access Token emitido para outro app → recusado sem consultar o perfil")
    void accessTokenForAnotherApp() {
        google.expect(requestTo("https://oauth2.googleapis.com/tokeninfo?access_token=ya29.outro"))
                .andRespond(withSuccess("{\"azp\":\"app-do-atacante\",\"aud\":\"app-do-atacante\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verifyAccessToken("ya29.outro", CLIENT))
                .isInstanceOf(AuthenticationFailedException.class);
        google.verify(); // userinfo não foi chamado
    }
}
