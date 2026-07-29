package com.aegis.lc4j.security;

import java.util.Set;

public record Principal(String userId, String tenantId, Set<String> permissions) {

    public boolean can(String permission) {
        return permissions.contains(permission);
    }
}
