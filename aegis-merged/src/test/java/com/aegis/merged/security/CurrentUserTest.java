package com.aegis.merged.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(Principal p) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null));
    }

    @Test
    void blankRequestDefaultsToCallersOwnTenant() {
        authenticateAs(new Principal("u1", "achu-bank", Set.of("admin:all")));
        assertThat(CurrentUser.requireTenantAccess(null)).isEqualTo("achu-bank");
        assertThat(CurrentUser.requireTenantAccess("")).isEqualTo("achu-bank");
    }

    @Test
    void ownTenantIsAlwaysAllowed() {
        authenticateAs(new Principal("u1", "achu-bank", Set.of("admin:all")));
        assertThat(CurrentUser.requireTenantAccess("achu-bank")).isEqualTo("achu-bank");
    }

    @Test
    void anOrdinaryAdminCannotReachAnotherTenant() {
        authenticateAs(new Principal("u1", "achu-bank", Set.of("admin:all")));
        assertThatThrownBy(() -> CurrentUser.requireTenantAccess("globex-bank"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void platformAdminCanReachAnyTenant() {
        authenticateAs(new Principal("u1", "achu-bank", Set.of("admin:all", "platform:admin")));
        assertThat(CurrentUser.requireTenantAccess("globex-bank")).isEqualTo("globex-bank");
    }

    @Test
    void rolePermissionsMatchExpectedTenantScoping() {
        assertThat(JwtService.permissionsFor("admin")).doesNotContain("platform:admin");
        assertThat(JwtService.permissionsFor("platform-admin")).contains("platform:admin");
        assertThat(JwtService.permissionsFor("customer"))
                .contains("money:transfer", "profile:write", "account:write")
                .doesNotContain("admin:all");
        assertThat(JwtService.permissionsFor("read-only"))
                .containsExactly("account:read");
    }
}
