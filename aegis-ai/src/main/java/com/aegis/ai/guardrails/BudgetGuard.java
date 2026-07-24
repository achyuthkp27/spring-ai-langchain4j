package com.aegis.ai.guardrails;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tenant token budget (OWASP LLM10: Unbounded Consumption). Denies a request
 * once a tenant exceeds its quota BEFORE spending money on the model call.
 */
@Component
public class BudgetGuard {

    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String tenant) {
            super("Token budget exceeded for tenant: " + tenant);
        }
    }

    private static final long DEFAULT_BUDGET = 100_000;

    /** Consumption is tracked per UTC day — the budget is a DAILY quota. Without a
        window, a tenant that ever crossed the limit stayed locked out until a
        restart or a manual budget bump. */
    private record Window(long epochDay, AtomicLong used) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Map<String, Long> budgets = new ConcurrentHashMap<>();

    public void setBudget(String tenant, long budget) {
        budgets.put(tenant, budget);
    }

    private AtomicLong todays(String tenant) {
        long today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay();
        return windows.compute(tenant, (k, w) ->
                (w == null || w.epochDay() != today) ? new Window(today, new AtomicLong()) : w
        ).used();
    }

    public void checkOrThrow(String tenant) {
        long budget = budgets.getOrDefault(tenant, DEFAULT_BUDGET);
        if (todays(tenant).get() >= budget) {
            throw new BudgetExceededException(tenant);
        }
    }

    public void record(String tenant, long tokens) {
        todays(tenant).addAndGet(tokens);
    }

    public long consumed(String tenant) {
        return todays(tenant).get();
    }
}
