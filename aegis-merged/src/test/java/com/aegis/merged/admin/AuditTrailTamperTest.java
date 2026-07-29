package com.aegis.merged.admin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_CONTAINER_TESTS", matches = "true")
class AuditTrailTamperTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    static JdbcTemplate jdbc;
    AuditTrail audit;

    @BeforeAll
    static void setUpDataSource() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
    }

    @AfterAll
    static void tearDown() {
        postgres.stop();
    }

    @BeforeEach
    void freshTable() {
        jdbc.execute("DROP TABLE IF EXISTS assistant_audit_event");
        audit = new AuditTrail(jdbc);
        audit.createSchema();
    }

    private void waitForRowCount(int expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM assistant_audit_event", Integer.class);
            if (count != null && count >= expected) return;
            Thread.sleep(50);
        }
        throw new AssertionError("audit rows never reached " + expected);
    }

    @Test
    void aCleanChainVerifiesAsValid() throws InterruptedException {
        audit.record("achu-bank", "u1", "c1", "llm", 10, 5, "q1");
        audit.record("achu-bank", "u1", "c1", "llm", 12, 7, "q2");
        waitForRowCount(2);

        AuditTrail.ChainVerification result = audit.verifyChain();
        assertThat(result.valid()).isTrue();
        assertThat(result.brokenAtId()).isNull();
    }

    @Test
    void editingAPersistedRowBreaksTheChainAtThatRow() throws InterruptedException {
        audit.record("achu-bank", "u1", "c1", "llm", 10, 5, "q1");
        audit.record("achu-bank", "u1", "c1", "llm", 12, 7, "q2");
        audit.record("achu-bank", "u1", "c1", "llm", 14, 9, "q3");
        waitForRowCount(3);

        Long tamperedId = jdbc.queryForObject(
                "SELECT id FROM assistant_audit_event ORDER BY id ASC LIMIT 1 OFFSET 1", Long.class);
        jdbc.update("UPDATE assistant_audit_event SET question = 'TAMPERED' WHERE id = ?", tamperedId);

        AuditTrail.ChainVerification result = audit.verifyChain();
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtId()).isEqualTo(tamperedId);
    }

    @Test
    void deletingAPersistedRowBreaksTheChain() throws InterruptedException {
        audit.record("achu-bank", "u1", "c1", "llm", 10, 5, "q1");
        audit.record("achu-bank", "u1", "c1", "llm", 12, 7, "q2");
        audit.record("achu-bank", "u1", "c1", "llm", 14, 9, "q3");
        waitForRowCount(3);

        Long deletedId = jdbc.queryForObject(
                "SELECT id FROM assistant_audit_event ORDER BY id ASC LIMIT 1 OFFSET 1", Long.class);
        jdbc.update("DELETE FROM assistant_audit_event WHERE id = ?", deletedId);

        AuditTrail.ChainVerification result = audit.verifyChain();
        assertThat(result.valid()).isFalse();
    }
}
