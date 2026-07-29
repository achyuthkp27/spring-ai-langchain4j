package com.aegis.merged.guardrails;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final long TTL_SECONDS = 3600;

    private final double userCapacity;
    private final double userRefillPerSec;
    private final double tenantCapacity;
    private final double tenantRefillPerSec;

    private static final String BUCKET_SCRIPT = """
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
            local last = tonumber(redis.call('HGET', KEYS[1], 'last'))
            local capacity = tonumber(ARGV[1])
            local refillPerSec = tonumber(ARGV[2])
            if tokens == nil or last == nil then
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
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return allowed
            """;

    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>(BUCKET_SCRIPT, Long.class);

    private record Bucket(double tokens, long lastRefillNanos) {
    }

    private final ConcurrentMap<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofHours(2))
            .<String, Bucket>build()
            .asMap();
    private final Optional<StringRedisTemplate> redis;

    public RateLimiter(Optional<StringRedisTemplate> redis) {
        this(redis, 20, 1.0, 200, 10.0);
    }

    @Autowired
    public RateLimiter(Optional<StringRedisTemplate> redis,
                       @Value("${aegis.ratelimit.user-capacity:20}") double userCapacity,
                       @Value("${aegis.ratelimit.user-refill-per-sec:1.0}") double userRefillPerSec,
                       @Value("${aegis.ratelimit.tenant-capacity:200}") double tenantCapacity,
                       @Value("${aegis.ratelimit.tenant-refill-per-sec:10.0}") double tenantRefillPerSec) {
        this.redis = redis;
        this.userCapacity = userCapacity;
        this.userRefillPerSec = userRefillPerSec;
        this.tenantCapacity = tenantCapacity;
        this.tenantRefillPerSec = tenantRefillPerSec;
    }

    public boolean allow(String tenantId) {
        return allowBucket("aegis:ratelimit:tenant:" + tenantId, tenantCapacity, tenantRefillPerSec);
    }

    public boolean allow(String tenantId, String userId) {
        if (userId == null || userId.isBlank()) {
            return allow(tenantId);
        }
        if (!allowBucket("aegis:ratelimit:user:" + tenantId + ":" + userId, userCapacity, userRefillPerSec)) {
            return false;
        }
        return allowBucket("aegis:ratelimit:tenant:" + tenantId, tenantCapacity, tenantRefillPerSec);
    }

    private boolean allowBucket(String key, double capacity, double refillPerSec) {
        if (redis.isPresent()) {
            return allowRedis(key, capacity, refillPerSec);
        }
        return allowLocal(key, capacity, refillPerSec);
    }

    private boolean allowRedis(String key, double capacity, double refillPerSec) {
        try {
            Long allowed = redis.get().execute(SCRIPT, List.of(key),
                    String.valueOf(capacity), String.valueOf(refillPerSec), String.valueOf(TTL_SECONDS));
            return allowed != null && allowed == 1L;
        } catch (Exception e) {
            log.warn("ratelimit.redis.failed fail-open-to-local key={} err={}", key, e.toString());
            return allowLocal(key, capacity, refillPerSec);
        }
    }

    private boolean allowLocal(String key, double capacity, double refillPerSec) {
        long now = System.nanoTime();
        boolean[] allowed = new boolean[1];
        buckets.compute(key, (k, existing) -> {
            Bucket b = existing != null ? existing : new Bucket(capacity, now);
            double elapsedSec = (now - b.lastRefillNanos()) / 1_000_000_000.0;
            double tokens = Math.min(capacity, b.tokens() + elapsedSec * refillPerSec);
            if (tokens < 1.0) {
                allowed[0] = false;
                return new Bucket(tokens, now);
            }
            allowed[0] = true;
            return new Bucket(tokens - 1.0, now);
        });
        return allowed[0];
    }
}
