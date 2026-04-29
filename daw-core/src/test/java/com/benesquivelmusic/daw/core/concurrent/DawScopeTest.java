package com.benesquivelmusic.daw.core.concurrent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link DawScope}'s shutdown-on-failure semantics
 * match the JEP 505 contract:
 *
 * <ol>
 *   <li>All forks succeed → results are usable after {@code joinAll}.</li>
 *   <li>One fork fails → {@code joinAll} throws and remaining forks are
 *       interrupted (the cancellation must be deterministic).</li>
 *   <li>Closing the scope cancels still-running forks.</li>
 * </ol>
 */
class DawScopeTest {

    @Test
    void allForksSucceedJoinAllReturnsResults() throws Exception {
        try (DawScope scope = DawScope.openShutdownOnFailure("fan-out")) {
            DawScope.Subtask<Integer> a = scope.fork("a", () -> 1);
            DawScope.Subtask<Integer> b = scope.fork("b", () -> 2);
            DawScope.Subtask<Integer> c = scope.fork("c", () -> 3);
            scope.joinAll();
            assertThat(a.resultNow() + b.resultNow() + c.resultNow()).isEqualTo(6);
        }
    }

    @Test
    void firstFailureCancelsAllOtherForks() throws Exception {
        AtomicInteger interruptedCount = new AtomicInteger();
        CountDownLatch siblingsStarted = new CountDownLatch(2);

        try (DawScope scope = DawScope.openShutdownOnFailure("bundle-export")) {
            // Two long-running siblings that count interrupts.
            scope.fork("long-1", () -> {
                siblingsStarted.countDown();
                try {
                    Thread.sleep(60_000);
                    return "done";
                } catch (InterruptedException ie) {
                    interruptedCount.incrementAndGet();
                    throw ie;
                }
            });
            scope.fork("long-2", () -> {
                siblingsStarted.countDown();
                try {
                    Thread.sleep(60_000);
                    return "done";
                } catch (InterruptedException ie) {
                    interruptedCount.incrementAndGet();
                    throw ie;
                }
            });
            // Ensure siblings are running before triggering failure.
            siblingsStarted.await(5, TimeUnit.SECONDS);
            scope.fork("boom", () -> {
                throw new IOException("disk full");
            });

            assertThatThrownBy(scope::joinAll)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IOException.class);
        }

        // Both siblings must have observed interruption — i.e. cancellation
        // was deterministic, no thread leak.
        assertThat(interruptedCount.get()).isEqualTo(2);
    }

    @Test
    void closingScopeCancelsRunningForks() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();

        DawScope scope = DawScope.openShutdownOnFailure("test");
        scope.fork("blocker", () -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
                return "done";
            } catch (InterruptedException ie) {
                interrupted.incrementAndGet();
                throw ie;
            }
        });
        started.await(5, TimeUnit.SECONDS);
        scope.close();
        assertThat(interrupted.get()).isEqualTo(1);
    }

    @Test
    void cancellationIsIdempotent() {
        try (DawScope scope = DawScope.openShutdownOnFailure("cancel")) {
            scope.fork("noop", () -> 42);
            scope.cancel();
            scope.cancel(); // must not throw
        }
    }

    @Test
    void forkAfterCloseThrows() throws Exception {
        DawScope scope = DawScope.openShutdownOnFailure("test");
        scope.close();
        assertThatThrownBy(() -> scope.fork(() -> 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void forkRunsOnVirtualThread() throws Exception {
        try (DawScope scope = DawScope.openShutdownOnFailure("vt-check")) {
            DawScope.Subtask<Boolean> isVirtual = scope.fork("v",
                    () -> Thread.currentThread().isVirtual());
            scope.joinAll();
            assertThat(isVirtual.resultNow()).isTrue();
        }
    }

    @Test
    void joinAllWithTimeoutInterruptsLongRunningForks() throws Exception {
        AtomicInteger interrupted = new AtomicInteger();
        try (DawScope scope = DawScope.openShutdownOnFailure("timeout")) {
            scope.fork("slow", () -> {
                try {
                    Thread.sleep(30_000);
                    return "done";
                } catch (InterruptedException ie) {
                    interrupted.incrementAndGet();
                    throw ie;
                }
            });
            assertThatThrownBy(() -> scope.joinAll(50, TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class);
        }
        assertThat(interrupted.get()).isEqualTo(1);
    }
}
