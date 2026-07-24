package com.aegis.lc4j.security;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * DEV-ONLY token issuer. In production this is your external IdP; the app only
 * ever validates tokens. Default identity = the demo customer.
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
    public Map<String, Object> token(@RequestBody(required = false) TokenRequest req) {
        String userId = req == null || req.userId() == null ? "demo-user" : req.userId();
        String tenantId = req == null || req.tenantId() == null ? "achu-bank" : req.tenantId();
        String role = req == null || req.role() == null ? "customer" : req.role();
        String token = jwtService.mint(userId, tenantId, role);
        return Map.of("token", token, "userId", userId, "role", role, "tenantId", tenantId);
    }
}
