package com.aegis.ai.guardrails;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tenant token-bucket rate limiter (OWASP LLM10: Unbounded Consumption).
 * A refill-over-time bucket: bounded burst, sustained rate cap. In-memory for the
 * POC; production would use Redis so the limit is shared across instances.
 */
@Component
public class RateLimiter {

    private static final double CAPACITY = 20;          // burst
    private static final double REFILL_PER_SEC = 1.0;   // sustained ~1 req/s/tenant

    private record Bucket(double tokens, long lastRefillNanos) {
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Returns true if allowed; false if the tenant is over its rate. */
    public synchronized boolean allow(String tenantId) {
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
