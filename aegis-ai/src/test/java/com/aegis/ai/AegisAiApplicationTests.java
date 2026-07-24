package com.aegis.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the full application context against a real Postgres (via Testcontainers),
 * with AI model auto-configuration stubbed out (spring.ai.model.chat=none).
 *
 * <p>Gated behind RUN_CONTAINER_TESTS=true so a plain local {@code mvn test} stays
 * green on machines without a usable Docker environment (e.g. a bleeding-edge
 * Docker Engine the bundled docker-java can't negotiate). CI sets the variable.
 * The end-to-end runtime behaviour is verified against the live app — see README.
 */
@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "spring.ai.model.chat=none",
        "spring.ai.ollama.init.pull-model-strategy=never"
})
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_CONTAINER_TESTS", matches = "true")
class AegisAiApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Test
    void contextLoads() {
        // Verifies wiring: web layer, JDBC chat-memory repository + schema init,
        // and all advisor beans construct against a real database.
    }
}
