package com.aegis.lc4j.admin;

import com.aegis.lc4j.guardrails.BudgetGuard;
import com.aegis.lc4j.rag.IngestionService;
import com.aegis.lc4j.rag.SemanticCache;
import com.aegis.lc4j.resilience.LlmResilience;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Admin/ops API: ingestion, analytics, guardrail state. Requires PERM_admin:all. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final IngestionService ingestion;
    private final AuditTrail audit;
    private final BudgetGuard budget;
    private final LlmResilience resilience;
    private final SemanticCache cache;

    public AdminController(IngestionService ingestion, AuditTrail audit, BudgetGuard budget,
                           LlmResilience resilience, SemanticCache cache) {
        this.ingestion = ingestion;
        this.audit = audit;
        this.budget = budget;
        this.resilience = resilience;
        this.cache = cache;
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingest() {
        int documents = ingestion.ingestAll();
        return Map.of("status", "ok", "documents", documents);
    }

    @PostMapping("/cache/clear")
    public Map<String, String> clearCache() {
        cache.clear();
        return Map.of("status", "cleared");
    }

    @GetMapping("/analytics")
    public Map<String, Object> analytics(@RequestParam(defaultValue = "50") int recent,
                                         @RequestParam(defaultValue = "60") int minutes) {
        return Map.of(
                "startedAt", audit.startedAt().toString(),
                "countsBySource", audit.countsBySource(),
                "toolUsage", audit.toolUsage(),
                "latency", audit.latency(),
                "timeseries", audit.timeseries(minutes),
                "recent", audit.recent(recent),
                "circuit", resilience.circuitState());
    }

    @GetMapping("/budget")
    public Map<String, Object> budget(@RequestParam String tenant) {
        return Map.of("tenant", tenant, "consumedToday", budget.consumed(tenant));
    }

    @PostMapping("/budget")
    public Map<String, Object> setBudget(@RequestParam String tenant, @RequestParam long amount) {
        budget.setBudget(tenant, amount);
        return Map.of("tenant", tenant, "budget", amount);
    }

    @GetMapping("/circuit")
    public Map<String, String> circuit() {
        return Map.of("state", resilience.circuitState());
    }
}
