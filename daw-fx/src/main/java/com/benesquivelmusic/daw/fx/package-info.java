/**
 * GPU-accelerated rendering primitives for the DAW UI.
 *
 * <p>The headline type is {@link com.benesquivelmusic.daw.fx.GpuCanvas}, a
 * resizable {@code Region} that exposes an off-heap
 * {@link java.lang.foreign.MemorySegment} of <strong>BGRA pre-multiplied</strong>
 * pixels to a pluggable {@link com.benesquivelmusic.daw.fx.GpuRenderer}. The
 * segment is allocated from a per-canvas confined
 * {@link java.lang.foreign.Arena} via Java FFM, wrapped in a
 * {@link javafx.scene.image.PixelBuffer} that backs a
 * {@link javafx.scene.image.WritableImage}, and displayed by an internal
 * {@link javafx.scene.image.ImageView}. Prism — JavaFX's hardware pipeline
 * (Direct3D 11 on Windows, Metal on macOS, OpenGL ES2 on Linux) — uploads
 * the image to the GPU and rasterises it.
 *
 * <p>{@link com.benesquivelmusic.daw.fx.GpuPipeline} reports the active Prism
 * backend at runtime, which is useful for diagnostics, telemetry, and gating
 * features that require a hardware pipeline.
 */
package com.benesquivelmusic.daw.fx;
