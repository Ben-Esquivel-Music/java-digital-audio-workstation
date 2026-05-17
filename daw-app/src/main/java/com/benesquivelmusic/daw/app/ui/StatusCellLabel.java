package com.benesquivelmusic.daw.app.ui;

import javafx.scene.control.Label;

/**
 * Story 274 — a status-bar cell that is never the first cell, so it must
 * always carry the leading "· " separator (UI Design Book §5.11 / §7.7:
 * the eye reads cell grouping from the dot, JavaFX CSS has no
 * {@code :first-child} so the separator is part of the cell text).
 *
 * <p><b>Why a dedicated type instead of prefixing at the call site:</b>
 * {@code statusBarLabel} and {@code checkpointLabel} are written from ~30
 * sites across {@link MainController}, {@link TransportController} and
 * {@link ProjectLifecycleController}, which hold the label by its
 * {@link Label} base reference and call {@code setText(...)} with raw,
 * dynamic action messages ("Stopped", "Saved (checkpoint #3)", …) that
 * cannot themselves carry the separator. Prefixing at every site is the
 * exact "magic string in N places" trap flagged in review; this type is
 * the single seam that guarantees the invariant for <em>all</em> writers.
 *
 * <p>{@link javafx.scene.control.Labeled#setText} is {@code final} and
 * cannot be overridden, so the invariant is enforced by a self-normalising
 * {@code textProperty} listener: any value lacking the leading middle dot
 * is rewritten with the separator. The rewrite re-fires the listener once
 * with an already-prefixed value and then converges (no recursion). It is
 * idempotent, so call sites and {@code Messages.properties} values that
 * already include the dot (for cross-cell uniformity) never double up.
 */
public final class StatusCellLabel extends Label {

    /** MIDDLE DOT (U+00B7) + space — the inter-cell separator (§5.11). */
    public static final String CELL_SEPARATOR = "· ";

    public StatusCellLabel() {
        textProperty().addListener((obs, old, value) -> {
            if (value != null && !value.isBlank() && !value.startsWith("·")) {
                setText(CELL_SEPARATOR + value);
            }
        });
    }
}
