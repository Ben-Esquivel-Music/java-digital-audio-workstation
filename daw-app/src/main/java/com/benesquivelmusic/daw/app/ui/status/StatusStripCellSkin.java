package com.benesquivelmusic.daw.app.ui.status;

import javafx.scene.control.Label;
import javafx.scene.control.SkinBase;

/**
 * Skin for {@link StatusStripCell} — the visual half of the §4.4 status-cell
 * Control/Skin split ({@code javafx-application-design} skill §3). Renders a
 * single {@link Label} carrying the {@code .status-strip-cell-text} style
 * class, laid out to fill by {@link SkinBase}'s default
 * {@code layoutChildren} (no override needed for one child).
 *
 * <h2>Binding-only contract</h2>
 *
 * <p>The label's text is wired to the control via a one-way
 * {@code bind(...)} — this skin <strong>never</strong> calls
 * {@code Label.setText}. Story 295 forbids any {@code Label} text mutation in
 * the cell sources so a theme or the strip can only ever drive the text
 * through {@link StatusStripCell#textProperty()}. {@link #dispose()} unbinds
 * the label before delegating to the superclass so the skin can be swapped
 * (e.g. on a theme change) without leaking the binding.
 */
public final class StatusStripCellSkin extends SkinBase<StatusStripCell> {

    private final Label label;

    /**
     * Builds the skin's single label child and binds its text to the
     * control's text property.
     *
     * @param control the owning {@link StatusStripCell}
     */
    public StatusStripCellSkin(StatusStripCell control) {
        super(control);
        label = new Label();
        label.getStyleClass().add("status-strip-cell-text");
        // Binding-only: the label mirrors the control's text via bind(); no
        // setText call appears in this file or in StatusStripCell.
        label.textProperty().bind(getSkinnable().textProperty());
        getChildren().add(label);
    }

    @Override
    public void dispose() {
        label.textProperty().unbind();
        super.dispose();
    }
}
