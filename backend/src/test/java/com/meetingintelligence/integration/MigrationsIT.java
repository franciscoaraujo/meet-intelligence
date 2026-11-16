package com.meetingintelligence.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aplica todas as migrations Flyway num PostgreSQL+pgvector real e deixa o Hibernate validar
 * o mapeamento das entidades (ddl-auto=validate). Falha se migration e entidade divergirem.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MigrationsIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Todas as migrations aplicam do zero e as entidades validam contra o schema")
    void allMigrationsApplyAndEntitiesValidate() {
        Integer failed = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class);
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true and version is not null", Integer.class);

        assertThat(failed).isZero();
        assertThat(applied).isGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("Spec 014 CA-03: meetings.answer_style existe, é obrigatória e tem padrão NEUTRAL")
    void answerStyleColumnDefaultsToNeutral() {
        String columnDefault = jdbc.queryForObject(
                "select column_default from information_schema.columns where table_name = 'meetings' and column_name = 'answer_style'",
                String.class);
        String nullable = jdbc.queryForObject(
                "select is_nullable from information_schema.columns where table_name = 'meetings' and column_name = 'answer_style'",
                String.class);

        assertThat(columnDefault).contains("NEUTRAL");
        assertThat(nullable).isEqualTo("NO");
    }

    @Test
    @DisplayName("Spec 015 CA-06: interview_questions.context_text existe e é opcional")
    void questionContextColumnIsOptional() {
        String nullable = jdbc.queryForObject(
                "select is_nullable from information_schema.columns where table_name = 'interview_questions' and column_name = 'context_text'",
                String.class);

        assertThat(nullable).isEqualTo("YES");
    }
}
