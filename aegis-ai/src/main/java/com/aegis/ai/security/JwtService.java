package com.aegis.ai.security;

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

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${aegis.jwt.secret:change-me-in-prod-this-is-a-dev-only-256bit-secret!!}") String secret,
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

    public Principal parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        String role = c.get("role", String.class);
        return new Principal(c.getSubject(), c.get("tenantId", String.class), permissionsFor(role));
    }

    public static Set<String> permissionsFor(String role) {
        return switch (role == null ? "" : role) {
            case "disputes-analyst" -> Set.of("account:read", "cases:create", "credit:request", "cards:manage");
            case "read-only" -> Set.of("account:read");

            case "admin" -> Set.of("account:read", "admin:all");
            default -> Set.of();
        };
    }

    public static List<String> knownRoles() {
        return List.of("disputes-analyst", "read-only", "admin");
    }
}
