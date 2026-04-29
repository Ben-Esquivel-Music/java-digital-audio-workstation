/**
 * Resizable, detachable, dockable panel system.
 *
 * <p>This package implements the dock manager described by the
 * <em>Resizable and Detachable Dockable Panels</em> story. The model is
 * deliberately UI-toolkit-agnostic: pure-logic types ({@link
 * com.benesquivelmusic.daw.app.ui.dock.Dockable},
 * {@link com.benesquivelmusic.daw.app.ui.dock.DockZone},
 * {@link com.benesquivelmusic.daw.app.ui.dock.DockEntry},
 * {@link com.benesquivelmusic.daw.app.ui.dock.DockLayout},
 * {@link com.benesquivelmusic.daw.app.ui.dock.DockLayoutJson},
 * {@link com.benesquivelmusic.daw.app.ui.dock.FloatingWindowStore},
 * and {@link com.benesquivelmusic.daw.app.ui.dock.DockManager}) carry no
 * dependency on JavaFX so they can be exercised by unit tests in a
 * headless environment.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Each top-level panel implements {@link
 *       com.benesquivelmusic.daw.app.ui.dock.Dockable}, which advertises
 *       a stable id, a display name, an icon name, and a preferred dock
 *       zone.</li>
 *   <li>The current arrangement of all dockable panels is captured by an
 *       immutable {@link com.benesquivelmusic.daw.app.ui.dock.DockLayout},
 *       a map of panel-id to {@link
 *       com.benesquivelmusic.daw.app.ui.dock.DockEntry}.</li>
 *   <li>Layouts serialise to a tolerant hand-rolled JSON format via
 *       {@link com.benesquivelmusic.daw.app.ui.dock.DockLayoutJson} so
 *       they can be embedded in a {@code Workspace} or persisted on
 *       disk.</li>
 *   <li>{@link com.benesquivelmusic.daw.app.ui.dock.FloatingWindowStore}
 *       keeps floating-window bounds across application restarts.</li>
 *   <li>{@link com.benesquivelmusic.daw.app.ui.dock.DockManager}
 *       coordinates the live layout, validates floating-window screen
 *       availability (so panels gracefully re-dock when their monitor is
 *       disconnected), and emits change callbacks to the UI host.</li>
 * </ul>
 */
package com.benesquivelmusic.daw.app.ui.dock;
