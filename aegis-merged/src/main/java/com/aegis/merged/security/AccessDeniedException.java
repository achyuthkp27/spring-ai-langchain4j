package com.aegis.merged.security;

/** Thrown inside a tool when the propagated principal lacks the required permission. */
public class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String message) {
        super(message);
    }
}
