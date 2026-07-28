package com.aegis.merged.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Externalizes {@code RateLimiter}/{@code BudgetGuard} state to Redis so it holds under
 * horizontal scaling — each app instance would otherwise keep its own counters, silently
 * multiplying a tenant's effective rate/budget by the instance count.
 *
 * Opt-in via aegis.redis.enabled (default false): Redis autoconfiguration is excluded in
 * {@link com.aegis.merged.AegisMergedApplication}, so the ONLY way a StringRedisTemplate bean
 * exists is through this class — the property is the single source of truth, and adding the
 * starter jar never changes behavior on a dev machine with no Redis running.
 */
@Configuration
@ConditionalOnProperty(prefix = "aegis.redis", name = "enabled", havingValue = "true")
public class RedisConfig {

    @Bean
    RedisConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        return new LettuceConnectionFactory(host, port);
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
