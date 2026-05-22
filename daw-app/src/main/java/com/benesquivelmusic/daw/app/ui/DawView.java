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
    PERFORMANCE_STAGE
}
