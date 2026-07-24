package com.aegis.ai.security;

import java.util.Set;

/**
 * The authenticated caller. Flows through ToolContext, invisible to the LLM —
 * the model can request actions but never sees or controls the identity used to
 * authorize them. In production this is derived from the validated JWT.
 */
public record Principal(String userId, String tenantId, Set<String> permissions) {

    public boolean can(String permission) {
        return permissions.contains(permission);
    }
}
