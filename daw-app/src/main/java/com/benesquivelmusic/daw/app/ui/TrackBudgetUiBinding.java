package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

/**
 * Glue layer between the realtime
 * {@link com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer
 * TrackCpuBudgetEnforcer} and the JavaFX UI — story 129 follow-up.
 *
 * <p>Subscribes to a {@link Flow.Publisher} of
 * {@link TrackPerformanceEvent}s and:</p>
 *
 * <ul>
 *   <li>Tracks the set of currently degraded track ids so the mixer
 *       view can paint a small "⚠" badge on the offending strips.</li>
 *   <li>Throttles user-visible warnings to one per track per
 *       {@link #NOTIFICATION_THROTTLE_NANOS}, so a cascade event over a
 *       hundred blocks does not produce a hundred toast notifications.</li>
 *   <li>Hands all UI-thread work to an injected {@link Executor} (the
 *       production wiring uses {@code Platform::runLater}; tests use a
 *       direct executor so the binding can be exercised headless).</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>The binding is invoked from the realtime publisher thread; all
 * shared state is held in concurrent collections. The optional UI
 * refresh callback is dispatched onto the configured {@link Executor}
 * so JavaFX node mutations always happen on the application thread
 * even though the publisher runs on a {@link java.util.concurrent.SubmissionPublisher}
 * worker.</p>
 */
