package com.aegis.merged.tools;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ConfirmationGuard {

    private static final Duration TTL = Duration.ofMinutes(5);

    private static final int SWEEP_THRESHOLD = 500;
    private static final String FIELD_SEPARATOR = "###";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int TOKEN_LENGTH = 8;

    private record PendingAction(String userId, String tool, String argsKey, Instant expiresAt,
                                 AtomicBoolean used) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final ConcurrentHashMap<String, PendingAction> pending = new ConcurrentHashMap<>();

    public String issue(String userId, String tool, Object... args) {
        sweepExpiredIfLarge();
        String token = newToken();
        pending.put(token, new PendingAction(userId, tool, canon(args), Instant.now().plus(TTL), new AtomicBoolean(false)));
        return token;
    }

    private static String newToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_ALPHABET.charAt(RANDOM.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }

    public boolean verify(String token, String userId, String tool, Object... args) {
        if (token == null || token.isBlank()) return false;
        PendingAction a = pending.get(token);
        if (a == null) return false;
        if (a.expired()) {
            pending.remove(token);
            return false;
        }
        if (!a.userId().equals(userId) || !a.tool().equals(tool) || !a.argsKey().equals(canon(args))) {
            return false;
        }
        if (!a.used().compareAndSet(false, true)) {
            return false; 
        }
        pending.remove(token);
        return true;
    }

    private void sweepExpiredIfLarge() {
        if (pending.size() < SWEEP_THRESHOLD) return;
        pending.values().removeIf(PendingAction::expired);
    }

    private static String canon(Object... args) {
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
            sb.append(a == null ? "null" : a.toString()).append(FIELD_SEPARATOR);
        }
        return sb.toString();
    }
}
