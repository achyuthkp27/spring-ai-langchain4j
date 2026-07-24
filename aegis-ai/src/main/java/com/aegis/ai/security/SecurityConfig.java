package com.aegis.ai.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)   // stateless JWT API, not cookie-based
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Authorize only the initial REQUEST dispatch, not ASYNC/ERROR.
                        // The SSE streaming endpoints return a Flux → Spring MVC does an
                        // async re-dispatch after the body is sent; re-authorizing it
                        // (with no security context on that thread) threw AccessDenied on
                        // an already-committed response and reset the stream. The request
                        // is fully authorized on REQUEST; the continuation needs no re-check.
                        .shouldFilterAllDispatcherTypes(false)
                        // Public: the UIs, dev login, health, and (POC) the MCP transport.
                        // (admin.html is a static shell — every API it calls needs an admin JWT.)
                        .requestMatchers("/", "/index.html", "/admin.html", "/api/auth/**",
                                "/actuator/health/**", "/actuator/prometheus").permitAll()
                        // MCP endpoints are permitted for the POC; production hardening =
                        // OAuth2 on MCP (deferred — needs client-side OAuth support). See README.
                        .requestMatchers("/sse", "/mcp/**").permitAll()
                        // All admin APIs (analytics, budget, circuit, ingest, cache) need
                        // the admin permission from a verified JWT.
                        .requestMatchers("/api/admin/**").hasAuthority("PERM_admin:all")
                        // Everything else (the app APIs) requires a valid JWT.
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))   // 401, not 403
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
