package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.recording.CaptureRing;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A device whose clock stops once the take's capture ring has been handed
 * its allowance of blocks (PR #984, fix round 7). Over the stock
 * {@link MockAudioBackend} the engine's render pump publishes one block
 * into the take's ring every block period (5.33 ms at 48 kHz / 256
 * frames) whatever the capture thread is doing. The ring holds 64 such
 * blocks (about 341 ms), so a capture thread that a loaded machine stalls
 * on its disk writes for longer than that drops blocks, and the drain pass
 * that ends the stall warns "Capture ring overflow" — a WARNING shown
 * while the take is still recording, which lands among the notifications
 * a test reads ahead of what its Stop reports. A test that reads every
 * notification shown from a point while its take is still recording is
 * exposed for as long as the take records after that point: until the
 * FX turn of its Stop, which a loaded machine can make late, or of the
 * auto-Stop of an early seal; a test that holds the capture thread is
 * exposed whenever such a stall is under way, as the hold takes the
 * thread at its next clock read, which can come after that pass has
 * warned, and the test waits for it before it stops the take. Over this device the
 * pump's pacing seam ({@link AudioBackend#awaitSinkCapacity(long)}) parks,
 * once the take has published its allowance of blocks ({@link #BLOCKS},
 * raised by one by each {@link #grantOneMoreBlock()}), until the allowance
 * grows, the pump is stopped (its stop interrupts the thread), the stream
 * closes or {@link #PARK_GUARD} has passed. A take with a single input
 * source is handed at most {@link #BLOCKS} + 1 blocks, no more than the
 * smallest ring holds ({@link CaptureRing#MIN_SLOTS}), so none can be
 * dropped however slowly the capture thread drains them. Only such a take:
 * {@link RecordingPipeline#getPublishedBlocks()} sums every input ring of
 * the take, a live sibling source publishes from its own subscriber
 * thread, which the park does not pace, and a sibling whose source dropped
 * is fed silence by the render pump in the same pass as the first source;
 * every budgeted test records one source. This
 * breaks the seam's "return within roughly the timeout" contract on
 * purpose, up to that guard: the device has stopped. Every other call
 * goes to the backend behind the device through a {@link Proxy}: a
 * {@link MockAudioBackend} is final, and a hand-written delegate would
 * have to forward each of the interface's methods to stay the same device.
 *
 * <p>The latest take is the one {@code latestTake} names: a test that
 * holds the capture thread passes its hold's
 * {@link CaptureThreadHold#latestPipeline()}, and one without a hold the
 * pipeline its controller's pipeline-setup seam last handed it. The backend
 * behind the device is a new mock ({@link #backend()}) or one the caller
 * keeps ({@link #backend(AudioBackend)}) — a fixture that drives that mock
 * itself, its device events, its sink, or a test that counts the calls its
 * own backend gets — which the device's calls reach as the same instance.
 * A device loss ends a park as a closed stream does: the loss's close of
 * the stream stops the pump, which interrupts it.</p>
 *
 * <p>The device keeps parking for its latest take once that take has
 * stopped ({@link #capturing} says why): while a stopped take is still the
 * latest, a later stream on the same device parks on each of its pump's
 * pacing calls, for up to {@link #PARK_GUARD} each, until that stream
 * closes or its pump is stopped. So a test that reopens a stream on a
 * budgeted device after its take, and waits for that stream to render,
 * must not use the budget, as
 * {@code DeviceLossRecordingFixture.recordABudgetedTakeUntilABlockIsOnDisk()}
 * notes for its tests.</p>
 */
final class CaptureBlockBudget {
    /** The blocks a take is handed before the device stops. */
    static final int BLOCKS = 4;
    /**
     * The longest one pacing call parks for an allowance that does not
     * grow: twice {@link CaptureThreadHold#HOLD_GUARD}. That is longer
     * than the sum of the bounded waits a test makes while the device is
     * stopped — from the allowance to the grant or the Stop (a block on
     * disk, the session input check, a few FX turns, the stand-in early
     * seal's thread) and from the grant to the Stop (the hold, the Stop's
     * turn) — so the guard never ends a park in a run in which those
     * waits pass. It only keeps a pump that nothing
     * stopped from parking forever; the block that pump then renders is
     * one beyond the allowance, which
     * {@link #assertNoBlockBeyondTheAllowanceAndNoneDropped} reports while
     * the take's producer gate is open. Once the take's Stop has closed
     * that gate, the block meets the closed gate and is not published.
     */
    private static final Duration PARK_GUARD = CaptureThreadHold.HOLD_GUARD.multipliedBy(2);
    private static final long UNLIMITED = Long.MAX_VALUE;

    /** The pipeline of the latest take, or empty before the first; read on the render pump. */
    private final Supplier<Optional<RecordingPipeline>> latestTake;
    /** How many blocks the latest take may have published; {@link #UNLIMITED} until {@link #backend()}. */
    private volatile long allowance = UNLIMITED;
    /**
     * The latest take once it was seen capturing: its rings are built and
     * may be read. A latch: the device never asks a latched take
     * {@code isActive()} again, so it keeps parking that take's pump after
     * the take has stopped. {@code RecordingPipeline.requestStop} clears
     * {@code active} (~:1311) before it closes the take's producer gates
     * ({@code closeInputProducers()}, ~:1326), so a pump the device released
     * on reading {@code !isActive()} could publish one block beyond the
     * allowance through a gate still open.
     */
    private volatile RecordingPipeline capturing;

    /**
     * @param latestTake the pipeline of the latest take, or empty before the
     *                   first; any thread, and it must not throw — the render
     *                   pump asks it
     */
    CaptureBlockBudget(Supplier<Optional<RecordingPipeline>> latestTake) {
        this.latestTake = Objects.requireNonNull(latestTake, "latestTake must not be null");
    }

    /** The device; from now on the latest take is handed {@link #BLOCKS} blocks. */
    AudioBackend backend() {
        return backend(new MockAudioBackend());
    }

    /**
     * {@link #backend()} over {@code device}, which the caller keeps: a call
     * the caller makes on {@code device} and one the engine makes on the
     * returned device reach the same backend, on the thread that makes it.
     */
    AudioBackend backend(AudioBackend device) {
        Objects.requireNonNull(device, "device must not be null");
        assertThat(BLOCKS + 1).as("fixture: a budgeted take never fills the smallest capture ring")
                .isLessThanOrEqualTo(CaptureRing.MIN_SLOTS);
        allowance = BLOCKS;
        InvocationHandler calls = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "CaptureBlockBudget[" + device.name() + "]";
                };
            }
            if (method.getName().equals("awaitSinkCapacity")) {
                long period = (Long) args[0];
                device.awaitSinkCapacity(period);
                awaitTheAllowance(device, period);
                return null;
            }
            try {
                return method.invoke(device, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        };
        return (AudioBackend) Proxy.newProxyInstance(AudioBackend.class.getClassLoader(),
                new Class<?>[] {AudioBackend.class}, calls);
    }

    /**
     * Lets the device hand the latest take one block beyond its allowance,
     * so at least one block is published after this call. The allowance
     * is raised from itself, which only the test thread writes, and not
     * from the take's published count, which the pump may raise between
     * a read and the write: an allowance computed from that read could
     * fall below what is already published, or grant no new block at all.
     * No-op unless {@link #backend()} was made.
     */
    void grantOneMoreBlock() {
        if (allowance != UNLIMITED) {
            allowance = allowance + 1;
        }
    }

    /** Asserts that {@code take} was handed no block beyond its allowance and dropped none. */
    void assertNoBlockBeyondTheAllowanceAndNoneDropped(RecordingPipeline take) {
        assertThat(take.getPublishedBlocks()).as("no block beyond the device's allowance was published")
                .isLessThanOrEqualTo(allowance);
        assertThat(take.getOverflowCount()).as("no block of the take was dropped").isZero();
    }

    private void awaitTheAllowance(AudioBackend device, long period) {
        long deadline = System.nanoTime() + PARK_GUARD.toNanos();
        while (overTheAllowance() && device.isOpen() && !Thread.currentThread().isInterrupted()
                && System.nanoTime() - deadline < 0) {
            LockSupport.parkNanos(this, period);
        }
    }

    private boolean overTheAllowance() {
        long budget = allowance;
        Optional<RecordingPipeline> latest = latestTake.get();
        if (budget == UNLIMITED || latest.isEmpty()) {
            return false;
        }
        RecordingPipeline take = latest.get();
        if (take != capturing) {
            // prepare() builds the rings; beginCapture() marks the take
            // active before it installs the callback that publishes into them.
            if (!take.isActive()) {
                return false;
            }
            capturing = take;
        }
        return take.getPublishedBlocks() >= budget;
    }
}
