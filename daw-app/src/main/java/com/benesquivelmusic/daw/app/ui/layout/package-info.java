/**
 * Mission Control layout layer (UI Design Book §4 Concept D, story 282).
 *
 * <p>Builds on the dock framework shipped by
 * {@code com.benesquivelmusic.daw.app.ui.dock} and adds the
 * <em>named-layout</em> abstraction Concept D requires:</p>
 *
 * <ul>
 *   <li>{@link com.benesquivelmusic.daw.app.ui.layout.NamedLayout} — an
 *       immutable record describing a saved dock arrangement (which panels
 *       are docked where, which are floating, window positions, split
 *       ratios — all carried as an opaque dock-layout JSON blob).</li>
 *   <li>{@link com.benesquivelmusic.daw.app.ui.layout.BuiltInLayouts} — the
 *       five canonical, read-only layouts from §4 ("Default", "Tracking",
 *       "Mixing", "Mastering", "Live") that always exist regardless of the
 *       current project.</li>
 *   <li>{@link com.benesquivelmusic.daw.app.ui.layout.LayoutManager} —
 *       the headless-testable façade with {@code saveCurrent / load /
 *       delete / rename} and an {@code ObservableList<NamedLayout>} for
 *       FX bindings; per-project persistence flows through
 *       {@code toJson()}/{@code fromJson(String)}.</li>
 *   <li>{@link com.benesquivelmusic.daw.app.ui.layout.DockManifestModel} —
 *       the observable list of panels (docked or floating) that the
 *       dock-manifest bar at the bottom of the main window renders.</li>
 *   <li>{@link com.benesquivelmusic.daw.app.ui.layout.PanelDetachRequestedEvent}
 *       and {@link com.benesquivelmusic.daw.app.ui.layout.PanelDockRequestedEvent} —
 *       typed FX events fired by grip-handle drag-detach / drag-redock
 *       interactions so consumers integrate via the standard event
 *       dispatch chain (Skill §12).</li>
 * </ul>
 *
 * <p>This package is intentionally <strong>UI-toolkit-agnostic</strong>
 * everywhere it can be (everything but the {@code Event} subclasses) so
 * unit tests run headlessly without a JavaFX screen.</p>
 */
package com.benesquivelmusic.daw.app.ui.layout;
