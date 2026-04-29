package com.benesquivelmusic.daw.app.ui.drag;

/**
 * The rendering style of a {@link GhostPreview} — a hint for the
 * presentation layer telling it how to draw the semi-transparent
 * preview image that follows the cursor during a drag.
 *
 * <p>The hint is intentionally coarse-grained so the model layer can
 * remain free of JavaFX types. The presenter selects an appropriate
 * Scene-graph rendering for each style.</p>
 */
public enum GhostStyle {
    /** Outline of the clip with its waveform faded over a tinted backdrop. */
    WAVEFORM_OUTLINE,
    /** Compact card showing the plugin name and icon. */
    PLUGIN_CARD,
    /** Miniature waveform preview rendered from the browser sample. */
    WAVEFORM_MINI
}
