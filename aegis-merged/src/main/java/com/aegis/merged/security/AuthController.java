package com.aegis.merged.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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
