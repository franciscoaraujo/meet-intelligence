package com.meetingintelligence.integration;

import com.meetingintelligence.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Spec 007 contra o servidor real (porta aleatória) e PostgreSQL real: login, isolamento entre
 * organizações, papéis, CORS e WebSocket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"app.s3.endpoint=http://127.0.0.1:1", "app.auth.demo-login-enabled=false"})
class SecurityHardeningIT extends AbstractPostgresIT {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;

    private UUID orgA;
    private UUID orgB;
    private UUID meetingA;
    private UUID meetingB;
    private String ownerA;
    private String ownerB;
    private String memberA;
    private String adminA;

    @BeforeEach
    void setUp() {
        // PATCH precisa de um cliente HTTP que suporte o método
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        orgA = UUID.randomUUID();
        orgB = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        meetingA = UUID.randomUUID();
        meetingB = UUID.randomUUID();
        jdbc.update("insert into organizations(id, name, slug) values (?, 'Org A', ?)", orgA, "a-" + orgA);
        jdbc.update("insert into organizations(id, name, slug) values (?, 'Org B', ?)", orgB, "b-" + orgB);
        jdbc.update("insert into users(id, email, full_name) values (?, ?, 'A')", userA, userA + "@a.test");
        jdbc.update("insert into users(id, email, full_name) values (?, ?, 'B')", userB, userB + "@b.test");
        jdbc.update("insert into meetings(id, organization_id, created_by_user_id, title) values (?, ?, ?, 'Entrevista A')",
                meetingA, orgA, userA);
        jdbc.update("insert into meetings(id, organization_id, created_by_user_id, title) values (?, ?, ?, 'Entrevista B')",
                meetingB, orgB, userB);
        ownerA = jwt.generateToken(userA, userA + "@a.test", orgA, "OWNER");
        memberA = jwt.generateToken(userA, userA + "@a.test", orgA, "MEMBER");
        adminA = jwt.generateToken(userA, userA + "@a.test", orgA, "ADMIN");
        ownerB = jwt.generateToken(userB, userB + "@b.test", orgB, "OWNER");
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/v1" + path;
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), method, new HttpEntity<>(body, h), String.class);
    }

    // ------------------------------------------------------------------ isolamento (S-02)

    @Test
    @DisplayName("CA-07: usuário da organização B recebe 404 em todos os endpoints da reunião da organização A")
    void otherOrganizationGets404Everywhere() {
        String m = "/meetings/" + meetingA;
        List<Object[]> calls = List.of(
                new Object[]{HttpMethod.GET, m, null},
                new Object[]{HttpMethod.PATCH, m + "/status?status=COMPLETED", null},
                new Object[]{HttpMethod.DELETE, m, null},
                new Object[]{HttpMethod.GET, m + "/chat", null},
                new Object[]{HttpMethod.POST, m + "/chat", Map.of("message", "resuma")},
                new Object[]{HttpMethod.GET, m + "/export/minutes", null},
                new Object[]{HttpMethod.GET, m + "/export/txt", null},
                new Object[]{HttpMethod.GET, m + "/insights", null},
                new Object[]{HttpMethod.GET, m + "/summary", null},
                new Object[]{HttpMethod.GET, m + "/recordings", null},
                new Object[]{HttpMethod.GET, m + "/recordings/" + UUID.randomUUID() + "/playback-url", null},
                new Object[]{HttpMethod.GET, m + "/transcripts", null},
                new Object[]{HttpMethod.PATCH, m + "/transcripts/" + UUID.randomUUID(), Map.of("correctedText", "x")},
                new Object[]{HttpMethod.GET, m + "/questions", null});

        for (Object[] c : calls) {
            ResponseEntity<String> res = call((HttpMethod) c[0], (String) c[1], ownerB, c[2]);
            assertThat(res.getStatusCode()).as(c[0] + " " + c[1]).isEqualTo(HttpStatus.NOT_FOUND);
        }
        // A reunião continua lá para o dono
        Integer count = jdbc.queryForObject("select count(*) from meetings where id = ?", Integer.class, meetingA);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("RF-11: o dono acessa a própria reunião; reunião inexistente também é 404")
    void ownerAccessesOwnMeeting() {
        assertThat(call(HttpMethod.GET, "/meetings/" + meetingA, ownerA, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/meetings/" + meetingA + "/transcripts", ownerA, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/meetings/" + UUID.randomUUID(), ownerA, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("CA-08: trecho de outra reunião pela URL da própria é 404")
    void segmentOfAnotherMeetingIs404() {
        UUID segmentB = UUID.randomUUID();
        jdbc.update("insert into transcript_segments(id, meeting_id, original_text, start_time_ms, end_time_ms) "
                + "values (?, ?, 'segredo da B', 0, 1000)", segmentB, meetingB);

        ResponseEntity<String> res = call(HttpMethod.PATCH, "/meetings/" + meetingA + "/transcripts/" + segmentB,
                ownerA, Map.of("correctedText", "alterado"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("select corrected_text from transcript_segments where id = ?", String.class, segmentB))
                .isNull();
    }

    @Test
    @DisplayName("S-05: organização e métricas são sempre as do usuário, mesmo com organizationId de outra na URL")
    void organizationComesFromToken() {
        ResponseEntity<String> res = call(HttpMethod.GET, "/organization?organizationId=" + orgB, ownerA, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("Org A").doesNotContain("Org B");
    }

    // ------------------------------------------------------------------ papéis (S-05)

    @Test
    @DisplayName("CA-10: MEMBER não muda plano nem convida; ADMIN não convida OWNER")
    void roles() {
        assertThat(call(HttpMethod.PATCH, "/organization/plan?planType=PRO", memberA, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/organization/members/invite", memberA,
                Map.of("email", "x@a.test", "role", "MEMBER")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/organization/members/invite", adminA,
                Map.of("email", "y@a.test", "role", "OWNER")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PATCH, "/organization/plan?planType=PRO", ownerA, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------ login (S-04)

    @Test
    @DisplayName("CA-01: um e-mail qualquer como credencial não faz login (401)")
    void emailIsNotACredential() {
        ResponseEntity<String> res = call(HttpMethod.POST, "/auth/google", null,
                Map.of("credential", "vitima@empresa.com"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> demo = call(HttpMethod.POST, "/auth/google", null,
                Map.of("credential", "demo.user@meetingintelligence.ai"));
        assertThat(demo.getStatusCode()).as("demonstração desligada").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("CA-04: configurar o Client ID exige OWNER/ADMIN; o GET continua público")
    void googleConfigRequiresOwner() {
        assertThat(call(HttpMethod.GET, "/auth/google/config", null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/auth/google/config", null, null).getBody()).contains("demoLoginEnabled");
        assertThat(call(HttpMethod.POST, "/auth/google/config", null, Map.of("clientId", "hack")).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/auth/google/config", memberA, Map.of("clientId", "hack")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        String original = rest.getForObject(url("/auth/google/config"), Map.class).get("clientId").toString();
        assertThat(call(HttpMethod.POST, "/auth/google/config", ownerA, Map.of("clientId", original)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Token forjado com outro segredo não autentica")
    void forgedTokenRejected() {
        String forged = new JwtService("outro-segredo-qualquer-com-mais-de-32-bytes!!", 24)
                .generateToken(UUID.randomUUID(), "x@a.test", orgA, "OWNER");
        assertThat(call(HttpMethod.GET, "/meetings/" + meetingA, forged, null).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ CORS (S-06)

    @Test
    @DisplayName("CA-11: origem fora da lista não recebe Access-Control-Allow-Origin; a do frontend recebe")
    void cors() {
        HttpHeaders evil = new HttpHeaders();
        evil.setOrigin("https://site-malicioso.example");
        evil.setAccessControlRequestMethod(HttpMethod.GET);
        ResponseEntity<String> bad = rest.exchange(url("/meetings"), HttpMethod.OPTIONS, new HttpEntity<>(evil), String.class);
        assertThat(bad.getHeaders().getAccessControlAllowOrigin()).isNull();

        HttpHeaders good = new HttpHeaders();
        good.setOrigin("http://localhost:5173");
        good.setAccessControlRequestMethod(HttpMethod.GET);
        ResponseEntity<String> ok = rest.exchange(url("/meetings"), HttpMethod.OPTIONS, new HttpEntity<>(good), String.class);
        assertThat(ok.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");
    }

    // ------------------------------------------------------------------ WebSocket (S-01)

    private StompSession connect(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        StompHeaders headers = new StompHeaders();
        if (token != null) headers.add("Authorization", "Bearer " + token);
        return client.connectAsync("http://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
                new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("CA-06: CONNECT sem token é recusado")
    void wsConnectWithoutToken() {
        assertThatThrownBy(() -> connect(null)).isNotNull();
    }

    @Test
    @DisplayName("CA-06: SUBSCRIBE na reunião de outra organização derruba a sessão; na própria, não")
    void wsSubscribeOtherOrganization() throws Exception {
        StompSession own = connect(ownerA);
        own.subscribe("/topic/meetings/" + meetingA + "/transcripts", new StompSessionHandlerAdapter() {});
        Thread.sleep(500);
        assertThat(own.isConnected()).isTrue();
        own.disconnect();

        StompSession intruder = connect(ownerB);
        intruder.subscribe("/topic/meetings/" + meetingA + "/transcripts", new StompSessionHandlerAdapter() {});
        await().atMost(5, TimeUnit.SECONDS).until(() -> !intruder.isConnected());
    }

    @Test
    @DisplayName("CA-06: SEND do cliente derruba a sessão (ninguém publica card falso)")
    void wsSendRejected() throws Exception {
        StompSession s = connect(ownerA);
        s.send("/topic/meetings/" + meetingA + "/copilot", "{\"type\":\"COMPLETED\"}".getBytes());
        await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
    }
}
