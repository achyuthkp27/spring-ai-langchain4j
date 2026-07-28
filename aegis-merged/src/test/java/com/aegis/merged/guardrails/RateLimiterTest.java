package com.aegis.merged.guardrails;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** LLM10 Unbounded Consumption — the per-tenant bucket eventually denies a burst. */
class RateLimiterTest {

    @Test
    @DisplayName("A rapid burst from one tenant is eventually rate-limited")
    void burstIsLimited() {
        var limiter = new RateLimiter(Optional.empty());
        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (limiter.allow("burst-tenant")) allowed++;
        }
        // Capacity is 20; a tight loop can't refill meaningfully, so most are denied.
        assertThat(allowed).isLessThanOrEqualTo(25);
        assertThat(limiter.allow("a-different-tenant")).isTrue();   // other tenants unaffected
    }
}
