package com.aegis.merged.guardrails;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises the Redis-backed path of {@link RateLimiter}/{@link BudgetGuard} (the in-memory
 * fallback is already covered by {@link RateLimiterTest}/{@link GuardrailsOwaspTest}) against
 * a real Redis via Testcontainers — the Lua scripts and atomic INCRBY/EXPIRE logic can't be
 * verified against a mock without re-implementing Redis semantics in the mock itself.
 *
 * <p>Gated behind RUN_CONTAINER_TESTS=true, same as {@code AegisMergedApplicationTests}, so a
 * plain local {@code mvn test} stays green without Docker.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_CONTAINER_TESTS", matches = "true")
class RedisGuardrailsTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate template;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("Redis-backed RateLimiter enforces the same burst cap as the in-memory bucket")
    void redisRateLimiterEnforcesBurst() {
        var limiter = new RateLimiter(Optional.of(template));
        String tenant = "redis-burst-" + UUID.randomUUID();
        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (limiter.allow(tenant)) allowed++;
        }
        assertThat(allowed).isLessThanOrEqualTo(25);
        assertThat(limiter.allow("other-tenant-" + UUID.randomUUID())).isTrue();
    }

    @Test
    @DisplayName("Redis-backed BudgetGuard denies once a tenant's daily quota is exceeded")
    void redisBudgetGuardEnforced() {
        var budget = new BudgetGuard(Optional.of(template));
        String tenant = "redis-budget-" + UUID.randomUUID();
        budget.setBudget(tenant, 100);
        budget.checkOrThrow(tenant);
        budget.record(tenant, 150);
        assertThrows(BudgetGuard.BudgetExceededException.class, () -> budget.checkOrThrow(tenant));
    }

    @Test
    @DisplayName("Redis-backed budget limits are visible across separate BudgetGuard instances (multi-instance semantics)")
    void redisBudgetLimitSharedAcrossInstances() {
        String tenant = "redis-shared-" + UUID.randomUUID();
        var setter = new BudgetGuard(Optional.of(template));
        setter.setBudget(tenant, 42);

        var reader = new BudgetGuard(Optional.of(template));
        reader.record(tenant, 50);
        assertThrows(BudgetGuard.BudgetExceededException.class, () -> reader.checkOrThrow(tenant));
    }
}
