package com.benesquivelmusic.daw.app.ui.help;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.Objects;

/**
 * Persistent strip rendered at the bottom of the main window that shows a
 * one-line description of the control under the mouse cursor (or, when
 * idle, of the keyboard-focused control).
 *
 * <p>The bar is hidden by default; toggle it with {@code Shift+F1} via
 * {@link HelpKeyHandler}, or by binding {@link #enabledProperty()} to a
 * menu item / preference.</p>
 */
public final class QuickHelpBar extends HBox {

    private final HelpRegistry registry;
    private final Label hintLabel = new Label();
    private final Label modeLabel = new Label("Quick Help");
    private final BooleanProperty enabled = new SimpleBooleanProperty(false);

    public QuickHelpBar(HelpRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        getStyleClass().add("quick-help-bar");
        setSpacing(8);
        setPadding(new Insets(4, 8, 4, 8));
        setAlignment(Pos.CENTER_LEFT);
        setStyle("-fx-background-color: derive(-fx-base, -8%); -fx-border-color: derive(-fx-base, -16%); -fx-border-width: 1 0 0 0;");

        modeLabel.getStyleClass().add("quick-help-mode");
        modeLabel.setStyle("-fx-font-weight: bold; -fx-opacity: 0.8;");

        hintLabel.getStyleClass().add("quick-help-text");
        hintLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(hintLabel, Priority.ALWAYS);
        hintLabel.setText("Hover any control to see contextual help.");

        getChildren().addAll(modeLabel, hintLabel);

        // Hide entirely when disabled.
        managedProperty().bind(enabled);
        visibleProperty().bind(enabled);
    }

    /** Toggle property — bind to {@code Shift+F1} or a menu item. */
    public BooleanProperty enabledProperty() {
        return enabled;
    }

    /** Returns true when the bar is currently visible. */
    public boolean isEnabled() {
        return enabled.get();
    }

    /** Sets the visible state directly. */
    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    /**
     * Attaches mouse-move and focus listeners to {@code scene} so the bar
     * tracks the control under the cursor / focused. Safe to call multiple
     * times — duplicate handlers are coalesced.
     */
    public void attachTo(Scene scene) {
        Objects.requireNonNull(scene, "scene");
        if (Boolean.TRUE.equals(scene.getProperties().get("daw.help.quickbar.attached"))) {
            return;
        }
        scene.getProperties().put("daw.help.quickbar.attached", Boolean.TRUE);
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (!isEnabled()) {
                return;
            }
            if (e.getTarget() instanceof Node n) {
                updateForNode(n);
            }
        });
        scene.focusOwnerProperty().addListener((obs, oldNode, newNode) -> {
            if (isEnabled()) {
                updateForNode(newNode);
            }
        });
    }

    /**
     * Forces the bar to display the help for {@code node}. Public so callers
     * can drive it from selection events and so tests can assert behaviour
     * without synthesising mouse motion.
     */
    public void updateForNode(Node node) {
        var slug = HelpControls.findHelpTopic(node);
        if (slug.isPresent()) {
            HelpTopic topic = registry.resolve(slug.get());
            hintLabel.setText(topic.title() + " — press F1 for details");
        } else if (node != null && node.getId() != null && !node.getId().isBlank()) {
            hintLabel.setText(node.getId() + " (no help topic registered)");
        } else {
            hintLabel.setText("Hover any control to see contextual help.");
        }
    }

    /** Visible for testing. */
    String testHintText() {
        return hintLabel.getText();
    }
}
