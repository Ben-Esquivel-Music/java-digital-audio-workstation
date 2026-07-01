package com.benesquivelmusic.daw.sdk.editor;

import java.util.Objects;

/**
 * The per-frame payload the host passes to
 * {@link PluginEditorFactory.Canvas#render(RenderTick)} (Plugin View Design
 * Book §4.1). A canvas plugin reads the elapsed time to advance its own
 * animation and reads {@link #tokens()} so its drawing tracks the active
 * theme.
 *
 * <p>{@code render(RenderTick)} runs on the JavaFX Application Thread and
 * should not allocate and must not block (§4.7). Because a fresh
 * {@code RenderTick} is created for every frame the host repaints, keep this
 * record small.</p>
 *
 * @param deltaSeconds seconds elapsed since the previous render tick
 *                     (&ge; 0, finite)
 * @param frameIndex   a monotonically increasing frame counter for this editor
 *                     (&ge; 0)
 * @param tokens       the resolved theme colours for this frame (never
 *                     {@code null})
 */
public record RenderTick(double deltaSeconds, long frameIndex, Theme tokens) {

    public RenderTick {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0.0) {
            throw new IllegalArgumentException(
                    "deltaSeconds must be finite and >= 0, got " + deltaSeconds);
        }
        if (frameIndex < 0) {
            throw new IllegalArgumentException(
                    "frameIndex must be >= 0, got " + frameIndex);
        }
        Objects.requireNonNull(tokens, "tokens must not be null");
    }
}
