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

    /** Sound wave telemetry — top-down room visualizer. */
    TELEMETRY,

    /** Mastering chain view — signal chain with presets and A/B comparison. */
    MASTERING
}
