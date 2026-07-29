package com.aegis.merged.security;

import com.aegis.merged.admin.AuditTrail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "spring.ai.model.chat=none",
        "spring.ai.ollama.init.pull-model-strategy=never"
})
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_CONTAINER_TESTS", matches = "true")
class SecurityWiringTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AuditTrail audit;

    private String mint(String userId, String tenantId, String role) throws Exception {
        String body = json.writeValueAsString(Map.of("userId", userId, "tenantId", tenantId, "role", role));
        String response = mockMvc.perform(post("/api/auth/token").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("token").asText();
    }

    @Test
    void assistantEndpointRequires401WithoutAToken() throws Exception {
        mockMvc.perform(post("/api/assistant").contentType(APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointReturns403ForACustomerToken() throws Exception {
        String token = mint("demo-user", "achu-bank", "customer");
        mockMvc.perform(get("/api/admin/overview").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpointReturns200ForAnAdminToken() throws Exception {
        String token = mint("admin1", "achu-bank", "admin");
        mockMvc.perform(get("/api/admin/overview").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void aForgedTokenIsRejected() throws Exception {

        String forged = io.jsonwebtoken.Jwts.builder()
                .subject("attacker")
                .claim("tenantId", "achu-bank")
                .claim("role", "admin")
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "a-completely-different-256-bit-secret-nobody-configured!!".getBytes()))
                .compact();

        mockMvc.perform(get("/api/admin/overview").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminOfOneTenantCannotReadAnotherTenantsConversations() throws Exception {
        String achuAdmin = mint("achu-admin", "achu-bank", "admin");
        mockMvc.perform(get("/api/admin/conversations").param("tenant", "globex-bank")
                        .header("Authorization", "Bearer " + achuAdmin))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPlatformAdminCanReadAnotherTenantsConversations() throws Exception {
        String platformAdmin = mint("platform-op", "achu-bank", "platform-admin");
        mockMvc.perform(get("/api/admin/conversations").param("tenant", "globex-bank")
                        .header("Authorization", "Bearer " + platformAdmin))
                .andExpect(status().isOk());
    }

    @Test
    void anAdminOfOneTenantCannotSeeAnotherTenantsRowsInOverviewOrEvents() throws Exception {
        String marker = "marker-" + System.nanoTime();
        audit.record("achu-bank", "achu-user", "c1", "llm", 5, 5, marker + "-achu");
        audit.record("globex-bank", "globex-user", "c1", "llm", 5, 5, marker + "-globex");

        String achuAdmin = mint("achu-admin2", "achu-bank", "admin");

        String eventsBody = mockMvc.perform(get("/api/admin/events").param("limit", "500")
                        .header("Authorization", "Bearer " + achuAdmin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(eventsBody).contains(marker + "-achu");
        assertThat(eventsBody).doesNotContain(marker + "-globex");

        String overviewBody = mockMvc.perform(get("/api/admin/overview")
                        .header("Authorization", "Bearer " + achuAdmin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(overviewBody).doesNotContain("globex-bank");
    }
}
