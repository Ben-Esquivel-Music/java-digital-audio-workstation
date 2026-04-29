package com.benesquivelmusic.daw.app.ui.drag;

/**
 * The kinds of draggable sources the application surfaces.
 *
 * <p>Each source kind has a default ghost-preview style — clips render
 * as a waveform-ghosted outline, plugins as a compact card with name
 * and icon, samples as a mini-waveform preview from the browser. This
 * mapping is encoded once here so the visual layer stays consistent.</p>
 */
public enum DragSourceKind {
    /** A clip dragged from the arrangement view. */
    CLIP(GhostStyle.WAVEFORM_OUTLINE),
    /** A plugin dragged from the plugin browser or another insert slot. */
    PLUGIN(GhostStyle.PLUGIN_CARD),
    /** A sample dragged from the file browser. */
    SAMPLE(GhostStyle.WAVEFORM_MINI);

    private final GhostStyle defaultGhostStyle;

    DragSourceKind(GhostStyle defaultGhostStyle) {
        this.defaultGhostStyle = defaultGhostStyle;
    }

    /** Returns the ghost-preview rendering style for this source kind. */
    public GhostStyle defaultGhostStyle() {
        return defaultGhostStyle;
    }
}
