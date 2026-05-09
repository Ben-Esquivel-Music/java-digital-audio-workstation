/**
 * GPU-accelerated rendering primitives for the DAW UI.
 *
 * <p>The headline type is {@link com.benesquivelmusic.daw.fx.GpuCanvas}, a
 * resizable {@code Region} that wraps a JavaFX {@link javafx.scene.canvas.Canvas}
 * and dispatches frames to a pluggable {@link com.benesquivelmusic.daw.fx.GpuRenderer}.
 * The Canvas is rasterised by Prism, JavaFX's hardware-accelerated rendering
 * pipeline (Direct3D 11 on Windows, Metal on macOS, OpenGL ES2 on Linux), so
 * fills, gradients, image draws and clips are pushed to the GPU.
 *
 * <p>{@link com.benesquivelmusic.daw.fx.GpuPipeline} reports the active Prism
 * backend at runtime, which is useful for diagnostics, telemetry, and gating
 * features that require a hardware pipeline.
 */
package com.benesquivelmusic.daw.fx;
