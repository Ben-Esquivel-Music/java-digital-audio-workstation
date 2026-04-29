/**
 * Listener and event types for the DAW SDK.
 *
 * <p>The package contains three related families of types:</p>
 *
 * <ul>
 *   <li>{@link com.benesquivelmusic.daw.sdk.event.DawEvent DawEvent}
 *       — sealed root of the domain-event hierarchy. Every observable
 *       change in the DAW (transport, mixer, track, clip, project,
 *       automation, plugin, real-time audio xrun) is published as a
 *       {@code DawEvent}. Consumers exhaustively pattern-match on the
 *       sealed sub-hierarchies, so adding a new event variant breaks
 *       every consumer at compile time.</li>
 *   <li>{@link com.benesquivelmusic.daw.sdk.event.ProjectChange ProjectChange}
 *       — a sealed hierarchy of records emitted by
 *       {@link com.benesquivelmusic.daw.sdk.store.ProjectStore ProjectStore}
 *       whenever a
 *       {@link com.benesquivelmusic.daw.sdk.store.CompoundAction CompoundAction}
 *       reduces over the immutable project snapshot. Each event carries the
 *       previous and next value of a single entity, so subscribers can
 *       perform precise incremental updates without diffing the entire
 *       {@code Project}.</li>
 *   <li>Legacy lifecycle listeners
 *       ({@link com.benesquivelmusic.daw.sdk.event.AutoSaveListener
 *       AutoSaveListener},
 *       {@link com.benesquivelmusic.daw.sdk.event.RecordingListener
 *       RecordingListener}) used by the legacy mutable engine. These
 *       are retained for backwards compatibility and bridged from the
 *       new {@code DawEvent} stream by
 *       {@link com.benesquivelmusic.daw.sdk.event.LegacyListenerAdapter
 *       LegacyListenerAdapter}; new code should subscribe to
 *       {@code DawEvent} directly.</li>
 * </ul>
 */
package com.benesquivelmusic.daw.sdk.event;
