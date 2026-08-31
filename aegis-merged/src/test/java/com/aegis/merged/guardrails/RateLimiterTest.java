package com.aegis.merged.guardrails;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.List;

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

    @Test
    @DisplayName("Tenant bucket is untouched by requests denied at the user-bucket stage")
    void tenantBucketUntouchedOnUserDenial() {
        var limiter = new RateLimiter(Optional.empty(), 5, 1.0, 8, 1.0);

        for (int i = 0; i < 5; i++) assertThat(limiter.allow("t", "user-a")).isTrue();
        assertThat(limiter.allow("t", "user-a")).isFalse();

        for (int i = 0; i < 10; i++) limiter.allow("t", "user-a");

        int userBSuccesses = 0;
        for (int i = 0; i < 5; i++) if (limiter.allow("t", "user-b")) userBSuccesses++;

        assertThat(userBSuccesses).isEqualTo(3);
    }

    @Test
    @DisplayName("A Redis failure degrades to the local bucket instead of failing the request")
    void redisFailureFallsBackToLocal() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        var limiter = new RateLimiter(Optional.of(redisTemplate), 5, 1.0, 200, 10.0);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allow("achu-bank", "u1")).isTrue();
        }
        assertThat(limiter.allow("achu-bank", "u1")).isFalse();
    }
}
