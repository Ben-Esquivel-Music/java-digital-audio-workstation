package com.benesquivelmusic.daw.app.ui;

import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Monitors the scene width and auto-collapses or expands the sidebar toolbar
 * so the layout adapts to different window sizes.
 *
 * <p>Behaviour:</p>
 * <ul>
 *     <li>When the scene width is at or below the collapse threshold (default
 *         {@value #DEFAULT_COLLAPSE_THRESHOLD} px) the toolbar is collapsed
 *         to icon-only mode.</li>
 *     <li>When the scene width exceeds the collapse threshold the toolbar is
 *         expanded to show icons and labels.</li>
 * </ul>
 *
 * <p>The listener is installed once via {@link #attach(Scene)} and
 * automatically fires on every subsequent resize.</p>
 */
public final class ResponsiveToolbarController {

    private static final Logger LOG =
            Logger.getLogger(ResponsiveToolbarController.class.getName());

    /**
     * Default width threshold (in pixels) below which the toolbar is
     * auto-collapsed.  Matches the boundary between the minimum window
     * size (1280 px) and a "comfortable" width (&gt;1600 px).
     */
    static final double DEFAULT_COLLAPSE_THRESHOLD = 1600.0;

    private final ToolbarCollapseController collapseController;
    private final double collapseThreshold;
    private ChangeListener<Number> widthListener;

    /**
     * Creates a responsive toolbar controller with the default collapse
     * threshold ({@value #DEFAULT_COLLAPSE_THRESHOLD} px).
     *
     * @param collapseController the collapse controller to delegate to
     *                           (must not be {@code null})
     */
    public ResponsiveToolbarController(ToolbarCollapseController collapseController) {
        this(collapseController, DEFAULT_COLLAPSE_THRESHOLD);
    }

    /**
     * Creates a responsive toolbar controller with a custom collapse threshold.
     *
     * @param collapseController the collapse controller to delegate to
     *                           (must not be {@code null})
     * @param collapseThreshold  the width threshold in pixels; the toolbar is
     *                           collapsed when the scene width is at or below
     *                           this value (must be positive)
     */
    public ResponsiveToolbarController(ToolbarCollapseController collapseController,
                                       double collapseThreshold) {
        this.collapseController = Objects.requireNonNull(collapseController,
                "collapseController must not be null");
        if (collapseThreshold <= 0) {
            throw new IllegalArgumentException(
                    "collapseThreshold must be positive, was: " + collapseThreshold);
        }
        this.collapseThreshold = collapseThreshold;
    }

    /**
     * Attaches a width listener to the given scene.  The listener fires
     * immediately with the scene's current width and on every subsequent
     * resize.
     *
     * @param scene the scene to monitor (must not be {@code null})
     */
    public void attach(Scene scene) {
        Objects.requireNonNull(scene, "scene must not be null");
        widthListener = (observable, oldWidth, newWidth) ->
                onWidthChanged(newWidth.doubleValue());
        scene.widthProperty().addListener(widthListener);
        onWidthChanged(scene.getWidth());
        LOG.fine(() -> "Responsive toolbar attached — threshold=" + collapseThreshold
                + ", current width=" + scene.getWidth());
    }

    /**
     * Detaches the width listener from the given scene.  Safe to call
     * even if no listener is currently attached.
     *
     * @param scene the scene to stop monitoring (must not be {@code null})
     */
    public void detach(Scene scene) {
        Objects.requireNonNull(scene, "scene must not be null");
        if (widthListener != null) {
            scene.widthProperty().removeListener(widthListener);
            widthListener = null;
            LOG.fine("Responsive toolbar detached");
        }
    }

    /**
     * Returns the collapse threshold in pixels.
     *
     * @return the threshold below which the toolbar is auto-collapsed
     */
    public double getCollapseThreshold() {
        return collapseThreshold;
    }

    /**
     * Evaluates the current width and collapses or expands the toolbar.
     *
     * @param width the current scene width in pixels
     */
    private void onWidthChanged(double width) {
        boolean shouldCollapse = width <= collapseThreshold;
        collapseController.setCollapsed(shouldCollapse);
    }
}
