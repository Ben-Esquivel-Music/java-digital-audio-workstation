package com.benesquivelmusic.daw.fx;

/**
 * Pluggable per-frame draw callback for {@link GpuCanvas}.
 *
 * <p>Implementations should be pure with respect to the supplied
 * {@link GpuRenderContext}: given the same width, height, and frame state,
 * they should produce the same pixels. This keeps redraws on resize and
 * theme change trivial.
 *
 * <p>The host clears the canvas before invoking {@link #render(GpuRenderContext)},
 * so renderers may assume a clean surface.
 */
@FunctionalInterface
public interface GpuRenderer {

    /**
     * Issues GPU draw commands for one frame.
     *
     * @param ctx per-frame context (graphics context, size, timing)
     */
    void render(GpuRenderContext ctx);

    /** A renderer that draws nothing. Useful as a default and in tests. */
    GpuRenderer NOOP = ctx -> { };
}
