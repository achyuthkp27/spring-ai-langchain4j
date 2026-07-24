package com.aegis.ai.guardrails;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LLM10 (Unbounded Consumption) resilience: once the model call keeps failing, the
 * circuit opens and new calls fail FAST instead of piling up — the property that keeps
 * the app responsive under the 1→100 stress test.
 */
class LlmGuardTest {

    @Test
    @DisplayName("Repeated failures open the circuit; further calls fail fast")
    void circuitOpensAfterFailures() {
        var guard = new LlmGuard();

        // Fill the sliding window (20) with failing calls.
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> guard.call(() -> { throw new RuntimeException("ollama down"); }))
                    .isInstanceOf(LlmGuard.LlmUnavailableException.class);
        }

        // The breaker should now be OPEN.
        assertThat(guard.circuitState()).isEqualTo("OPEN");

        // A new call is rejected instantly (no supplier execution) with the friendly message.
        boolean[] ran = {false};
        assertThatThrownBy(() -> guard.call(() -> { ran[0] = true; return "should not run"; }))
                .isInstanceOf(LlmGuard.LlmUnavailableException.class)
                .hasMessageContaining("busy");
        assertThat(ran[0]).as("supplier must NOT run while circuit is open").isFalse();
    }

    @Test
    @DisplayName("Healthy calls pass through and keep the circuit closed")
    void healthyCallsStayClosed() {
        var guard = new LlmGuard();
        for (int i = 0; i < 5; i++) {
            assertThat(guard.call(() -> "ok")).isEqualTo("ok");
        }
        assertThat(guard.circuitState()).isEqualTo("CLOSED");
    }
}
