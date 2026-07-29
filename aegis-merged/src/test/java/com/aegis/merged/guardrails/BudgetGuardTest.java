package com.aegis.merged.guardrails;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetGuardTest {

    @Test
    @DisplayName("A tenant under budget is allowed; once consumed reaches the budget, it is rejected")
    void enforcesTheBudgetOnceReached() {
        var guard = new BudgetGuard(Optional.empty(), 100);
        guard.checkOrThrow("achu-bank");
        guard.record("achu-bank", 100);

        assertThatThrownBy(() -> guard.checkOrThrow("achu-bank"))
                .isInstanceOf(BudgetGuard.BudgetExceededException.class);
    }

    @Test
    @DisplayName("Concurrent record() calls for the same tenant do not lose updates")
    void concurrentRecordingDoesNotLoseUpdates() throws InterruptedException {
        var guard = new BudgetGuard(Optional.empty(), 1_000_000);
        int threads = 50;
        int tokensPerCall = 7;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(16);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    guard.record("achu-bank", tokensPerCall);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertThat(doneGate.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(guard.consumed("achu-bank")).isEqualTo((long) threads * tokensPerCall);
    }

    @Test
    @DisplayName("A tenant that races past its budget under concurrency is reliably blocked "
            + "once the race window closes")
    void enforcementResumesReliablyAfterAConcurrentRace() throws InterruptedException {
        var guard = new BudgetGuard(Optional.empty(), 20);
        int threads = 30;
        int tokensPerCall = 5;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    guard.checkOrThrow("achu-bank");
                    guard.record("achu-bank", tokensPerCall);
                    allowed.incrementAndGet();
                } catch (BudgetGuard.BudgetExceededException ignored) {
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertThat(doneGate.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(guard.consumed("achu-bank")).isEqualTo((long) allowed.get() * tokensPerCall);
        assertThatThrownBy(() -> guard.checkOrThrow("achu-bank"))
                .isInstanceOf(BudgetGuard.BudgetExceededException.class);
    }
}
