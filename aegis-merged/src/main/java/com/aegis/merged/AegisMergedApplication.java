package com.aegis.merged;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;

// Redis autoconfig is excluded so a StringRedisTemplate bean only exists when
// aegis.redis.enabled=true creates one explicitly (see config.RedisConfig) — otherwise
// RateLimiter/BudgetGuard's Optional<StringRedisTemplate> would never be empty, even on a
// machine with no Redis running. RedisRepositoriesAutoConfiguration is also excluded: with
// no @RedisHash entities in this app it does nothing useful, but left enabled it still tries
// to wire a plain 'redisTemplate' bean that RedisConfig never defines, failing startup.
@SpringBootApplication(exclude = { RedisAutoConfiguration.class, RedisReactiveAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class })
public class AegisMergedApplication {

    public static void main(String[] args) {
        SpringApplication.run(AegisMergedApplication.class, args);
    }
}
