package com.meetingintelligence.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 020 contra o servidor real e PostgreSQL real: arquivar, restaurar e regras de status. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"app.s3.endpoint=http://127.0.0.1:1"})
class MeetingLifecycleIT extends AbstractPostgresIT {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID meeting;
    private String owner;
    private String otherOrg;

    @BeforeEach
    void setUp() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        UUID org = UUID.randomUUID();
        UUID org2 = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        meeting = UUID.randomUUID();
        jdbc.update("insert into organizations(id, name, slug) values (?, 'Org', ?)", org, "l-" + org);
        jdbc.update("insert into organizations(id, name, slug) values (?, 'Outra', ?)", org2, "l-" + org2);
        jdbc.update("insert into users(id, email, full_name) values (?, ?, 'U')", user, user + "@l.test");
        jdbc.update("insert into meetings(id, organization_id, created_by_user_id, title, status) values (?, ?, ?, 'Entrevista', 'COMPLETED')",
                meeting, org, user);
        jdbc.update("insert into transcript_segments(id, meeting_id, original_text, start_time_ms, end_time_ms) "
                + "values (?, ?, 'Fale sobre você', 0, 1000)", UUID.randomUUID(), meeting);
        owner = jwt.generateToken(user, user + "@l.test", org, "OWNER");
        otherOrg = jwt.generateToken(UUID.randomUUID(), "x@o.test", org2, "OWNER");
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange("http://localhost:" + port + "/api/v1" + path, method, new HttpEntity<>(h), String.class);
    }

    private List<String> listIds(boolean archived) throws Exception {
        JsonNode page = mapper.readTree(call(HttpMethod.GET, "/meetings?archived=" + archived + "&size=200", owner).getBody());
        List<String> ids = new ArrayList<>();
        page.path("content").forEach(n -> ids.add(n.path("id").asText()));
        return ids;
    }

    @Test
    @DisplayName("CA-07: arquivar tira da lista, mostra em Arquivadas e não apaga nada; restaurar devolve")
    void archiveAndRestore() throws Exception {
        assertThat(listIds(false)).contains(meeting.toString());

        ResponseEntity<String> archived = call(HttpMethod.POST, "/meetings/" + meeting + "/archive", owner);
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(archived.getBody()).path("archivedAt").isNull()).isFalse();
        assertThat(listIds(false)).doesNotContain(meeting.toString());
        assertThat(listIds(true)).contains(meeting.toString());
        assertThat(jdbc.queryForObject("select count(*) from transcript_segments where meeting_id = ?", Integer.class, meeting))
                .isEqualTo(1);
        // Arquivada continua acessível pela sala (histórico)
        assertThat(call(HttpMethod.GET, "/meetings/" + meeting + "/transcripts", owner).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(call(HttpMethod.POST, "/meetings/" + meeting + "/unarchive", owner).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listIds(false)).contains(meeting.toString());
        assertThat(listIds(true)).doesNotContain(meeting.toString());
    }

    @Test
    @DisplayName("CA-07: arquivar ou restaurar reunião de outra organização → 404")
    void archiveOtherOrganization() {
        assertThat(call(HttpMethod.POST, "/meetings/" + meeting + "/archive", otherOrg).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, "/meetings/" + meeting + "/unarchive", otherOrg).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("select archived_at is null from meetings where id = ?", Boolean.class, meeting)).isTrue();
    }

    @Test
    @DisplayName("RN-02: reunião concluída não volta a agendada; \"Retomar\" a leva para em andamento sem fim marcado")
    void statusRules() throws Exception {
        assertThat(call(HttpMethod.PATCH, "/meetings/" + meeting + "/status?status=SCHEDULED", owner).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        jdbc.update("update meetings set actual_end = now() where id = ?", meeting);
        ResponseEntity<String> resumed = call(HttpMethod.PATCH, "/meetings/" + meeting + "/status?status=IN_PROGRESS", owner);
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(resumed.getBody());
        assertThat(body.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(body.path("actualEnd").isNull()).isTrue();

        JsonNode done = mapper.readTree(call(HttpMethod.PATCH, "/meetings/" + meeting + "/status?status=COMPLETED", owner).getBody());
        assertThat(done.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.path("actualEnd").isNull()).isFalse();
    }
}
