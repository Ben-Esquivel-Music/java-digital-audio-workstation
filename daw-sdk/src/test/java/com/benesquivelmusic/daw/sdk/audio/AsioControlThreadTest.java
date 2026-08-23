package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
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
 * <p>The second half of these tests is the re-review of that fix. A budget
 * stops the CALLER waiting; it cannot stop a downcall already inside the
 * driver, and {@code Future.cancel(false)} answers {@code true} for a callable
 * that is running — {@code FutureTask} stays in state {@code NEW} for the
 * whole duration of the call — so the timeout message used to claim "the
 * driver never saw it" about exactly the wedge it was describing. A phase
 * handshake now decides that, and an operation abandoned mid-flight keeps the
 * thread un-quiesced so cleanup calls fail fast instead of queueing behind it
 * and each burning a full budget.</p>
 *
 * <p>{@link #releaseTheControlThread()} releases whatever a test stalled and
 * waits for the thread, unconditionally and bounded, after EVERY test: the
 * executor is one process-wide platform thread, and leaking a blocked or
 * abandoned task onto it would wedge — or now fail fast — every later ASIO
 * test in this JVM.</p>
 */
class AsioControlThreadTest {

    private static final Duration TINY_BUDGET = Duration.ofMillis(200);
    private static final Duration GENEROUS_BUDGET = Duration.ofSeconds(2);
    private static final long GUARD_BUDGET_MILLIS = 5_000L;

    /**
     * Released by {@link #releaseTheControlThread()} for EVERY test. Any test
     * that stalls the control thread blocks its operation on this latch rather
     * than on a latch of its own, so releasing it cannot be forgotten.
     */
    private final CountDownLatch wedgeRelease = new CountDownLatch(1);

    /**
     * Unconditionally releases whatever the test stalled and waits, bounded,
     * for the control thread to be usable again.
     *
     * <p>{@link AsioControlThread} is process-wide static state: one executor,
     * one platform thread, and one abandoned-operation count shared by every
     * ASIO test in this surefire JVM. A test that returned while an abandoned
     * operation was still executing would make unrelated ASIO tests fail fast
     * for a reason they cannot see, so the wait is asserted HERE — the test
     * that leaked is the test that fails.</p>
     */
    @AfterEach
    void releaseTheControlThread() {
        wedgeRelease.countDown();
        assertThat(AsioControlThread.awaitQuiescence(
                Duration.ofMillis(GUARD_BUDGET_MILLIS)))
                .as("a test must not return while an operation it abandoned is still"
                        + " executing on the process-wide control thread")
                .isTrue();
    }

    @Test
    void anOperationThatOverrunsItsBudgetFailsInsteadOfWaitingForever() {
        // The drain that used to live here now lives in @AfterEach: this
        // operation is EXECUTING when its budget expires, so the control thread
        // is not quiesced and a follow-up bounded call would (correctly) be
        // refused rather than queued.
        assertThatThrownBy(() -> AsioControlThread.call(() -> {
            wedgeRelease.await();
            return 1;
        }, TINY_BUDGET))
                .as("a wedged driver must surface as a bounded, named failure — not"
                        + " as a lifecycle thread parked forever inside the lock")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("did not complete within "
                        + TINY_BUDGET.toMillis() + " ms")
                .hasMessageContaining("asio-control")
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
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

            // Regression guard for the fail-fast gate: the panel is a user
            // reading a dialog, not an abandoned call, so the control thread
            // stays quiesced and this caller must still QUEUE and exhaust its
            // budget rather than be refused on arrival.
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

    @Test
    void anOperationAlreadyExecutingIsNotReportedAsWithdrawn() throws Exception {
        AudioBackendException failure = abandonARunningOperation();

        assertThat(failure.getMessage())
                .as("Future.cancel(false) answers true for a callable that has already"
                        + " started — FutureTask stays in state NEW for the whole"
                        + " duration of the call — so a disposition derived from it told"
                        + " the log the driver never saw an operation the driver is"
                        + " inside, in exactly the wedge case the message describes")
                .contains("already executing inside the driver")
                .doesNotContain("withdrawn")
                .doesNotContain("never saw it");
    }

    @Test
    void anOperationStillQueuedWhenItsBudgetExpiresNeverReachesTheDriver()
            throws Throwable {
        AtomicBoolean reachedTheDriver = new AtomicBoolean();
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch holderReturned = new CountDownLatch(1);
        // The holder is the modal path on purpose: it occupies the thread
        // WITHOUT being abandoned, which is the only way a later bounded call
        // still queues instead of being refused.
        Thread holder = Thread.ofPlatform().daemon(true).name("queue-holder").start(() -> {
            try {
                AsioControlThread.callUnbounded(() -> {
                    holderStarted.countDown();
                    wedgeRelease.await();
                    return 1;
                });
            } catch (Throwable failure) {
                fail("the unbounded holder must not fail", failure);
            } finally {
                holderReturned.countDown();
            }
        });
        try {
            assertThat(holderStarted.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS))
                    .as("the holder must own the control thread before the queued call")
                    .isTrue();

            assertThatThrownBy(() -> AsioControlThread.call(() -> {
                reachedTheDriver.set(true);
                return 2;
            }, TINY_BUDGET))
                    .as("an operation that never started is the one case where"
                            + " withdrawal is provable, and the caller may be told so")
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessageContaining("withdrawn")
                    .hasMessageContaining("never saw it");
        } finally {
            wedgeRelease.countDown();
            assertThat(holderReturned.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS))
                    .as("the holder must have returned before the queue is drained")
                    .isTrue();
            holder.join(GUARD_BUDGET_MILLIS);
        }

        // Drain the queue: if the withdrawn operation were merely cancelled
        // rather than prevented, this is when it would have run.
        AsioControlThread.call(() -> 0, GENEROUS_BUDGET);
        assertThat(reachedTheDriver)
                .as("a withdrawn operation must never run, or the message that says the"
                        + " driver never saw it is a falsehood the log cannot detect")
                .isFalse();
    }

    @Test
    void aBoundedCallIsRefusedWhileAnAbandonedOperationIsStillExecuting()
            throws Exception {
        abandonARunningOperation();

        assertThat(AsioControlThread.isQuiesced())
                .as("an operation the host gave up on is still inside the driver")
                .isFalse();

        long startedAt = System.nanoTime();
        Throwable refused = catchThrowable(
                () -> AsioControlThread.call(() -> 2, GENEROUS_BUDGET));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(refused)
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("still executing a driver call that outlived its"
                        + " budget")
                .hasMessageContaining("may still hold the device");
        assertThat(elapsedMillis)
                .as("a failed open issues roughly six teardown downcalls; letting each"
                        + " queue behind the wedged call and burn its own full budget is"
                        + " the app-wide stall this gate collapses")
                .isLessThan(GENEROUS_BUDGET.toMillis() / 4);
    }

    @Test
    void theControlThreadHealsOnceTheAbandonedOperationFinishes() throws Throwable {
        abandonARunningOperation();
        wedgeRelease.countDown();

        assertThat(AsioControlThread.awaitQuiescence(
                Duration.ofMillis(GUARD_BUDGET_MILLIS)))
                .as("the driver returning is all it takes; nothing has to be reset")
                .isTrue();
        assertThat(AsioControlThread.isQuiesced()).isTrue();
        assertThat(AsioControlThread.<String>call(() -> "ASE_OK", TINY_BUDGET))
                .as("the gate must be self-healing, not a latch")
                .isEqualTo("ASE_OK");
    }

    @Test
    void awaitQuiescenceIsImmediateWhenIdleAndTimesOutWhileAnAbandonedCallRuns()
            throws Exception {
        long idleStartedAt = System.nanoTime();
        assertThat(AsioControlThread.awaitQuiescence(
                Duration.ofMillis(GUARD_BUDGET_MILLIS)))
                .as("an idle control thread is quiesced")
                .isTrue();
        assertThat((System.nanoTime() - idleStartedAt) / 1_000_000L)
                .as("an idle wait must return on its first check, not poll to its"
                        + " deadline — AsioBackend calls this on lifecycle paths")
                .isLessThan(TINY_BUDGET.toMillis());

        abandonARunningOperation();

        assertThat(AsioControlThread.awaitQuiescence(TINY_BUDGET))
                .as("waiting cannot conjure a return from the driver; a budget that"
                        + " expires first must report failure rather than pretend")
                .isFalse();
    }

    /**
     * Occupies the control thread with an operation that blocks until
     * {@link #wedgeRelease}, and returns the failure its caller was handed once
     * the budget expires. The operation is provably EXECUTING at that moment —
     * the case the reviewer's finding is about — which the started latch
     * asserts rather than assumes.
     */
    private AudioBackendException abandonARunningOperation() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        Throwable thrown = catchThrowable(() -> AsioControlThread.call(() -> {
            started.countDown();
            wedgeRelease.await();
            return 1;
        }, TINY_BUDGET));

        assertThat(started.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS))
                .as("the operation must have STARTED, or this is the queued case")
                .isTrue();
        assertThat(thrown).isInstanceOf(AudioBackendException.class);
        return (AudioBackendException) thrown;
    }
}
