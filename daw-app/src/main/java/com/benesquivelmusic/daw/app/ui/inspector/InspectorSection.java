package com.benesquivelmusic.daw.app.ui.inspector;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Reusable collapsible section card for the Inspector drawer (UI
 * Design Book §5.6, story 272).
 *
 * <h2>Anatomy</h2>
 *
 * <ul>
 *   <li><strong>Header row</strong>: a chevron glyph ({@code ▾} when
 *       expanded, {@code ▸} when collapsed — story 265's icon set) +
 *       the section title, styled per §3.2 "Label small" (10 px / 600 /
 *       uppercase / +12 % tracking / {@code -text-mute}).
 *       <em>Explicitly not purple</em> per §1.6 / §7.6 — the section
 *       header is muted text, never a saturated band.</li>
 *   <li><strong>Body</strong>: a freely composable {@link Node} that the
 *       caller installs via {@link #setBody(Node)}.</li>
 * </ul>
 *
 * <p>Toggling {@link #expandedProperty()} hides / shows the body and
 * swaps the chevron glyph. The header is keyboard-accessible —
 * {@code Enter} / {@code Space} toggle expansion.
 *
 * <p>This is a plain {@link VBox} subclass rather than a
 * {@code Control + Skin} pair on purpose: it is composition only, has
 * no internal state model, and is consumed inside the inspector
 * drawer's skin (which is the Control + Skin layer at this level).
 * Skill §3: "for one-off layouts inside an app, prefer a plain
 * {@code Region}/{@code Pane} subclass — do not force the Control/Skin
 * pattern where it adds no value".
 */
public class InspectorSection extends VBox {

    /** Stable style class — selectable as {@code .inspector-section}. */
    public static final String DEFAULT_STYLE_CLASS = "inspector-section";

    private static final String GLYPH_EXPANDED = "\u25BE"; // ▾
    private static final String GLYPH_COLLAPSED = "\u25B8"; // ▸

    private final StringProperty title = new SimpleStringProperty(this, "title", "");
    private final BooleanProperty expanded =
            new SimpleBooleanProperty(this, "expanded", true);

    private final Label chevron = new Label(GLYPH_EXPANDED);
    private final Label titleLabel = new Label();
    private final HBox header = new HBox(4, chevron, titleLabel);
    private final VBox bodyHolder = new VBox();

    /** Empty section with no title and no body. */
    public InspectorSection() {
        this("", null);
    }

    /**
     * @param title section title (typically uppercase, e.g. "TRACK")
     */
    public InspectorSection(String title) {
        this(title, null);
    }

    /**
     * @param title initial title
     * @param body  initial body, or {@code null}
     */
    public InspectorSection(String title, Node body) {
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        this.title.set(title == null ? "" : title);

        chevron.getStyleClass().add("inspector-section-chevron");
        titleLabel.getStyleClass().add("inspector-section-title");
        titleLabel.textProperty().bind(this.title);

        header.getStyleClass().add("inspector-section-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setFocusTraversable(true);

        bodyHolder.getStyleClass().add("inspector-section-body");
        VBox.setVgrow(bodyHolder, Priority.NEVER);
        if (body != null) {
            bodyHolder.getChildren().setAll(body);
        }

        getChildren().setAll(header, bodyHolder);

        // Click / Enter / Space toggles expansion.
        header.setOnMouseClicked(e -> setExpanded(!isExpanded()));
        header.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER, SPACE -> {
                    setExpanded(!isExpanded());
                    e.consume();
                }
                default -> { /* ignore */ }
            }
        });

        // React to expansion: hide body and swap chevron glyph.
        expanded.addListener((obs, was, now) -> applyExpanded(now));
        applyExpanded(expanded.get());
    }

    private void applyExpanded(boolean exp) {
        chevron.setText(exp ? GLYPH_EXPANDED : GLYPH_COLLAPSED);
        bodyHolder.setVisible(exp);
        bodyHolder.setManaged(exp);
    }

    /** Replaces the section body. {@code null} clears it. */
    public void setBody(Node body) {
        if (body == null) {
            bodyHolder.getChildren().clear();
        } else {
            bodyHolder.getChildren().setAll(body);
        }
    }

    /** @return the underlying body container — children can be added directly. */
    public Region getBodyContainer() {
        return bodyHolder;
    }

    public final StringProperty titleProperty() { return title; }
    public final String getTitle() { return title.get(); }
    public final void setTitle(String t) { title.set(t == null ? "" : t); }

    public final BooleanProperty expandedProperty() { return expanded; }
    public final boolean isExpanded() { return expanded.get(); }
    public final void setExpanded(boolean e) { expanded.set(e); }

    /** @return the header HBox (chevron + title) — exposed for tests / theming. */
    public HBox getHeader() {
        return header;
    }

    /** @return the title {@link Label} — used by styling tests. */
    public Label getTitleLabel() {
        return titleLabel;
    }
}
