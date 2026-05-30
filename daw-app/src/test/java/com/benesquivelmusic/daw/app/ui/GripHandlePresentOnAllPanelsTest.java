package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.DockableVisualizationPanel;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.PanelGripHandle;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 288 — {@code GripHandlePresentOnAllPanelsTest}. Every self-owned
 * {@code Dockable} panel must expose a {@link PanelGripHandle} in its
 * chrome so the user can detach / re-dock it by direct manipulation.
 *
 * <p>The panels extend {@code VBox} and build {@code DawgIcon}s /
 * {@code Tooltip}s, so they need the started toolkit and the {@code
 * …app.ui} package ({@code project_extendwith_jpms_test_env.md}); they are
 * constructed on the FX thread, mirroring {@code DockableImplementationTest}.
 * The presence check is a manual recursive {@code getChildrenUnmodifiable()}
 * walk — a CSS {@code lookup(".dock-grip")} would need a {@code Scene}
 * ({@code feedback_javafx_headless_test_pitfalls.md}).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class GripHandlePresentOnAllPanelsTest {

    @Test
    void mixerViewHasGripHandle() throws Exception {
        DawProject project = new DawProject("test", AudioFormat.STUDIO_QUALITY);
        Region view = createOnFxThread(() -> new MixerView(project));
        assertHasGrip(view);
    }

    @Test
    void browserPanelHasGripHandle() throws Exception {
        Region view = createOnFxThread(BrowserPanel::new);
        assertHasGrip(view);
    }

    @Test
    void editorViewHasGripHandle() throws Exception {
        Region view = createOnFxThread(EditorView::new);
        assertHasGrip(view);
    }

    @Test
    void masteringViewHasGripHandle() throws Exception {
        Region view = createOnFxThread(MasteringView::new);
        assertHasGrip(view);
    }

    @Test
    void dockableVisualizationPanelHasGripHandle() throws Exception {
        Region view = createOnFxThread(() -> new DockableVisualizationPanel(
                DefaultWorkspaces.PANEL_SPECTRUM,
                "Spectrum",
                "SPECTRUM",
                DockZone.BOTTOM,
                DawIcon.WAVEFORM,
                null,
                new Region())); // dummy analyzer content
        assertHasGrip(view);
    }

    /** Asserts at least one descendant of {@code root} is a grip handle. */
    private static void assertHasGrip(Region root) {
        assertThat(containsGrip(root))
                .as("panel chrome exposes a PanelGripHandle")
                .isTrue();
    }

    /** Headless-safe recursive node-tree walk (no Scene, no CSS lookup). */
    private static boolean containsGrip(Node node) {
        if (node instanceof PanelGripHandle) {
            return true;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (containsGrip(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private interface FxSupplier<T> {
        T get();
    }

    private static <T> T createOnFxThread(FxSupplier<T> supplier) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(supplier.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread did not respond within 5s");
        }
        if (err.get() != null) {
            throw new RuntimeException("Failed to construct on FX thread", err.get());
        }
        return ref.get();
    }
}
