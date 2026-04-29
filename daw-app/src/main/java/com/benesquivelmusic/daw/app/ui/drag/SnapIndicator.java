package com.benesquivelmusic.daw.app.ui.drag;

import java.util.Objects;

/**
 * Visual indicator showing the snapped drop position when a clip or
 * sample is being dropped onto the arrangement.
 *
 * <p>The presenter draws a vertical guide line at {@link #snappedXPx} and
 * a small inline badge showing {@link #snapValueLabel} (e.g. {@code 1/4},
 * {@code Bar}, {@code Beat}). When {@link #visible} is {@code false}
 * (Shift-disable-snap or non-arrangement targets) the presenter hides
 * the guide.</p>
 *
 * @param snappedXPx     X coordinate, in arrangement-canvas pixels, of the
 *                       vertical guide line
 * @param snapValueLabel human-readable snap-grid label, e.g.
 *                       {@code "1/4"}, {@code "Bar"}
 * @param visible        whether the indicator should be drawn at all
 */
public record SnapIndicator(
        double snappedXPx,
        String snapValueLabel,
        boolean visible) {

    /** The hidden-snap sentinel — used when snap is irrelevant or disabled. */
    public static final SnapIndicator HIDDEN =
            new SnapIndicator(0.0, "", false);

    public SnapIndicator {
        Objects.requireNonNull(snapValueLabel, "snapValueLabel");
    }
}
