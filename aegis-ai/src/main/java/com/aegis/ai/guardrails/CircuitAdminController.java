package com.aegis.ai.guardrails;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Observe the LLM circuit breaker state — handy to watch during the stress test. */
@RestController
@RequestMapping("/api/admin/circuit")
public class CircuitAdminController {

    private final LlmGuard llmGuard;

    public CircuitAdminController(LlmGuard llmGuard) {
        this.llmGuard = llmGuard;
    }

    @GetMapping
    public Map<String, Object> state() {
        return Map.of("llmCircuit", llmGuard.circuitState());
    }
}
