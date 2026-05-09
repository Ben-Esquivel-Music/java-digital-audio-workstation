package com.benesquivelmusic.daw.fx;

import java.lang.foreign.MemorySegment;

/**
 * Per-frame context handed to a {@link GpuRenderer}.
 *
 * <p>The renderer writes pixels directly into the off-heap {@code pixels}
 * segment in <strong>BGRA pre-multiplied</strong> byte order — the native
 * upload format on Direct3D and the most efficient choice on Metal and OpenGL.
 * The host then uploads the segment to a {@link javafx.scene.image.WritableImage}
 * via {@link javafx.scene.image.PixelBuffer} and Prism rasterises that image
 * through the active GPU pipeline (see {@link GpuPipeline}).
 *
 * <p>The host clears the buffer (using {@link GpuCanvas#clearColorProperty()}
 * when set, or to fully transparent zero otherwise) before invoking the
 * renderer, so renderers may assume a clean surface.
 *
 * <h2>Memory layout</h2>
 * The buffer is packed (no row padding); a pixel at {@code (x, y)} starts at
 * byte offset {@code y * stride + x * 4}, with channel order {@code [B, G, R, A]}.
 * {@code stride} is always {@code width * 4} and {@code pixels.byteSize()} is
 * always {@code height * stride}.
 *
 * <h2>Threading</h2>
 * {@link GpuRenderer#render(GpuRenderContext)} is invoked on the JavaFX
 * Application Thread. Renderers that produce pixels off-thread (e.g. heavy
 * DSP) should compute into their own buffers asynchronously and copy into
 * {@code pixels} from the FX thread inside {@code render}.
 *
 * <h2>Lifetime</h2>
 * The {@code pixels} segment is owned by the {@link GpuCanvas}'s confined
 * {@link java.lang.foreign.Arena} and is valid <strong>only for the duration
 * of the {@link GpuRenderer#render(GpuRenderContext)} call</strong>. The arena
 * is reallocated on resize and closed on {@link GpuCanvas#dispose()} or when
 * the canvas shrinks to zero size. Renderers MUST NOT retain a reference to
 * the segment beyond the render callback; doing so risks
 * {@link IllegalStateException} (the arena was closed by a later resize or
 * dispose) or {@link java.lang.WrongThreadException} (a worker thread that
 * captured the segment touches it after a resize swapped it out).
 *
 * <p>Both {@code nowNanos} and {@link System#nanoTime()} read the same
 * monotonic clock, so renderers can compare them safely.
 *
 * @param pixels        off-heap BGRA-pre pixel buffer of size
 *                      {@code height * stride} bytes; renderer-writable
 * @param width         current canvas width in pixels
 * @param height        current canvas height in pixels
 * @param stride        bytes per row; always {@code width * 4}
 * @param nowNanos      timestamp from {@code AnimationTimer#handle}, or
 *                      {@link System#nanoTime()} for one-off renders
 * @param deltaSeconds  seconds elapsed since the previous frame (0.0 for the
 *                      very first frame and for one-off renders)
 * @param frameNumber   monotonically increasing frame index, starting at 0
 */
public record GpuRenderContext(
        MemorySegment pixels,
        int width,
        int height,
        int stride,
        long nowNanos,
        double deltaSeconds,
        long frameNumber) {
}
