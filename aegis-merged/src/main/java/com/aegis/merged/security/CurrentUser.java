package com.aegis.merged.security;

import org.springframework.security.core.context.SecurityContextHolder;

/** Convenience accessor for the authenticated Principal set by the JWT filter. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Principal get() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Principal p) {
            return p;
        }
        throw new AccessDeniedException("No authenticated user");
    }

    /** Every /api/admin/** endpoint that takes or defaults a tenantId must call this before
        touching another tenant's data. hasAuthority("PERM_admin:all") (see SecurityConfig) only
        proves "an admin of SOME bank" — without this, an achu-bank admin's token reads/erases
        globex-bank's conversations, audit rows, and AML flags just as freely as their own.
        {@code requestedTenant} may be null/blank to mean "default to my own tenant". */
    public static String requireTenantAccess(String requestedTenant) {
        Principal p = get();
        if (requestedTenant == null || requestedTenant.isBlank()) {
            return p.tenantId();
        }
        if (!p.can("platform:admin") && !p.tenantId().equals(requestedTenant)) {
            throw new AccessDeniedException("Not your tenant.");
        }
        return requestedTenant;
    }
}
