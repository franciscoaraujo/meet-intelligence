package com.meetingintelligence.integration;

import com.meetingintelligence.domain.entity.OrganizationAiConfig;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationAiConfigRepository;
import com.meetingintelligence.infrastructure.security.SecretCipher;
import com.meetingintelligence.infrastructure.security.SecretEncryptionMigrator;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 007 RF-14 / CA-09 num PostgreSQL real: as chaves de IA ficam cifradas no banco. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SecretCipher.class, SecretEncryptionMigrator.class})
class SecretsAtRestIT extends AbstractPostgresIT {

    @Autowired private OrganizationAiConfigRepository configs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private SecretEncryptionMigrator migrator;

    private UUID newOrg() {
        UUID org = UUID.randomUUID();
        jdbc.update("insert into organizations(id, name, slug) values (?, 'Org', ?)", org, "s-" + org);
        return org;
    }

    @Test
    @DisplayName("CA-09: a chave salva fica como enc:v1:... no banco e volta igual pela entidade")
    void savedKeysAreEncrypted() {
        UUID org = newOrg();
        OrganizationAiConfig cfg = OrganizationAiConfig.builder()
                .organization(em.getReference(com.meetingintelligence.domain.entity.Organization.class, org))
                .grokApiKey("xai-chave-de-teste-123")
                .geminiApiKey("AIza-chave-de-teste")
                .build();
        configs.saveAndFlush(cfg);
        em.clear();

        String raw = jdbc.queryForObject("select grok_api_key from organization_ai_configs where organization_id = ?",
                String.class, org);
        assertThat(raw).startsWith("enc:v1:").doesNotContain("xai-chave-de-teste-123");
        assertThat(configs.findByOrganizationId(org).orElseThrow().getGrokApiKey()).isEqualTo("xai-chave-de-teste-123");
        assertThat(configs.findByOrganizationId(org).orElseThrow().getGeminiApiKey()).isEqualTo("AIza-chave-de-teste");
    }

    @Test
    @DisplayName("CA-09: valor antigo em texto puro é cifrado pela migração da inicialização, uma vez só")
    void legacyPlaintextMigrated() {
        UUID org = newOrg();
        jdbc.update("insert into organization_ai_configs(id, organization_id, claude_api_key) values (?, ?, 'sk-ant-antiga')",
                UUID.randomUUID(), org);

        int first = migrator.encryptColumn("organization_ai_configs", "claude_api_key");
        int second = migrator.encryptColumn("organization_ai_configs", "claude_api_key");

        assertThat(first).isPositive();
        assertThat(second).isZero();
        assertThat(jdbc.queryForObject("select claude_api_key from organization_ai_configs where organization_id = ?",
                String.class, org)).startsWith("enc:v1:");
        em.clear();
        assertThat(configs.findByOrganizationId(org).orElseThrow().getClaudeApiKey()).isEqualTo("sk-ant-antiga");
    }

    @Test
    @DisplayName("V13: as colunas de chave aceitam o valor cifrado de chaves longas")
    void columnsAreText() {
        String type = jdbc.queryForObject("select data_type from information_schema.columns "
                + "where table_name = 'organization_ai_configs' and column_name = 'grok_api_key'", String.class);
        assertThat(type).isEqualTo("text");
    }
}
