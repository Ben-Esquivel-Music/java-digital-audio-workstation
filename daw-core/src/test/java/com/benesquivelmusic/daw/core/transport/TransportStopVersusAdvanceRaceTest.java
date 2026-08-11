package com.benesquivelmusic.daw.core.transport;

import com.benesquivelmusic.daw.core.transport.Transport.ChangeKind;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 315 review — {@link Transport#stop()} versus an in-flight
 * {@link Transport#advancePosition(double)} on the RT thread.
 *
 * <p>Two hazards, both invisible to a single-threaded test because they exist
 * only <em>between</em> two instructions of the RT advance:</p>
 *
 * <ol>
 *   <li><b>Seek-drain back-off.</b> The RT thread pulls a queued seek out of
 *       the single-slot queue, is descheduled, the FX thread stops (which
 *       clears a queue that no longer holds the value), and the RT thread
 *       resumes and stores the seek target — leaving the transport
 *       {@code STOPPED} but parked at the cancelled target.</li>
 *   <li><b>CAS back-off.</b> The advance reads the state <em>before</em> its
 *       compare-and-set. The old ordering argument ("stop stores STOPPED
 *       before the anchor, so a retry sees it") only holds when the stop
 *       actually changes the position: with
 *       {@link Transport#setReturnToStartOnStop(boolean)} disabled, or with the
 *       playhead already sitting on the anchor, the CAS witness survives the
 *       stop, the CAS succeeds after it, and the playhead creeps one block
 *       past the stop point.</li>
 * </ol>
 *
 * <p>Each test drives the hazard directly: a long-lived "RT" thread and the
 * test thread meet at a {@link CyclicBarrier} and then fire
 * {@code advancePosition} and {@code stop()} simultaneously, for a bounded
 * iteration count with no sleeps. The asserted invariants hold for
 * <em>every</em> interleaving of the fixed implementation — a failure is
 * always a real violation, never a timing artefact.</p>
 */
class TransportStopVersusAdvanceRaceTest {

    /** Bounded, deterministic in runtime: no sleeps, barrier-synchronized. */
    private static final int ITERATIONS = 20_000;

    private static final double ANCHOR = 50.0;
    private static final double BLOCK_BEATS = 1.0;

    @Test
    void aStopThatLandsDuringASeekDrainSupersedesTheQueuedSeek() throws Exception {
        Queue<String> violations = new ConcurrentLinkedQueue<>();

        race(transport -> {
            transport.setRealTimeClockActive(true);
            transport.setPositionInBeats(ANCHOR);
            transport.play();                     // anchor = 50
            transport.setPositionInBeats(1_000.0); // queued behind the RT drain
        }, (transport, iteration) -> {
            if (transport.getState() != TransportState.STOPPED) {
                violations.add("iteration " + iteration + ": transport is not stopped");
            }
            if (transport.getPositionInBeats() != ANCHOR) {
                violations.add("iteration " + iteration
                        + ": a stop must supersede the queued seek, but the playhead "
                        + "sits at " + transport.getPositionInBeats());
            }
        });

        assertThat(violations).isEmpty();
    }

    @Test
    void anInFlightAdvanceNeverCreepsPastAStopWhenThePlayheadIsAlreadyOnTheAnchor() throws Exception {
        // Trigger #2: stop() stores the anchor over an identical value, so the
        // CAS witness survives and a naive CAS would still succeed.
        //
        // Loop mode is engaged deliberately: the wrap arithmetic sits between
        // the advance's state read and its compare-and-set, widening the
        // hazard window enough for the stop to land inside it often enough to
        // observe. (Verified against the pre-fix implementation, which fails
        // this test.) The wrapped value (0.5) is nowhere near the anchor, so
        // the invariant below still reads as "the playhead never moved".
        Queue<String> violations = new ConcurrentLinkedQueue<>();

        race(transport -> {
            transport.setLoopEnabled(true);
            transport.setLoopRegion(0.0, ANCHOR + 0.5);
            transport.setPositionInBeats(ANCHOR);
            transport.play(); // anchor = 50 = the current position
        }, (transport, iteration) -> {
            if (transport.getPositionInBeats() != ANCHOR) {
                violations.add("iteration " + iteration
                        + ": the playhead advanced past the stop to "
                        + transport.getPositionInBeats());
            }
        });

        assertThat(violations).isEmpty();
    }

    @Test
    void anInFlightAdvanceNeverCreepsPastAStopWhenReturnToStartIsDisabled() throws Exception {
        // Trigger #1: stop() writes no position at all, so the CAS witness is
        // trivially intact. The playhead may legitimately land on either side
        // of the stop, so the invariant is directional: the position observed
        // once the stop has been ANNOUNCED is an upper bound — the advance is
        // strictly monotonic here (no loop), so anything above it committed
        // after the transport was already stopped.
        Queue<String> violations = new ConcurrentLinkedQueue<>();
        AtomicReference<Double> positionAtStopAnnouncement = new AtomicReference<>();

        race(transport -> {
            transport.setReturnToStartOnStop(false);
            transport.setPositionInBeats(ANCHOR);
            transport.play();
            positionAtStopAnnouncement.set(null);
            transport.addChangeListener(kind -> {
                if (kind == ChangeKind.STATE
                        && transport.getState() == TransportState.STOPPED) {
                    positionAtStopAnnouncement.compareAndSet(
                            null, transport.getPositionInBeats());
                }
            });
        }, (transport, iteration) -> {
            Double announced = positionAtStopAnnouncement.get();
            if (announced == null) {
                violations.add("iteration " + iteration + ": stop() fired no STATE signal");
                return;
            }
            if (transport.getPositionInBeats() > announced) {
                violations.add("iteration " + iteration
                        + ": the playhead moved from " + announced + " to "
                        + transport.getPositionInBeats() + " after the stop was announced");
            }
        });

        assertThat(violations).isEmpty();
    }

    /**
     * Runs {@link #ITERATIONS} rounds of "one RT advance versus one stop",
     * fired simultaneously from two threads meeting at a barrier.
     *
     * @param arrange  prepares a fresh transport for the round
     * @param assertion runs on the test thread once both operations completed
     */
    private static void race(Consumer<Transport> arrange,
                             RoundAssertion assertion) throws Exception {
        AtomicReference<Transport> current = new AtomicReference<>();
        CyclicBarrier ready = new CyclicBarrier(2);
        CyclicBarrier fire = new CyclicBarrier(2);
        CyclicBarrier done = new CyclicBarrier(2);
        // The hazard windows are a handful of instructions wide, and a barrier
        // release alone leaves microseconds of wake-up jitter — far too coarse.
        // Both threads therefore spin-rendezvous immediately after the barrier
        // so the advance and the stop enter within tens of nanoseconds of each
        // other. Bounded by the iteration count; never sleeps.
        AtomicInteger gate = new AtomicInteger();
        Queue<String> workerFailures = new ConcurrentLinkedQueue<>();

        Thread rt = new Thread(() -> {
            try {
                for (int i = 0; i < ITERATIONS; i++) {
                    await(ready);
                    Transport transport = current.get();
                    await(fire);
                    rendezvous(gate);
                    transport.advancePosition(BLOCK_BEATS);
                    await(done);
                }
            } catch (RuntimeException e) {
                workerFailures.add(String.valueOf(e));
                // Break every barrier so the test thread cannot hang.
                ready.reset();
                fire.reset();
                done.reset();
            }
        }, "rt-advance-sim");
        rt.setDaemon(true);
        rt.start();

        for (int i = 0; i < ITERATIONS; i++) {
            Transport transport = new Transport();
            arrange.accept(transport);
            current.set(transport);
            gate.set(0);
            await(ready);
            await(fire);
            rendezvous(gate);
            transport.stop();
            await(done);
            assertion.check(transport, i);
        }

        rt.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(rt.isAlive()).as("the simulated RT thread must terminate").isFalse();
        assertThat(List.copyOf(workerFailures)).isEmpty();
    }

    /** Sub-microsecond alignment: both threads leave together, hot. */
    private static void rendezvous(AtomicInteger gate) {
        gate.incrementAndGet();
        while (gate.get() < 2) {
            Thread.onSpinWait();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted at the barrier", e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("barrier broken or timed out", e);
        }
    }

    @FunctionalInterface
    private interface RoundAssertion {
        void check(Transport transport, int iteration);
    }
}
