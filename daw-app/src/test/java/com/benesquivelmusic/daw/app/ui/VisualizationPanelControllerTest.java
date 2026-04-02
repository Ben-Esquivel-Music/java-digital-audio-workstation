package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.VisualizationPreferences.DisplayTile;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(JavaFxToolkitExtension.class)
class VisualizationPanelControllerTest {

    private Preferences prefs;
    private VisualizationPreferences vizPrefs;

    @BeforeEach
    void setUp() {
        prefs = Preferences.userRoot().node("vizPanelTest_" + System.nanoTime());
        vizPrefs = new VisualizationPreferences(prefs);
    }

    private <T> T runOnFxThread(java.util.concurrent.Callable<T> callable) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(callable.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        if (error.get() != null) {
            throw error.get();
        }
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    private VisualizationPanelController createController() throws Exception {
        return runOnFxThread(() -> {
            HBox vizTileRow = new HBox();
            Button button = new Button("Visualizations");
            Map<DisplayTile, Node> tileLookup = new EnumMap<>(DisplayTile.class);
            for (DisplayTile tile : DisplayTile.values()) {
                VBox tileNode = new VBox();
                tileLookup.put(tile, tileNode);
            }
            VisualizationPanelController controller = new VisualizationPanelController(
                    vizTileRow, button, vizPrefs, tileLookup);
            controller.initialize();
            return controller;
        });
    }

    @Test
    void shouldRejectNullVizTileRow() throws Exception {
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new VisualizationPanelController(
                    null, new Button(), vizPrefs, new EnumMap<>(DisplayTile.class)))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullButton() throws Exception {
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new VisualizationPanelController(
                    new HBox(), null, vizPrefs, new EnumMap<>(DisplayTile.class)))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullPreferences() throws Exception {
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new VisualizationPanelController(
                    new HBox(), new Button(), null, new EnumMap<>(DisplayTile.class)))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullTileLookup() throws Exception {
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new VisualizationPanelController(
                    new HBox(), new Button(), vizPrefs, null))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldInitializeWithRowVisible() throws Exception {
        VisualizationPanelController controller = runOnFxThread(() -> {
            HBox vizTileRow = new HBox();
            Button button = new Button("Visualizations");
            Map<DisplayTile, Node> tileLookup = new EnumMap<>(DisplayTile.class);
            for (DisplayTile tile : DisplayTile.values()) {
                tileLookup.put(tile, new VBox());
            }
            VisualizationPanelController c = new VisualizationPanelController(
                    vizTileRow, button, vizPrefs, tileLookup);
            c.initialize();
            assertThat(vizTileRow.isVisible()).isTrue();
            assertThat(vizTileRow.isManaged()).isTrue();
            return c;
        });
        assertThat(controller).isNotNull();
    }

    @Test
    void shouldInitializeWithRowHiddenWhenPrefFalse() throws Exception {
        vizPrefs.setRowVisible(false);
        runOnFxThread(() -> {
            HBox vizTileRow = new HBox();
            Button button = new Button("Visualizations");
            Map<DisplayTile, Node> tileLookup = new EnumMap<>(DisplayTile.class);
            for (DisplayTile tile : DisplayTile.values()) {
                tileLookup.put(tile, new VBox());
            }
            VisualizationPanelController c = new VisualizationPanelController(
                    vizTileRow, button, vizPrefs, tileLookup);
            c.initialize();
            assertThat(vizTileRow.isVisible()).isFalse();
            assertThat(vizTileRow.isManaged()).isFalse();
        });
    }

    @Test
    void shouldToggleRowVisibility() throws Exception {
        runOnFxThread(() -> {
            HBox vizTileRow = new HBox();
            Button button = new Button("Visualizations");
            Map<DisplayTile, Node> tileLookup = new EnumMap<>(DisplayTile.class);
            for (DisplayTile tile : DisplayTile.values()) {
                tileLookup.put(tile, new VBox());
            }
            VisualizationPanelController c = new VisualizationPanelController(
                    vizTileRow, button, vizPrefs, tileLookup);
            c.initialize();

            // Toggle off
            c.toggleRowVisibility();
            assertThat(vizPrefs.isRowVisible()).isFalse();

            // Toggle back on
            c.toggleRowVisibility();
            assertThat(vizPrefs.isRowVisible()).isTrue();
        });
    }

    @Test
    void shouldHideTileWhenPrefSetBeforeInit() throws Exception {
        vizPrefs.setTileVisible(DisplayTile.SPECTRUM, false);
        runOnFxThread(() -> {
            HBox vizTileRow = new HBox();
            Button button = new Button("Visualizations");
            Map<DisplayTile, Node> tileLookup = new EnumMap<>(DisplayTile.class);
            VBox spectrumNode = new VBox();
            tileLookup.put(DisplayTile.SPECTRUM, spectrumNode);
            for (DisplayTile tile : DisplayTile.values()) {
                if (tile != DisplayTile.SPECTRUM) {
                    tileLookup.put(tile, new VBox());
                }
            }
            VisualizationPanelController c = new VisualizationPanelController(
                    vizTileRow, button, vizPrefs, tileLookup);
            c.initialize();
            assertThat(spectrumNode.isVisible()).isFalse();
            assertThat(spectrumNode.isManaged()).isFalse();
        });
    }

    @Test
    void shouldAddActiveStyleWhenRowVisible() throws Exception {
        runOnFxThread(() -> {
            HBox vizTileRow = new HBox();
            Button button = new Button("Visualizations");
            Map<DisplayTile, Node> tileLookup = new EnumMap<>(DisplayTile.class);
            for (DisplayTile tile : DisplayTile.values()) {
                tileLookup.put(tile, new VBox());
            }
            VisualizationPanelController c = new VisualizationPanelController(
                    vizTileRow, button, vizPrefs, tileLookup);
            c.initialize();
            assertThat(button.getStyleClass()).contains("toolbar-button-active");
        });
    }

    @Test
    void shouldRemoveActiveStyleWhenRowHidden() throws Exception {
        vizPrefs.setRowVisible(false);
        runOnFxThread(() -> {
            HBox vizTileRow = new HBox();
            Button button = new Button("Visualizations");
            Map<DisplayTile, Node> tileLookup = new EnumMap<>(DisplayTile.class);
            for (DisplayTile tile : DisplayTile.values()) {
                tileLookup.put(tile, new VBox());
            }
            VisualizationPanelController c = new VisualizationPanelController(
                    vizTileRow, button, vizPrefs, tileLookup);
            c.initialize();
            assertThat(button.getStyleClass()).doesNotContain("toolbar-button-active");
        });
    }

    @Test
    void shouldBuildContextMenu() throws Exception {
        runOnFxThread(() -> {
            HBox vizTileRow = new HBox();
            Button button = new Button("Visualizations");
            Map<DisplayTile, Node> tileLookup = new EnumMap<>(DisplayTile.class);
            for (DisplayTile tile : DisplayTile.values()) {
                tileLookup.put(tile, new VBox());
            }
            VisualizationPanelController c = new VisualizationPanelController(
                    vizTileRow, button, vizPrefs, tileLookup);
            c.initialize();

            // Verify the context menu getter returns null before the menu is opened
            assertThat(c.getContextMenu()).isNull();

            // Verify button has onContextMenuRequested handler set (non-null)
            assertThat(button.getOnContextMenuRequested()).isNotNull();
        });
    }
}
