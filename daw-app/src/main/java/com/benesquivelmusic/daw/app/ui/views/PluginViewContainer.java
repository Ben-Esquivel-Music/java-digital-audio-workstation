package com.benesquivelmusic.daw.app.ui.views;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

/**
 * Stable-identity host that displays the currently-focused plugin GUI
 * (story 281, UI Design Book §4 Concept F).
 *
 * <p>The {@link com.benesquivelmusic.daw.app.ui.views.WorkshopView}'s right
 * pane swaps between different plugins as the user clicks different tracks
 * or inserts. The container itself is the seam: callers update
 * {@link #setPluginView(Node)} to switch what is shown, but the
 * <strong>container node itself is never recreated</strong>. Reusing the
 * single host preserves any per-pane state stored at the host level
 * (focus, scroll, etc.), keeps the breadcrumb header bound to a stable
 * parent, and — most importantly — lets tests assert that switching the
 * focused plugin does not unmount or rebuild the container, satisfying
 * the "right pane updates without unmounting/re-creating plugin view
 * container" acceptance criterion.</p>
 *
 * <h2>Design type — plain {@link StackPane}, not Control/Skin</h2>
 *
 * <p>Per the JavaFX-design rules the Control/Skin pattern is for "reusable
 * widget with its own observable state". {@code PluginViewContainer} is a
 * trivial single-purpose host — one observable {@code pluginViewProperty}
 * and a placeholder slot — so forcing Control/Skin here would add ceremony
 * with no payoff.</p>
 *
 * <h2>Empty state</h2>
 *
 * <p>When no plugin is focused (the {@link #pluginViewProperty()} is
 * {@code null}) the container shows a caller-supplied
 * {@link #setPlaceholder(Node) placeholder} node — typically a short
 * "No plugin focused." {@code Label}.</p>
 */
public final class PluginViewContainer extends StackPane {

    /** Stable style class — selectable as {@code .plugin-view-container}. */
    public static final String STYLE_CLASS = "plugin-view-container";

    private final ObjectProperty<Node> pluginView =
            new SimpleObjectProperty<>(this, "pluginView", null);
    private final ObjectProperty<Node> placeholder =
            new SimpleObjectProperty<>(this, "placeholder", null);
    private final ReadOnlyObjectWrapper<Node> currentContent =
            new ReadOnlyObjectWrapper<>(this, "currentContent", null);

    /**
     * Creates an empty container — until {@link #setPluginView(Node)} or
     * {@link #setPlaceholder(Node)} is called the container holds no
     * children.
     */
    public PluginViewContainer() {
        getStyleClass().add(STYLE_CLASS);
        setAccessibleRole(AccessibleRole.NODE);
        setAccessibleRoleDescription("Plugin");
        pluginView.addListener((obs, was, now) -> updateDisplayed());
        placeholder.addListener((obs, was, now) -> updateDisplayed());
        updateDisplayed();
    }

    /**
     * Sets the focused plugin view. Pass {@code null} to clear back to the
     * placeholder. Setting the same instance twice is a no-op — the
     * container is intentionally identity-preserving (see class Javadoc).
     *
     * @param view the new focused plugin GUI, or {@code null} for empty
     */
    public void setPluginView(Node view) {
        pluginView.set(view);
    }

    /** @return the currently focused plugin view, or {@code null} when empty. */
    public Node getPluginView() {
        return pluginView.get();
    }

    /** @return the focused-plugin-view property (observable). */
    public ObjectProperty<Node> pluginViewProperty() {
        return pluginView;
    }

    /**
     * Sets the empty-state placeholder shown when
     * {@link #pluginViewProperty()} is {@code null}.
     *
     * @param node the placeholder node, or {@code null} for "show nothing"
     */
    public void setPlaceholder(Node node) {
        placeholder.set(node);
    }

    /** @return the placeholder node, or {@code null} if none. */
    public Node getPlaceholder() {
        return placeholder.get();
    }

    /** @return the placeholder property (observable). */
    public ObjectProperty<Node> placeholderProperty() {
        return placeholder;
    }

    /**
     * @return the node currently displayed (either the focused plugin
     *         view, the placeholder, or {@code null} if both are unset) —
     *         test seam
     */
    public Node getCurrentContent() {
        return currentContent.get();
    }

    /** @return the displayed-content property (read-only, test seam). */
    public ReadOnlyObjectProperty<Node> currentContentProperty() {
        return currentContent.getReadOnlyProperty();
    }

    private void updateDisplayed() {
        // Prefer the focused plugin view; fall back to the placeholder
        // when no plugin is focused. Both null is a valid "show nothing"
        // initial state (the container is constructed empty and the
        // caller wires the placeholder + plugin view post-construction).
        Node target = pluginView.get();
        if (target == null) {
            target = placeholder.get();
        }
        getChildren().clear();
        if (target != null) {
            getChildren().add(target);
        }
        currentContent.set(target);
    }
}
