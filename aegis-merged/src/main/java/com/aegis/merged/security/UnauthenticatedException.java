package com.aegis.merged.security;

public class UnauthenticatedException extends AccessDeniedException {
    public UnauthenticatedException(String message) {
        super(message);
    }
}
