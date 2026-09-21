package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DeferredDspUpdateTest {
    @Test
    void changesDuringPreparationDiscardOldStateAndApplyTheLatestPair() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicInteger(1);
        var second = new AtomicInteger(2);
        var applied = new AtomicInteger();
        try (var update = new DeferredDspUpdate(() -> {
            int left = first.get();
            int right = second.get();
            started.countDown();
            await(release);
            return () -> applied.set(left * 10 + right);
        })) {
            update.request();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            first.set(3);
            second.set(4);
            update.request();
            release.countDown();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (applied.get() != 34) {
                    update.apply();
                    assertThat(applied.get()).isIn(0, 34);
                    Thread.sleep(5);
                }
            });
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellationDiscardsAnInFlightReplacement() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var applied = new AtomicInteger();
        try (var update = new DeferredDspUpdate(() -> {
            started.countDown();
            await(release);
            finished.countDown();
            return applied::incrementAndGet;
        })) {
            update.request();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            update.cancel();
            release.countDown();
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            update.apply();
            assertThat(applied).hasValue(0);
        } finally {
            release.countDown();
        }
    }

    @Test
    void preparationDoesNotMutateLiveStateUntilTheCallerDrainsIt() throws Exception {
        var prepared = new CountDownLatch(1);
        var applied = new CountDownLatch(1);
        var preparations = new AtomicInteger();
        var applications = new AtomicInteger();
        var preparationThread = new AtomicReference<Thread>();
        var applicationThread = new AtomicReference<Thread>();
        try (var update = new DeferredDspUpdate(() -> {
            preparationThread.set(Thread.currentThread());
            preparations.incrementAndGet();
            prepared.countDown();
            return () -> {
                applicationThread.set(Thread.currentThread());
                applications.incrementAndGet();
                applied.countDown();
            };
        })) {
            update.request();
            assertThat(prepared.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(applications).hasValue(0);
            var caller = Thread.currentThread();
            drainUntilApplied(update, applied);
            assertThat(applicationThread).hasValue(caller);
            assertThat(preparationThread.get()).isNotSameAs(caller);
            update.apply();
            assertThat(preparations).hasValue(1);
            assertThat(applications).hasValue(1);
        }
    }

    @Test
    void cancellationOfAnOldRevisionStillAllowsTheNextRequestToApply() throws Exception {
        var oldStarted = new CountDownLatch(1);
        var oldRelease = new CountDownLatch(1);
        var latestStarted = new CountDownLatch(1);
        var latestRelease = new CountDownLatch(1);
        var applied = new CountDownLatch(1);
        var preparations = new AtomicInteger();
        var value = new AtomicInteger();
        try (var update = new DeferredDspUpdate(() -> {
            int candidate = preparations.incrementAndGet();
            if (candidate == 1) {
                oldStarted.countDown();
                await(oldRelease);
            } else {
                latestStarted.countDown();
                await(latestRelease);
            }
            return () -> {
                value.set(candidate);
                applied.countDown();
            };
        })) {
            update.request();
            assertThat(oldStarted.await(5, TimeUnit.SECONDS)).isTrue();
            update.cancel();
            update.request();
            oldRelease.countDown();
            assertThat(latestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            update.apply();
            assertThat(value).hasValue(0);
            latestRelease.countDown();
            drainUntilApplied(update, applied);
            assertThat(value).hasValue(2);
        } finally {
            oldRelease.countDown();
            latestRelease.countDown();
        }
    }

    @Test
    void awaitingAfterADrainFailureRethrowsThatFailureAndANewRequestCanRecover() throws Exception {
        var started = new CountDownLatch(1);
        var failure = new IllegalStateException("DSP preparation failed");
        var preparations = new AtomicInteger();
        var applications = new AtomicInteger();
        try (var update = new DeferredDspUpdate(() -> {
            if (preparations.incrementAndGet() == 1) {
                started.countDown();
                throw failure;
            }
            return applications::incrementAndGet;
        })) {
            update.request();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            RuntimeException observed = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (true) {
                    try {
                        update.apply();
                    } catch (RuntimeException caught) {
                        return caught;
                    }
                    Thread.sleep(1);
                }
            });
            assertThat(observed).isSameAs(failure);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThatThrownBy(update::await).isSameAs(failure));

            update.request();
            assertTimeoutPreemptively(Duration.ofSeconds(5), update::await);
            assertThat(applications).hasValue(1);
        }
    }

    @Test
    void closingDiscardsAReplacementThatFinishesAfterInterruption() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var applications = new AtomicInteger();
        try (var update = new DeferredDspUpdate(() -> {
            worker.set(Thread.currentThread());
            started.countDown();
            awaitIgnoringInterrupts(release);
            return applications::incrementAndGet;
        })) {
            update.request();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            update.close();
            release.countDown();
            assertThat(worker.get().join(Duration.ofSeconds(5))).isTrue();
            update.apply();
            update.request();
            update.apply();
            assertThat(applications).hasValue(0);
            assertThat(worker.get().isAlive()).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void closingDiscardsTheFailureProducedByInterruptingPreparation() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        try (var update = new DeferredDspUpdate(() -> {
            worker.set(Thread.currentThread());
            started.countDown();
            await(release);
            throw new IllegalStateException("preparation failed while closing");
        })) {
            update.request();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            update.close();
            assertThat(worker.get().join(Duration.ofSeconds(5))).isTrue();
            assertThatCode(update::apply).doesNotThrowAnyException();
            assertThat(worker.get().isAlive()).isFalse();
        } finally {
            release.countDown();
        }
    }

    private static void drainUntilApplied(DeferredDspUpdate update, CountDownLatch applied)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            update.apply();
            if (applied.await(1, TimeUnit.MILLISECONDS)) return;
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Prepared state never reached the drain");
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (true) {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 || !latch.await(remaining, TimeUnit.NANOSECONDS)) {
                        throw new IllegalStateException("Preparation test timed out");
                    }
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Preparation test timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
