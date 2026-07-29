package com.aegis.lc4j.guardrails;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class TokenAudit {

    private final MeterRegistry registry;

    public TokenAudit(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String tenant, String source, Duration elapsed,
                       long inputTokens, long outputTokens) {
        Timer.builder("aegis.llm.latency")
                .tags("tenant", tenant, "source", source)
                .register(registry)
                .record(elapsed);
        registry.counter("aegis.llm.tokens.input", "tenant", tenant).increment(inputTokens);
        registry.counter("aegis.llm.tokens.output", "tenant", tenant).increment(outputTokens);
    }
}
