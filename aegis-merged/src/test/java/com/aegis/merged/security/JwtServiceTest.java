package com.aegis.merged.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String STRONG_SECRET = "a-real-256-bit-secret-that-is-not-the-default!!";
    private static final String KNOWN_TENANTS = "achu-bank,globex-bank";

    private static MockEnvironment prodEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    @Test
    void refusesToStartUnderProdWithDefaultSecret() {
        MockEnvironment env = prodEnv();
        assertThatThrownBy(() -> new JwtService(JwtService.DEFAULT_SECRET, 3600, KNOWN_TENANTS, env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
    }

    @Test
    void refusesToStartUnderProdWithShortCustomSecret() {
        MockEnvironment env = prodEnv();
        assertThatThrownBy(() -> new JwtService("too-short-12", 3600, KNOWN_TENANTS, env))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startsUnderProdWithAStrongSecret() {
        MockEnvironment env = prodEnv();
        JwtService svc = new JwtService(STRONG_SECRET, 3600, KNOWN_TENANTS, env);
        String token = svc.mint("u1", "achu-bank", "customer");
        assertThat(svc.parse(token).userId()).isEqualTo("u1");
    }

    @Test
    void logsButDoesNotThrowOutsideProdWithDefaultSecret() {
        MockEnvironment env = new MockEnvironment(); 
        JwtService svc = new JwtService(JwtService.DEFAULT_SECRET, 3600, KNOWN_TENANTS, env);
        String token = svc.mint("u1", "achu-bank", "customer");
        assertThat(svc.parse(token).userId()).isEqualTo("u1");
    }

    @Test
    void mintedTokenCarriesSignedTenantAndRolePermissions() {
        MockEnvironment env = new MockEnvironment();
        JwtService svc = new JwtService(STRONG_SECRET, 3600, KNOWN_TENANTS, env);
        Principal p = svc.parse(svc.mint("u1", "globex-bank", "admin"));
        assertThat(p.tenantId()).isEqualTo("globex-bank");
        assertThat(p.can("admin:all")).isTrue();
        assertThat(p.can("money:transfer")).isFalse();
    }

    @Test
    void rejectsATenantIdNotOnTheKnownList() {
        MockEnvironment env = new MockEnvironment();
        JwtService svc = new JwtService(STRONG_SECRET, 3600, KNOWN_TENANTS, env);
        String token = svc.mint("u1", "not-a-real-bank", "customer");
        assertThatThrownBy(() -> svc.parse(token)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void emptyKnownTenantsMeansNoRestriction() {
        MockEnvironment env = new MockEnvironment();
        JwtService svc = new JwtService(STRONG_SECRET, 3600, "", env);
        String token = svc.mint("u1", "any-tenant-at-all", "customer");
        assertThat(svc.parse(token).tenantId()).isEqualTo("any-tenant-at-all");
    }
}
