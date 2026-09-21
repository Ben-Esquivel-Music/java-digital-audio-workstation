package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.plugin.CompressorPlugin;
import com.benesquivelmusic.daw.core.plugin.ParametricEqPlugin;
import com.benesquivelmusic.daw.core.plugin.ReverbPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/** Story 320 supersedes story 302's special mastering-view route. */
@ExtendWith(JavaFxToolkitExtension.class)
final class MasteringRouteArmsDroppedTest {
    @Test
    void everyMasteringStageInsertsAndOpensItsContractEditor() {
        runOnFxThread(() -> {
            var channel = new MixerChannel("Selected track");
            var controller = PluginSignalPathActivationTest.controller();
            PluginSignalPathActivationTest.configure(controller, channel);
            try {
                controller.onActivateBuiltInPlugin(ParametricEqPlugin.class);
                controller.onActivateBuiltInPlugin(CompressorPlugin.class);
                controller.onActivateBuiltInPlugin(ReverbPlugin.class);
                assertThat(channel.getInsertSlots()).hasSize(3);
                assertThat(controller.activeEditorSessionForTest()).isNotNull();
                assertThat(controller.activeEditorSessionForTest().frame().getPluginName())
                        .isEqualTo(channel.getInsertSlots().getLast().getName());
            } finally { controller.dispose(); }
            return null;
        });
    }
}
