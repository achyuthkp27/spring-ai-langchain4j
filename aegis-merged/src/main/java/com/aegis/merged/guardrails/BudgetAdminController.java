package com.aegis.merged.guardrails;

import com.aegis.merged.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Admin view/set of per-tenant token budgets (authenticated). */
@RestController
@RequestMapping("/api/admin/budget")
public class BudgetAdminController {

    private final BudgetGuard budget;

    public BudgetAdminController(BudgetGuard budget) {
        this.budget = budget;
    }

    public record SetBudgetRequest(String tenantId, long budget) {
    }

    @GetMapping
    public Map<String, Object> status() {
        String tenant = CurrentUser.get().tenantId();
        return Map.of("tenantId", tenant, "consumedTokens", budget.consumed(tenant));
    }

    @PostMapping
    public Map<String, Object> set(@RequestBody SetBudgetRequest req) {
        budget.setBudget(req.tenantId(), req.budget());
        return Map.of("status", "ok", "tenantId", req.tenantId(), "budget", req.budget());
    }
}
