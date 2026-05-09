/**
 * {@code daw.fx} — GPU-accelerated JavaFX rendering primitives.
 *
 * <p>Hosts {@link com.benesquivelmusic.daw.fx.GpuCanvas}, a {@code Region}
 * that wraps a {@link javafx.scene.canvas.Canvas} and drives a pluggable
 * {@link com.benesquivelmusic.daw.fx.GpuRenderer} from an {@code AnimationTimer}.
 * All drawing flows through Prism's hardware pipeline (Direct3D on Windows,
 * Metal on macOS, OpenGL ES2 on Linux); the active backend is reported by
 * {@link com.benesquivelmusic.daw.fx.GpuPipeline}.
 */
module daw.fx {
    requires transitive javafx.controls;
    requires transitive javafx.graphics;

    exports com.benesquivelmusic.daw.fx;
}
