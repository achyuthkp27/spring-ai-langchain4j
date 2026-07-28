package com.aegis.merged.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String STRONG_SECRET = "a-real-256-bit-secret-that-is-not-the-default!!";

    private static MockEnvironment prodEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    @Test
    void refusesToStartUnderProdWithDefaultSecret() {
        MockEnvironment env = prodEnv();
        assertThatThrownBy(() -> new JwtService(JwtService.DEFAULT_SECRET, 3600, env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
    }

    @Test
    void refusesToStartUnderProdWithShortCustomSecret() {
        MockEnvironment env = prodEnv();
        assertThatThrownBy(() -> new JwtService("too-short-12", 3600, env))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startsUnderProdWithAStrongSecret() {
        MockEnvironment env = prodEnv();
        JwtService svc = new JwtService(STRONG_SECRET, 3600, env);
        String token = svc.mint("u1", "achu-bank", "customer");
        assertThat(svc.parse(token).userId()).isEqualTo("u1");
    }

    @Test
    void logsButDoesNotThrowOutsideProdWithDefaultSecret() {
        MockEnvironment env = new MockEnvironment(); // default profile, not "prod"
        JwtService svc = new JwtService(JwtService.DEFAULT_SECRET, 3600, env);
        String token = svc.mint("u1", "achu-bank", "customer");
        assertThat(svc.parse(token).userId()).isEqualTo("u1");
    }

    @Test
    void mintedTokenCarriesSignedTenantAndRolePermissions() {
        MockEnvironment env = new MockEnvironment();
        JwtService svc = new JwtService(STRONG_SECRET, 3600, env);
        Principal p = svc.parse(svc.mint("u1", "globex-bank", "admin"));
        assertThat(p.tenantId()).isEqualTo("globex-bank");
        assertThat(p.can("admin:all")).isTrue();
        assertThat(p.can("money:transfer")).isFalse();
    }
}
