package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisor;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the drag-source UI components accept the shared
 * {@link DragVisualAdvisor} setter wired in by {@code MainController}
 * (Story 197 — drag visual feedback integration).
 *
 * <p>The pure-advisor behaviour is exercised by
 * {@link com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisorTest} and
 * {@link com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisorIntegrationTest}.
 * This test asserts only the constructor + setter + accessor wiring of
 * the call-site classes ({@link BrowserPanel}, {@link InsertEffectRack}
 * is covered indirectly via the {@code MixerView} setter, and
 * {@link ClipInteractionController} is package-private so its setter is
 * verified separately in {@code ClipInteractionControllerTest} when
 * present). Firing real JavaFX drag events would require an interactive
 * scene and {@code Stage.show()}, which the project's headless test
 * harness does not support.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DragVisualAdvisorWiringTest {

    private static <T> T runOnFxThread(java.util.function.Supplier<T> action) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(action.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread action timed out");
        }
        if (err.get() != null) {
            throw new RuntimeException(err.get());
        }
        return ref.get();
    }

    @Test
    void browserPanelAcceptsAdvisorSetter() throws Exception {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        BrowserPanel panel = runOnFxThread(BrowserPanel::new);

        runOnFxThread(() -> {
            panel.setDragVisualAdvisor(advisor);
            return null;
        });

        assertThat(panel.getDragVisualAdvisor()).isSameAs(advisor);
    }

    @Test
    void browserPanelToleratesNullAdvisor() throws Exception {
        BrowserPanel panel = runOnFxThread(BrowserPanel::new);

        // No advisor installed — getter should return null and the
        // panel must still behave as a normal JavaFX node.
        assertThat(panel.getDragVisualAdvisor()).isNull();
        runOnFxThread(() -> {
            panel.setDragVisualAdvisor(null);
            return null;
        });
        assertThat(panel.getDragVisualAdvisor()).isNull();
    }

    @Test
    void browserPanelClearsAdvisorWhenSetToNullAfterInstall() throws Exception {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        BrowserPanel panel = runOnFxThread(BrowserPanel::new);

        runOnFxThread(() -> {
            panel.setDragVisualAdvisor(advisor);
            panel.setDragVisualAdvisor(null);
            return null;
        });

        assertThat(panel.getDragVisualAdvisor()).isNull();
    }
}
