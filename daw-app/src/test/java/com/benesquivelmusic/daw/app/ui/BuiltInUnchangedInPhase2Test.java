package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.BusCompressorPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;

import javafx.scene.Node;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 301 acceptance — Plugin View Design Book §2.3 / §8.2.1: built-in
 * plugins are untouched in this phase.
 *
 * <p>{@code PluginViewController#openEditor(DawPlugin)} dispatches through
 * the legacy built-in {@code switch} first (the documented §2.3 internal
 * escape hatch, kept byte-for-byte until the built-ins migrate in story
 * 302). Activating a {@code BuiltInDawPlugin} whose id IS in the switch —
 * here {@link BusCompressorPlugin#PLUGIN_ID} — must still route to the
 * legacy {@code openBusCompressorWindow(...)} floating-Stage path, and must
 * NOT open the new story-301 contract editor:</p>
 *
 * <ul>
 *   <li>the {@code showEditorInWorkshopPane} dep is never invoked and no
 *       contract-editor session is created
 *       ({@code activeEditorSessionForTest()} stays {@code null});</li>
 *   <li>a showing {@link Stage} titled {@code "Bus Compressor"} exists —
 *       the JavaFX 26 headless Glass platform (the repo's standard test
 *       environment) supports real Stages, so the legacy window is
 *       directly observable.</li>
 * </ul>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class BuiltInUnchangedInPhase2Test {

    private static final String LEGACY_WINDOW_TITLE = "Bus Compressor";

    private PluginViewController controller;
    private BusCompressorPlugin plugin;

    @Test
    void busCompressorStillRoutesToLegacyFloatingWindowNotContractFrame() {
        runOnFxThread(() -> {
            plugin = new BusCompressorPlugin();
            plugin.initialize(new PluginContext() {
                @Override public double getSampleRate() { return 48_000.0; }
                @Override public int getBufferSize() { return 512; }
                @Override public void log(String message) { }
            });
            plugin.activate();

            List<List<String>> workshopInvocations = new ArrayList<>();
            controller = new PluginViewController(deps(
                    (segments, node) -> workshopInvocations.add(segments)));

            controller.openEditor(plugin);

            assertThat(workshopInvocations)
                    .as("a switch-known built-in must never reach the "
                            + "story-301 contract path — the Workshop-pane "
                            + "consumer stays uninvoked (§2.3 escape hatch)")
                    .isEmpty();
            assertThat(controller.activeEditorSessionForTest())
                    .as("no contract-editor session is created for a "
                            + "legacy-routed built-in")
                    .isNull();
            assertThat(showingLegacyStages())
                    .as("the legacy 'Bus Compressor' floating Stage opened "
                            + "(pre-301 behaviour, unchanged this phase)")
                    .isNotEmpty();
            return null;
        });
    }

    @AfterEach
    void cleanup() {
        runOnFxThread(() -> {
            if (controller != null) {
                // dispose() hides the stage; its onHidden handler disposes
                // the view and deactivates the plugin.
                controller.dispose();
                controller = null;
            }
            // Best-effort: if dispose alone left the window showing, hide it
            // directly so the shared toolkit fork stays clean.
            for (Stage lingering : showingLegacyStages()) {
                lingering.hide();
            }
            assertThat(showingLegacyStages())
                    .as("no lingering 'Bus Compressor' window remains showing")
                    .isEmpty();
            if (plugin != null) {
                // The plugin is test-constructed (not routed through the
                // controller's built-in cache), so the test owns disposal.
                plugin.dispose();
                plugin = null;
            }
            return null;
        });
    }

    // ── Harness ───────────────────────────────────────────────────────────

    /** Minimal live {@code Deps}: fixed audio facts and no-op sinks. */
    private static PluginViewController.Deps deps(
            BiConsumer<List<String>, Node> showEditorInWorkshopPane) {
        return new PluginViewController.Deps(
                () -> 48_000.0,
                () -> 512,
                () -> null,
                () -> { },
                () -> { },
                (status, icon) -> { },
                (level, message) -> { },
                () -> { },
                showEditorInWorkshopPane,
                () -> null,
                () -> { });
    }

    /**
     * All currently-showing Stages titled {@value #LEGACY_WINDOW_TITLE}.
     * FX thread only ({@link Window#getWindows()} is an FX observable list;
     * copied before filtering so a concurrent hide cannot break iteration).
     */
    private static List<Stage> showingLegacyStages() {
        List<Stage> stages = new ArrayList<>();
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window instanceof Stage stage && stage.isShowing()
                    && LEGACY_WINDOW_TITLE.equals(stage.getTitle())) {
                stages.add(stage);
            }
        }
        return stages;
    }
}
