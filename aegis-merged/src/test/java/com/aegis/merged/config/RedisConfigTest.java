package com.aegis.merged.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_CONTAINER_TESTS", matches = "true")
class RedisConfigTest {

    private static final String PASSWORD = "test-redis-password";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withCommand("redis-server", "--requirepass", PASSWORD)
            .withExposedPorts(6379);

    @Test
    void connectionFactoryAuthenticatesWithTheConfiguredPassword() {
        RedisConfig config = new RedisConfig();
        RedisConnectionFactory factory = config.redisConnectionFactory(
                redis.getHost(), redis.getMappedPort(6379), "", PASSWORD);
        if (factory instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory lettuce) {
            lettuce.afterPropertiesSet();
        }

        var template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        template.opsForValue().set("aegis:test:key", "ok");

        assertThat(template.opsForValue().get("aegis:test:key")).isEqualTo("ok");
    }
}
