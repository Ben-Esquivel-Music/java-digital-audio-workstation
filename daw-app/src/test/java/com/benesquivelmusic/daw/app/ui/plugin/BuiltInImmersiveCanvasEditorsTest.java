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

import javafx.scene.Scene;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

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
 * discipline — the backing canvas here is never placed in a {@code Scene}).
 * A second test pins the window half of that guard: in a scene on a hidden
 * {@link Stage} the loop must stay stopped — a hidden stage keeps its scene
 * attached, so scene presence alone is not enough — resuming only while the
 * stage is showing.</p>
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
                    CanvasSurface surface = surfaceOver(backing, renderRequests);
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

    /**
     * The window half of the hidden-editor guard (story 302 review follow-up):
     * a canvas editor in a scene whose {@link Stage} is hidden must NOT
     * self-schedule — a hidden stage keeps its scene attached, so scene
     * presence alone must never gate the loop — and must resume while the
     * stage is showing, stopping again when it hides.
     */
    @Test
    void eachCanvasBuiltInSelfSchedulesOnlyWhileItsWindowIsShowing() {
        for (Supplier<DawPlugin> constructor : CANVAS_BUILT_INS) {
            DawPlugin plugin = constructor.get();
            plugin.initialize(stubContext());
            String name = plugin.getClass().getSimpleName();
            try {
                PluginEditorFactory.Canvas canvasFactory =
                        (PluginEditorFactory.Canvas) plugin.editorFactory();
                AtomicInteger renderRequests = new AtomicInteger();
                runOnFxThread(() -> {
                    javafx.scene.canvas.Canvas backing = new javafx.scene.canvas.Canvas(400, 300);
                    CanvasSurface surface = surfaceOver(backing, renderRequests);
                    Stage stage = new Stage();
                    try {
                        stage.setScene(new Scene(new StackPane(backing), 400, 300));
                        canvasFactory.attach(surface);

                        canvasFactory.render(new RenderTick(0.0, 0, Theme.neutral()));
                        assertThat(renderRequests.get())
                                .as("%s must not self-schedule renders while its "
                                        + "stage is hidden (a hidden stage keeps "
                                        + "the scene attached)", name)
                                .isZero();

                        stage.show();
                        canvasFactory.render(new RenderTick(1.0 / 60.0, 1, Theme.neutral()));
                        assertThat(renderRequests.get())
                                .as("%s must resume self-scheduling while its "
                                        + "window is showing", name)
                                .isEqualTo(1);

                        stage.hide();
                        canvasFactory.render(new RenderTick(2.0 / 60.0, 2, Theme.neutral()));
                        assertThat(renderRequests.get())
                                .as("%s must stop self-scheduling once its "
                                        + "window is hidden again", name)
                                .isEqualTo(1);

                        canvasFactory.detach();
                    } finally {
                        stage.hide();
                    }
                    return null;
                });
            } finally {
                plugin.dispose();
            }
        }
    }

    /** Host-shaped surface over {@code backing} counting self-schedule requests. */
    private static CanvasSurface surfaceOver(javafx.scene.canvas.Canvas backing,
            AtomicInteger renderRequests) {
        return new CanvasSurface() {
            @Override public double width() { return backing.getWidth(); }
            @Override public double height() { return backing.getHeight(); }
            @Override public GraphicsContext graphicsContext() {
                return backing.getGraphicsContext2D();
            }
            @Override public void requestRender() {
                renderRequests.incrementAndGet();
            }
        };
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 48_000.0; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) { }
        };
    }
}
