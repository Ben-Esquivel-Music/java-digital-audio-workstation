package com.benesquivelmusic.daw.app.ui.inspector.skin;

import com.benesquivelmusic.daw.app.ui.inspector.InspectorDrawer;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSection;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SkinBase;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

/**
 * Skin for {@link InspectorDrawer} — implements the §5.6 layout.
 *
 * <p>The skin holds two side-by-side {@link StackPane} content panes:
 * the <strong>rail</strong> (shown when collapsed; contains the
 * vertical "INSPECTOR" label rotated -90°) and the <strong>expanded
 * panel</strong> (header bar + sections stack inside a
 * {@link ScrollPane}). Toggling
 * {@link InspectorDrawer#expandedProperty()} animates the control's
 * {@code prefWidth} between the {@code -inspector-collapsed-width} and
 * {@code -inspector-expanded-width} styleable values, then swaps the
 * visible content pane.
 */
public final class InspectorDrawerSkin extends SkinBase<InspectorDrawer> {

    private final StackPane rail;
    private final Label railLabel;
    private final VBox expandedPanel;
    private final Label headerBar;
    private final VBox sectionsBox;
    private final ScrollPane scroll;
    private final StackPane root;

    private final ChangeListener<Boolean> expandedListener;
    private final ChangeListener<String> headerTextListener;
    private final ChangeListener<Number> expandedWidthListener;
    private final ChangeListener<Number> collapsedWidthListener;

    private Timeline transition;

    public InspectorDrawerSkin(InspectorDrawer control) {
        super(control);

        // ── Rail (collapsed) ──
        railLabel = new Label("INSPECTOR");
        railLabel.getStyleClass().add("inspector-rail-label");
        // Rotate the rail label so it reads bottom-to-top per the §5.6
        // mockup. JavaFX rotates around the node's centre.
        railLabel.getTransforms().add(new Rotate(-90));
        rail = new StackPane(railLabel);
        rail.getStyleClass().add("inspector-rail");
        rail.setAlignment(Pos.CENTER);

        // ── Expanded panel ──
        headerBar = new Label();
        headerBar.getStyleClass().add("inspector-header-bar");
        headerBar.textProperty().bind(control.headerTextProperty());

        sectionsBox = new VBox(8);
        sectionsBox.getStyleClass().add("inspector-sections-box");
        for (InspectorSection s : control.getSections()) {
            sectionsBox.getChildren().add(s);
        }

        scroll = new ScrollPane(sectionsBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("inspector-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        expandedPanel = new VBox(headerBar, scroll);
        expandedPanel.getStyleClass().add("inspector-expanded-panel");
        // 16 px right-edge gutter per §5.6.
        expandedPanel.setPadding(new Insets(8, 16, 8, 8));

        root = new StackPane(expandedPanel, rail);
        root.setAlignment(Pos.TOP_LEFT);
        getChildren().add(root);

        // ── Listeners ──
        expandedListener = (obs, was, now) -> applyExpanded(now, true);
        control.expandedProperty().addListener(expandedListener);

        headerTextListener = (obs, was, now) -> {
            /* bound; no-op explicit listener kept for symmetry */
        };
        control.headerTextProperty().addListener(headerTextListener);

        expandedWidthListener = (obs, was, now) -> {
            if (control.isExpanded()) {
                applyExpanded(true, false);
            }
        };
        collapsedWidthListener = (obs, was, now) -> {
            if (!control.isExpanded()) {
                applyExpanded(false, false);
            }
        };
        control.inspectorExpandedWidthProperty().addListener(expandedWidthListener);
        control.inspectorCollapsedWidthProperty().addListener(collapsedWidthListener);

        // Initial state — snap (no animation on first attach).
        applyExpanded(control.isExpanded(), false);
    }

    /**
     * Drives the rail / expanded layout in response to
     * {@link InspectorDrawer#expandedProperty()}.
     *
     * @param exp     {@code true} → expanded, {@code false} → collapsed rail
     * @param animate whether to play the 220 ms transition; ignored
     *                when {@link InspectorDrawer#animatedProperty()} is
     *                {@code false} (the transition collapses to a
     *                {@code 0 ms} step — story 279 reduce-motion).
     */
    private void applyExpanded(boolean exp, boolean animate) {
        InspectorDrawer d = getSkinnable();
        double target = exp ? d.getInspectorExpandedWidth() : d.getInspectorCollapsedWidth();

        // Snap visibility flags around the animation — rail visible only
        // when fully collapsed; expanded panel visible only when
        // expanded. We update these synchronously so the test that
        // toggles {@code animatedProperty=false} sees the correct
        // visibility within a single pulse.
        rail.setVisible(!exp);
        rail.setManaged(!exp);
        expandedPanel.setVisible(exp);
        expandedPanel.setManaged(exp);

        if (transition != null) {
            transition.stop();
            transition = null;
        }

        boolean reduceMotion = !d.isAnimated();
        if (!animate || reduceMotion) {
            d.setPrefWidth(target);
            d.setMinWidth(target);
            d.setMaxWidth(target);
            return;
        }

        double from = d.getWidth();
        if (from <= 0) {
            from = d.getPrefWidth();
        }
        if (from <= 0) {
            from = target;
        }
        // Keep min/max pinned during the animation; they will be reset
        // at the end so the control sits at its final width.
        d.setMinWidth(Math.min(from, target));
        d.setMaxWidth(Math.max(from, target));
        transition = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(d.prefWidthProperty(), from, Interpolator.EASE_OUT)),
                new KeyFrame(Duration.millis(InspectorDrawer.DEFAULT_TRANSITION_MS),
                        new KeyValue(d.prefWidthProperty(), target, Interpolator.EASE_OUT))
        );
        transition.setOnFinished(e -> {
            d.setMinWidth(target);
            d.setMaxWidth(target);
            transition = null;
        });
        transition.playFromStart();
    }

    /** @return the rail's vertical "INSPECTOR" label — for tests / theming. */
    public Label getRailLabel() {
        return railLabel;
    }

    /** @return the expanded panel header bar — for tests / theming. */
    public Label getHeaderBar() {
        return headerBar;
    }

    /** @return the container hosting the section cards — for tests. */
    public VBox getSectionsBox() {
        return sectionsBox;
    }

    /** @return the rail container — for tests / theming. */
    public Region getRail() {
        return rail;
    }

    /** @return the expanded panel container — for tests / theming. */
    public Region getExpandedPanel() {
        return expandedPanel;
    }

    @Override
    public void dispose() {
        InspectorDrawer d = getSkinnable();
        if (d != null) {
            d.expandedProperty().removeListener(expandedListener);
            d.headerTextProperty().removeListener(headerTextListener);
            d.inspectorExpandedWidthProperty().removeListener(expandedWidthListener);
            d.inspectorCollapsedWidthProperty().removeListener(collapsedWidthListener);
        }
        if (transition != null) {
            transition.stop();
            transition = null;
        }
        headerBar.textProperty().unbind();
        super.dispose();
    }
}
