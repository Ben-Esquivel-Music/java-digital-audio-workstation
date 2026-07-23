package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.sdk.editor.CanvasSurface;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.RenderTick;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 302 review follow-up — the host side of the hidden-stage
 * animation-leak discipline (§5.D, §6.3). A self-scheduling canvas editor
 * stops re-requesting frames while its window is hidden (scene presence alone
 * never gates the loop — a hidden {@link Stage} keeps its scene attached), so
 * the session must kick one repaint when the window shows again; the editor's
 * own end-of-frame re-request then resumes the loop. Without the kick a
 * re-shown editor would freeze on its last frame.
 *
 * <p>Deterministic: no {@code FxDispatcher} default is installed in this
 * unit context, so the session's {@code scheduleRender()} renders
 * synchronously instead of coalescing onto a dispatcher pulse.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
@DisplayName("Re-showing a hidden editor window kicks a canvas repaint (story 302 review)")
class EditorSessionReshowRestartsCanvasLoopTest {

    /** Headless deps — every nullable service degraded (session tolerates it). */
    private static final PluginEditorSession.Deps DEPS =
            new PluginEditorSession.Deps(() -> 48_000.0, () -> null, null, null);

    private PluginEditorSession session;
    private Stage stage;

    @AfterEach
    void tearDown() {
        PluginEditorSession doomedSession = session;
        Stage doomedStage = stage;
        session = null;
        stage = null;
        runOnFxThread(() -> {
            if (doomedSession != null) {
                doomedSession.dispose();
            }
            if (doomedStage != null) {
                doomedStage.hide();
            }
            return null;
        });
    }

    @Test
    void showingTheStageSchedulesARenderAndReshowSchedulesAnother() {
        AtomicInteger renders = new AtomicInteger();
        PluginEditorFactory.Canvas factory = new PluginEditorFactory.Canvas() {
            @Override
            public void attach(CanvasSurface surface) {
                // surface unused — only render cadence is under test
            }

            @Override
            public void render(RenderTick tick) {
                renders.incrementAndGet();
            }

            @Override
            public void detach() {
                // nothing to release
            }
        };

        session = runOnFxThread(
                () -> PluginEditorSession.open(new CanvasOnlyPlugin(factory), null, DEPS));

        int afterSceneEntry = runOnFxThread(() -> {
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(session.frame()), 900, 700));
            return renders.get();
        });
        assertThat(afterSceneEntry)
                .as("open + scene entry render before the stage ever shows")
                .isPositive();

        int afterShow = runOnFxThread(() -> {
            stage.show();
            return renders.get();
        });
        assertThat(afterShow)
                .as("showing the window must schedule a repaint (the restart "
                        + "kick a self-stopped canvas loop resumes from)")
                .isGreaterThan(afterSceneEntry);

        int afterReshow = runOnFxThread(() -> {
            stage.hide();
            int afterHide = renders.get();
            stage.show();
            assertThat(renders.get())
                    .as("re-showing the hidden window must kick another repaint")
                    .isGreaterThan(afterHide);
            return renders.get();
        });
        assertThat(afterReshow).isGreaterThan(afterShow);
    }

    /** Minimal story-300 contract plugin returning the injected canvas factory. */
    private static final class CanvasOnlyPlugin implements DawPlugin {

        private final PluginEditorFactory factory;
        private final PluginDescriptor descriptor = new PluginDescriptor(
                "com.test.story302.reshow.canvas",
                "Reshow Test", "1.0.0", "Test Vendor", PluginType.EFFECT);

        CanvasOnlyPlugin(PluginEditorFactory factory) {
            this.factory = factory;
        }

        @Override
        public PluginDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public void initialize(PluginContext context) {
            // lifecycle no-op
        }

        @Override
        public void activate() {
            // lifecycle no-op
        }

        @Override
        public void deactivate() {
            // lifecycle no-op
        }

        @Override
        public void dispose() {
            // lifecycle no-op
        }

        @Override
        public List<PluginParameter> getParameters() {
            return List.of(new PluginParameter(1, "Gain", 0.0, 1.0, 0.5));
        }

        @Override
        public PluginEditorFactory editorFactory() {
            return factory;
        }
    }
}
