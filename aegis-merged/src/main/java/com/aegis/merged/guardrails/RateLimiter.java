package com.aegis.merged.guardrails;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tenant token-bucket rate limiter (OWASP LLM10: Unbounded Consumption).
 * A refill-over-time bucket: bounded burst, sustained rate cap.
 *
 * Backed by Redis (atomic via a single Lua script — a naive GET-then-SET would race across
 * instances) when {@code aegis.redis.enabled=true}; otherwise falls back to the original
 * in-memory bucket, which is correct for a single instance but NOT shared across replicas.
 */
@Component
public class RateLimiter {

    private static final double CAPACITY = 20;          // burst
    private static final double REFILL_PER_SEC = 1.0;   // sustained ~1 req/s/tenant
    private static final long TTL_SECONDS = 3600;        // bucket key expiry (idle tenants)

    // Atomic token-bucket refill + consume: read current tokens/last-refill, compute the
    // refill, decide allow/deny, write back — all in one round-trip so concurrent requests
    // from different instances can never both consume the "last" token.
    private static final String BUCKET_SCRIPT = """
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
            local last = tonumber(redis.call('HGET', KEYS[1], 'last'))
            local capacity = tonumber(ARGV[1])
            local refillPerSec = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            if tokens == nil then
                tokens = capacity
                last = now
            end
            local elapsed = (now - last) / 1000.0
            tokens = math.min(capacity, tokens + elapsed * refillPerSec)
            local allowed = 0
            if tokens >= 1.0 then
                tokens = tokens - 1.0
                allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'last', tostring(now))
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return allowed
            """;

    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>(BUCKET_SCRIPT, Long.class);

    private record Bucket(double tokens, long lastRefillNanos) {
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Optional<StringRedisTemplate> redis;

    public RateLimiter(Optional<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    /** Returns true if allowed; false if the tenant is over its rate. */
    public boolean allow(String tenantId) {
        if (redis.isPresent()) {
            return allowRedis(tenantId);
        }
        return allowLocal(tenantId);
    }

    private boolean allowRedis(String tenantId) {
        String key = "aegis:ratelimit:" + tenantId;
        Long allowed = redis.get().execute(SCRIPT, List.of(key),
                String.valueOf(CAPACITY), String.valueOf(REFILL_PER_SEC),
                String.valueOf(System.currentTimeMillis()), String.valueOf(TTL_SECONDS));
        return allowed != null && allowed == 1L;
    }

    private synchronized boolean allowLocal(String tenantId) {
        long now = System.nanoTime();
        Bucket b = buckets.getOrDefault(tenantId, new Bucket(CAPACITY, now));
        double elapsedSec = (now - b.lastRefillNanos()) / 1_000_000_000.0;
        double tokens = Math.min(CAPACITY, b.tokens() + elapsedSec * REFILL_PER_SEC);
        if (tokens < 1.0) {
            buckets.put(tenantId, new Bucket(tokens, now));
            return false;
        }
        buckets.put(tenantId, new Bucket(tokens - 1.0, now));
        return true;
    }
}
