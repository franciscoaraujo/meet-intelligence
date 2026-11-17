package com.meetingintelligence.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Cifra, na inicialização, os segredos que ainda estão em texto puro (spec 007, RF-14). Idempotente:
 * valores já com o prefixo {@code enc:v1:} não são tocados.
 */
@Component
@Slf4j
public class SecretEncryptionMigrator implements ApplicationRunner {

    /** Tabela → colunas com segredo. */
    static final Map<String, List<String>> COLUMNS = Map.of(
            "organization_ai_configs", List.of("gemini_api_key", "openai_api_key", "grok_api_key", "claude_api_key"),
            "users", List.of("google_refresh_token"));

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public SecretEncryptionMigrator(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        int total = 0;
        for (var entry : COLUMNS.entrySet()) {
            for (String column : entry.getValue()) {
                total += encryptColumn(entry.getKey(), column);
            }
        }
        if (total > 0) {
            log.info("Spec 007: {} segredo(s) em texto puro foram criptografados no banco", total);
        }
    }

    /** Cifra uma coluna da lista {@link #COLUMNS}; qualquer outro nome é recusado (os nomes entram no SQL). */
    public int encryptColumn(String table, String column) {
        if (!COLUMNS.getOrDefault(table, List.of()).contains(column)) {
            throw new IllegalArgumentException("Coluna fora da lista de segredos: " + table + "." + column);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, " + column + " AS v FROM " + table
                        + " WHERE " + column + " IS NOT NULL AND " + column + " <> '' AND " + column + " NOT LIKE 'enc:v1:%'");
        for (Map<String, Object> row : rows) {
            jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ?",
                    cipher.encrypt((String) row.get("v")), row.get("id"));
        }
        return rows.size();
    }
}
