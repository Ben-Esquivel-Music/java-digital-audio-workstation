package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.core.plugin.BinauralMonitorPlugin;
import com.benesquivelmusic.daw.core.plugin.MatchEqPlugin;
import com.benesquivelmusic.daw.core.plugin.SoundWaveTelemetryPlugin;
import com.benesquivelmusic.daw.core.plugin.SpectrumAnalyzerPlugin;
import com.benesquivelmusic.daw.sdk.editor.CanvasSurface;
import com.benesquivelmusic.daw.sdk.editor.ChromePolicy;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.RenderTick;
import com.benesquivelmusic.daw.sdk.editor.Theme;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;

import javafx.scene.canvas.GraphicsContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 302 — the §8.3-item-5 immersive canvas built-ins.
 * {@code BinauralMonitorPlugin}, {@code MatchEqPlugin},
 * {@code SoundWaveTelemetryPlugin} and {@code SpectrumAnalyzerPlugin} each
 * return a {@link PluginEditorFactory.Canvas} factory reporting
 * {@link ChromePolicy#IMMERSIVE} (§5.D), and the contract lifecycle
 * {@code attach} → {@code render} → {@code detach} is exercised against a
 * host-shaped surface.
 *
 * <p>The render pass also pins the hidden-editor guard: off-scene, a canvas
 * editor must NOT self-schedule another frame through
 * {@link CanvasSurface#requestRender()} (the hidden-stage animation-leak
 * discipline — the backing canvas here is never placed in a {@code Scene}).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class BuiltInImmersiveCanvasEditorsTest {

    private static final List<Supplier<DawPlugin>> CANVAS_BUILT_INS = List.of(
            BinauralMonitorPlugin::new,
            MatchEqPlugin::new,
            SoundWaveTelemetryPlugin::new,
            SpectrumAnalyzerPlugin::new);

    @Test
    void eachCanvasBuiltInReportsImmersiveChromeAndSurvivesAttachRenderDetach() {
        for (Supplier<DawPlugin> constructor : CANVAS_BUILT_INS) {
            DawPlugin plugin = constructor.get();
            plugin.initialize(stubContext());
            try {
                PluginEditorFactory factory = plugin.editorFactory();

                assertThat(factory)
                        .as("%s must expose a Canvas editor factory",
                                plugin.getClass().getSimpleName())
                        .isInstanceOf(PluginEditorFactory.Canvas.class);
                assertThat(factory.hints().chrome())
                        .as("%s's chrome policy must be IMMERSIVE (§5.D)",
                                plugin.getClass().getSimpleName())
                        .isEqualTo(ChromePolicy.IMMERSIVE);

                PluginEditorFactory.Canvas canvasFactory = (PluginEditorFactory.Canvas) factory;
                AtomicInteger renderRequests = new AtomicInteger();
                runOnFxThread(() -> {
                    javafx.scene.canvas.Canvas backing = new javafx.scene.canvas.Canvas(400, 300);
                    CanvasSurface surface = new CanvasSurface() {
                        @Override public double width() { return backing.getWidth(); }
                        @Override public double height() { return backing.getHeight(); }
                        @Override public GraphicsContext graphicsContext() {
                            return backing.getGraphicsContext2D();
                        }
                        @Override public void requestRender() {
                            renderRequests.incrementAndGet();
                        }
                    };
                    canvasFactory.attach(surface);
                    canvasFactory.render(new RenderTick(0.0, 0, Theme.neutral()));
                    canvasFactory.render(new RenderTick(1.0 / 60.0, 1, Theme.neutral()));
                    canvasFactory.detach();
                    return null;
                });
                assertThat(renderRequests.get())
                        .as("%s must not self-schedule renders while its canvas is "
                                + "outside a Scene (hidden-editor animation guard)",
                                plugin.getClass().getSimpleName())
                        .isZero();
            } finally {
                plugin.dispose();
            }
        }
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 48_000.0; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) { }
        };
    }
}
