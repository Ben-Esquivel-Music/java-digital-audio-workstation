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
 *       the configured {@link DegradationPolicy} is applied and a
 *       {@link TrackPerformanceEvent.TrackDegraded} notification is
 *       published.</li>
 *   <li>When a degraded track's rolling CPU fraction stays below its
 *       budget for {@link #RESTORE_HYSTERESIS_NANOS} of real time
 *       (one second by default), full quality is restored and a
 *       {@link TrackPerformanceEvent.TrackRestored} event is
 *       published.</li>
 *   <li>When the sum of all tracks' rolling CPU fractions exceeds the
 *       master budget, a cascade sheds the highest-CPU tracks first
 *       until the total drops back below the master ceiling.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>{@link #recordTrackCpu(String, long)} is designed to be called
 * from the audio thread. Subscribers receive
 * {@link TrackPerformanceEvent}s through a {@link SubmissionPublisher}
 * that delivers on a separate executor so the audio thread never
 * blocks. Internal mutable state is guarded by a {@link ReentrantLock}
 * rather than {@code synchronized} to avoid pinning virtual-thread
 * carriers (see Loom guidance, JEP 444).</p>
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
     * and no master budget (master ceiling at {@code 1.0}).
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
     *                                 value, highest-CPU tracks are
     *                                 shed first
     * @param clockNanos               monotonic nanosecond clock
     */
    public TrackCpuBudgetEnforcer(double sampleRate,
                                  int bufferSize,
                                  double masterMaxFractionOfBlock,
                                  LongSupplier clockNanos) {
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
        this.blockBudgetNanos = (long) ((bufferSize / sampleRate) * 1_000_000_000.0);
        this.clockNanos = Objects.requireNonNull(clockNanos, "clockNanos must not be null");
        this.masterMaxFractionOfBlock = masterMaxFractionOfBlock;
        this.publisher = new SubmissionPublisher<>();
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
     * UI code subscribes here so it never polls the audio thread.
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
     * configured {@link DegradationPolicy} is applied and a
     * {@link TrackPerformanceEvent.TrackDegraded} notification is
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
            publisher.offer(ev, null);
        }
        if (restored != null) {
            final TrackPerformanceEvent ev = restored;
            LOG.fine(() -> "Track restored: " + ev);
            publisher.offer(ev, null);
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
     * rolling CPU fractions exceeds the master ceiling, tracks are
     * shed in descending-CPU order until the total drops below the
     * ceiling. Returns the shed order (highest CPU first). Tracks
     * that are already degraded retain their state; newly shed tracks
     * have their configured policy applied and a
     * {@link TrackPerformanceEvent.TrackDegraded} event emitted (even
     * if their per-track budget had not yet tripped) so the user can
     * see which tracks the cascade affected.
     *
     * <p>This method is typically invoked once per block after all
     * {@link #recordTrackCpu(String, long)} calls for that block have
     * completed.</p>
     *
     * @return the list of track ids shed by this master-budget
     *         evaluation, in the order they were shed (highest CPU
     *         first); empty if the master budget was not exceeded
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
            publisher.offer(publishScratch.get(i), null);
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

    @Override
    public void close() {
        publisher.close();
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
