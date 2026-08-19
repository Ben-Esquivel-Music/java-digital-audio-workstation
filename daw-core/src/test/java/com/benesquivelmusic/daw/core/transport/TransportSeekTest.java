package com.benesquivelmusic.daw.core.transport;

import com.benesquivelmusic.daw.core.transport.Transport.ChangeKind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 315 — the block-boundary seek queue (Audio Engine Wiring Design Book
 * §4.1, §5.1, §6.1).
 *
 * <p>A UI seek issued while the transport is rolling <em>and</em> an RT clock
 * has {@linkplain Transport#setRealTimeClockActive(boolean) claimed} it must
 * not race the RT thread's read-modify-write advance: it is queued
 * (last-writer-wins) and applied by {@link Transport#advancePosition(double)}
 * exactly at the next block boundary — never lost, never torn, and applied
 * verbatim (the block that was just rendered ran from the pre-seek position,
 * so no delta is added on top of the seek target).</p>
 *
 * <p>Every deferral test below claims the clock explicitly: that is precisely
 * what these tests simulate — a live audio callback driving
 * {@code advancePosition}. Without a claim there is no driver to drain the
 * queue, and the transport applies seeks inline (see
 * {@link TransportRealTimeClockOwnershipTest}).</p>
 */
class TransportSeekTest {

    /** Simulates the audio callback having claimed the transport's clock. */
    private static Transport rollingWithRealTimeClock() {
        Transport transport = new Transport();
        transport.setRealTimeClockActive(true);
        return transport;
    }

    @Test
    void seekDuringPlaybackIsDeferredToTheNextBlockBoundaryAndAppliedExactly() {
        Transport transport = rollingWithRealTimeClock();
        transport.play();

        transport.setPositionInBeats(17 * 4.0); // seek to bar 17 (4/4)

        assertThat(transport.getPositionInBeats())
                .as("the seek must not land mid-block — the RT thread applies it")
                .isZero();

        transport.advancePosition(0.25); // the block boundary

        assertThat(transport.getPositionInBeats())
                .as("the seek target is applied verbatim — no block delta is added")
                .isEqualTo(68.0);
    }

    @Test
    void seekDuringRecordingIsDeferredTheSameWay() {
        Transport transport = rollingWithRealTimeClock();
        transport.record();

        transport.setPositionInBeats(12.0);
        assertThat(transport.getPositionInBeats()).isZero();

        transport.advancePosition(0.25);
        assertThat(transport.getPositionInBeats()).isEqualTo(12.0);
    }

    @Test
    void seekWhileStoppedAppliesImmediately() {
        Transport transport = new Transport();
        transport.setPositionInBeats(9.0);
        assertThat(transport.getPositionInBeats()).isEqualTo(9.0);
    }

    @Test
    void seekWhilePausedAppliesImmediately() {
        Transport transport = rollingWithRealTimeClock();
        transport.play();
        transport.pause();
        transport.setPositionInBeats(9.0);
        assertThat(transport.getPositionInBeats()).isEqualTo(9.0);
    }

    @Test
    void pauseDrainsAQueuedSeekSoItIsNeverLost() {
        Transport transport = rollingWithRealTimeClock();
        transport.play();
        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);

        transport.setPositionInBeats(12.0); // queued — no signal yet
        transport.pause();                  // RT advance halts; pause() drains

        assertThat(transport.getPositionInBeats()).isEqualTo(12.0);
        assertThat(fired).containsExactly(ChangeKind.STATE, ChangeKind.POSITION);
    }

    @Test
    void stopClearsAQueuedSeekSoTheNextPlayNeverAppliesAStaleTarget() {
        Transport transport = rollingWithRealTimeClock();
        transport.setPositionInBeats(5.0);
        transport.play();                  // anchor = 5
        transport.setPositionInBeats(9.0); // queued

        transport.stop();                  // supersedes the queued seek

        assertThat(transport.getPositionInBeats()).isEqualTo(5.0);

        transport.play();
        transport.advancePosition(0.5);
        assertThat(transport.getPositionInBeats())
                .as("the stale seek must not resurface on the next play's first block")
                .isEqualTo(5.5);
    }

    @Test
    void lastWriterWinsWhenSuccessiveSeeksLandBetweenBlocks() {
        Transport transport = rollingWithRealTimeClock();
        transport.play();

        transport.setPositionInBeats(10.0);
        transport.setPositionInBeats(20.0);
        transport.setPositionInBeats(30.0);
        transport.advancePosition(0.25);

        assertThat(transport.getPositionInBeats()).isEqualTo(30.0);
    }

    @Test
    void queuedSeekTargetsAreValidatedLikeImmediateOnes() {
        Transport transport = rollingWithRealTimeClock();
        transport.play();
        assertThatThrownBy(() -> transport.setPositionInBeats(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.setPositionInBeats(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.setPositionInBeats(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Stress: one thread hammers {@code advancePosition} (the simulated RT
     * callback) while the test thread issues rapid seeks. Every position an
     * observer can read must be finite, non-negative, and on the exact value
     * lattice reachable from integer seek targets advanced in 0.125-beat
     * steps — a torn 64-bit read would (with overwhelming probability) fall
     * off that lattice or out of range. After quiescing, one final seek plus
     * one advance must land exactly on the final target: the last seek always
     * wins and is never lost.
     *
     * <p>Deterministic and bounded: latches + fixed iteration counts, no
     * sleep-based timing.</p>
     */
    @Test
    void rapidSeeksAgainstARunningAdvanceLoopAreNeverLostAndNeverTear() throws Exception {
        Transport transport = rollingWithRealTimeClock();
        transport.play();

        Queue<String> violations = new ConcurrentLinkedQueue<>();
        transport.addChangeListener(kind -> {
            if (kind == ChangeKind.POSITION) {
                double p = transport.getPositionInBeats();
                boolean onLattice = p * 8.0 == Math.rint(p * 8.0);
                if (!Double.isFinite(p) || p < 0.0 || !onLattice) {
                    violations.add("torn or invalid position observed: " + p);
                }
            }
        });

        AtomicBoolean done = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        Thread advancer = new Thread(() -> {
            started.countDown();
            while (!done.get()) {
                transport.advancePosition(0.125);
            }
        }, "rt-advance-sim");
        advancer.start();
        assertThat(started.await(5, TimeUnit.SECONDS))
                .as("the simulated RT thread must start")
                .isTrue();

        for (int i = 1; i <= 20_000; i++) {
            transport.setPositionInBeats(i); // queued: state is PLAYING
        }

        done.set(true);
        advancer.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(advancer.isAlive())
                .as("the simulated RT thread must terminate")
                .isFalse();

        // Quiesce deterministically: the final seek must win over anything
        // still pending, and the next block boundary applies it verbatim.
        transport.setPositionInBeats(123_456.0);
        transport.advancePosition(0.125);

        assertThat(transport.getPositionInBeats()).isEqualTo(123_456.0);
        assertThat(violations).isEmpty();
    }

    // ── Relative seeks compose against the pending target (story 315 review) ──

    @Test
    void seekTargetEqualsThePositionWhenNoSeekIsPending() {
        Transport transport = rollingWithRealTimeClock();
        transport.setPositionInBeats(8.0); // stopped — applies inline
        assertThat(transport.getSeekTargetInBeats())
                .isEqualTo(transport.getPositionInBeats())
                .isEqualTo(8.0);

        transport.play();
        transport.advancePosition(2.0);
        assertThat(transport.getSeekTargetInBeats())
                .as("still no seek queued — the committed position is the base")
                .isEqualTo(10.0);
    }

    @Test
    void seekTargetExposesTheQueuedTargetWhileOneIsPending() {
        Transport transport = rollingWithRealTimeClock();
        transport.play();
        transport.setPositionInBeats(40.0); // queued

        assertThat(transport.getPositionInBeats())
                .as("the committed position has not moved yet")
                .isZero();
        assertThat(transport.getSeekTargetInBeats()).isEqualTo(40.0);
    }

    @Test
    void twoRelativeSeeksInsideOneBlockAccumulateWhenComposedAgainstTheSeekTarget() {
        // Two Skip Forward presses between block boundaries. Composed against
        // getPositionInBeats() both would compute 0 + 4 and the single-slot
        // queue would keep one jump; composed against getSeekTargetInBeats()
        // the second builds on the first.
        Transport transport = rollingWithRealTimeClock();
        transport.play();

        skipForward(transport, 4.0);
        skipForward(transport, 4.0);

        transport.advancePosition(0.25); // the block boundary drains the queue

        assertThat(transport.getPositionInBeats())
                .as("two skips must land two jumps ahead, not one")
                .isEqualTo(8.0);
    }

    @Test
    void relativeSeeksComposedAgainstTheSeekTargetStillWorkWhileStopped() {
        Transport transport = new Transport(); // unclaimed, stopped
        skipForward(transport, 4.0);
        skipForward(transport, 4.0);

        assertThat(transport.getPositionInBeats()).isEqualTo(8.0);
    }

    /**
     * Regression for the pop-then-commit drain gap (story 315 Copilot review):
     * {@code advancePosition} used to pop the seek queue <em>before</em>
     * committing the target into the position, so a
     * {@code getSeekTargetInBeats()} read landing between the two saw neither
     * value and returned the stale pre-seek base — two Skip Forward presses
     * straddling the drain collapsed into one jump. The fixed drain commits
     * before it clears, so the base is linearizable: always the queued target
     * or a committed position that already includes it.
     *
     * <p>Deterministic by construction, not by timing: the simulated RT thread
     * advances by <em>zero</em> beats (legal — the delta must only be
     * non-negative), so drains run constantly while the committed position
     * never drifts, and every round asserts <em>exact</em> equality — the
     * second skip must land exactly one jump past the first, no tolerance.
     * Against the pop-then-commit drain this fails intermittently; with
     * commit-before-clear it can never fail.</p>
     */
    @Test
    void relativeSeeksComposedAgainstTheSeekTargetNeverCollapseAcrossTheRtDrain()
            throws Exception {
        Transport transport = rollingWithRealTimeClock();
        transport.play(); // position 0 committed while stopped; loop disabled

        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(1);
        Thread advancer = new Thread(() -> {
            started.countDown();
            while (running.get()) {
                transport.advancePosition(0.0); // drain-only block boundaries
            }
        }, "rt-advance-sim");
        advancer.start();
        assertThat(started.await(5, TimeUnit.SECONDS))
                .as("the simulated RT thread must start")
                .isTrue();

        double lastTarget = 0.0;
        try {
            for (int round = 0; round < 20_000; round++) {
                double t1 = transport.getSeekTargetInBeats() + 1.0;
                transport.setPositionInBeats(t1);
                double t2 = transport.getSeekTargetInBeats() + 1.0;
                transport.setPositionInBeats(t2);
                assertThat(t2)
                        .as("round %d: the second skip must build on the first"
                                + " — a stale base collapses two skips into one",
                                round)
                        .isEqualTo(t1 + 1.0);
                lastTarget = t2;
            }
        } finally {
            running.set(false);
            advancer.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertThat(advancer.isAlive())
                .as("the simulated RT thread must terminate")
                .isFalse();

        transport.setRealTimeClockActive(false); // drains any still-queued target
        assertThat(transport.getPositionInBeats()).isEqualTo(lastTarget);
    }

    /** The relative-seek idiom story 315 documents on {@code getSeekTargetInBeats}. */
    private static void skipForward(Transport transport, double jumpInBeats) {
        transport.setPositionInBeats(
                Math.max(0.0, transport.getSeekTargetInBeats() + jumpInBeats));
    }
}