public final class TrackBudgetUiBinding implements Flow.Subscriber<TrackPerformanceEvent>, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TrackBudgetUiBinding.class.getName());

    /**
     * Minimum interval between two user-visible "track degraded"
     * notifications for the same track. Matches the issue's
     * "throttled to one per track per 30 s" requirement.
     */
    public static final long NOTIFICATION_THROTTLE_NANOS = 30_000_000_000L;

    private final NotificationManager notifications;
    private final UnaryOperator<String> trackNameLookup;
    private final Consumer<Set<String>> onDegradedSetChanged;
    private final Executor uiExecutor;
    private final LongSupplier clockNanos;

    /** Tracks currently degraded ids; concurrent so the realtime callback can read consistently. */
    private final Set<String> degradedTrackIds = ConcurrentHashMap.newKeySet();
    /** Last notification timestamp per track (for the 30 s throttle). */
    private final Map<String, Long> lastNotificationNanos = new ConcurrentHashMap<>();
    /** Subscription handle; cancelled on {@link #close()}. */
    private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

    /**
     * Creates a binding with production defaults (no notifications, no
     * UI callback, system clock, direct executor). Useful for tests
     * that only assert the degraded set transitions.
     *
     * @param notifications the notification sink (must not be {@code null})
     */
    public TrackBudgetUiBinding(NotificationManager notifications) {
        this(notifications,
                id -> id,
                _ -> { },
                Runnable::run,
                System::nanoTime);
    }

    /**
     * Creates a fully wired binding.
     *
     * @param notifications        sink for user-visible warnings (not {@code null})
     * @param trackNameLookup      converts a track id to its display
     *                             name; must return non-{@code null}
     *                             (typically falls back to the id itself
     *                             for missing entries)
     * @param onDegradedSetChanged callback invoked on the configured
     *                             {@link Executor} whenever the
     *                             degraded-track set changes; receives
     *                             an unmodifiable snapshot of the set
     * @param uiExecutor           executor used to deliver
     *                             {@code onDegradedSetChanged} (typically
     *                             {@code Platform::runLater}; tests use
     *                             {@code Runnable::run}); must not be
     *                             {@code null}
     * @param clockNanos           monotonic clock used for the
     *                             notification throttle; injectable so
     *                             tests can advance time without
     *                             {@link Thread#sleep(long)} (not {@code null})
     */
    public TrackBudgetUiBinding(NotificationManager notifications,
                                UnaryOperator<String> trackNameLookup,
                                Consumer<Set<String>> onDegradedSetChanged,
                                Executor uiExecutor,
                                LongSupplier clockNanos) {
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.trackNameLookup = Objects.requireNonNull(trackNameLookup, "trackNameLookup must not be null");
        this.onDegradedSetChanged = Objects.requireNonNull(onDegradedSetChanged,
                "onDegradedSetChanged must not be null");
        this.uiExecutor = Objects.requireNonNull(uiExecutor, "uiExecutor must not be null");
        this.clockNanos = Objects.requireNonNull(clockNanos, "clockNanos must not be null");
    }

    /** Returns whether the given track id is currently flagged as degraded. */
    public boolean isDegraded(String trackId) {
        return degradedTrackIds.contains(trackId);
    }

    /** Returns an unmodifiable snapshot of the current degraded set. */
    public Set<String> degradedTrackIds() {
        return Collections.unmodifiableSet(new HashSet<>(degradedTrackIds));
    }

    /** Clears the binding's per-track state — used on project change / engine reset. */
    public void reset() {
        boolean changed = !degradedTrackIds.isEmpty();
        degradedTrackIds.clear();
        lastNotificationNanos.clear();
        if (changed) {
            dispatchDegradedSet();
        }
    }

    // ── Flow.Subscriber ─────────────────────────────────────────────────────

    @Override
    public void onSubscribe(Flow.Subscription s) {
        Flow.Subscription previous = subscription.getAndSet(s);
        if (previous != null) {
            previous.cancel();
        }
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(TrackPerformanceEvent event) {
        switch (event) {
            case TrackPerformanceEvent.TrackDegraded d -> handleDegraded(d);
            case TrackPerformanceEvent.TrackRestored r -> handleRestored(r);
        }
    }

    @Override
    public void onError(Throwable t) {
        LOG.warning(() -> "TrackBudgetUiBinding subscription error: " + t);
    }

    @Override
    public void onComplete() {
        // Publisher closed; nothing to do.
    }

    @Override
    public void close() {
        Flow.Subscription s = subscription.getAndSet(null);
        if (s != null) {
            s.cancel();
        }
    }

    // ── Internal handlers ───────────────────────────────────────────────────

    private void handleDegraded(TrackPerformanceEvent.TrackDegraded d) {
        boolean newlyDegraded = degradedTrackIds.add(d.trackId());
        long now = clockNanos.getAsLong();
        Long last = lastNotificationNanos.get(d.trackId());
        boolean shouldNotify = last == null || (now - last) >= NOTIFICATION_THROTTLE_NANOS;
        if (shouldNotify) {
            lastNotificationNanos.put(d.trackId(), now);
            String name = safeTrackName(d.trackId());
            notifications.notify(
                    "Track '" + name + "' was reduced — CPU over budget");
        }
        if (newlyDegraded) {
            dispatchDegradedSet();
        }
    }

    private void handleRestored(TrackPerformanceEvent.TrackRestored r) {
        boolean changed = degradedTrackIds.remove(r.trackId());
        if (changed) {
            dispatchDegradedSet();
        }
    }

    private String safeTrackName(String trackId) {
        try {
            String name = trackNameLookup.apply(trackId);
            return (name == null || name.isBlank()) ? trackId : name;
        } catch (RuntimeException e) {
            return trackId;
        }
    }

    private void dispatchDegradedSet() {
        Set<String> snapshot = Collections.unmodifiableSet(new HashSet<>(degradedTrackIds));
        try {
            uiExecutor.execute(() -> {
                try {
                    onDegradedSetChanged.accept(snapshot);
                } catch (RuntimeException e) {
                    LOG.warning(() -> "onDegradedSetChanged callback threw: " + e);
                }
            });
        } catch (RuntimeException e) {
            LOG.warning(() -> "uiExecutor rejected dispatch: " + e);
        }
    }

    /**
     * Test-only hook returning a defensive copy of the per-track
     * notification timestamps (nanoseconds, from the injected clock).
     * Production callers should not rely on this view.
     */
    Map<String, Long> notificationTimestampsForTesting() {
        return new HashMap<>(lastNotificationNanos);
    }
}
