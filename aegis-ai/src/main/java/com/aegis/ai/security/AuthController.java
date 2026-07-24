package com.aegis.ai.security;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * DEV-ONLY token issuer. In production this is your external IdP / OAuth2 authorization
 * server; the app only ever *validates* tokens (JwtService.parse), never mints them.
 * Exposed here so the UI/tests can obtain a signed token to demonstrate real auth.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    public record TokenRequest(String userId, String tenantId, String role) {
    }

    @PostMapping("/token")
    public Map<String, Object> token(@RequestBody TokenRequest req) {
        String role = req.role() == null ? "read-only" : req.role();
        String token = jwtService.mint(
                req.userId() == null ? "demo-user" : req.userId(),
                req.tenantId() == null ? "achu-bank" : req.tenantId(),
                role);
        return Map.of("token", token, "role", role, "tenantId",
                req.tenantId() == null ? "achu-bank" : req.tenantId());
    }
}
