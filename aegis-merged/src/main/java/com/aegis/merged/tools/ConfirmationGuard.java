package com.aegis.merged.tools;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ConfirmationGuard {

    private static final Duration TTL = Duration.ofMinutes(5);

    private static final int SWEEP_THRESHOLD = 500;
    private static final String REDIS_KEY_PREFIX = "aegis:confirm:";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int TOKEN_LENGTH = 8;

    private static final String CONSUME_SCRIPT = """
            local v = redis.call('GET', KEYS[1])
            if v then redis.call('DEL', KEYS[1]) end
            return v
            """;
    private static final RedisScript<String> CONSUME = new DefaultRedisScript<>(CONSUME_SCRIPT, String.class);

    private record PendingAction(String userId, String tool, String argsKey, Instant expiresAt,
                                 AtomicBoolean used) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final ConcurrentHashMap<String, PendingAction> pending = new ConcurrentHashMap<>();
    private final Optional<StringRedisTemplate> redis;

    public ConfirmationGuard() {
        this(Optional.empty());
    }

    @Autowired
    public ConfirmationGuard(Optional<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    public String issue(String userId, String tool, Object... args) {
        String token = newToken();
        if (redis.isPresent()) {
            redis.get().opsForValue().set(REDIS_KEY_PREFIX + token, canon(userId, tool, canon(args)), TTL);
        } else {
            sweepExpiredIfLarge();
            pending.put(token, new PendingAction(userId, tool, canon(args), Instant.now().plus(TTL), new AtomicBoolean(false)));
        }
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
        if (redis.isPresent()) {
            String stored = redis.get().execute(CONSUME, List.of(REDIS_KEY_PREFIX + token));
            return stored != null && stored.equals(canon(userId, tool, canon(args)));
        }
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
            String s = a == null ? "null" : a.toString();
            sb.append(s.length()).append(':').append(s);
        }
        return sb.toString();
    }
}
