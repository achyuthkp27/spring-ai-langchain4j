package com.aegis.merged.guardrails;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    @DisplayName("A rapid burst from one user is eventually rate-limited")
    void burstIsLimited() {
        var limiter = new RateLimiter(Optional.empty());
        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (limiter.allow("burst-tenant", "burst-user")) allowed++;
        }
        
        assertThat(allowed).isLessThanOrEqualTo(25);
    }

    @Test
    @DisplayName("A different tenant entirely is unaffected by another tenant's burst")
    void otherTenantsUnaffected() {
        var limiter = new RateLimiter(Optional.empty());
        for (int i = 0; i < 100; i++) limiter.allow("burst-tenant", "burst-user");
        assertThat(limiter.allow("a-different-tenant", "some-user")).isTrue();
    }

    @Test
    @DisplayName("One chatty user exhausting their own bucket does not rate-limit a different user of the same tenant")
    void oneUserCannotStarveAnotherUserOfTheSameTenant() {
        var limiter = new RateLimiter(Optional.empty());

        for (int i = 0; i < 50; i++) limiter.allow("achu-bank", "user-a");
        assertThat(limiter.allow("achu-bank", "user-a")).isFalse();

        assertThat(limiter.allow("achu-bank", "user-b")).isTrue();
    }
}
