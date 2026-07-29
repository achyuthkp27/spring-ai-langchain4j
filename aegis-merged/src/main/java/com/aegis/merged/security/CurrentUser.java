package com.aegis.merged.security;

import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static Principal get() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Principal p) {
            return p;
        }
        throw new UnauthenticatedException("No authenticated user");
    }

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
