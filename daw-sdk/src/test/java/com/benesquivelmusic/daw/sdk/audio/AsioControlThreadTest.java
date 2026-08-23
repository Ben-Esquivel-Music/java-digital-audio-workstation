package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Story 316 re-review (E2) — the ASIO control thread's wait is BOUNDED.
 *
 * <p>{@code AsioControlThread.call} used to wait on {@code Future.get()} with
 * no timeout. Every ASIO operation the engine performs runs through it from
 * inside {@code AudioEngine}'s lifecycle lock, so one driver wedged in
 * {@code ASIOInit} — or simply holding the single COM apartment thread while
 * its modal control panel was open — froze every lifecycle transition in the
 * application, Stop included.</p>
 *
 * <p>These tests use a millisecond budget through the package-private
 * overload rather than {@link AsioControlThread#DEFAULT_BUDGET}; the
 * production budget's SIZE is a judgement call documented on the class, and
 * pinning fifteen seconds of wall clock would say nothing useful.</p>
 *
 * <p>Every test releases the operation it stalls before returning: the
 * executor is one process-wide platform thread, and leaking a blocked task
 * onto it would wedge every later ASIO test in this JVM.</p>
 */
class AsioControlThreadTest {

    private static final Duration TINY_BUDGET = Duration.ofMillis(200);
    private static final long GUARD_BUDGET_MILLIS = 5_000L;

    @Test
    void anOperationThatOverrunsItsBudgetFailsInsteadOfWaitingForever()
            throws Throwable {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try {
            assertThatThrownBy(() -> AsioControlThread.call(() -> {
                release.await();
                return 1;
            }, TINY_BUDGET))
                    .as("a wedged driver must surface as a bounded, named failure — not"
                            + " as a lifecycle thread parked forever inside the lock")
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessageContaining("did not complete within "
                            + TINY_BUDGET.toMillis() + " ms")
                    .hasMessageContaining("asio-control")
                    .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        } finally {
            release.countDown();
            // Drain: the timed-out operation was already executing, so the
            // control thread stays busy until it returns.
            AsioControlThread.call(() -> {
                finished.countDown();
                return 0;
            }, Duration.ofMillis(GUARD_BUDGET_MILLIS));
        }
        assertThat(finished.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS))
                .as("the control thread must be usable again afterwards")
                .isTrue();
    }

    @Test
    void anOperationInsideItsBudgetStillReturnsItsValue() throws Throwable {
        assertThat(AsioControlThread.<String>call(() -> "ASE_OK", TINY_BUDGET))
                .isEqualTo("ASE_OK");
    }

    @Test
    void anOperationsOwnFailureIsUnwrapped() {
        assertThatThrownBy(() -> AsioControlThread.call(() -> {
            throw new IllegalStateException("driver said no");
        }, TINY_BUDGET))
                .as("bounding the wait must not change how a real driver failure"
                        + " propagates")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("driver said no");
    }

    @Test
    void theModalControlPanelIsExemptFromTheBudget() throws Throwable {
        // The exemption is the null budget callUnbounded uses: a user reading
        // a driver dialog is not a wedged driver, so this operation outlives
        // any budget a bounded caller would have applied to it.
        long overrunMillis = TINY_BUDGET.toMillis() * 3;
        long startedAt = System.nanoTime();
        int result = AsioControlThread.callUnbounded(() -> {
            Thread.sleep(overrunMillis);
            return 1;
        });
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(result).isEqualTo(1);
        assertThat(elapsedMillis)
                .as("the unbounded path must have waited past what the bounded path"
                        + " would have refused, or this test proves no exemption")
                .isGreaterThanOrEqualTo(TINY_BUDGET.toMillis());
    }

    @Test
    void aBoundedCallerQueuedBehindTheModalPanelIsToldWhy() throws Exception {
        CountDownLatch panelOpen = new CountDownLatch(1);
        CountDownLatch closePanel = new CountDownLatch(1);
        CountDownLatch panelReturned = new CountDownLatch(1);
        Thread modal = Thread.ofPlatform().daemon(true).name("modal-panel").start(() -> {
            try {
                AsioControlThread.callUnbounded(() -> {
                    panelOpen.countDown();
                    closePanel.await();
                    return 1;
                });
            } catch (Throwable failure) {
                fail("the unbounded modal call must not fail", failure);
            } finally {
                panelReturned.countDown();
            }
        });
        try {
            assertThat(panelOpen.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS))
                    .as("the modal operation must own the control thread first")
                    .isTrue();

            assertThatThrownBy(() -> AsioControlThread.call(() -> 2, TINY_BUDGET))
                    .as("one COM apartment means one thread, so a bounded caller cannot"
                            + " overtake the panel — but the failure must name the panel"
                            + " rather than blame a wedge, and must say the operation was"
                            + " withdrawn so the driver never saw it")
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessageContaining("modal control panel")
                    .hasMessageContaining("withdrawn");
        } finally {
            closePanel.countDown();
            assertThat(panelReturned.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS))
                    .as("the modal operation must have returned before the next test")
                    .isTrue();
            modal.join(GUARD_BUDGET_MILLIS);
        }
    }
}
