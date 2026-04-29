package com.benesquivelmusic.daw.sdk.event;

import com.benesquivelmusic.daw.sdk.audio.XrunEvent;

import java.time.Instant;

/**
 * Sealed root of the DAW domain-event hierarchy.
 *
 * <p>Every observable change in the DAW &mdash; transport state, mixer
 * channel updates, track edits, clip mutations, project lifecycle events,
 * automation edits, plugin lifecycle events, and real-time audio anomalies
 * &mdash; is published as a {@code DawEvent}. The hierarchy is closed:
 * each permitted sub-interface is itself sealed and exhaustively
 * destructured by a Java&nbsp;21+ exhaustive {@code switch}, so adding a new
 * event variant forces every consumer to be updated at compile time.</p>
 *
 * <p>Records carry the <strong>minimal identifying data</strong> needed to
 * locate the affected entity (typically a {@link java.util.UUID UUID} and
 * a timestamp). Consumers read the post-change state from the current
 * {@link com.benesquivelmusic.daw.sdk.model.Project Project} snapshot
 * exposed by {@link com.benesquivelmusic.daw.sdk.store.ProjectStore
 * ProjectStore}; the event itself is a notification, not a state-bearing
 * delta.</p>
 *
 * <h2>Idiomatic consumer pattern (Java 21+, JEP 441)</h2>
 *
 * <pre>{@code
 * void on(DawEvent event) {
 *     switch (event) {
 *         case TransportEvent t -> handleTransport(t);
 *         case MixerEvent m     -> handleMixer(m);
 *         case TrackEvent t     -> handleTrack(t);
 *         case ClipEvent c      -> handleClip(c);
 *         case ProjectEvent p   -> handleProject(p);
 *         case AutomationEvent a-> handleAutomation(a);
 *         case PluginEvent p    -> handlePlugin(p);
 *         case XrunEvent x      -> handleXrun(x);
 *     }
 * }
 * }</pre>
 *
 * <p>Removing or adding any permitted variant breaks every exhaustive
 * {@code switch} at compile time, surfacing the omission immediately
 * rather than at runtime &mdash; this is the core safety guarantee of
 * sealed types combined with pattern matching.</p>
 *
 * <h2>Migration from legacy listeners</h2>
 *
 * <p>Legacy listener interfaces in this package
 * ({@link RecordingListener}, {@link AutoSaveListener}) are retained for
 * backwards compatibility. New code should subscribe to a
 * {@link java.util.concurrent.Flow.Publisher Flow.Publisher&lt;DawEvent&gt;}
 * exposed by the engine. The {@link LegacyListenerAdapter} bridges the
 * new event stream to the old listener callbacks until every call site
 * has migrated.</p>
 *
 * @see TransportEvent
 * @see MixerEvent
 * @see TrackEvent
 * @see ClipEvent
 * @see ProjectEvent
 * @see AutomationEvent
 * @see PluginEvent
 * @see com.benesquivelmusic.daw.sdk.audio.XrunEvent
 */
public sealed interface DawEvent
        permits TransportEvent,
                MixerEvent,
                TrackEvent,
                ClipEvent,
                ProjectEvent,
                AutomationEvent,
                PluginEvent,
                XrunEvent {

    /**
     * Returns the wall-clock instant at which this event was produced.
     *
     * <p>Implementations that are not tied to a wall-clock instant
     * &mdash; such as {@link XrunEvent}, which is identified by a
     * sample-frame index &mdash; return {@link Instant#EPOCH}. Consumers
     * that need a frame index should pattern-match on the concrete
     * variant rather than rely on this method.</p>
     */
    default Instant timestamp() {
        return Instant.EPOCH;
    }
}
