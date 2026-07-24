package com.aegis.lc4j.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
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
                        // Authorize only the initial REQUEST dispatch, not ASYNC/ERROR: the SSE
                        // endpoint completes on an async dispatch with no security context, and
                        // re-authorizing it would reset an already-committed stream.
                        .shouldFilterAllDispatcherTypes(false)
                        .requestMatchers("/api/auth/**", "/actuator/health/**",
                                "/actuator/prometheus").permitAll()
                        // MCP transport public for the POC (production = OAuth2 on MCP).
                        .requestMatchers("/sse", "/mcp/**").permitAll()
                        .requestMatchers("/api/admin/**").hasAuthority("PERM_admin:all")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))   // 401, not 403
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
