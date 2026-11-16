package com.meetingintelligence.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.exception.AuthenticationFailedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Valida credenciais do Google só pelas APIs oficiais (spec 007, RF-03/RF-04). Não há caminho que
 * decodifique um token localmente: se o Google recusar ou não responder, o login falha.
 */
@Component
public class GoogleTokenVerifier {

    static final String TOKENINFO = "https://oauth2.googleapis.com/tokeninfo";
    static final String USERINFO = "https://www.googleapis.com/oauth2/v3/userinfo";

    /** Identidade confirmada pelo Google. */
    public record GoogleIdentity(String email, String name, String picture, String subject) {}

    private final RestTemplate http;
    private final ObjectMapper mapper;

    @Autowired
    public GoogleTokenVerifier(ObjectMapper mapper) {
        this(defaultClient(), mapper);
    }

    public GoogleTokenVerifier(RestTemplate http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    private static RestTemplate defaultClient() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(10_000);
        return new RestTemplate(f);
    }

    /** RF-03: ID Token (JWT) conferido no tokeninfo, com aud = nosso Client ID e e-mail verificado. */
    public GoogleIdentity verifyIdToken(String idToken, String expectedClientId) {
        JsonNode info = call(UriComponentsBuilder.fromHttpUrl(TOKENINFO).queryParam("id_token", idToken)
                .build().toUriString(), null);
        requireAudience(info, expectedClientId);
        requireVerifiedEmail(info);
        return identity(info);
    }

    /** RF-04: Access Token (ya29.*) conferido no tokeninfo (aud/azp) antes de ler o userinfo. */
    public GoogleIdentity verifyAccessToken(String accessToken, String expectedClientId) {
        JsonNode info = call(UriComponentsBuilder.fromHttpUrl(TOKENINFO).queryParam("access_token", accessToken)
                .build().toUriString(), null);
        requireAudience(info, expectedClientId);
        JsonNode user = call(USERINFO, accessToken);
        requireVerifiedEmail(user);
        return identity(user);
    }

    private JsonNode call(String url, String bearer) {
        try {
            HttpHeaders headers = new HttpHeaders();
            if (bearer != null) headers.setBearerAuth(bearer.trim());
            ResponseEntity<String> res = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                throw new AuthenticationFailedException("O Google não confirmou a credencial.");
            }
            return mapper.readTree(res.getBody());
        } catch (AuthenticationFailedException e) {
            throw e;
        } catch (Exception e) {
            // RN-03: Google recusou (4xx) ou não respondeu: recusa, nunca decodifica localmente
            throw new AuthenticationFailedException("Credencial do Google inválida ou expirada. Entre novamente.");
        }
    }

    private static void requireAudience(JsonNode info, String expectedClientId) {
        if (expectedClientId == null || expectedClientId.isBlank()) {
            throw new AuthenticationFailedException("Login com Google não configurado no servidor.");
        }
        String aud = info.path("aud").asText("");
        String azp = info.path("azp").asText("");
        if (!expectedClientId.equals(aud) && !expectedClientId.equals(azp)) {
            throw new AuthenticationFailedException("A credencial do Google não foi emitida para este aplicativo.");
        }
    }

    private static void requireVerifiedEmail(JsonNode info) {
        String email = info.path("email").asText("");
        JsonNode verified = info.path("email_verified");
        boolean ok = verified.isBoolean() ? verified.asBoolean() : "true".equalsIgnoreCase(verified.asText(""));
        if (email.isBlank() || !ok) {
            throw new AuthenticationFailedException("A conta Google não tem e-mail verificado.");
        }
    }

    private static GoogleIdentity identity(JsonNode info) {
        String email = info.path("email").asText();
        String name = info.path("name").asText("");
        return new GoogleIdentity(email, name.isBlank() ? email.split("@")[0] : name,
                info.path("picture").asText(null), info.path("sub").asText(null));
    }
}
