package com.aegis.lc4j.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.concurrent.TimeoutException;

@Component
public class LlmResilience {

    private static final Logger log = LoggerFactory.getLogger(LlmResilience.class);

    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) { super(message); }
    }

    public static final String BUSY_MESSAGE = "The assistant is busy right now. Please retry in a moment.";
    public static final String TIMEOUT_MESSAGE = "That request took too long. Please try again.";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofSeconds(45);

    private final CircuitBreaker breaker;
    private final TimeLimiter timeLimiter;
    private final ExecutorService pool = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "llm-guard");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService watchdogs = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "llm-watchdog");
        t.setDaemon(true);
        return t;
    });

    public LlmResilience() {
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
            throw new LlmUnavailableException(BUSY_MESSAGE);
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {   
            log.warn("llm.call.failed {}", e.toString());
            throw new LlmUnavailableException(TIMEOUT_MESSAGE);
        }
    }

    public final class StreamCall {
        private final long startNanos = System.nanoTime();
        private volatile long lastActivityNanos = startNanos;
        private final ScheduledFuture<?> watchdog;
        private final Runnable onIdleTimeout;
        private volatile boolean done;

        private StreamCall(Runnable onIdleTimeout) {
            this.onIdleTimeout = onIdleTimeout;
            this.watchdog = watchdogs.scheduleAtFixedRate(this::check, 5, 5, TimeUnit.SECONDS);
        }

        private void check() {
            if (done) return;
            long idleNanos = System.nanoTime() - lastActivityNanos;
            if (idleNanos > STREAM_IDLE_TIMEOUT.toNanos()) {
                failure(new TimeoutException("stream idle timeout"));
                onIdleTimeout.run();
            }
        }

        public void tick() {
            lastActivityNanos = System.nanoTime();
        }

        public void success() {
            if (done) return;
            done = true;
            watchdog.cancel(false);
            breaker.onSuccess(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }

        public void failure(Throwable t) {
            if (done) return;
            done = true;
            watchdog.cancel(false);
            breaker.onError(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS, t);
        }
    }

    public StreamCall startStreamCall(Runnable onIdleTimeout) {
        try {
            breaker.acquirePermission();
        } catch (CallNotPermittedException e) {
            log.warn("llm.circuit.open rejected stream");
            throw new LlmUnavailableException(BUSY_MESSAGE);
        }
        return new StreamCall(onIdleTimeout);
    }

    public String circuitState() {
        return breaker.getState().name();
    }
}
