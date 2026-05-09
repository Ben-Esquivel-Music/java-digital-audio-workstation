package com.benesquivelmusic.daw.fx;

/**
 * Pluggable per-frame draw callback for {@link GpuCanvas}.
 *
 * <p>Implementations write pixels directly into the off-heap
 * {@link GpuRenderContext#pixels()} segment in <strong>BGRA pre-multiplied</strong>
 * byte order. Implementations should be pure with respect to the supplied
 * {@link GpuRenderContext}: given the same width, height, and frame state,
 * they should produce the same pixels. This keeps redraws on resize and
 * theme change trivial.
 *
 * <p>{@link #render(GpuRenderContext)} runs on the JavaFX Application Thread;
 * see {@link GpuRenderContext} for the surface lifetime contract and threading
 * notes for off-thread DSP.
 *
 * <p>The host clears the pixel buffer (using
 * {@link GpuCanvas#clearColorProperty()} when set, or to fully transparent
 * otherwise) before invoking {@link #render(GpuRenderContext)}, so renderers
 * may assume a clean surface.
 *
 * <p>Exceptions thrown from {@code render} are caught by the host: the frame
 * is discarded, {@link GpuCanvas#getFrameCount()} does not advance, and the
 * render loop continues. Treat this as a debugging aid, not a contract — a
 * well-behaved renderer should not throw.
 */
@FunctionalInterface
public interface GpuRenderer {

    /**
     * Writes one frame's worth of BGRA-pre pixels into
     * {@link GpuRenderContext#pixels()}.
     *
     * @param ctx per-frame context (off-heap pixel buffer, size, timing)
     */
    void render(GpuRenderContext ctx);

    /** A renderer that writes nothing. Useful as a default and in tests. */
    GpuRenderer NOOP = ctx -> { };
}
