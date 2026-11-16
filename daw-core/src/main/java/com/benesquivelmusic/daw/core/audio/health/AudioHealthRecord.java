package com.benesquivelmusic.daw.core.audio.health;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.BackendCounterLedger;
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 *       record, so a thread's counts outlive it, and keeps its undrained
 *       descriptors readable until the drain has taken them (story 337,
 *       rulings E18, E19, E21 and E81).</li>
 *   <li><strong>The backend ledger</strong> — the failure counters backends
 *       keep on their own threads, in the SDK's {@link BackendCounterLedger}:
 *       the open stream's backend, pulled through
 *       {@code AudioBackend.threadCounters()} while the stream is open and
 *       folded in when it closes, and every input device opened beside the
 *       stream, whose reports count as input failures (story 337, rulings E4,
 *       E86 and E90). Each source is read through a guard: a report that
 *       throws or is {@code null} counts nothing more from that source while
 *       it is attached, keeps every total it had already given, and is logged
 *       once — except a {@link VirtualMachineError}, which is not logged and
 *       propagates to the one reader that met it (story 337, rulings E78 and
 *       E95). A reading quotes, for each kind of failure the backends count,
 *       the fault of the source whose count of it grew (story 337, ruling
 *       E94): when several grew, of the one attached last among those that
 *       kept a fault (rulings E99 and E100).</li>
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
 * methods are for any non-real-time reader and never take that lock.
 * {@link #read} is the one drain-side reading: every count, then every
 * sample, then one report of each attached source; if a source is folded
 * while those reports are being read, the reading drops them and reads the
 * still-attached sources once more, and uses only that second read (story
 * 337, rulings E79, E82 and E98). It has exactly one consumer, the
 * application's drain, and it alone marks a source's growth as seen, under a
 * second private monitor that guards only fields and is never held while a
 * counter source runs or a record is logged; a fold publishes the folded
 * ledger under that monitor too. Each single-fact reader is one consistent
 * cut of its own; a reader that combines two facts takes a {@link #read}.</p>
 */
public final class AudioHealthRecord {

    private static final Logger LOG = Logger.getLogger(AudioHealthRecord.class.getName());

    /** The ledger owner of the open stream's backend; an input device is its own owner. */
    private static final Object STREAM_BACKEND = new Object();

    /*
     * The kinds of backend failure a reading quotes a fault for: the three
     * categories backends count, then input halts.
     */
    private static final int CALLBACK_KIND = 0;
    private static final int INPUT_KIND = 1;
    private static final int DEVICE_KIND = 2;
    private static final int HALT_KIND = 3;
    private static final int KINDS = 4;

    private final Object registryLock = new Object();
    private volatile LaneSet lanes = LaneSet.EMPTY;
    private volatile BackendCounterLedger backend = BackendCounterLedger.EMPTY;
    /** Written under {@link #registryLock}; one per {@link #attachBackendCounters}. */
    private volatile long streamOpens;
    /** Guarded by {@link #registryLock}: counter sources attached so far, the attach order of each. */
    private long attachments;

    /**
     * Guards what readings have seen of the counter sources, the faults folds keep for the next reading, the
     * fold count and a fold's publication of the ledger; never held while a counter source runs or a record is
     * logged.
     */
    private final Object seenLock = new Object();
    /** Guarded by {@link #seenLock}: the folds published; a reading marks only if none was since its load. */
    private long folds;
    /** Guarded by {@link #seenLock}: per category kind, the fault readings quote; {@code null} for none. */
    private final Throwable[] quotedFaults = new Throwable[HALT_KIND];
    /** Guarded by {@link #seenLock}: per kind, whether a folded source grew with no reading seeing it. */
    private final boolean[] foldedGrowth = new boolean[KINDS];
    /** Guarded by {@link #seenLock}: per kind, that source's fault in the report its fold folded; or {@code null}. */
    private final Throwable[] foldedFaults = new Throwable[KINDS];
    /** Guarded by {@link #seenLock}: per kind, that folded source's attach order. */
    private final long[] foldedOrders = new long[KINDS];

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
            return lane;
        }
    }

    /**
     * Retires a lane whose owner thread has exited or never started: folds its
     * counts, heartbeats, lost descriptors and last failures into the record
     * and removes it from the live lanes. Its undrained descriptors stay
     * readable until the drain has taken them; once drained it holds no
     * reference. At most 32 undrained retired lanes are kept, the oldest
     * beyond that cleared and its descriptors counted lost (story 337, ruling
     * E81). Idempotent.
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
        }
    }

    /**
     * Attaches the open stream's backend counter source (story 337, ruling
     * E4) and counts one stream open (story 337, ruling E87). Any previous
     * stream source is folded in first, and the new source's current values
     * become its baseline, so only what grows from now on is counted — a
     * reopen of the same backend instance never counts its history twice.
     *
     * <p>The source is read through a guard (story 337, rulings E78 and E95):
     * a report that throws anything but a {@link VirtualMachineError}, or is
     * {@code null} — at this attach or later — freezes the source at its last
     * good report without its device heartbeat and is logged once at WARNING,
     * naming the backend. A {@link VirtualMachineError} from the report
     * freezes the source the same way, is not logged, and propagates: here it
     * fails the attach, before anything is attached or counted. The report is
     * read outside the record's lock; the fold of a previous source runs
     * under it.</p>
     *
     * @param backendName the backend's name for the log record, read lazily
     *                    and only when the source fails; typically
     *                    {@code backend::name}
     * @param source      the backend's counter source, typically
     *                    {@code backend::threadCounters}
     */
    public void attachBackendCounters(Supplier<String> backendName,
                                      Supplier<BackendThreadCounters> source) {
        GuardedCounterSource guarded = new GuardedCounterSource(backendName, source, false);
        BackendThreadCounters baseline = guarded.get();
        synchronized (registryLock) {
            fold(STREAM_BACKEND);
            attachments = attachments + 1L;
            guarded.attachOrder = attachments;
            backend = backend.attached(STREAM_BACKEND, guarded, baseline);
            streamOpens = streamOpens + 1L;
        }
    }

    /**
     * Folds the attached stream source's growth since attach into the record
     * and detaches it — the close-time fold of story 337, ruling E4. A no-op
     * when nothing is attached. Input devices attached beside the stream stay
     * attached. Growth no reading has seen yet counts as this source's growth
     * in the reading that first counts it (story 337, rulings E94 and E98).
     */
    public void detachBackendCounters() {
        synchronized (registryLock) {
            fold(STREAM_BACKEND);
        }
    }

    /**
     * Attaches the counter source of an input device opened beside the
     * stream (story 337, ruling E86), with its own baseline and the same guard
     * as {@link #attachBackendCounters}. Its report counts as input only:
     * its callback faults and lost blocks are lost input blocks
     * ({@code audio.input}, never {@code audio.callback}), its input halts
     * stay input halts, its last input fault comes before its last callback
     * fault, and its device heartbeat is not the stream's. Its
     * device-notification faults stay {@code audio.device}. Attaching the
     * same {@code input} again folds its previous source first.
     *
     * @param input     the input device's identity, compared by identity;
     *                  typically the backend instance that opened it
     * @param inputName the device's name for the log record, read lazily and
     *                  only when the source fails
     * @param source    the device's counter source
     */
    public void attachInputCounters(Object input, Supplier<String> inputName,
                                    Supplier<BackendThreadCounters> source) {
        Objects.requireNonNull(input, "input must not be null");
        GuardedCounterSource guarded = new GuardedCounterSource(inputName, source, true);
        BackendThreadCounters baseline = guarded.get();
        synchronized (registryLock) {
            fold(input);
            attachments = attachments + 1L;
            guarded.attachOrder = attachments;
            backend = backend.attached(input, guarded, baseline);
        }
    }

    /**
     * Folds an input device's growth since its attach into the record and
     * detaches it (story 337, ruling E86). Idempotent: a no-op when
     * {@code input} is not attached. A halt or loss no reading has seen yet
     * counts as this device's growth in the reading that first counts it
     * (story 337, rulings E94 and E98).
     *
     * @param input the identity {@link #attachInputCounters} was given
     */
    public void detachInputCounters(Object input) {
        Objects.requireNonNull(input, "input must not be null");
        synchronized (registryLock) {
            fold(input);
        }
    }

    /**
     * Folds {@code owner}'s live counter source, if it has one, from one call
     * of its report: the counts folded and the faults kept come from that one
     * report (story 337, ruling E98). Growth beyond what readings saw of the
     * source is kept for the reading that first counts it, ranked as
     * {@link #read} ranks growers (rulings E94, E99 and E100), and published
     * with the folded ledger and the fold count in one step under
     * {@link #seenLock}, so a reading that loaded the ledger before that step
     * is taken again. A {@link VirtualMachineError} from the report publishes
     * nothing. Caller holds {@link #registryLock}; the report is called
     * outside {@link #seenLock}.
     */
    private void fold(Object owner) {
        BackendCounterLedger ledger = backend;
        List<BackendCounterLedger.LiveSource> live = ledger.live();
        for (int index = 0; index < live.size(); index++) {
            BackendCounterLedger.LiveSource source = live.get(index);
            if (source.owner() == owner) {
                GuardedCounterSource guard = (GuardedCounterSource) source.source();
                BackendThreadCounters report = guard.get();
                BackendCounterLedger.LiveSource pinned =
                        new BackendCounterLedger.LiveSource(owner, () -> report, source.baseline());
                BackendThreadCounters growth = pinned.delta();
                List<BackendCounterLedger.LiveSource> sources = new ArrayList<>(live);
                sources.set(index, pinned);
                publishFold(guard, growth, new BackendCounterLedger(ledger.folded(), sources).detached(owner));
                return;
            }
        }
    }

    /**
     * Keeps, per kind, the fault of {@code growth} when the folded source's count grew beyond what readings saw
     * of it and it ranks before the source already kept; then publishes {@code next} and counts the fold.
     */
    private void publishFold(GuardedCounterSource guard, BackendThreadCounters growth, BackendCounterLedger next) {
        synchronized (seenLock) {
            for (int kind = 0; kind < KINDS; kind++) {
                Throwable fault = faultOf(growth, kind);
                if (countOf(growth, kind) > guard.seen[kind] && (!foldedGrowth[kind]
                        || precedes(fault, guard.attachOrder, foldedFaults[kind], foldedOrders[kind]))) {
                    foldedGrowth[kind] = true;
                    foldedFaults[kind] = fault;
                    foldedOrders[kind] = guard.attachOrder;
                }
            }
            backend = next;
            folds = folds + 1L;
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
     * Takes one reading of the record (story 337, rulings E79, E82 and E98),
     * in this order: every lane count, with the lanes' last failures, the
     * render-drive heartbeat, the render-drive epoch and the stream opens;
     * then every sampled descriptor, of the undrained retired lanes (oldest
     * retired first) and then of the live lanes, handed to {@code samples};
     * then the lost-sample count; then one report of each attached source,
     * whose counts the reading's totals include. If a source is folded while
     * those reports are being read, the reading drops them and reads the
     * still-attached sources once more, and uses only that second read; a
     * fold during that read drops it too, so a source is read at most once
     * more per fold. The reading never waits for a device: it never takes the
     * lock under which a fold reads the folded source's report, and no report
     * is read under a monitor the reading shares with a fold.
     *
     * <p>A lane publishes its count last, so every lane failure the reading
     * counts has its sample handed to {@code samples} in this same call, or
     * was dropped and counted lost; a sample may arrive a reading before its
     * count. Exactly one consumer may call this — the application's drain —
     * because the rings have one consumer. A {@link VirtualMachineError} from
     * a backend report propagates after every sample was handed over.</p>
     *
     * <p>For a category the backends count, the reading's last failure — when
     * no lane has one — is the fault of the source whose count of it grew
     * most recently, as readings saw it, and its input-halt fault is the last
     * input fault of a source whose input halts grew since the previous
     * reading (story 337, ruling E94). When several sources' counts of it
     * grew within one reading, the fault is that of the source attached last
     * among those that kept a fault, and none when none of them kept one
     * (ruling E99); a source ranks by the attachment under which its count
     * grew (ruling E100). A source folded with growth no reading saw counts
     * as grown in the reading whose totals first include that growth, with
     * the fault of the report its fold folded, and is ranked with the live
     * sources by the same rule. Only this method marks a source's growth as
     * seen.</p>
     *
     * @param samples receives each sample, lane by lane, oldest first
     * @return the reading; never {@code null}
     */
    public AudioHealthReading read(Consumer<AudioFailureSample> samples) {
        Objects.requireNonNull(samples, "samples must not be null");
        LaneSet set = lanes;
        long[] totals = laneCounts(set);
        Throwable[] lastFailures = new Throwable[AudioFailureCodes.COUNT];
        for (int code = 0; code < AudioFailureCodes.COUNT; code++) {
            lastFailures[code] = laneLastFailure(set, code);
        }
        long beats = renderDriveBeats(set);
        long epoch = set.renderDriveEpoch();
        long opens = streamOpens;
        for (AudioHealthLane lane : set.draining()) {
            while (lane.poll(samples)) {
                // Each poll hands one descriptor to the consumer.
            }
        }
        for (AudioHealthLane lane : set.live()) {
            while (lane.poll(samples)) {
                // Each poll hands one descriptor to the consumer.
            }
        }
        long lost = lostSamples(set);
        BackendThreadCounters counters;
        Throwable[] faults;
        // Each repeat needs one more fold, and only a device close or reopen publishes one, so this ends.
        while (true) {
            BackendCounterLedger ledger;
            long foldsAtLoad;
            synchronized (seenLock) {
                ledger = backend;
                foldsAtLoad = folds;
            }
            List<BackendCounterLedger.LiveSource> live = ledger.live();
            BackendThreadCounters[] deltas = new BackendThreadCounters[live.size()];
            BackendThreadCounters passCounters = ledger.folded();
            for (int index = 0; index < deltas.length; index++) {
                deltas[index] = live.get(index).delta();
                passCounters = deltas[index].plus(passCounters);
            }
            Throwable[] passFaults = new Throwable[KINDS];
            if (markGrowthSeen(live, deltas, passFaults, foldsAtLoad)) {
                counters = passCounters;
                faults = passFaults;
                break;
            }
        }
        addBackend(totals, counters);
        for (int code = 0; code < AudioFailureCodes.COUNT; code++) {
            if (lastFailures[code] == null) {
                lastFailures[code] = quotedFault(faults, code);
            }
        }
        return new AudioHealthReading(totals, lastFailures, counters, faults[HALT_KIND], beats, epoch, opens,
                lost);
    }

    /**
     * Unless a fold was published since the pass loaded its ledger: marks
     * each live source's growth beyond what readings saw of it as seen, takes
     * the growth of the sources folded before that ledger, and fills
     * {@code faults} — per category kind the fault readings quote, and the
     * input-halt fault of this reading — with the fault of the grower ranked
     * first by {@link #precedes} (story 337, rulings E94, E99 and E100).
     *
     * @param live        the pass's live sources, oldest attached first
     * @param deltas      each live source's growth since its attach, read once by this pass
     * @param faults      receives one fault per kind, {@code null} for none
     * @param foldsAtLoad the fold count when the pass loaded its ledger
     * @return {@code false}, having marked and taken nothing, when a fold was
     *         published since: the pass must be taken again
     */
    private boolean markGrowthSeen(List<BackendCounterLedger.LiveSource> live, BackendThreadCounters[] deltas,
                                   Throwable[] faults, long foldsAtLoad) {
        synchronized (seenLock) {
            if (folds != foldsAtLoad) {
                return false;
            }
            for (int kind = 0; kind < KINDS; kind++) {
                boolean grew = false;
                Throwable fault = null;
                long order = 0L;
                for (int index = 0; index < deltas.length; index++) {
                    GuardedCounterSource guard = (GuardedCounterSource) live.get(index).source();
                    long count = countOf(deltas[index], kind);
                    if (count > guard.seen[kind]) {
                        guard.seen[kind] = count;
                        Throwable candidate = faultOf(deltas[index], kind);
                        if (!grew || precedes(candidate, guard.attachOrder, fault, order)) {
                            fault = candidate;
                            order = guard.attachOrder;
                        }
                        grew = true;
                    }
                }
                if (foldedGrowth[kind] && (!grew || precedes(foldedFaults[kind], foldedOrders[kind], fault, order))) {
                    fault = foldedFaults[kind];
                }
                grew = grew || foldedGrowth[kind];
                foldedGrowth[kind] = false;
                foldedFaults[kind] = null;
                if (kind == HALT_KIND) {
                    faults[kind] = fault;
                } else {
                    if (grew) {
                        quotedFaults[kind] = fault;
                    }
                    faults[kind] = quotedFaults[kind];
                }
            }
            return true;
        }
    }

    /**
     * Whether a grower that kept {@code fault} under the attachment {@code order} is quoted before one that kept
     * {@code otherFault} under {@code otherOrder}: one that kept a fault first, then the one attached later
     * (story 337, rulings E99 and E100).
     */
    private static boolean precedes(Throwable fault, long order, Throwable otherFault, long otherOrder) {
        if ((fault == null) != (otherFault == null)) {
            return fault != null;
        }
        return order > otherOrder;
    }

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
        long[] totals = laneCounts(lanes);
        addBackend(totals, backend.view());
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
     * the categories backends count — the last fault of the newest-attached
     * backend source that has one, however old, then the folded one. That is
     * not the reason a notice quotes, which is the fault of the source whose
     * count grew ({@link AudioHealthReading#lastFailure}; story 337, ruling
     * E94). A category with no throwable (for example {@code audio.config})
     * has none.
     *
     * @param category the category
     * @return the newest failure, if any
     */
    public Optional<Throwable> lastFailure(AudioFailureCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        int code = category.code();
        Throwable last = laneLastFailure(lanes, code);
        if (last != null) {
            return Optional.of(last);
        }
        if (code == AudioFailureCodes.CALLBACK || code == AudioFailureCodes.INPUT
                || code == AudioFailureCodes.DEVICE) {
            return backendLastFailure(backend.view(), code);
        }
        return Optional.empty();
    }

    /**
     * Returns how many descriptors were dropped because a lane's ring had no
     * room for them, or because a retired lane nobody drained was evicted —
     * samples only; the totals stay exact.
     *
     * @return the lost-descriptor count, monotonic
     */
    public long lostSamples() {
        return lostSamples(lanes);
    }

    /**
     * Returns the render-drive heartbeat: blocks the engine's render drive has
     * handed to the backend, across every stream (story 337, ruling E5,
     * channel 1). Monotonic.
     *
     * @return the render-drive beat count
     */
    public long renderDriveBeats() {
        return renderDriveBeats(lanes);
    }

    /**
     * Returns how many render-drive lanes have been claimed — one per
     * render-pump start, that is per stream open and per resume after a pause
     * — so a reader can tell a render drive started after a given moment.
     *
     * @return the render-drive epoch, monotonic
     */
    public long renderDriveEpoch() {
        return lanes.renderDriveEpoch();
    }

    /**
     * Returns the device-callback heartbeat of the open stream's backend
     * (story 337, ruling E5, channel 2): device-callback periods counted
     * across every stream. Empty when the open stream's backend has no device
     * callback thread of its own, or its report failed (story 337, ruling
     * E78) — a watchdog then uses {@link #renderDriveBeats()} — or, with no
     * stream open, when no backend ever reported one. An input device opened
     * beside the stream never contributes (story 337, ruling E86).
     *
     * @return the device-callback period count, or empty
     */
    public OptionalLong deviceCallbackPeriods() {
        BackendCounterLedger ledger = backend;
        Optional<BackendThreadCounters> live = ledger.liveDelta(STREAM_BACKEND);
        if (live.isPresent()) {
            OptionalLong periods = live.get().deviceCallbackPeriods();
            if (periods.isEmpty()) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(periods.getAsLong()
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
     * closed streams and closed input devices, plus the growth of every
     * attached source since its attach; last faults from the newest-attached
     * source that has one.
     *
     * @return the counters; never {@code null}
     */
    public BackendThreadCounters backendCounters() {
        return backend.view();
    }

    // ── Shared readers ─────────────────────────────────────────────────

    /** Every lane count of {@code set}: retired plus live, indexed by code. */
    private static long[] laneCounts(LaneSet set) {
        long[] totals = set.retiredCounts().clone();
        for (AudioHealthLane lane : set.live()) {
            for (int code = 0; code < AudioFailureCodes.COUNT; code++) {
                totals[code] += lane.countOf(code);
            }
        }
        return totals;
    }

    /** The newest lane failure of {@code code}: live lanes newest first, then retired; or {@code null}. */
    private static Throwable laneLastFailure(LaneSet set, int code) {
        AudioHealthLane[] live = set.live();
        for (int i = live.length - 1; i >= 0; i--) {
            Throwable last = live[i].lastFailureOf(code);
            if (last != null) {
                return last;
            }
        }
        return set.retiredLastFailures()[code];
    }

    private static long renderDriveBeats(LaneSet set) {
        long beats = set.retiredRenderDriveBeats();
        for (AudioHealthLane lane : set.live()) {
            if (lane.role() == LaneRole.RENDER_DRIVE) {
                beats += lane.beats();
            }
        }
        return beats;
    }

    private static long lostSamples(LaneSet set) {
        long lost = set.retiredLost();
        for (AudioHealthLane lane : set.live()) {
            lost += lane.lostRecords();
        }
        return lost;
    }

    /** Adds a backend report into {@code totals}: callback faults, input losses and halts, device faults. */
    private static void addBackend(long[] totals, BackendThreadCounters counters) {
        totals[AudioFailureCodes.CALLBACK] += counters.callbackFaults();
        totals[AudioFailureCodes.INPUT] += counters.inputBlocksLost() + counters.inputHalts();
        totals[AudioFailureCodes.DEVICE] += counters.deviceNotificationFaults();
    }

    /** A report's count of one kind: callback faults, lost input blocks and halts, device faults, or halts. */
    private static long countOf(BackendThreadCounters counters, int kind) {
        if (kind == CALLBACK_KIND) {
            return counters.callbackFaults();
        }
        if (kind == INPUT_KIND) {
            return counters.inputBlocksLost() + counters.inputHalts();
        }
        if (kind == DEVICE_KIND) {
            return counters.deviceNotificationFaults();
        }
        return counters.inputHalts();
    }

    /** A report's last fault behind its count of one kind, or {@code null}. */
    private static Throwable faultOf(BackendThreadCounters counters, int kind) {
        if (kind == CALLBACK_KIND) {
            return counters.lastCallbackFault().orElse(null);
        }
        if (kind == DEVICE_KIND) {
            return counters.lastDeviceNotificationFault().orElse(null);
        }
        return counters.lastInputFault().orElse(null);
    }

    /** The quoted fault of a category backends count, from a reading's {@code faults}; {@code null} for the rest. */
    private static Throwable quotedFault(Throwable[] faults, int code) {
        if (code == AudioFailureCodes.CALLBACK) {
            return faults[CALLBACK_KIND];
        }
        if (code == AudioFailureCodes.INPUT) {
            return faults[INPUT_KIND];
        }
        if (code == AudioFailureCodes.DEVICE) {
            return faults[DEVICE_KIND];
        }
        return null;
    }

    /** The backend's last fault for one of the three categories backends count; empty for the rest. */
    private static Optional<Throwable> backendLastFailure(BackendThreadCounters counters, int code) {
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
     * A report as an input device opened beside the stream gives it (story
     * 337, ruling E86): its callback faults and lost blocks are lost input
     * blocks, its last input fault comes before its last callback fault, and
     * it has no heartbeat the stream could use.
     */
    private static BackendThreadCounters inputOnly(BackendThreadCounters counters) {
        return new BackendThreadCounters(0L,
                counters.callbackFaults() + counters.inputBlocksLost(),
                counters.inputHalts(),
                counters.deviceNotificationFaults(),
                OptionalLong.empty(),
                Optional.empty(),
                counters.lastInputFault().or(counters::lastCallbackFault),
                counters.lastDeviceNotificationFault());
    }

    /** {@code counters} without its device heartbeat. */
    private static BackendThreadCounters withoutHeartbeat(BackendThreadCounters counters) {
        return new BackendThreadCounters(counters.callbackFaults(), counters.inputBlocksLost(),
                counters.inputHalts(), counters.deviceNotificationFaults(), OptionalLong.empty(),
                counters.lastCallbackFault(), counters.lastInputFault(),
                counters.lastDeviceNotificationFault());
    }

    /**
     * Every foreign counter source the record is given, behind a guard (story
     * 337, rulings E78 and E95). A report that throws anything but a
     * {@link VirtualMachineError}, or is {@code null}, freezes the source at
     * its last good report without its device heartbeat, for as long as it
     * stays attached, and writes one WARNING naming the backend; every later
     * read answers the frozen report without calling the source again. A
     * {@link VirtualMachineError} freezes the source the same way, writes no
     * log record, and propagates to the reader that met it. The source is
     * called outside this guard's monitor, which only guards the guard's own
     * two fields, so no foreign call ever runs under a lock the drain could
     * wait on.
     */
    private static final class GuardedCounterSource implements Supplier<BackendThreadCounters> {

        private final Supplier<String> name;
        private final Supplier<BackendThreadCounters> source;
        private final boolean inputOnly;
        /** Guarded by this. */
        private BackendThreadCounters lastGood = BackendThreadCounters.NONE;
        /** Guarded by this. */
        private boolean failed;
        /** Guarded by the record's {@code seenLock}: per kind, the growth since attach the last reading saw. */
        private final long[] seen = new long[KINDS];
        /**
         * This source's place in the record's attach order: written once, under the record's
         * {@code registryLock}, before the guard is published in the ledger and never after. A reading reads it
         * under {@code seenLock} and sees that write, because it reached the guard through the volatile ledger.
         */
        private long attachOrder;

        GuardedCounterSource(Supplier<String> name, Supplier<BackendThreadCounters> source,
                             boolean inputOnly) {
            this.name = Objects.requireNonNull(name, "name must not be null");
            this.source = Objects.requireNonNull(source, "source must not be null");
            this.inputOnly = inputOnly;
        }

        @Override
        public BackendThreadCounters get() {
            synchronized (this) {
                if (failed) {
                    return lastGood;
                }
            }
            BackendThreadCounters report;
            try {
                report = source.get();
            } catch (VirtualMachineError fatal) {
                synchronized (this) {
                    freeze();
                }
                throw fatal;
            } catch (Throwable failure) {
                return fail(failure);
            }
            if (report == null) {
                return fail(null);
            }
            BackendThreadCounters mapped = inputOnly ? inputOnly(report) : report;
            synchronized (this) {
                if (failed) {
                    return lastGood;
                }
                // A stale concurrent read can never roll a total back.
                if (countSum(mapped) >= countSum(lastGood)) {
                    lastGood = mapped;
                }
            }
            return mapped;
        }

        /** Freezes this source at its last good report, logging the first failure only. */
        private BackendThreadCounters fail(Throwable failure) {
            boolean first;
            BackendThreadCounters frozen;
            synchronized (this) {
                first = freeze();
                frozen = lastGood;
            }
            if (first) {
                LOG.log(Level.WARNING, failure, () -> describe(failure));
            }
            return frozen;
        }

        /**
         * Freezes this source at its last good report without its heartbeat.
         * Caller holds this guard's monitor.
         *
         * @return whether this was the source's first failure
         */
        private boolean freeze() {
            if (failed) {
                return false;
            }
            failed = true;
            lastGood = withoutHeartbeat(lastGood);
            return true;
        }

        /** The WARNING's sentence, listing only what this kind of source contributes (story 337, ruling E96). */
        private String describe(Throwable failure) {
            String what = failure == null
                    ? " reported no failure counters"
                    : " failed to report its failure counters";
            String uncounted = inputOnly
                    ? "its lost input blocks, input stops and device notifications"
                    : "its device-callback faults, lost input blocks, input stops, device notifications and"
                            + " device heartbeat";
            return subject() + what + "; until it is closed, " + uncounted + " are not counted";
        }

        /** "The audio backend “name”", or "An audio backend" when the name cannot be read. */
        private String subject() {
            String device = inputOnly ? "input device" : "audio backend";
            try {
                String text = name.get();
                if (text != null && !text.isBlank()) {
                    return "The " + device + " “" + text + "”";
                }
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable unreadable) {
                // The name is only for the log record; the record still says what failed.
            }
            return inputOnly ? "An input device" : "An audio backend";
        }

        private static long countSum(BackendThreadCounters counters) {
            return counters.callbackFaults() + counters.inputBlocksLost() + counters.inputHalts()
                    + counters.deviceNotificationFaults();
        }
    }
}
