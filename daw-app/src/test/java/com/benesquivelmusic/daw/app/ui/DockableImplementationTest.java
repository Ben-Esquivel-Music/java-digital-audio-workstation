package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 285 — every top-level panel must implement {@link Dockable}
 * directly (no inner-record adapters) with the contract values the story
 * specifies. The Dockable methods are pure and inspector-friendly; the
 * panels still need the JavaFX toolkit because they extend {@code VBox} /
 * {@code ScrollPane}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DockableImplementationTest {

    @Test
    void mixerViewImplementsDockable() throws Exception {
        DawProject project = new DawProject("test", AudioFormat.STUDIO_QUALITY);
        MixerView view = createOnFxThread(() -> new MixerView(project));
        assertThat(view).isInstanceOf(Dockable.class);
        Dockable d = view;
        assertThat(d.dockId()).isEqualTo(DefaultWorkspaces.PANEL_MIXER);
        assertThat(d.displayName()).isEqualTo("Mixer");
        assertThat(d.iconName()).isEqualTo("MIXER");
        assertThat(d.preferredZone()).isEqualTo(DockZone.BOTTOM);
    }

    @Test
    void browserPanelImplementsDockable() throws Exception {
        BrowserPanel view = createOnFxThread(BrowserPanel::new);
        assertThat(view).isInstanceOf(Dockable.class);
        Dockable d = view;
        assertThat(d.dockId()).isEqualTo(DefaultWorkspaces.PANEL_BROWSER);
        assertThat(d.displayName()).isEqualTo("Browser");
        assertThat(d.iconName()).isEqualTo("BROWSER");
        assertThat(d.preferredZone()).isEqualTo(DockZone.LEFT);
    }

    @Test
    void editorViewImplementsDockable() throws Exception {
        EditorView view = createOnFxThread(EditorView::new);
        assertThat(view).isInstanceOf(Dockable.class);
        Dockable d = view;
        assertThat(d.dockId()).isEqualTo(DefaultWorkspaces.PANEL_EDITOR);
        assertThat(d.displayName()).isEqualTo("Editor");
        assertThat(d.iconName()).isEqualTo("EDITOR");
        assertThat(d.preferredZone()).isEqualTo(DockZone.CENTER);
    }

    @Test
    void masteringViewImplementsDockable() throws Exception {
        MasteringView view = createOnFxThread(MasteringView::new);
        assertThat(view).isInstanceOf(Dockable.class);
        Dockable d = view;
        assertThat(d.dockId()).isEqualTo(DefaultWorkspaces.PANEL_MASTERING);
        assertThat(d.displayName()).isEqualTo("Mastering");
        assertThat(d.iconName()).isEqualTo("MASTERING");
        assertThat(d.preferredZone()).isEqualTo(DockZone.CENTER);
    }

    // TelemetrySetupPanel Dockable contract intentionally deferred — see
    // TelemetrySetupPanel.java: the panel is owned by TelemetryView (plugin
    // view) and not yet registered as a top-level dock surface, so the
    // contract isn't published until there is a consumer.

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
