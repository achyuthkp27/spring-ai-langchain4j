package com.aegis.merged.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    static final String DEFAULT_SECRET = "change-me-in-prod-this-is-a-dev-only-256bit-secret!!";

    private final SecretKey key;
    private final long ttlSeconds;
    private final boolean usingDefaultSecret;
    private final Environment env;

    private final Set<String> knownTenants;

    private static final int MIN_SECRET_BYTES = 32;

    public JwtService(@Value("${aegis.jwt.secret:" + DEFAULT_SECRET + "}") String secret,
                      @Value("${aegis.jwt.ttl-seconds:3600}") long ttlSeconds,
                      @Value("${aegis.known-tenants:achu-bank,globex-bank}") String knownTenantsCsv,
                      Environment env) {
        this.usingDefaultSecret = DEFAULT_SECRET.equals(secret);
        this.env = env;
        this.knownTenants = knownTenantsCsv == null || knownTenantsCsv.isBlank()
                ? Set.of()
                : Arrays.stream(knownTenantsCsv.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));

        if (prod && (usingDefaultSecret || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES)) {
            throw new IllegalStateException(
                    "Refusing to start under the 'prod' profile with a missing/default/short "
                            + "aegis.jwt.secret (need >= " + MIN_SECRET_BYTES + " bytes). Set a real secret "
                            + "(e.g. from a KMS/Vault-backed env var) before running in prod.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
        if (usingDefaultSecret) {
            log.error("SECURITY: aegis.jwt.secret is unset — using the built-in DEV-ONLY default. "
                    + "Every JWT this instance mints or trusts is forgeable by anyone who reads this "
                    + "source file. Set aegis.jwt.secret to a real secret before any shared/prod use.");
        }
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
        String tenantId = c.get("tenantId", String.class);
        if (!knownTenants.isEmpty() && !knownTenants.contains(tenantId)) {
            throw new AccessDeniedException("Unknown tenant.");
        }
        return new Principal(c.getSubject(), tenantId, permissionsFor(role));
    }

    public static Set<String> permissionsFor(String role) {
        return switch (role == null ? "" : role) {

            case "customer" -> Set.of("account:read", "account:write", "money:transfer",
                    "cases:create", "credit:request", "cards:manage", "profile:write");
            case "read-only" -> Set.of("account:read");

            case "admin" -> Set.of("account:read", "admin:all");

            case "platform-admin" -> Set.of("account:read", "admin:all", "platform:admin");
            default -> Set.of();
        };
    }

    public static List<String> knownRoles() {
        return List.of("customer", "read-only", "admin", "platform-admin");
    }
}
