package com.aegis.merged.guardrails;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * A per-tenant daily token budget. This is a <b>soft quota</b>, not a hard cap: the check runs at
 * request start and the charge ({@link #record}) lands at response end, because the token count
 * isn't known until generation completes. Concurrent requests can therefore all pass the check
 * before any of them records, so a tenant may overshoot its budget by roughly
 * {@code concurrency x tokens_per_call}. A hard cap would require reserving an estimated cost up
 * front and reconciling afterwards; the overshoot is accepted here as the price of charging actual
 * (not estimated) usage. Redis failures fail open with a logged warning — a budget outage must not
 * take down chat.
 */
@Component
public class BudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);

    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String tenant) {
            super("Token budget exceeded for tenant: " + tenant);
        }
    }

    private final long defaultBudget;

    private static final String INCR_SCRIPT = """
            local val = redis.call('INCRBY', KEYS[1], ARGV[1])
            if val == tonumber(ARGV[1]) then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return val
            """;
    private static final RedisScript<Long> INCR = new DefaultRedisScript<>(INCR_SCRIPT, Long.class);

    private static final String CHECK_SCRIPT = """
            local used = tonumber(redis.call('GET', KEYS[1]))
            if used == nil then used = 0 end
            local limit = tonumber(redis.call('GET', KEYS[2]))
            if limit == nil then limit = tonumber(ARGV[1]) end
            if used >= limit then return 0 end
            return 1
            """;
    private static final RedisScript<Long> CHECK = new DefaultRedisScript<>(CHECK_SCRIPT, Long.class);

    private record Window(long epochDay, AtomicLong used) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Map<String, Long> budgets = new ConcurrentHashMap<>();
    private final Optional<StringRedisTemplate> redis;

    public BudgetGuard(Optional<StringRedisTemplate> redis) {
        this(redis, 100_000);
    }

    @Autowired
    public BudgetGuard(Optional<StringRedisTemplate> redis,
                       @Value("${aegis.budget.default-daily-tokens:100000}") long defaultBudget) {
        this.redis = redis;
        this.defaultBudget = defaultBudget;
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
            try {
                String v = redis.get().opsForValue().get("aegis:budget:limit:" + tenant);
                return v != null ? Long.parseLong(v) : defaultBudget;
            } catch (Exception e) {
                log.warn("budget.redis.failed fail-open tenant={} err={}", tenant, e.toString());
                return defaultBudget;
            }
        }
        return budgets.getOrDefault(tenant, defaultBudget);
    }

    public void checkOrThrow(String tenant) {
        if (redis.isPresent()) {
            try {
                Long allowed = redis.get().execute(CHECK,
                        List.of(dailyKey(tenant), "aegis:budget:limit:" + tenant),
                        String.valueOf(defaultBudget));
                if (allowed != null && allowed == 0L) {
                    throw new BudgetExceededException(tenant);
                }
            } catch (BudgetExceededException e) {
                throw e;
            } catch (Exception e) {
                log.warn("budget.redis.failed fail-open tenant={} err={}", tenant, e.toString());
            }
            return;
        }
        if (consumed(tenant) >= budgetFor(tenant)) {
            throw new BudgetExceededException(tenant);
        }
    }

    public void record(String tenant, long tokens) {
        if (redis.isPresent()) {
            try {
                redis.get().execute(INCR, List.of(dailyKey(tenant)),
                        String.valueOf(tokens), String.valueOf(secondsUntilNextUtcDay()));
            } catch (Exception e) {
                log.warn("budget.redis.failed tenant={} err={}", tenant, e.toString());
            }
            return;
        }
        todays(tenant).addAndGet(tokens);
    }

    public long consumed(String tenant) {
        if (redis.isPresent()) {
            try {
                String v = redis.get().opsForValue().get(dailyKey(tenant));
                return v != null ? Long.parseLong(v) : 0L;
            } catch (Exception e) {
                log.warn("budget.redis.failed fail-open tenant={} err={}", tenant, e.toString());
                return 0L;
            }
        }
        return todays(tenant).get();
    }

    private String dailyKey(String tenant) {
        return "aegis:budget:" + tenant + ":" + LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }

    private static long secondsUntilNextUtcDay() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        return Duration.between(now, midnight).getSeconds() + 60;
    }

    private AtomicLong todays(String tenant) {
        long today = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        return windows.compute(tenant, (k, w) ->
                (w == null || w.epochDay() != today) ? new Window(today, new AtomicLong()) : w
        ).used();
    }
}
