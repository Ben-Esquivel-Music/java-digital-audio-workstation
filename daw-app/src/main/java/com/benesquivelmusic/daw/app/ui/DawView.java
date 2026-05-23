package com.benesquivelmusic.daw.app.ui;

/**
 * Identifies the primary content views available in the DAW application.
 *
 * <p>At any time exactly one view is active and occupies the center content
 * area of the main {@link javafx.scene.layout.BorderPane}.</p>
 */
public enum DawView {

    /** Timeline-based arrangement view — the default on startup. */
    ARRANGEMENT,

    /** Channel-strip mixer view. */
    MIXER,

    /** Audio/MIDI clip editor view. */
    EDITOR,

    /** Mastering chain view — signal chain with presets and A/B comparison. */
    MASTERING,

    /**
     * Performance Stage view — an oversized-control "cockpit" for live
     * use (story 280, UI Design Book §4 Concept E). Unlike the four
     * standard views above it does not merely occupy the centre content
     * area: it replaces the whole standard chrome (toolbar, track list,
     * inspector). {@code ViewNavigationController#switchView} handles this
     * value by toggling activate/deactivate rather than performing a
     * simple centre-content swap.
     */
    PERFORMANCE_STAGE,

    /**
     * Workshop view — sound-design layout pairing a 60&nbsp;% arrangement
     * pane with a 40&nbsp;% focused-plugin pane plus clip detail below
     * (story 281, UI Design Book §4 Concept F). Unlike Performance Stage,
     * Workshop is a standard centre-content view: it occupies the same
     * slot as Arrangement / Mixer / Editor / Mastering and reuses the
     * existing arrangement panel and audio/MIDI editor verbatim inside its
     * own {@link javafx.scene.control.SplitPane} layout.
     */
    WORKSHOP
}
