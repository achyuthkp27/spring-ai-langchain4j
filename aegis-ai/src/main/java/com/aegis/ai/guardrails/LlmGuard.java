package com.aegis.ai.guardrails;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Resilience around the LLM call. Ollama serialises generation, so under concurrent load
 * (the 1→100 stress test) calls queue and slow down. Without a guard a slow model pins
 * request threads until they exhaust the pool and the whole app stalls. This wraps every
 * model call in:
 *   - a TIME LIMITER (hard timeout, cancels the run) so no call hangs forever, and
 *   - a CIRCUIT BREAKER that, once too many calls fail/are slow, "opens" and fails new
 *     calls instantly with a friendly message — shedding load so the app stays responsive
 *     and giving Ollama room to recover, instead of collapsing under a growing queue.
 */
@Component
public class LlmGuard {

    private static final Logger log = LoggerFactory.getLogger(LlmGuard.class);

    /** Thrown when the breaker is open or a call times out; controllers turn this into a 1-liner. */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) { super(message); }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final CircuitBreaker breaker;
    private final TimeLimiter timeLimiter;
    // Small pool just to host the timed future for the blocking path; Ollama serialises
    // anyway, so this only holds threads that are waiting, and the time limiter cancels them.
    private final ExecutorService pool = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "llm-guard");
        t.setDaemon(true);
        return t;
    });

    public LlmGuard() {
        var cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(20)                                   // decide over the last 20 calls
                .failureRateThreshold(50)                               // open if >=50% fail
                .slowCallRateThreshold(90)                              // ...or >=90% are "slow"
                .slowCallDurationThreshold(Duration.ofSeconds(25))     // slow = >25s
                .waitDurationInOpenState(Duration.ofSeconds(15))       // stay open 15s, then test
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordException(t -> !(t instanceof LlmUnavailableException)) // don't double-count
                .build();
        this.breaker = CircuitBreaker.of("llm", cbConfig);
        this.timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(TIMEOUT)
                .cancelRunningFuture(true)
                .build());
        this.breaker.getEventPublisher().onStateTransition(e ->
                log.warn("llm.circuit state {} -> {}", e.getStateTransition().getFromState(),
                        e.getStateTransition().getToState()));
    }

    /** Blocking call, guarded by circuit breaker + hard timeout. */
    public <T> T call(Supplier<T> supplier) {
        Callable<T> timed = TimeLimiter.decorateFutureSupplier(timeLimiter,
                () -> CompletableFuture.supplyAsync(supplier, pool));
        Callable<T> guarded = CircuitBreaker.decorateCallable(breaker, timed);
        try {
            return guarded.call();
        } catch (CallNotPermittedException e) {
            log.warn("llm.circuit.open rejected call");
            throw new LlmUnavailableException("The assistant is busy right now. Please retry in a moment.");
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {   // includes TimeoutException from the time limiter
            log.warn("llm.call.failed {}", e.toString());
            throw new LlmUnavailableException("That request took too long. Please try again.");
        }
    }

    /** Streaming variant: apply the same timeout + circuit breaker to a token Flux. */
    public <T> Flux<T> guard(Flux<T> source) {
        return source
                .timeout(TIMEOUT)
                .transformDeferred(CircuitBreakerOperator.of(breaker));
    }

    public String circuitState() {
        return breaker.getState().name();
    }
}
