package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.Objects;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Controls the sidebar toolbar collapse/expand behavior.
 *
 * <p>The sidebar can be in one of two states:</p>
 * <ul>
 *     <li><strong>Collapsed</strong> (~56px) — icons only, no text labels,
 *         section headers hidden</li>
 *     <li><strong>Expanded</strong> (~200px) — icons with text labels
 *         and section headers visible</li>
 * </ul>
 *
 * <p>The transition between states is animated (150ms width change).
 * The collapsed/expanded state is persisted via {@link Preferences}
 * so it survives application restarts.</p>
 */
public final class ToolbarCollapseController {

    private static final Logger LOG = Logger.getLogger(ToolbarCollapseController.class.getName());

    /** Width of the sidebar in collapsed mode (icons only). */
    static final double COLLAPSED_WIDTH = 56;
    /** Width of the sidebar in expanded mode (icons + labels). */
    static final double EXPANDED_WIDTH = 200;
    /** Width of sidebar buttons in collapsed mode. */
    static final double COLLAPSED_BUTTON_WIDTH = 48;
    /** Width of sidebar buttons in expanded mode. */
    static final double EXPANDED_BUTTON_WIDTH = 190;
    /** Duration of the width transition animation. */
    static final Duration ANIMATION_DURATION = Duration.millis(150);
    /** Preferences key for persisted collapsed state. */
    static final String PREF_KEY_TOOLBAR_COLLAPSED = "toolbar.collapsed";
    /** Icon size for the expand/collapse toggle button. */
    private static final double ICON_SIZE = 16;

    private final VBox sidebarToolbar;
    private final Button expandCollapseButton;
    private final Preferences prefs;
    private boolean collapsed;

    /**
     * Creates a new toolbar collapse controller.
     *
     * @param sidebarToolbar       the sidebar {@link VBox} to resize
     * @param expandCollapseButton the toggle button for collapse/expand
     * @param prefs                the preferences node for state persistence
     */
    public ToolbarCollapseController(VBox sidebarToolbar,
                                     Button expandCollapseButton,
                                     Preferences prefs) {
        this.sidebarToolbar = Objects.requireNonNull(sidebarToolbar,
                "sidebarToolbar must not be null");
        this.expandCollapseButton = Objects.requireNonNull(expandCollapseButton,
                "expandCollapseButton must not be null");
        this.prefs = Objects.requireNonNull(prefs,
                "prefs must not be null");
    }

    /**
     * Initializes the controller: loads persisted state, wires the button,
     * and applies the initial collapsed/expanded layout without animation.
     */
    public void initialize() {
        collapsed = prefs.getBoolean(PREF_KEY_TOOLBAR_COLLAPSED, false);
        expandCollapseButton.setOnAction(event -> toggle());
        applyState(false);
        LOG.fine(() -> "Toolbar collapse controller initialized — "
                + (collapsed ? "collapsed" : "expanded"));
    }

    /**
     * Toggles between collapsed and expanded states with animation.
     */
    public void toggle() {
        collapsed = !collapsed;
        prefs.putBoolean(PREF_KEY_TOOLBAR_COLLAPSED, collapsed);
        applyState(true);
        LOG.fine(() -> collapsed ? "Toolbar collapsed" : "Toolbar expanded");
    }

    /**
     * Returns whether the sidebar is currently collapsed.
     *
     * @return {@code true} if collapsed (icon-only mode)
     */
    public boolean isCollapsed() {
        return collapsed;
    }

    /**
     * Applies the current collapsed/expanded state to the sidebar.
     *
     * @param animate {@code true} to animate the width transition
     */
    private void applyState(boolean animate) {
        double targetWidth = collapsed ? COLLAPSED_WIDTH : EXPANDED_WIDTH;

        // Update child visibility before animation starts for collapse,
        // or after animation completes for expand
        if (collapsed) {
            updateChildVisibility();
        }

        if (animate) {
            Timeline timeline = new Timeline(
                    new KeyFrame(ANIMATION_DURATION,
                            new KeyValue(sidebarToolbar.prefWidthProperty(), targetWidth),
                            new KeyValue(sidebarToolbar.minWidthProperty(), targetWidth),
                            new KeyValue(sidebarToolbar.maxWidthProperty(), targetWidth))
            );
            if (!collapsed) {
                timeline.setOnFinished(event -> updateChildVisibility());
            }
            timeline.play();
        } else {
            sidebarToolbar.setPrefWidth(targetWidth);
            sidebarToolbar.setMinWidth(targetWidth);
            sidebarToolbar.setMaxWidth(targetWidth);
            if (!collapsed) {
                updateChildVisibility();
            }
        }

        updateExpandCollapseButton();
    }

    /**
     * Updates sidebar children based on the current collapsed/expanded state.
     * In collapsed mode, buttons show only their graphic; section labels are hidden.
     * In expanded mode, buttons show icon + text; section labels are visible.
     */
    private void updateChildVisibility() {
        for (Node child : sidebarToolbar.getChildren()) {
            if (child instanceof Button button) {
                if (collapsed) {
                    button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                    button.setPrefWidth(COLLAPSED_BUTTON_WIDTH);
                    button.setMinWidth(COLLAPSED_BUTTON_WIDTH);
                    button.setMaxWidth(COLLAPSED_BUTTON_WIDTH);
                } else {
                    button.setContentDisplay(ContentDisplay.LEFT);
                    button.setPrefWidth(EXPANDED_BUTTON_WIDTH);
                    button.setMinWidth(EXPANDED_BUTTON_WIDTH);
                    button.setMaxWidth(EXPANDED_BUTTON_WIDTH);
                }
            } else if (child instanceof Label label
                    && label.getStyleClass().contains("toolbar-section-label")) {
                label.setVisible(!collapsed);
                label.setManaged(!collapsed);
            }
        }
    }

    /**
     * Updates the expand/collapse button icon and text to reflect the current state.
     * When collapsed, shows the EXPAND icon (action = expand).
     * When expanded, shows the COLLAPSE icon (action = collapse).
     */
    private void updateExpandCollapseButton() {
        if (collapsed) {
            expandCollapseButton.setGraphic(IconNode.of(DawIcon.EXPAND, ICON_SIZE));
            expandCollapseButton.setText("Expand");
        } else {
            expandCollapseButton.setGraphic(IconNode.of(DawIcon.COLLAPSE, ICON_SIZE));
            expandCollapseButton.setText("Collapse");
        }
    }
}
