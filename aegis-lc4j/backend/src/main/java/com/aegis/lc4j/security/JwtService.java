package com.aegis.lc4j.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Mints and validates signed JWTs (HS256). tenant/role/user are SIGNED claims.
 * (A real deployment uses an external IdP; the validation side is identical.)
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${aegis.jwt.secret}") String secret,
                      @Value("${aegis.jwt.ttl-seconds:3600}") long ttlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String mint(String userId, String tenantId, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .claim("tenantId", tenantId)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    /** Validates signature + expiry and returns the Principal, or throws. */
    public Principal parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        String role = c.get("role", String.class);
        return new Principal(c.getSubject(), c.get("tenantId", String.class), permissionsFor(role));
    }

    public static Set<String> permissionsFor(String role) {
        return switch (role == null ? "" : role) {
            // Self-service customer: can read own accounts, dispute own transactions,
            // manage own cards, and request (not approve) provisional credit.
            case "customer" -> Set.of("account:read", "cases:create", "credit:request", "cards:manage");
            case "read-only" -> Set.of("account:read");
            // Operator of the bot: audit/analytics + admin actions, not money-adjacent perms.
            case "admin" -> Set.of("account:read", "admin:all");
            default -> Set.of();
        };
    }

    public static List<String> knownRoles() {
        return List.of("customer", "read-only", "admin");
    }
}
