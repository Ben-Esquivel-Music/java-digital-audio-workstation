/**
 * Listener and change-event types for the DAW SDK.
 *
 * <p>The package contains two related families of types:</p>
 *
 * <ul>
 *   <li>Lifecycle listeners ({@code AutoSaveListener},
 *       {@code RecordingListener}) used by the legacy mutable engine.</li>
 *   <li>{@link com.benesquivelmusic.daw.sdk.event.ProjectChange ProjectChange}
 *       — a sealed hierarchy of records emitted by
 *       {@link com.benesquivelmusic.daw.sdk.store.ProjectStore ProjectStore}
 *       whenever a
 *       {@link com.benesquivelmusic.daw.sdk.store.CompoundAction CompoundAction}
 *       reduces over the immutable project snapshot. Each event carries the
 *       previous and next value of a single entity, so subscribers can
 *       perform precise incremental updates without diffing the entire
 *       {@code Project}.</li>
 * </ul>
 */
package com.benesquivelmusic.daw.sdk.event;
