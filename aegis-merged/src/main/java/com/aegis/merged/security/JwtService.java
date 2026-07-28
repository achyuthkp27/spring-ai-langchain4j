package com.aegis.merged.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Mints and validates signed JWTs (HS256). tenant/role/user are SIGNED claims —
 * a client can no longer just assert them via a header. This is the keystone: the
 * Principal the whole authz/tenant model trusts now comes from a verified token.
 *
 * (A real deployment uses an external IdP / OAuth2 issuer + asymmetric keys; the
 * validation side is identical — only the key source changes.)
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    static final String DEFAULT_SECRET = "change-me-in-prod-this-is-a-dev-only-256bit-secret!!";

    private final SecretKey key;
    private final long ttlSeconds;
    private final boolean usingDefaultSecret;
    private final Environment env;

    // HS256 needs a 256-bit (32-byte) key at minimum; jjwt's Keys.hmacShaKeyFor already throws
    // below this, but a same-length low-entropy passphrase (e.g. 32 repeated chars) passes that
    // check while still being trivially guessable — MIN_SECRET_BYTES is a floor, not a proof of
    // real entropy, but it turns the common "short/default secret" mistake into a startup
    // failure with a clear message instead of a silent forgeable deployment.
    private static final int MIN_SECRET_BYTES = 32;

    public JwtService(@Value("${aegis.jwt.secret:" + DEFAULT_SECRET + "}") String secret,
                      @Value("${aegis.jwt.ttl-seconds:3600}") long ttlSeconds,
                      Environment env) {
        this.usingDefaultSecret = DEFAULT_SECRET.equals(secret);
        this.env = env;
        boolean prod = env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));
        // Runs in the CONSTRUCTOR, not an ApplicationReadyEvent listener: a listener fires
        // after the connector is already bound and accepting traffic, so a "refuse to start"
        // check there still lets a handful of requests through the door first. Failing here
        // aborts context refresh before Tomcat ever opens the port.
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

    /** Validates signature + expiry and returns the Principal, or throws. */
    public Principal parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        String role = c.get("role", String.class);
        return new Principal(c.getSubject(), c.get("tenantId", String.class), permissionsFor(role));
    }

    public static Set<String> permissionsFor(String role) {
        return switch (role == null ? "" : role) {
            // Self-service customer: read own accounts, mutate their own profile/account
            // settings, move money only between their own accounts, dispute own transactions,
            // manage own cards, request (not approve) provisional credit. Split from plain
            // "account:read" so a read-only token can never reach a mutating tool by accident —
            // see BankingTools, every write-tool now requires one of these, not account:read.
            case "customer" -> Set.of("account:read", "account:write", "money:transfer",
                    "cases:create", "credit:request", "cards:manage", "profile:write");
            case "read-only" -> Set.of("account:read");
            // Operator of ONE bank's bot: full audit/analytics visibility + admin actions for
            // their own tenant, but NOT the money-adjacent permissions (cases:create,
            // credit:request), and NOT another tenant's data — see CurrentUser.requireTenantAccess,
            // which every /api/admin/** handler calls before touching a tenantId it was given.
            case "admin" -> Set.of("account:read", "admin:all");
            // Genuinely cross-tenant operator view (platform ops, not a single bank's staff) —
            // the only role requireTenantAccess lets read/erase a tenant other than its own.
            case "platform-admin" -> Set.of("account:read", "admin:all", "platform:admin");
            default -> Set.of();
        };
    }

    public static List<String> knownRoles() {
        return List.of("customer", "read-only", "admin", "platform-admin");
    }
}
