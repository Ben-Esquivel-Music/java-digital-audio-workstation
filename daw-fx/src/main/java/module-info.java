/**
 * {@code daw.fx} — GPU-accelerated JavaFX rendering primitives.
 *
 * <p>Hosts {@link com.benesquivelmusic.daw.fx.GpuCanvas}, a {@code Region}
 * that hands renderers an off-heap BGRA pre-multiplied pixel buffer
 * (allocated from a confined {@link java.lang.foreign.Arena}), commits it to
 * a {@link javafx.scene.image.WritableImage} via
 * {@link javafx.scene.image.PixelBuffer}, and lets Prism rasterise the result
 * through the platform GPU (Direct3D on Windows, Metal on macOS, OpenGL ES2
 * on Linux). The active backend is reported by
 * {@link com.benesquivelmusic.daw.fx.GpuPipeline}.
 */
module daw.fx {
    requires transitive javafx.controls;
    requires transitive javafx.graphics;
    requires java.logging;

    exports com.benesquivelmusic.daw.fx;
}
