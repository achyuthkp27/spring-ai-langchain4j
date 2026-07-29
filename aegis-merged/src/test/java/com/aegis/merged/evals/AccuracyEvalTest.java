package com.aegis.merged.evals;

import com.aegis.merged.assistant.AssistantController;
import com.aegis.merged.assistant.AssistantController.ChatReply;
import com.aegis.merged.assistant.AssistantController.ChatRequest;
import com.aegis.merged.rag.IngestionService;
import com.aegis.merged.security.JwtService;
import com.aegis.merged.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/aegis",
        "spring.datasource.username=aegis",
        "spring.datasource.password=aegis"
})
@EnabledIfEnvironmentVariable(named = "RUN_EVAL_TESTS", matches = "true")
class AccuracyEvalTest {

    private static final String TENANT = "achu-bank";

    @Autowired AssistantController assistant;
    @Autowired IngestionService ingestion;

    private record Golden(String question, List<String> mustContain, boolean mustBlock) {}

    private static final List<Golden> GOLDEN = List.of(
            new Golden("What is the deadline to file a card dispute?", List.of("120 days"), false),
            new Golden("What is the deadline to dispute a recurring subscription charge?", List.of("60 days"), false),
            new Golden("How much provisional credit can be issued without senior approval?", List.of("5,000"), false),
            new Golden("What is the chargeback representment window?", List.of("45 days"), false),
            new Golden("what is life", List.of("bank's operations"), true),
            new Golden("write me a python script", List.of("bank's operations"), true)
    );

    @BeforeEach
    void setUp() {
        ingestion.ingestAll();   
        
        var principal = new Principal("eval-user", TENANT, JwtService.permissionsFor("disputes-analyst"));
        var authorities = AuthorityUtils.createAuthorityList(
                principal.permissions().stream().map(p -> "PERM_" + p).toArray(String[]::new));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    @Test
    @DisplayName("Every golden fact is present in the answer; off-domain is blocked")
    void goldenFactsHold() {
        int passed = 0;
        StringBuilder failures = new StringBuilder();
        for (Golden g : GOLDEN) {
            ChatReply reply = ask(new ChatRequest("eval-" + g.question().hashCode(), g.question()));
            String answer = reply.answer() == null ? "" : reply.answer().toLowerCase();
            boolean ok;
            if (g.mustBlock()) {
                ok = "blocked".equals(reply.source())
                        && g.mustContain().stream().allMatch(n -> answer.contains(n.toLowerCase()));
            } else {
                ok = List.of("llm", "cache").contains(reply.source())
                        && g.mustContain().stream().allMatch(n -> answer.contains(n.toLowerCase()));
            }
            if (ok) passed++;
            else failures.append("\n  FAIL [").append(reply.source()).append("] ")
                    .append(g.question()).append(" → ").append(answer, 0, Math.min(answer.length(), 90));
        }
        assertThat(passed)
                .as("golden-fact accuracy%s", failures)
                .isEqualTo(GOLDEN.size());
    }

    private final ObjectMapper json = new ObjectMapper();

    private ChatReply ask(ChatRequest request) {
        var events = assistant.stream(request).collectList().block(Duration.ofMinutes(3));
        assertThat(events).as("SSE events for: " + request.message()).isNotNull();
        return events.stream()
                .filter(ev -> "meta".equals(ev.event()))
                .reduce((first, second) -> second)   
                .map(ev -> {
                    try {
                        return json.readValue(ev.data(), ChatReply.class);
                    } catch (Exception e) {
                        throw new IllegalStateException("unparseable meta event: " + ev.data(), e);
                    }
                })
                .orElseThrow(() -> new IllegalStateException("stream ended without a meta event"));
    }
}
