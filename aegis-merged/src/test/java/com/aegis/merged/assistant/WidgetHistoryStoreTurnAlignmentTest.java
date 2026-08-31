package com.aegis.merged.assistant;

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
import java.util.List;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_CONTAINER_TESTS", matches = "true")
class WidgetHistoryStoreTurnAlignmentTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    static JdbcTemplate jdbc;
    WidgetHistoryStore store;

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
    void freshTables() {
        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(jdbc.getDataSource())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        store = new WidgetHistoryStore(jdbc);
    }

    @Test
    void currentTurnSeqReflectsOnlyTurnsThatActuallyCalledNextTurnSeq() {
        String key = "achu-bank:u1:c1";

        assertThat(store.currentTurnSeq(key)).isEqualTo(0);

        int first = store.nextTurnSeq(key);
        assertThat(first).isEqualTo(1);
        assertThat(store.currentTurnSeq(key)).isEqualTo(1);

        int second = store.nextTurnSeq(key);
        assertThat(second).isEqualTo(2);
        assertThat(store.currentTurnSeq(key)).isEqualTo(2);
    }

    @Test
    void aTurnThatNeverCallsNextTurnSeqDoesNotAdvanceTheCounter() {
        String key = "achu-bank:u1:c2";

        store.nextTurnSeq(key);
        assertThat(store.currentTurnSeq(key)).isEqualTo(1);

        assertThat(store.currentTurnSeq(key)).isEqualTo(1);
    }

    @Test
    void savedWidgetsRoundTripWithTheirTurnSeqIntact() throws InterruptedException {
        String key = "achu-bank:u1:c3";
        int turnSeq = store.nextTurnSeq(key);

        store.save(key, turnSeq, "cards", "{\"cardId\":\"CRD-1\"}");

        var rows = waitForWidgetRows(key, 1);
        assertThat(rows.get(0).turnSeq()).isEqualTo(turnSeq);
        assertThat(rows.get(0).widgetType()).isEqualTo("cards");
    }

    private List<WidgetHistoryStore.WidgetRow> waitForWidgetRows(String key, int expected)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            var rows = store.loadForConversation(key);
            if (rows.size() >= expected) return rows;
            Thread.sleep(50);
        }
        throw new AssertionError("widget rows for " + key + " never reached " + expected);
    }
}
