package com.benesquivelmusic.daw.sdk.ui;

/**
 * Immutable 2D rectangle described by its top-left corner and dimensions.
 *
 * <p>This record is defined here (rather than reusing {@code javafx.geometry.Rectangle2D}
 * or {@code java.awt.geom.Rectangle2D}) so the SDK module can stay free of JavaFX/AWT
 * dependencies while still describing UI panel bounds for features such as
 * {@link Workspace}.</p>
 *
 * @param x      left coordinate (pixels)
 * @param y      top coordinate (pixels)
 * @param width  rectangle width (pixels, must be {@code >= 0})
 * @param height rectangle height (pixels, must be {@code >= 0})
 */
public record Rectangle2D(double x, double y, double width, double height) {

    /**
     * Validates that {@code width} and {@code height} are non-negative finite numbers.
     */
    public Rectangle2D {
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(width) || !Double.isFinite(height)) {
            throw new IllegalArgumentException("Rectangle2D coordinates must be finite numbers");
        }
        if (width < 0) {
            throw new IllegalArgumentException("width must be >= 0, got " + width);
        }
        if (height < 0) {
            throw new IllegalArgumentException("height must be >= 0, got " + height);
        }
    }
}
