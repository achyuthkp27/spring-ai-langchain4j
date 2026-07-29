package com.aegis.merged.admin;

import com.aegis.merged.guardrails.BudgetGuard;
import com.aegis.merged.guardrails.LlmGuard;
import com.aegis.merged.rag.SemanticCache;
import com.aegis.merged.security.AccessDeniedException;
import com.aegis.merged.security.CurrentUser;
import com.aegis.merged.security.Principal;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AuditTrail audit;
    private final BudgetGuard budget;
    private final LlmGuard llmGuard;
    private final MeterRegistry meters;
    private final JdbcTemplate jdbc;
    private final SemanticCache semanticCache;
    private final AmlMonitor aml;
    private final String chatModel;
    private static final int MAX_CONVERSATIONS_LIMIT = 500;

    public AdminController(AuditTrail audit, BudgetGuard budget, LlmGuard llmGuard,
                           MeterRegistry meters, JdbcTemplate jdbc, SemanticCache semanticCache,
                           AmlMonitor aml,
                           @Value("${spring.ai.ollama.chat.options.model:unknown}") String chatModel) {
        this.audit = audit;
        this.budget = budget;
        this.llmGuard = llmGuard;
        this.meters = meters;
        this.jdbc = jdbc;
        this.semanticCache = semanticCache;
        this.aml = aml;
        this.chatModel = chatModel;
    }

    private static String resolveTenantFilter(String requested) {
        Principal p = CurrentUser.get();
        if (requested == null || requested.isBlank()) {
            return p.can("platform:admin") ? null : p.tenantId();
        }
        if (!p.can("platform:admin") && !p.tenantId().equals(requested)) {
            throw new AccessDeniedException("Not your tenant.");
        }
        return requested;
    }

    private static void requireOwnConversation(String conversationId) {
        Principal p = CurrentUser.get();
        if (p.can("platform:admin")) return;
        if (!conversationId.startsWith(p.tenantId() + ":")) {
            throw new AccessDeniedException("Not your tenant.");
        }
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Long> sources = audit.countsBySource();
        long total = sources.values().stream().mapToLong(Long::longValue).sum();
        long llm = sources.getOrDefault("llm", 0L);
        long cache = sources.getOrDefault("cache", 0L);
        long blocked = sources.entrySet().stream()
                .filter(e -> e.getKey().startsWith("blocked")).mapToLong(Map.Entry::getValue).sum();

        List<Map<String, Object>> spend = new ArrayList<>();
        meters.find("aegis.ai.tokens.total").counters().forEach(c -> spend.add(Map.of(
                "tenant", String.valueOf(c.getId().getTag("tenant")),
                "model", String.valueOf(c.getId().getTag("model")),
                "tokens", Math.round(c.count()))));

        Map<String, Object> tenants = new TreeMap<>();
        for (Map<String, Object> s : spend) {
            String t = (String) s.get("tenant");
            tenants.put(t, Map.of("budgetConsumed", budget.consumed(t)));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startedAt", audit.startedAt().toString());
        out.put("uptimeMinutes", Duration.between(audit.startedAt(), Instant.now()).toMinutes());
        out.put("chatModel", chatModel);
        out.put("requests", Map.of(
                "total", total, "llm", llm, "cache", cache, "blocked", blocked,
                "cacheHitRate", (llm + cache) == 0 ? 0.0 : (double) cache / (llm + cache)));
        out.put("bySource", sources);
        out.put("latencyMs", audit.latency());
        out.put("tokenSpend", spend);
        out.put("tenantBudgets", tenants);
        out.put("toolUsage", audit.toolUsage());
        out.put("llmCircuit", llmGuard.circuitState());
        return out;
    }

    @GetMapping("/events")
    public List<AuditTrail.Event> events(@RequestParam(defaultValue = "100") int limit) {
        return audit.recent(Math.min(limit, AuditTrail.MAX_EVENTS));
    }

    @GetMapping("/timeseries")
    public List<Map<String, Object>> timeseries(@RequestParam(defaultValue = "60") int minutes) {
        return audit.timeseries(Math.min(Math.max(minutes, 5), 24 * 60));
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations(@RequestParam(defaultValue = "50") int limit,
                                                    @RequestParam(required = false) String tenant) {
        String tenantFilter = resolveTenantFilter(tenant);
        int cappedLimit = Math.min(Math.max(limit, 1), MAX_CONVERSATIONS_LIMIT);
        return jdbc.query("""
                SELECT conversation_id, COUNT(*) AS messages, MAX("timestamp") AS last_at
                FROM spring_ai_chat_memory
                WHERE (?::varchar IS NULL OR conversation_id LIKE ?)
                GROUP BY conversation_id
                ORDER BY last_at DESC LIMIT ?""",
                (rs, i) -> {
                    
                    String id = rs.getString("conversation_id");
                    String[] parts = id.split(":", 3);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", id);
                    m.put("tenant", parts.length == 3 ? parts[0] : "?");
                    m.put("user", parts.length == 3 ? parts[1] : "?");
                    m.put("conversation", parts.length == 3 ? parts[2] : id);
                    m.put("messages", rs.getLong("messages"));
                    m.put("lastAt", rs.getTimestamp("last_at").toInstant().toString());
                    return m;
                }, tenantFilter, tenantFilter == null ? null : tenantFilter + ":%", cappedLimit);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<Map<String, Object>> transcript(@PathVariable String id) {
        requireOwnConversation(id);
        return jdbc.query("""
                SELECT type, content, "timestamp" FROM spring_ai_chat_memory
                WHERE conversation_id = ? ORDER BY "timestamp" """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", rs.getString("type"));
                    m.put("content", rs.getString("content"));
                    m.put("at", rs.getTimestamp("timestamp").toInstant().toString());
                    return m;
                }, id);
    }

    @GetMapping("/rag")
    public List<Map<String, Object>> rag(@RequestParam(required = false) String tenant) {
        String tenantFilter = resolveTenantFilter(tenant);
        return jdbc.query("""
                SELECT metadata->>'tenantId' AS tenant, COUNT(*) AS chunks,
                       COUNT(DISTINCT metadata->>'source') AS documents
                FROM vector_store
                WHERE (?::varchar IS NULL OR metadata->>'tenantId' = ?)
                GROUP BY 1 ORDER BY 1""",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tenant", rs.getString("tenant"));
                    m.put("documents", rs.getLong("documents"));
                    m.put("chunks", rs.getLong("chunks"));
                    return m;
                }, tenantFilter, tenantFilter);
    }

    @GetMapping("/audit/query")
    public List<Map<String, Object>> auditQuery(
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "500") int limit) {
        String tenantFilter = resolveTenantFilter(tenant);
        Instant fromAt = from != null ? Instant.parse(from) : Instant.now().minus(Duration.ofDays(7));
        Instant toAt = to != null ? Instant.parse(to) : Instant.now();
        int cappedLimit = Math.min(Math.max(limit, 1), 5000);
        return jdbc.query("""
                SELECT id, created_at, tenant, user_id, conversation_id, source,
                       elapsed_ms, answer_chars, question
                FROM assistant_audit_event
                WHERE created_at BETWEEN ? AND ?
                  AND (?::varchar IS NULL OR tenant = ?::varchar)
                ORDER BY created_at DESC LIMIT ?""",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    m.put("tenant", rs.getString("tenant"));
                    m.put("userId", rs.getString("user_id"));
                    m.put("conversationId", rs.getString("conversation_id"));
                    m.put("source", rs.getString("source"));
                    m.put("elapsedMs", rs.getLong("elapsed_ms"));
                    m.put("answerChars", rs.getInt("answer_chars"));
                    m.put("question", rs.getString("question"));
                    return m;
                },
                Timestamp.from(fromAt), Timestamp.from(toAt), tenantFilter, tenantFilter, cappedLimit);
    }

    @GetMapping("/audit/verify")
    public AuditTrail.ChainVerification verifyAuditChain() {
        return audit.verifyChain();
    }

    @GetMapping("/aml/flags")
    public List<AmlMonitor.Flag> amlFlags(@RequestParam String tenant) {
        return aml.scanTenant(CurrentUser.requireTenantAccess(tenant));
    }

    @PostMapping("/cache/clear")
    public Map<String, Object> clearCache(@RequestParam(required = false) String tenant) {
        String tenantFilter = resolveTenantFilter(tenant);
        if (tenantFilter == null) semanticCache.clear();
        else semanticCache.clear(tenantFilter);
        return Map.of("status", "ok", "cleared", tenantFilter == null ? "all" : tenantFilter);
    }
}
