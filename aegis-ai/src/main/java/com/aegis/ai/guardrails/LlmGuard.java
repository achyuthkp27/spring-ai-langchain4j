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

@Component
public class LlmGuard {

    private static final Logger log = LoggerFactory.getLogger(LlmGuard.class);

    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) { super(message); }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final CircuitBreaker breaker;
    private final TimeLimiter timeLimiter;

    private final ExecutorService pool = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "llm-guard");
        t.setDaemon(true);
        return t;
    });

    public LlmGuard() {
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
                .timeoutDuration(TIMEOUT)
                .cancelRunningFuture(true)
                .build());
        this.breaker.getEventPublisher().onStateTransition(e ->
                log.warn("llm.circuit state {} -> {}", e.getStateTransition().getFromState(),
                        e.getStateTransition().getToState()));
    }

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
        } catch (Exception e) {   
            log.warn("llm.call.failed {}", e.toString());
            throw new LlmUnavailableException("That request took too long. Please try again.");
        }
    }

    public <T> Flux<T> guard(Flux<T> source) {
        return source
                .timeout(TIMEOUT)
                .transformDeferred(CircuitBreakerOperator.of(breaker));
    }

    public String circuitState() {
        return breaker.getState().name();
    }
}
