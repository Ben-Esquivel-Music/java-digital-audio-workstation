package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin;
import com.benesquivelmusic.daw.core.plugin.BusCompressorPlugin;
import com.benesquivelmusic.daw.core.plugin.CompressorPlugin;
import com.benesquivelmusic.daw.core.plugin.DitherPlugin;
import com.benesquivelmusic.daw.core.plugin.ParametricEqPlugin;
import com.benesquivelmusic.daw.core.plugin.ReverbPlugin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 302 — the three mastering-route {@code switch} arms are dropped, not
 * migrated. Activating {@code ParametricEqPlugin} / {@code CompressorPlugin} /
 * {@code ReverbPlugin} no longer dispatches through a {@code switch} arm and
 * does not build a contract editor: the plugins DECLARE their routing
 * ({@link BuiltInDawPlugin#routesToMasteringView()}) and reach the mastering
 * view through the surviving non-{@code switch} activation path — no plugin id
 * drives behaviour (§9 rejection #5).
 *
 * <p>Deliberately toolkit-free: the mastering route must complete without
 * constructing any editor node, so a regression that falls through to the
 * contract-editor path surfaces here as a missing mastering hop plus an
 * activation-failure notification (the JavaFX toolkit is not initialised in
 * this test, so any accidental {@code EditorFrame} construction throws into
 * the controller's failure handler).</p>
 */
final class MasteringRouteArmsDroppedTest {

    @Test
    void masteringChainStagesDeclareTheirRouting() {
        assertThat(new ParametricEqPlugin().routesToMasteringView()).isTrue();
        assertThat(new CompressorPlugin().routesToMasteringView()).isTrue();
        assertThat(new ReverbPlugin().routesToMasteringView()).isTrue();
        // Ordinary built-ins keep the default and open contract editors.
        assertThat(new DitherPlugin().routesToMasteringView()).isFalse();
        assertThat(new BusCompressorPlugin().routesToMasteringView()).isFalse();
    }

    @Test
    void activatingAMasteringPluginRoutesToTheMasteringViewWithoutAnEditor() {
        AtomicInteger masteringHops = new AtomicInteger();
        AtomicInteger workshopPaneUpdates = new AtomicInteger();
        List<String> notifications = new ArrayList<>();

        PluginViewController controller = new PluginViewController(
                new PluginViewController.Deps(
                        () -> 48_000.0,
                        () -> 512,
                        () -> null,
                        () -> { },
                        masteringHops::incrementAndGet,
                        (status, icon) -> { },
                        (level, message) -> notifications.add(level + ": " + message),
                        (segments, node) -> workshopPaneUpdates.incrementAndGet(),
                        () -> null,
                        () -> { }));

        controller.onActivateBuiltInPlugin(ParametricEqPlugin.class);
        controller.onActivateBuiltInPlugin(CompressorPlugin.class);
        controller.onActivateBuiltInPlugin(ReverbPlugin.class);

        assertThat(masteringHops.get())
                .as("each mastering-chain activation must hop to the mastering view")
                .isEqualTo(3);
        assertThat(workshopPaneUpdates.get())
                .as("no contract editor may be placed for a mastering-route plugin")
                .isZero();
        assertThat(controller.activeEditorSessionForTest())
                .as("no editor session may be opened for a mastering-route plugin")
                .isNull();
        assertThat(notifications)
                .as("the mastering route must succeed without activation failures")
                .isEmpty();

        controller.dispose();
    }
}
