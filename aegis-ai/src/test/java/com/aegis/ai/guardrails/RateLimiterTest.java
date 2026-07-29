package com.aegis.ai.guardrails;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    @DisplayName("A rapid burst from one tenant is eventually rate-limited")
    void burstIsLimited() {
        var limiter = new RateLimiter();
        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (limiter.allow("burst-tenant")) allowed++;
        }
        
        assertThat(allowed).isLessThanOrEqualTo(25);
        assertThat(limiter.allow("a-different-tenant")).isTrue();   
    }
}
