/**
 * The {@link com.benesquivelmusic.daw.sdk.store.ProjectStore ProjectStore}
 * is the single source of truth for the immutable
 * {@link com.benesquivelmusic.daw.sdk.model.Project Project} snapshot.
 *
 * <p>Write operations are expressed as
 * {@link com.benesquivelmusic.daw.sdk.store.CompoundAction CompoundAction}
 * reducers ({@code Project -> Project}); the store applies them atomically
 * and publishes the resulting
 * {@link com.benesquivelmusic.daw.sdk.event.ProjectChange ProjectChange}
 * events through {@link java.util.concurrent.Flow.Publisher Flow.Publisher}.</p>
 *
 * <p>{@link com.benesquivelmusic.daw.sdk.store.UndoManager UndoManager}
 * captures successive {@code (before, after)} snapshots so undo / redo
 * collapse to swapping references — equality is structural, so the
 * operations are guaranteed correct without bespoke inverse-action logic.</p>
 */
package com.benesquivelmusic.daw.sdk.store;
