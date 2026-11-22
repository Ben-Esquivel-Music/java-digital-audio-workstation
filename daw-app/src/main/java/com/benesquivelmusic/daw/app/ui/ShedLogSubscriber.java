package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes one Shed entry to the xrun journal for each track the CPU budget
 * enforcer degraded (story 338, ruling E42, amendment A5): the detail names
 * what the applied policy did, with the event's rolling average as a whole
 * percent of the block time. A degradation by {@code DoNothing} changed
 * nothing and writes nothing; a restore writes nothing. A Shed entry never
 * raises the xrun count. The entry keeps the track's id; the window's
 * {@link EngineHealthFeed} names the track on the FX thread.
 *
 * <p>Subscribed beside the enforcer's UI binding, and cancelled when the
 * enforcer is replaced. Runs on the enforcer publisher's thread, never on
 * the audio thread; the journal is thread-safe.</p>
 */
final class ShedLogSubscriber implements Flow.Subscriber<TrackPerformanceEvent> {

    private static final Logger LOG = Logger.getLogger(ShedLogSubscriber.class.getName());

    /** The terminal state of {@link #subscription}: cancelled; requests and cancels nothing. */
    private static final Flow.Subscription CANCELLED = new Flow.Subscription() {
        @Override
        public void request(long n) {
            // Terminal: nothing to request from.
        }

        @Override
        public void cancel() {
            // Terminal: already cancelled.
        }
    };

    private final XrunJournal journal;
    /** {@code null}, then the live subscription, then {@link #CANCELLED} for good. */
    private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

    /** @param journal the engine controller's xrun journal */
    ShedLogSubscriber(XrunJournal journal) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
    }

    @Override
    public void onSubscribe(Flow.Subscription arrived) {
        Objects.requireNonNull(arrived, "subscription must not be null");
        if (subscription.compareAndSet(null, arrived)) {
            arrived.request(Long.MAX_VALUE);
        } else {
            arrived.cancel();
        }
    }

    @Override
    public void onNext(TrackPerformanceEvent event) {
        if (subscription.get() == CANCELLED
                || !(event instanceof TrackPerformanceEvent.TrackDegraded degraded)) {
            return;
        }
        long percent = Math.round(degraded.measuredFraction() * 100.0);
        switch (degraded.appliedPolicy()) {
            case DegradationPolicy.BypassExpensive _ -> journal.append(
                    XrunJournal.Detail.EXPENSIVE_PROCESSING_BYPASSED, List.of(degraded.trackId(), percent), 1L);
            case DegradationPolicy.ReduceOversampling reduce -> journal.append(XrunJournal.Detail.OVERSAMPLING_REDUCED,
                    List.of(degraded.trackId(), reduce.fallbackFactor(), percent), 1L);
            case DegradationPolicy.SubstituteSimpleKernel _ -> journal.append(
                    XrunJournal.Detail.SIMPLER_PROCESSING_SUBSTITUTED, List.of(degraded.trackId(), percent), 1L);
            case DegradationPolicy.DoNothing _ -> {
                // Nothing was shed.
            }
        }
    }

    @Override
    public void onError(Throwable throwable) {
        LOG.log(Level.WARNING, "The CPU budget enforcer's events ended with an error; no more Shed entries", throwable);
    }

    @Override
    public void onComplete() {
        // The enforcer was closed: its replacement gets a subscriber of its own.
    }

    /**
     * Ends the subscription: cancels the one held, or makes one that arrives
     * later cancel itself. Any thread; idempotent.
     */
    void cancel() {
        Flow.Subscription prior = subscription.getAndSet(CANCELLED);
        if (prior != null && prior != CANCELLED) {
            prior.cancel();
        }
    }
}
