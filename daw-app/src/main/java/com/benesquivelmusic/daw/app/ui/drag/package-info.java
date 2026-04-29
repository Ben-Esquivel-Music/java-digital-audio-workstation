/**
 * Drag-and-drop visual feedback advisor.
 *
 * <p>This package implements the visual-feedback layer described by user
 * story 197 — "Drag Cursor and Drop-Target Visual Feedback Polish." Every
 * draggable source in the application (clips, plugins, browser samples)
 * consults the same
 * {@link com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisor} to obtain:
 *
 * <ul>
 *   <li>A {@link com.benesquivelmusic.daw.app.ui.drag.GhostPreview} — a
 *       semi-transparent preview of what is being dragged.</li>
 *   <li>A {@link com.benesquivelmusic.daw.app.ui.drag.DropTargetHighlight}
 *       describing whether the current target is valid, and which tint
 *       to apply.</li>
 *   <li>A {@link com.benesquivelmusic.daw.app.ui.drag.DragCursor} — the
 *       cursor variant indicating duplicate / link / disabled-snap /
 *       no-drop semantics.</li>
 *   <li>A {@link com.benesquivelmusic.daw.app.ui.drag.SnapIndicator}
 *       describing the vertical snap guide line and current snap label.</li>
 * </ul>
 *
 * <p>The model is deliberately UI-toolkit-agnostic. None of the types in
 * this package depend on JavaFX, so all behaviour can be exercised by
 * fast unit tests in a headless environment. The JavaFX presenter is
 * expected to translate {@link com.benesquivelmusic.daw.app.ui.drag.DragVisualState}
 * into Scene-graph nodes (Image, Rectangle, Line, Cursor) using the
 * timings from
 * {@link com.benesquivelmusic.daw.app.ui.drag.AnimationProfile}.
 */
package com.benesquivelmusic.daw.app.ui.drag;
