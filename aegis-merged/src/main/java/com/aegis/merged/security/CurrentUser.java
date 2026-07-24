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
}
