package com.aegis.lc4j.security;

import java.util.Set;

/**
 * The authenticated customer. Baked into tool objects at construction time —
 * the model can request actions but never sees or controls the identity used to
 * authorize them.
 */
public record Principal(String userId, String tenantId, Set<String> permissions) {

    public boolean can(String permission) {
        return permissions.contains(permission);
    }
}
