/**
 * Immutable, record-based domain models for a DAW project.
 *
 * <p>This package is the modern, value-oriented replacement for the mutable
 * POJOs historically used in {@code com.benesquivelmusic.daw.core}. Every
 * type here is a Java {@code record}: shallowly immutable, with structural
 * {@code equals}/{@code hashCode}, and an explicit {@code withX(...)} method
 * for each field so callers can produce updated copies ergonomically.</p>
 *
 * <p>The {@link com.benesquivelmusic.daw.sdk.model.Project Project} record
 * aggregates the full session state via {@link java.util.UUID}-keyed maps
 * (defensively copied with {@link java.util.Map#copyOf(java.util.Map)}). The
 * {@link com.benesquivelmusic.daw.sdk.store.ProjectStore ProjectStore}
 * publishes
 * {@link com.benesquivelmusic.daw.sdk.event.ProjectChange ProjectChange}
 * events through {@link java.util.concurrent.Flow.Publisher Flow.Publisher}
 * whenever a {@link com.benesquivelmusic.daw.sdk.store.CompoundAction
 * CompoundAction} reduces over the current snapshot.</p>
 *
 * <p><b>Migration:</b> the legacy mutable classes in
 * {@code com.benesquivelmusic.daw.core.track},
 * {@code com.benesquivelmusic.daw.core.mixer},
 * {@code com.benesquivelmusic.daw.core.audio} and
 * {@code com.benesquivelmusic.daw.core.midi} remain in place as a deprecated
 * facade so existing code compiles; call sites will be migrated to the
 * immutable types in subsequent stories.</p>
 */
package com.benesquivelmusic.daw.sdk.model;
