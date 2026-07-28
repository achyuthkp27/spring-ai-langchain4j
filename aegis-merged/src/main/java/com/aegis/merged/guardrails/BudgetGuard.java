package com.aegis.merged.guardrails;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tenant token budget (OWASP LLM10: Unbounded Consumption). Denies a request
 * once a tenant exceeds its quota BEFORE spending money on the model call.
 *
 * Backed by Redis (atomic INCRBY-with-expiry via Lua, so a fresh daily key is created
 * exactly once) when {@code aegis.redis.enabled=true} — required for the daily counter AND
 * the per-tenant budget limit to be consistent across instances (an admin's
 * {@code POST /api/admin/budget} on one instance must be visible on every other instance).
 * Falls back to the original in-memory maps otherwise.
 */
@Component
public class BudgetGuard {

    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String tenant) {
            super("Token budget exceeded for tenant: " + tenant);
        }
    }

    private static final long DEFAULT_BUDGET = 100_000;

    // Atomic "increment, and set expiry only on first creation" — a naive INCRBY then EXPIRE
    // would re-arm the TTL on every call, and a naive GET-then-SET would race across instances.
    private static final String INCR_SCRIPT = """
            local val = redis.call('INCRBY', KEYS[1], ARGV[1])
            if val == tonumber(ARGV[1]) then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return val
            """;
    private static final RedisScript<Long> INCR = new DefaultRedisScript<>(INCR_SCRIPT, Long.class);

    /** Consumption is tracked per UTC day — the budget is a DAILY quota. Without a
        window, a tenant that ever crossed the limit stayed locked out until a
        restart or a manual budget bump. */
    private record Window(long epochDay, AtomicLong used) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Map<String, Long> budgets = new ConcurrentHashMap<>();
    private final Optional<StringRedisTemplate> redis;

    public BudgetGuard(Optional<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    public void setBudget(String tenant, long budget) {
        if (redis.isPresent()) {
            redis.get().opsForValue().set("aegis:budget:limit:" + tenant, String.valueOf(budget));
        } else {
            budgets.put(tenant, budget);
        }
    }

    private long budgetFor(String tenant) {
        if (redis.isPresent()) {
            String v = redis.get().opsForValue().get("aegis:budget:limit:" + tenant);
            return v != null ? Long.parseLong(v) : DEFAULT_BUDGET;
        }
        return budgets.getOrDefault(tenant, DEFAULT_BUDGET);
    }

    public void checkOrThrow(String tenant) {
        if (consumed(tenant) >= budgetFor(tenant)) {
            throw new BudgetExceededException(tenant);
        }
    }

    public void record(String tenant, long tokens) {
        if (redis.isPresent()) {
            redis.get().execute(INCR, List.of(dailyKey(tenant)),
                    String.valueOf(tokens), String.valueOf(secondsUntilNextUtcDay()));
        } else {
            todays(tenant).addAndGet(tokens);
        }
    }

    public long consumed(String tenant) {
        if (redis.isPresent()) {
            String v = redis.get().opsForValue().get(dailyKey(tenant));
            return v != null ? Long.parseLong(v) : 0L;
        }
        return todays(tenant).get();
    }

    private String dailyKey(String tenant) {
        return "aegis:budget:" + tenant + ":" + LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }

    private static long secondsUntilNextUtcDay() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        return Duration.between(now, midnight).getSeconds() + 60; // small buffer
    }

    private AtomicLong todays(String tenant) {
        long today = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        return windows.compute(tenant, (k, w) ->
                (w == null || w.epochDay() != today) ? new Window(today, new AtomicLong()) : w
        ).used();
    }
}
