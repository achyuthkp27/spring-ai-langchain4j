package com.aegis.merged.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * DEV-ONLY token issuer. In production this is your external IdP / OAuth2 authorization
 * server; the app only ever *validates* tokens (JwtService.parse), never mints them.
 * Exposed here so the UI/tests can obtain a signed token to demonstrate real auth.
 *
 * Gated two ways so it can never accidentally ship live: {@code @Profile("!prod")} means the
 * bean isn't even registered under the prod profile (so the route 404s, same as if the class
 * didn't exist), and {@code aegis.auth.dev-tokens} is a separate kill switch for any other
 * environment that should not hand out self-service identities. There is no code path in this
 * app that mints a real token from a real IdP — until one exists, do not point this build at
 * anything with real user data.
 */
@RestController
@RequestMapping("/api/auth")
@Profile("!prod")
@ConditionalOnProperty(prefix = "aegis.auth", name = "dev-tokens", havingValue = "true", matchIfMissing = true)
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    public record TokenRequest(String userId, String tenantId, String role) {
    }

    @PostMapping("/token")
    public Map<String, Object> token(@RequestBody(required = false) TokenRequest req) {
        String userId = req == null || req.userId() == null ? "demo-user" : req.userId();
        String tenantId = req == null || req.tenantId() == null ? "achu-bank" : req.tenantId();
        String role = req == null || req.role() == null ? "customer" : req.role();
        String token = jwtService.mint(userId, tenantId, role);
        return Map.of("token", token, "userId", userId, "role", role, "tenantId", tenantId);
    }
}
