/**
 * Top-level application <em>views</em> that are entered as a distinct mode
 * rather than occupying the standard centre content area.
 *
 * <p>The first member is
 * {@link com.benesquivelmusic.daw.app.ui.views.PerformanceStageView} — the
 * Performance Stage cockpit from user story 280 (UI Design Book §4 Concept
 * E). Unlike the four standard {@code DawView}s (Arrangement / Mixer /
 * Editor / Mastering), which {@code ViewNavigationController} swaps into the
 * centre of the main {@code BorderPane}, a view in this package replaces the
 * whole standard chrome and is activated / deactivated through a dedicated
 * path.</p>
 *
 * <p>The package also holds the typed events such views fire —
 * {@link com.benesquivelmusic.daw.app.ui.views.CueLaunchRequestedEvent} —
 * following the {@code javafx.event.Event} subclass convention (skill §12).</p>
 *
 * <p>This package is internal to {@code daw.app}: it is neither exported nor
 * opened. Nothing in it is reflected over by FXML — {@code PerformanceStageView}
 * is instantiated directly from Java — so {@code module-info.java} needs no
 * entry for it.</p>
 */
package com.benesquivelmusic.daw.app.ui.views;
