package com.benesquivelmusic.daw.core.audio.health;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One audio engine's health record — channel B of the failure-surfacing
 * design (book §3.2, §4.3; story 337, ruling E18).
 *
 * <p>One record exists per engine, for the engine's whole life: it is never
 * replaced and its counters are never reset, so every total only ever grows.
 * It holds three kinds of state.</p>
 * <ul>
 *   <li><strong>Lanes</strong> — one {@link AudioHealthLane} per render thread
 *       (the render drive per stream open or resume, each pool worker per
 *       engine start),
 *       claimed off the real-time thread before the thread starts and retired
 *       after it exits; retiring folds the lane's final counts into the
 *       record, so a thread's counts outlive it (story 337, rulings E18,
 *       E19, E21).</li>
 *   <li><strong>The backend ledger</strong> — the failure counters a backend
 *       keeps on its own threads, pulled through
 *       {@code AudioBackend.threadCounters()} while a stream is open and
 *       folded in when it closes (story 337, ruling E4).</li>
 *   <li><strong>Heartbeats</strong> — the render drive's per-block beat and the
 *       backend's device-callback period count: two monotonic counters, not
 *       timestamps, that a watchdog samples with its own clock (story 337,
 *       ruling E5).</li>
 * </ul>
 *
 * <h2>Threads</h2>
 * <p>{@link #recordOnCurrentThread} is the one real-time method: engine-owned
 * render collaborators ({@code RenderPipeline}, {@code MidiTrackRenderer},
 * {@code AudioWorkerPool}) receive the record by constructor and record
 * through it (story 337, ruling E20). The lifecycle methods run on the
 * engine's lifecycle threads under a private lock, never on a render thread;
 * each publishes an immutable snapshot with one volatile write. The read
 * methods are for any non-real-time reader; {@link #drainSamples} has exactly
 * one consumer, the application's drain.</p>
 */
public final class AudioHealthRecord {

    private final Object registryLock = new Object();
    private volatile LaneSet lanes = LaneSet.EMPTY;
    private volatile BackendLedger backend = BackendLedger.NONE;
    private volatile long renderDriveEpoch;

    /** Creates an empty record. */
    public AudioHealthRecord() {
    }

    // ── Lifecycle (off the real-time thread) ───────────────────────────

    /**
     * Claims a new lane for a render thread about to be built. A lane is never
     * reused: each render thread gets its own, so the lane has one writer for
     * its whole life. Claiming a {@link LaneRole#RENDER_DRIVE} lane counts one
     * more render-drive epoch.
     *
     * @param role the role of the thread that will own the lane
     * @return the new lane; never {@code null}
     */
    public AudioHealthLane claimLane(LaneRole role) {
        Objects.requireNonNull(role, "role must not be null");
        synchronized (registryLock) {
            AudioHealthLane lane = new AudioHealthLane(this, role);
            lanes = lanes.with(lane);
            if (role == LaneRole.RENDER_DRIVE) {
                renderDriveEpoch = renderDriveEpoch + 1L;
            }
            return lane;
        }
    }

    /**
     * Retires a lane whose owner thread has exited or never started: folds its
     * counts, heartbeats, lost descriptors and last failures into the record,
     * removes it from the live lanes and releases the references its ring
     * still holds. Idempotent.
     *
     * @param lane a lane of this record
     * @throws IllegalArgumentException if {@code lane} belongs to another record
     */
    public void retireLane(AudioHealthLane lane) {
        Objects.requireNonNull(lane, "lane must not be null");
        if (lane.owner() != this) {
            throw new IllegalArgumentException("the lane belongs to another health record");
        }
        synchronized (registryLock) {
            LaneSet current = lanes;
            if (!lane.markRetired() || !current.contains(lane)) {
                return;
            }
            lanes = current.retire(lane);
            lane.clearSlots();
        }
    }

    /**
     * Attaches the open stream's backend counter source (story 337, ruling
     * E4). Any previous source is folded in first, and the new source's
     * current values become its baseline, so only what grows from now on is
     * counted — a reopen of the same backend instance never counts its
     * history twice.
     *
     * @param source the backend's counter source, typically
     *               {@code backend::threadCounters}
     */
    public void attachBackendCounters(Supplier<BackendThreadCounters> source) {
        Objects.requireNonNull(source, "source must not be null");
        synchronized (registryLock) {
            backend = backend.attached(source);
        }
    }

    /**
     * Folds the attached source's growth since attach into the record and
     * detaches it — the close-time fold of story 337, ruling E4. A no-op when
     * nothing is attached.
     */
    public void detachBackendCounters() {
        synchronized (registryLock) {
            BackendLedger current = backend;
            if (current.live().isPresent()) {
                backend = current.detached();
            }
        }
    }

    // ── Real-time ──────────────────────────────────────────────────────

    /**
     * Records a failure into the current thread's lane when the current
     * thread is a {@link RenderThread} whose lane belongs to THIS record.
     * Allocation-free, lock-free and log-free.
     *
     * @param category an {@link AudioFailureCodes} constant
     * @param failure  the thrown object, or {@code null}
     * @param subject  the site's subject, or {@code null}
     * @param detail   a site-specific number
     * @return {@code true} when recorded; {@code false} off a render thread or
     *         on another record's render thread, where nothing is recorded
     */
    @RealTimeSafe
    public boolean recordOnCurrentThread(int category, Throwable failure,
                                         Object subject, int detail) {
        if (Thread.currentThread() instanceof RenderThread thread) {
            AudioHealthLane lane = thread.lane();
            if (lane.owner() == this) {
                lane.record(category, failure, subject, detail);
                return true;
            }
        }
        return false;
    }

    // ── Drain side (any non-real-time reader) ──────────────────────────

    /**
     * Returns every category's total, indexed by {@link AudioFailureCodes}
     * code: retired and live lanes, plus the backend ledger
     * ({@code CALLBACK} += callback faults, {@code INPUT} += lost input
     * blocks and input halts, {@code DEVICE} += device-notification faults).
     * Monotonic across calls.
     *
     * @return a fresh array of {@link AudioFailureCodes#COUNT} totals
     */
    public long[] totals() {
        LaneSet set = lanes;
        long[] totals = set.retiredCounts().clone();
        for (AudioHealthLane lane : set.live()) {
            for (int code = 0; code < AudioFailureCodes.COUNT; code++) {
                totals[code] += lane.countOf(code);
            }
        }
        BackendThreadCounters counters = backend.view();
        totals[AudioFailureCodes.CALLBACK] += counters.callbackFaults();
        totals[AudioFailureCodes.INPUT] += counters.inputBlocksLost() + counters.inputHalts();
        totals[AudioFailureCodes.DEVICE] += counters.deviceNotificationFaults();
        return totals;
    }

    /**
     * Returns one category's total, as {@link #totals()} counts it.
     *
     * @param category the category
     * @return the total, monotonic
     */
    public long total(AudioFailureCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        return totals()[category.code()];
    }

    /**
     * Returns the newest failure recorded for {@code category}: from the live
     * lanes (most recently claimed first), else from retired lanes, else — for
     * the categories backends count — from the backend ledger. A category with
     * no throwable (for example {@code audio.config}) has none.
     *
     * @param category the category
     * @return the newest failure, if any
     */
    public Optional<Throwable> lastFailure(AudioFailureCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        int code = category.code();
        LaneSet set = lanes;
        AudioHealthLane[] live = set.live();
        for (int i = live.length - 1; i >= 0; i--) {
            Throwable last = live[i].lastFailureOf(code);
            if (last != null) {
                return Optional.of(last);
            }
        }
        Throwable retired = set.retiredLastFailures()[code];
        if (retired != null) {
            return Optional.of(retired);
        }
        BackendThreadCounters counters = backend.view();
        if (code == AudioFailureCodes.CALLBACK) {
            return counters.lastCallbackFault();
        }
        if (code == AudioFailureCodes.INPUT) {
            return counters.lastInputFault();
        }
        if (code == AudioFailureCodes.DEVICE) {
            return counters.lastDeviceNotificationFault();
        }
        return Optional.empty();
    }

    /**
     * Drains every sampled descriptor of every live lane to {@code consumer}.
     * Exactly one consumer may call this — the application's drain.
     *
     * @param consumer receives each sample, lane by lane, oldest first
     * @return how many samples were delivered
     */
    public int drainSamples(Consumer<AudioFailureSample> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        int delivered = 0;
        for (AudioHealthLane lane : lanes.live()) {
            while (lane.poll(consumer)) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * Returns how many descriptors were dropped because a lane's ring was
     * full — samples only; the totals stay exact.
     *
     * @return the lost-descriptor count, monotonic
     */
    public long lostSamples() {
        LaneSet set = lanes;
        long lost = set.retiredLost();
        for (AudioHealthLane lane : set.live()) {
            lost += lane.lostRecords();
        }
        return lost;
    }

    /**
     * Returns the render-drive heartbeat: blocks the engine's render drive has
     * handed to the backend, across every stream (story 337, ruling E5,
     * channel 1). Monotonic.
     *
     * @return the render-drive beat count
     */
    public long renderDriveBeats() {
        LaneSet set = lanes;
        long beats = set.retiredRenderDriveBeats();
        for (AudioHealthLane lane : set.live()) {
            if (lane.role() == LaneRole.RENDER_DRIVE) {
                beats += lane.beats();
            }
        }
        return beats;
    }

    /**
     * Returns how many render-drive lanes have been claimed — one per
     * render-pump start, that is per stream open and per resume after a pause
     * — so a reader can tell a render drive started after a given moment.
     *
     * @return the render-drive epoch, monotonic
     */
    public long renderDriveEpoch() {
        return renderDriveEpoch;
    }

    /**
     * Returns the device-callback heartbeat of the open stream's backend
     * (story 337, ruling E5, channel 2): device-callback periods counted
     * across every stream. Empty when the open stream's backend has no device
     * callback thread of its own — a watchdog then uses
     * {@link #renderDriveBeats()} — or, with no stream open, when no backend
     * ever reported one.
     *
     * @return the device-callback period count, or empty
     */
    public OptionalLong deviceCallbackPeriods() {
        BackendLedger ledger = backend;
        if (ledger.live().isPresent()) {
            OptionalLong live = ledger.liveDelta().deviceCallbackPeriods();
            if (live.isEmpty()) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(live.getAsLong()
                    + ledger.folded().deviceCallbackPeriods().orElse(0L));
        }
        return ledger.folded().deviceCallbackPeriods();
    }

    /**
     * Returns how many backend input threads ended on a fault they could not
     * continue past (story 337, ruling E17).
     *
     * @return the input-halt count, monotonic
     */
    public long inputHalts() {
        return backend.view().inputHalts();
    }

    /**
     * Returns the backend counters as the record has seen them: folded from
     * closed streams plus the open stream's growth since it was attached; last
     * faults from the open stream's backend when it has one.
     *
     * @return the counters; never {@code null}
     */
    public BackendThreadCounters backendCounters() {
        return backend.view();
    }
}
