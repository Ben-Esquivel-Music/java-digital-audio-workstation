package com.benesquivelmusic.daw.app.ui.controls;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.List;

/**
 * Tiny non-interactive breadcrumb display — a horizontally laid-out chain
 * of segment labels separated by the configured separator glyph
 * ({@code ▸} by default).
 *
 * <p>Introduced for the {@link com.benesquivelmusic.daw.app.ui.views.WorkshopView}
 * right-pane header (story 281, UI Design Book §4 Concept F): renders the
 * focused plugin's location as e.g. <em>{@code Track 03 ▸ Insert 1 ▸ Serum}</em>.
 * Per the story Non-Goals the segments are <strong>plain text only</strong> —
 * clicking a segment does nothing; navigation comes from the inspector
 * selection model in the surrounding application.</p>
 *
 * <h2>Design type — plain {@link HBox}, not Control/Skin</h2>
 *
 * <p>Per the JavaFX-design rules the Control/Skin pattern is for "reusable
 * widget with its own observable state". {@code BreadcrumbBar} is a
 * stateless trivial layout — a one-shot list of labels rebuilt whenever
 * {@link #segmentsProperty()} mutates — so forcing Control/Skin here would
 * add ceremony with no payoff. Subclassing {@link HBox} keeps it a single
 * file and lets the CSS author target {@code .breadcrumb-bar} /
 * {@code .breadcrumb-segment} / {@code .breadcrumb-separator} directly.</p>
 *
 * <h2>Styling</h2>
 *
 * <ul>
 *   <li>Root style class — {@code breadcrumb-bar}</li>
 *   <li>Each segment {@link Label} — {@code breadcrumb-segment}</li>
 *   <li>Each separator {@link Label} (between segments) — {@code breadcrumb-separator}</li>
 * </ul>
 *
 * <p>All colours / spacing resolve from CSS — never hard-coded in Java
 * (story 277 token theme rule).</p>
 */
public final class BreadcrumbBar extends HBox {

    /** Stable style class — selectable as {@code .breadcrumb-bar}. */
    public static final String STYLE_CLASS = "breadcrumb-bar";
    /** Per-segment style class — selectable as {@code .breadcrumb-segment}. */
    public static final String SEGMENT_STYLE_CLASS = "breadcrumb-segment";
    /** Per-separator style class — selectable as {@code .breadcrumb-separator}. */
    public static final String SEPARATOR_STYLE_CLASS = "breadcrumb-separator";

    /** Default separator glyph — black right-pointing small triangle. */
    public static final String DEFAULT_SEPARATOR = "▸"; // ▸

    private final ObservableList<String> segments =
            FXCollections.observableArrayList();
    private final ObjectProperty<String> separator =
            new SimpleObjectProperty<>(this, "separator", DEFAULT_SEPARATOR);

    /**
     * Creates an empty breadcrumb bar — call
     * {@link #setSegments(java.util.List)} or mutate
     * {@link #segmentsProperty()} directly to populate it.
     */
    public BreadcrumbBar() {
        getStyleClass().add(STYLE_CLASS);
        setAlignment(Pos.CENTER_LEFT);
        setAccessibleRole(AccessibleRole.NODE);
        setAccessibleRoleDescription("Breadcrumb");
        // Rebuild on any segment-list mutation or separator change.
        segments.addListener((ListChangeListener<String>) c -> rebuild());
        separator.addListener((obs, was, now) -> rebuild());
        rebuild();
    }

    /**
     * Replaces the current breadcrumb segments. {@code null} is normalised
     * to an empty list so the bar simply clears.
     *
     * @param newSegments the new segments, left-to-right; may be {@code null}
     */
    public void setSegments(List<String> newSegments) {
        if (newSegments == null) {
            segments.clear();
        } else {
            segments.setAll(newSegments);
        }
    }

    /**
     * @return the live observable segments list — mutating it triggers a
     *         rebuild
     */
    public ObservableList<String> segmentsProperty() {
        return segments;
    }

    /** @return an unmodifiable snapshot of the current segments. */
    public List<String> getSegments() {
        return List.copyOf(segments);
    }

    /**
     * @return the separator-glyph property (default {@value #DEFAULT_SEPARATOR})
     */
    public ObjectProperty<String> separatorProperty() {
        return separator;
    }

    /** @return the current separator glyph. */
    public String getSeparator() {
        return separator.get();
    }

    /**
     * Sets the separator glyph between segments. {@code null} or empty
     * input is normalised to {@link #DEFAULT_SEPARATOR}.
     *
     * @param glyph the new separator glyph, or {@code null} for the default
     */
    public void setSeparator(String glyph) {
        separator.set(glyph == null || glyph.isEmpty() ? DEFAULT_SEPARATOR : glyph);
    }

    /**
     * Rebuilds the child nodes: a {@link Label} per segment, interspersed
     * with separator labels. Pure layout — no CSS injection — so a theme
     * swap re-tints the bar with no code change.
     */
    private void rebuild() {
        getChildren().clear();
        String sep = getSeparator();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                Label sepLabel = new Label(sep);
                sepLabel.getStyleClass().add(SEPARATOR_STYLE_CLASS);
                sepLabel.setFocusTraversable(false);
                getChildren().add(sepLabel);
            }
            Label segment = new Label(segments.get(i));
            segment.getStyleClass().add(SEGMENT_STYLE_CLASS);
            segment.setFocusTraversable(false);
            // The last segment is the focused leaf — let it consume any
            // residual horizontal space so the bar reads "label ▸ label ▸
            // {leaf}" with the leaf truncating instead of the parents.
            if (i == segments.size() - 1) {
                HBox.setHgrow(segment, Priority.ALWAYS);
                segment.setMaxWidth(Region.USE_COMPUTED_SIZE);
            }
            getChildren().add(segment);
        }
    }
}
