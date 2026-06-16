package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.sdk.audio.XrunEvent;
import com.benesquivelmusic.daw.sdk.event.AutomationEvent;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;
import com.benesquivelmusic.daw.sdk.event.DawEvent;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import com.benesquivelmusic.daw.sdk.event.PluginEvent;
import com.benesquivelmusic.daw.sdk.event.ProjectEvent;
import com.benesquivelmusic.daw.sdk.event.TrackEvent;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;

import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 298 — bridges the typed {@link EventBus} to the {@link JournalWriter}
 * so every domain mutation is captured in the write-ahead journal.
 *
 * <p>Per the story's guidance — "reuse the story 283 EventBus producers as the
 * change source where they already fire" — this recorder subscribes to the
 * sealed root {@link DawEvent} on the bus and maps each event to a compact
 * {@link JournalRecord}. Because the engine emits the same leaf events for an
 * action's apply, its undo, and its redo (story 283), subscribing here
 * captures <em>apply + undo + redo</em> uniformly without any special-casing.</p>
 *
 * <p>The handler runs on {@link com.benesquivelmusic.daw.sdk.event.DispatchMode#ON_CALLER_THREAD
 * ON_CALLER_THREAD} and forwards to the journal's non-blocking
 * {@link JournalWriter#offer}, so it never blocks the publisher and never
 * throws out of the bus callback.</p>
 */
public final class JournalEventRecorder implements AutoCloseable {

    private final EventBus bus;
    private final JournalWriter writer;
    private final InstantSource clock;

    private EventBus.Subscription subscription;
    private volatile boolean started;
    private volatile boolean closed;

    /**
     * Creates a recorder that will subscribe {@code writer} to {@code bus}.
     *
     * @param bus    the event bus that is the journal's change source
     * @param writer the journal writer to feed
     * @param clock  the instant source used to stamp records (test seam)
     */
    public JournalEventRecorder(EventBus bus, JournalWriter writer, InstantSource clock) {
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Subscribes to the bus. Idempotent; a no-op once {@link #close()} has
     * been called.
     *
     * @return this recorder, for fluent wiring
     */
    public JournalEventRecorder start() {
        if (closed || started) {
            return this;
        }
        started = true;
        this.subscription = bus.on(DawEvent.class, this::onEvent);
        return this;
    }

    /** {@return {@code true} once {@link #start()} has subscribed} */
    public boolean isStarted() {
        return started;
    }

    private void onEvent(DawEvent event) {
        try {
            Instant ts = effectiveTimestamp(event);
            String type = typeTag(event);
            UUID entityId = entityIdOf(event);
            writer.offer(type, entityId, null, ts);
        } catch (RuntimeException ignored) {
            // The change source must never be destabilised by a journal hiccup.
        }
    }

    /**
     * Maps an event to its compact, stable journal type tag — the family name
     * qualified with the leaf record's simple name, e.g.
     * {@code "MixerEvent.MuteChanged"}.
     */
    private static String typeTag(DawEvent event) {
        String family = switch (event) {
            case TransportEvent ignored -> "TransportEvent";
            case MixerEvent ignored -> "MixerEvent";
            case TrackEvent ignored -> "TrackEvent";
            case ClipEvent ignored -> "ClipEvent";
            case ProjectEvent ignored -> "ProjectEvent";
            case AutomationEvent ignored -> "AutomationEvent";
            case PluginEvent ignored -> "PluginEvent";
            case XrunEvent ignored -> "XrunEvent";
        };
        return family + "." + event.getClass().getSimpleName();
    }

    /**
     * Extracts the identifying UUID from an event, or {@code null} when the
     * event family carries no single entity identity (transport, project,
     * x-run). A {@code switch} over the sealed root makes the mapping
     * exhaustive — a new family forces this method to be revisited.
     */
    private static UUID entityIdOf(DawEvent event) {
        return switch (event) {
            case MixerEvent m -> m.channelId();
            case TrackEvent t -> t.trackId();
            case ClipEvent c -> c.clipId();
            case AutomationEvent a -> a.laneId();
            case PluginEvent p -> p.pluginInstanceId();
            case ProjectEvent ignored -> null;     // whole-project lifecycle, no entity
            case TransportEvent ignored -> null;   // transport state, no entity
            case XrunEvent ignored -> null;        // audio anomaly, no entity
        };
    }

    /**
     * The event's own timestamp when it is a real wall-clock instant;
     * otherwise (e.g. {@link XrunEvent}, whose {@code timestamp()} is
     * {@link Instant#EPOCH}) the recorder's clock is substituted so the
     * journal preserves arrival ordering.
     */
    private Instant effectiveTimestamp(DawEvent event) {
        Instant ts = event.timestamp();
        return Instant.EPOCH.equals(ts) ? clock.instant() : ts;
    }

    /**
     * Closes the bus subscription. Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        EventBus.Subscription sub = this.subscription;
        if (sub != null) {
            sub.close();
        }
    }
}
