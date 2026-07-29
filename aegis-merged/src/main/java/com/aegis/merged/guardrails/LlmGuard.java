package com.aegis.merged.guardrails;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class LlmGuard {

    private static final Logger log = LoggerFactory.getLogger(LlmGuard.class);

    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) { super(message); }
    }

    private final Duration firstTokenTimeout;
    private final Duration interTokenTimeout;

    private final CircuitBreaker breaker;
    private final TimeLimiter timeLimiter;
    private final Bulkhead bulkhead;

    private final ExecutorService pool = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "llm-guard");
        t.setDaemon(true);
        return t;
    });

    public LlmGuard() {
        this(Duration.ofSeconds(30), Duration.ofSeconds(15), Duration.ofSeconds(30));
    }

    @Autowired
    public LlmGuard(@Value("${aegis.llmguard.call-timeout:30s}") Duration callTimeout,
                    @Value("${aegis.llmguard.first-token-timeout:15s}") Duration firstTokenTimeout,
                    @Value("${aegis.llmguard.inter-token-timeout:30s}") Duration interTokenTimeout) {
        this.firstTokenTimeout = firstTokenTimeout;
        this.interTokenTimeout = interTokenTimeout;
        var cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .failureRateThreshold(50)
                .slowCallRateThreshold(90)
                .slowCallDurationThreshold(Duration.ofSeconds(25))
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordException(t -> !(t instanceof LlmUnavailableException))
                .build();
        this.breaker = CircuitBreaker.of("llm", cbConfig);
        this.timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(callTimeout)
                .cancelRunningFuture(true)
                .build());
        this.bulkhead = Bulkhead.of("llm", BulkheadConfig.custom()
                .maxConcurrentCalls(4)
                .maxWaitDuration(Duration.ofSeconds(5))
                .build());
        this.breaker.getEventPublisher().onStateTransition(e ->
                log.warn("llm.circuit state {} -> {}", e.getStateTransition().getFromState(),
                        e.getStateTransition().getToState()));
    }

    public <T> T call(Supplier<T> supplier) {
        Callable<T> timed = TimeLimiter.decorateFutureSupplier(timeLimiter,
                () -> CompletableFuture.supplyAsync(supplier, pool));
        Callable<T> breakerGuarded = CircuitBreaker.decorateCallable(breaker, timed);
        Callable<T> guarded = Bulkhead.decorateCallable(bulkhead, breakerGuarded);
        try {
            return guarded.call();
        } catch (CallNotPermittedException e) {
            log.warn("llm.circuit.open rejected call");
            throw new LlmUnavailableException("The assistant is busy right now. Please retry in a moment.");
        } catch (BulkheadFullException e) {
            log.warn("llm.bulkhead.full rejected call");
            throw new LlmUnavailableException("The assistant is busy right now. Please retry in a moment.");
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {   
            log.warn("llm.call.failed {}", e.toString());
            throw new LlmUnavailableException("That request took too long. Please try again.");
        }
    }

    public <T> Flux<T> guard(Flux<T> source) {
        return source
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .timeout(Mono.delay(firstTokenTimeout), t -> Mono.delay(interTokenTimeout))
                .transformDeferred(BulkheadOperator.of(bulkhead));
    }

    public String circuitState() {
        return breaker.getState().name();
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
