package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Per-track CPU budget enforcer with graceful degradation.
 *
 * <p>Implements the story behavior: each registered track has a soft
 * {@link TrackCpuBudget}; the enforcer maintains a rolling average of
 * the fraction of the block budget ({@code bufferSize / sampleRate})
 * consumed by that track's processing segment. Behavior mirrors the
 * DAWs called out in the issue (Studio One's High Precision
 * Monitoring, Reaper's Anticipative FX throttling):</p>
 *
 * <ul>
 *   <li>When a track's rolling CPU fraction stays above its budget
 *       for {@link #CONSECUTIVE_BLOCKS_TO_DEGRADE} consecutive blocks,
 *       the track is marked degraded under its configured
 *       {@link DegradationPolicy} and a
 *       {@link TrackPerformanceEvent.TrackDegraded} notification is
 *       published. The engine does not apply the policy yet: the
 *       track's processing is not reduced.</li>
 *   <li>When a degraded track's rolling CPU fraction stays below its
 *       budget for {@link #RESTORE_HYSTERESIS_NANOS} of real time
 *       (one second by default), the mark is lifted and a
 *       {@link TrackPerformanceEvent.TrackRestored} event is
 *       published.</li>
 *   <li>When the sum of all tracks' rolling CPU fractions exceeds the
 *       master budget, a cascade marks the tracks not yet degraded,
 *       highest CPU first, until the sum less the averages of the tracks
 *       it marked is within the master ceiling or none is left; no
 *       track's processing is reduced.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>{@link #recordTrackCpu(String, long)} and
 * {@link #evaluateMasterBudget()} are called from the audio thread.
 * They take this enforcer's {@link ReentrantLock} (rather than
 * {@code synchronized}, to avoid pinning virtual-thread carriers, JEP
 * 444) and offer each event to a {@link SubmissionPublisher}, which
 * takes its own lock and delivers on a separate executor. The audio
 * thread never waits for a subscriber: an event that a subscriber's
 * full buffer cannot hold is dropped for that subscriber, and one that
 * is a {@link PerformanceEventLossListener} is told which, and how many
 * events were offered before it, on the offering thread. Once
 * {@link #close()} has closed the publisher, nothing is offered, so the
 * audio thread is never thrown at for a closed publisher.</p>
 *
 * <p>The clock is injectable via {@link LongSupplier} so tests can
 * simulate restoration hysteresis deterministically without sleeping.</p>
 */
public final class TrackCpuBudgetEnforcer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TrackCpuBudgetEnforcer.class.getName());

    /** Number of consecutive over-budget blocks before a track is degraded. */
    public static final int CONSECUTIVE_BLOCKS_TO_DEGRADE = 5;

    /** Real-time hysteresis under budget required to restore a track (1 second). */
    public static final long RESTORE_HYSTERESIS_NANOS = 1_000_000_000L;

    /** Rolling-window size, in blocks, used for the per-track CPU average. */
    public static final int ROLLING_WINDOW_BLOCKS = 16;

    private final long blockBudgetNanos;
    private final LongSupplier clockNanos;
    private final double masterMaxFractionOfBlock;
    private final SubmissionPublisher<TrackPerformanceEvent> publisher;
    /**
     * The events offered; written by {@link #publish} on the thread that publishes, and read there by its drop
     * handler.
     */
    private long offered;
    /**
     * The drop handler of every offer: tells a {@link PerformanceEventLossListener} what it lost and how many
     * events were offered before it, and never retries. Created once with the enforcer, so an offer allocates
     * nothing for it.
     */
    private final BiPredicate<Flow.Subscriber<? super TrackPerformanceEvent>, TrackPerformanceEvent> reportDrop =
            (subscriber, dropped) -> {
                if (subscriber instanceof PerformanceEventLossListener listener) {
                    listener.eventDropped(dropped, offered);
                }
                return false;
            };

    private final ReentrantLock lock = new ReentrantLock();
    /** Preserves registration order so master-budget cascades are deterministic for same-CPU ties. */
    private final Map<String, TrackState> tracks = new LinkedHashMap<>();

    // ── Pre-allocated buffers for evaluateMasterBudget() ──────────────
    // Reused every block to avoid GC pressure on the audio thread.
    /** Scratch arrays for the master-budget snapshot. Grown lazily in {@link #ensureSnapshotCapacity(int)}. */
    private String[] snapshotIds = new String[16];
    private double[] snapshotAvgs = new double[16];
    /** Scratch list for the shed-order result. Cleared and reused each call. */
    private final List<String> shedOrderScratch = new ArrayList<>();
    /** Scratch list for deferred event publishing. Cleared and reused each call. */
    private final List<TrackPerformanceEvent> publishScratch = new ArrayList<>();

    /**
     * Creates an enforcer with {@link System#nanoTime()} as the clock
     * and a master budget of {@code 1.0}: the whole block time.
     *
     * @param sampleRate frames per second (must be positive)
     * @param bufferSize frames per callback (must be positive)
     */
    public TrackCpuBudgetEnforcer(double sampleRate, int bufferSize) {
        this(sampleRate, bufferSize, 1.0, System::nanoTime);
    }

    /**
     * Creates an enforcer with an injectable clock and an explicit
     * master budget.
     *
     * @param sampleRate               frames per second (must be positive)
     * @param bufferSize               frames per callback (must be positive)
     * @param masterMaxFractionOfBlock master (global) CPU ceiling in
     *                                 the open interval {@code (0.0, 1.0]};
     *                                 when total track CPU exceeds this
     *                                 value, the highest-CPU tracks are
     *                                 marked degraded first
     * @param clockNanos               monotonic nanosecond clock
     */
    public TrackCpuBudgetEnforcer(double sampleRate,
                                  int bufferSize,
                                  double masterMaxFractionOfBlock,
                                  LongSupplier clockNanos) {
        this(sampleRate, bufferSize, masterMaxFractionOfBlock, clockNanos, new SubmissionPublisher<>());
    }

    /**
     * Creates an enforcer that offers its events to {@code publisher}, which it owns and closes.
     *
     * @param sampleRate               frames per second (must be positive)
     * @param bufferSize               frames per callback (must be positive)
     * @param masterMaxFractionOfBlock master (global) CPU ceiling in the open interval {@code (0.0, 1.0]}
     * @param clockNanos               monotonic nanosecond clock
     * @param publisher                the publisher of {@link #performanceEvents()}
     */
    TrackCpuBudgetEnforcer(double sampleRate,
                           int bufferSize,
                           double masterMaxFractionOfBlock,
                           LongSupplier clockNanos,
                           SubmissionPublisher<TrackPerformanceEvent> publisher) {
        if (sampleRate <= 0.0 || Double.isNaN(sampleRate) || Double.isInfinite(sampleRate)) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive: " + bufferSize);
        }
        if (Double.isNaN(masterMaxFractionOfBlock)
                || masterMaxFractionOfBlock <= 0.0
                || masterMaxFractionOfBlock > 1.0) {
            throw new IllegalArgumentException(
                    "masterMaxFractionOfBlock must be in (0.0, 1.0]: " + masterMaxFractionOfBlock);
        }
        this.blockBudgetNanos = blockBudgetNanos(sampleRate, bufferSize);
        this.clockNanos = Objects.requireNonNull(clockNanos, "clockNanos must not be null");
        this.masterMaxFractionOfBlock = masterMaxFractionOfBlock;
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    /**
     * The block time of a stream of {@code bufferSize} frames per callback at
     * {@code sampleRate}, in nanoseconds: what an enforcer built for that
     * stream measures each track against. The engine compares it with an
     * installed enforcer's when it builds a render drive and in each block it
     * renders (story 338, ruling E204).
     *
     * @param sampleRate frames per second
     * @param bufferSize frames per callback
     * @return {@code bufferSize / sampleRate} in nanoseconds
     */
    public static long blockBudgetNanos(double sampleRate, int bufferSize) {
        return (long) ((bufferSize / sampleRate) * 1_000_000_000.0);
    }

    /** Returns the block budget derived from {@code bufferSize / sampleRate} in nanoseconds. */
    public long getBlockBudgetNanos() {
        return blockBudgetNanos;
    }

    /** Returns the master (global) CPU ceiling configured on this enforcer. */
    public double getMasterMaxFractionOfBlock() {
        return masterMaxFractionOfBlock;
    }

    /**
     * Returns the publisher that emits {@link TrackPerformanceEvent}s.
     * UI code subscribes here so it never polls the audio thread. A
     * subscriber that must account for every event implements
     * {@link PerformanceEventLossListener}.
     */
    public Flow.Publisher<TrackPerformanceEvent> performanceEvents() {
        return publisher;
    }

    /**
     * Registers (or re-registers) a track with the given budget. If
     * {@code budget} is {@code null} the track is configured with
     * {@link TrackCpuBudget#UNLIMITED} — matching the "older project
     * loaded with no budgets set" case from the issue.
     *
     * @param trackId stable identifier of the track; must not be {@code null}
     * @param budget  the budget to apply, or {@code null} for unlimited
     */
    public void registerTrack(String trackId, TrackCpuBudget budget) {
        Objects.requireNonNull(trackId, "trackId must not be null");
        TrackCpuBudget effective = budget == null ? TrackCpuBudget.UNLIMITED : budget;
        lock.lock();
        try {
            TrackState existing = tracks.get(trackId);
            if (existing == null) {
                tracks.put(trackId, new TrackState(effective));
            } else {
                existing.budget = effective;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Removes a track from the enforcer (e.g., when deleted from the session). */
    public void unregisterTrack(String trackId) {
        Objects.requireNonNull(trackId, "trackId must not be null");
        lock.lock();
        try {
            tracks.remove(trackId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records a per-track CPU measurement for the current block. Call
     * this with the nanoseconds elapsed between the start and end of
     * the track's processing segment.
     *
     * <p>If the rolling average trips the track's budget for
     * {@link #CONSECUTIVE_BLOCKS_TO_DEGRADE} consecutive blocks the
     * track is marked degraded under its configured
     * {@link DegradationPolicy}, which the engine does not apply yet,
     * and a {@link TrackPerformanceEvent.TrackDegraded} notification is
     * emitted. If a degraded track stays below its budget for
     * {@link #RESTORE_HYSTERESIS_NANOS} of real time a matching
     * {@link TrackPerformanceEvent.TrackRestored} is emitted.</p>
     *
     * @param trackId      identifier of the track
     * @param elapsedNanos nanoseconds spent processing this track in
     *                     the current block; must be {@code >= 0}
     */
    public void recordTrackCpu(String trackId, long elapsedNanos) {
        Objects.requireNonNull(trackId, "trackId must not be null");
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("elapsedNanos must be non-negative: " + elapsedNanos);
        }
        double fraction = blockBudgetNanos == 0L
                ? 0.0
                : (double) elapsedNanos / (double) blockBudgetNanos;
        long now = clockNanos.getAsLong();
        TrackPerformanceEvent degraded = null;
        TrackPerformanceEvent restored = null;
        lock.lock();
        try {
            TrackState st = tracks.get(trackId);
            if (st == null) {
                // Track not registered; ignore silently so engine code
                // can safely call this before/after registration races.
                return;
            }
            st.addSample(fraction);
            double avg = st.rollingAverage();
            TrackCpuBudget budget = st.budget;

            if (budget.isOverBudget(avg)) {
                st.consecutiveOverBudget++;
                st.underBudgetSinceNanos = -1L;
                if (!st.degraded && st.consecutiveOverBudget >= CONSECUTIVE_BLOCKS_TO_DEGRADE) {
                    st.degraded = true;
                    st.appliedPolicy = budget.onOverBudget();
                    // DoNothing: stay "degraded" internally (so we
                    // don't re-fire) but do not publish an event —
                    // preserves current behavior as documented.
                    if (!(st.appliedPolicy instanceof DegradationPolicy.DoNothing)) {
                        degraded = new TrackPerformanceEvent.TrackDegraded(
                                trackId, avg, budget, st.appliedPolicy);
                    }
                }
            } else {
                st.consecutiveOverBudget = 0;
                if (st.degraded) {
                    if (st.underBudgetSinceNanos < 0L) {
                        st.underBudgetSinceNanos = now;
                    } else if (now - st.underBudgetSinceNanos >= RESTORE_HYSTERESIS_NANOS) {
                        boolean publish = !(st.appliedPolicy instanceof DegradationPolicy.DoNothing);
                        st.degraded = false;
                        st.appliedPolicy = null;
                        st.underBudgetSinceNanos = -1L;
                        if (publish) {
                            restored = new TrackPerformanceEvent.TrackRestored(trackId, avg);
                        }
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        if (degraded != null) {
            final TrackPerformanceEvent ev = degraded;
            LOG.fine(() -> "Track degraded: " + ev);
            publish(ev);
        }
        if (restored != null) {
            final TrackPerformanceEvent ev = restored;
            LOG.fine(() -> "Track restored: " + ev);
            publish(ev);
        }
    }

    /**
     * Returns the rolling-average CPU fraction for the given track,
     * or {@link Optional#empty()} if the track is not registered or
     * has no samples yet.
     */
    public Optional<Double> rollingAverageFor(String trackId) {
        lock.lock();
        try {
            TrackState st = tracks.get(trackId);
            if (st == null || st.sampleCount == 0) {
                return Optional.empty();
            }
            return Optional.of(st.rollingAverage());
        } finally {
            lock.unlock();
        }
    }

    /** Returns whether the given track is currently in a degraded state. */
    public boolean isDegraded(String trackId) {
        lock.lock();
        try {
            TrackState st = tracks.get(trackId);
            return st != null && st.degraded;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Evaluates the master-budget cascade. When the sum of all tracks'
     * rolling CPU fractions exceeds the master ceiling, the tracks not
     * yet degraded are marked degraded in descending-CPU order until the
     * sum, less the averages of the tracks marked in this evaluation, is
     * within the ceiling, or none is left. Returns the order they were
     * marked (highest CPU first). Tracks already degraded keep their
     * state and stay in the sum. Each track marked is marked under its
     * configured policy, which the engine does not apply yet, and a
     * {@link TrackPerformanceEvent.TrackDegraded} event is emitted (even
     * if its per-track budget had not yet tripped) so the user can see
     * which tracks the cascade marked.
     *
     * <p>This method is typically invoked once per block after all
     * {@link #recordTrackCpu(String, long)} calls for that block have
     * completed.</p>
     *
     * @return the ids of the tracks this evaluation marked, highest CPU
     *         first; empty if the master budget was not exceeded
     */
    public List<String> evaluateMasterBudget() {
        shedOrderScratch.clear();
        publishScratch.clear();
        lock.lock();
        try {
            int size = tracks.size();
            ensureSnapshotCapacity(size);
            double total = 0.0;
            int snapshotLen = 0;
            for (Map.Entry<String, TrackState> e : tracks.entrySet()) {
                double avg = e.getValue().rollingAverage();
                total += avg;
                snapshotIds[snapshotLen] = e.getKey();
                snapshotAvgs[snapshotLen] = avg;
                snapshotLen++;
            }
            if (total <= masterMaxFractionOfBlock) {
                return List.of();
            }
            // Sort descending by CPU fraction using a simple insertion sort
            // to avoid allocating a Comparator or boxed wrappers. The
            // snapshot is typically small (tens of tracks at most).
            for (int i = 1; i < snapshotLen; i++) {
                double keyAvg = snapshotAvgs[i];
                String keyId = snapshotIds[i];
                int j = i - 1;
                while (j >= 0 && snapshotAvgs[j] < keyAvg) {
                    snapshotAvgs[j + 1] = snapshotAvgs[j];
                    snapshotIds[j + 1] = snapshotIds[j];
                    j--;
                }
                snapshotAvgs[j + 1] = keyAvg;
                snapshotIds[j + 1] = keyId;
            }
            for (int i = 0; i < snapshotLen; i++) {
                if (total <= masterMaxFractionOfBlock) {
                    break;
                }
                String trackId = snapshotIds[i];
                double avg = snapshotAvgs[i];
                TrackState st = tracks.get(trackId);
                if (st == null || st.degraded) {
                    continue;
                }
                st.degraded = true;
                st.appliedPolicy = st.budget.onOverBudget();
                st.consecutiveOverBudget = CONSECUTIVE_BLOCKS_TO_DEGRADE;
                st.underBudgetSinceNanos = -1L;
                shedOrderScratch.add(trackId);
                total -= avg;
                if (!(st.appliedPolicy instanceof DegradationPolicy.DoNothing)) {
                    publishScratch.add(new TrackPerformanceEvent.TrackDegraded(
                            trackId, avg, st.budget, st.appliedPolicy));
                }
            }
        } finally {
            lock.unlock();
        }
        for (int i = 0; i < publishScratch.size(); i++) {
            publish(publishScratch.get(i));
        }
        // Return an unmodifiable copy so callers cannot mutate the scratch list.
        return List.copyOf(shedOrderScratch);
    }

    /** Clears all per-track state. Called on transport start/stop or sample-rate change. */
    public void reset() {
        lock.lock();
        try {
            for (TrackState st : tracks.values()) {
                st.reset();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the publisher without waiting: each subscriber still receives
     * the events its buffer holds, then {@code onComplete}. A degradation
     * or restore evaluated after the close is not offered: it is neither
     * delivered nor reported dropped, and nothing is thrown at the thread
     * that evaluated it (ruling E178). The window closes a replaced enforcer
     * only once the block in flight has ended (ruling E203), so there such a
     * late evaluation comes only from a block in flight when the window's
     * disposal, or a replacement after it, closes the enforcer without
     * waiting.
     */
    @Override
    public void close() {
        publisher.close();
    }

    /**
     * Offers one event, with {@link #reportDrop} for a subscriber whose
     * buffer is full, unless the publisher is closed, and then counts it in
     * {@link #offered}, so the handler reads the count of the events offered
     * before this one. A close that lands between the check and the offer
     * makes the closed publisher refuse the offer with an
     * {@link IllegalStateException}; that is the same case, so it is neither
     * counted nor thrown on.
     */
    private void publish(TrackPerformanceEvent event) {
        if (publisher.isClosed()) {
            return;
        }
        try {
            publisher.offer(event, reportDrop);
            offered++;
        } catch (IllegalStateException refused) {
            if (!publisher.isClosed()) {
                throw refused;
            }
        }
    }

    /**
     * Ensures the snapshot arrays are large enough for the given track count.
     * Only allocates when the track count exceeds the current capacity.
     * Must be called under {@link #lock}.
     */
    private void ensureSnapshotCapacity(int required) {
        if (snapshotIds.length < required) {
            int newLen = Math.max(required, snapshotIds.length * 2);
            snapshotIds = new String[newLen];
            snapshotAvgs = new double[newLen];
        }
    }

    /** Mutable per-track bookkeeping. Access guarded by the enclosing {@link #lock}. */
    private static final class TrackState {
        /** Ring buffer of the most recent CPU-fraction samples. */
        private final double[] window = new double[ROLLING_WINDOW_BLOCKS];
        private int writeIndex;
        private int sampleCount;
        private double windowSum;

        private TrackCpuBudget budget;
        private int consecutiveOverBudget;
        private boolean degraded;
        private DegradationPolicy appliedPolicy;
        private long underBudgetSinceNanos = -1L;

        TrackState(TrackCpuBudget budget) {
            this.budget = budget;
        }

        void addSample(double fraction) {
            if (sampleCount == window.length) {
                windowSum -= window[writeIndex];
            } else {
                sampleCount++;
            }
            window[writeIndex] = fraction;
            windowSum += fraction;
            writeIndex = (writeIndex + 1) % window.length;
        }

        double rollingAverage() {
            return sampleCount == 0 ? 0.0 : windowSum / sampleCount;
        }

        void reset() {
            java.util.Arrays.fill(window, 0.0);
            writeIndex = 0;
            sampleCount = 0;
            windowSum = 0.0;
            consecutiveOverBudget = 0;
            degraded = false;
            appliedPolicy = null;
            underBudgetSinceNanos = -1L;
        }
    }
}
