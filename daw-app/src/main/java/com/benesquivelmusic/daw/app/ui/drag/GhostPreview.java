package com.benesquivelmusic.daw.app.ui.drag;

import java.util.Objects;

/**
 * Description of a semi-transparent ghost preview that follows the cursor
 * during a drag.
 *
 * <p>This is a pure data carrier — the presenter (which has access to
 * JavaFX) is responsible for translating it into a Scene-graph node:
 * a {@link javafx.scene.image.ImageView}, {@link javafx.scene.shape.Rectangle}
 * with a clip-waveform overlay, or a small VBox with the plugin name and
 * icon.</p>
 *
 * @param sourceKind kind of object being dragged
 * @param style      rendering style hint
 * @param label      short text label shown in or beside the ghost (clip
 *                   name, plugin name, file name)
 * @param width      preferred width in pixels
 * @param height     preferred height in pixels
 * @param opacity    alpha value in the range {@code [0.0, 1.0]}
 */
public record GhostPreview(
        DragSourceKind sourceKind,
        GhostStyle style,
        String label,
        double width,
        double height,
        double opacity) {

    public GhostPreview {
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(label, "label");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "width and height must be positive");
        }
        if (!(opacity >= 0.0 && opacity <= 1.0)) {
            throw new IllegalArgumentException(
                    "opacity must be in [0,1]: " + opacity);
        }
    }
}
