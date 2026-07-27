package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.ApplyClass;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the honest engine-reconfigure versus restart-required contract. */
@ExtendWith(JavaFxToolkitExtension.class)
class AudioApplyClassesTest {

    @Test
    void engineRowsWarnInFooterWithoutRestartBannerAndBackendNamesRestart() throws Exception {
        SettingsCatalogue catalogue = SettingsCatalogue.create();
        assertThat(catalogue.byId("audio.sampleRate").orElseThrow().applyClass())
                .isEqualTo(ApplyClass.ENGINE_RECONFIGURE);
        assertThat(catalogue.byId("audio.bufferSize").orElseThrow().applyClass())
                .isEqualTo(ApplyClass.ENGINE_RECONFIGURE);
        assertThat(catalogue.byId("audio.srcQuality").orElseThrow().applyClass())
                .isEqualTo(ApplyClass.ENGINE_RECONFIGURE);
        assertThat(catalogue.byId("audio.workerPoolSize").orElseThrow().applyClass())
                .isEqualTo(ApplyClass.ENGINE_RECONFIGURE);
        assertThat(catalogue.byId("audio.backend").orElseThrow().applyClass())
                .isEqualTo(ApplyClass.RESTART_REQUIRED);

        Story307TestSupport.onFx(() -> {
            SettingsModel model = Story307TestSupport.model("audioApplyClasses");
            SettingsDialog dialog = new SettingsDialog(model);
            SettingsShell shell = dialog.getShell();

            assertThat(shell.isRestartBannerVisible()).isFalse();
            assertThat(shell.isFooterStatusVisible()).isFalse();

            int editedBuffer = model.getBufferSize() == 256 ? 512 : 256;
            shell.settingRow("audio.bufferSize").orElseThrow().setValue(editedBuffer);
            assertThat(shell.isFooterStatusVisible()).isTrue();
            assertThat(shell.footerStatusText()).containsIgnoringCase("engine");
            assertThat(shell.isRestartBannerVisible()).isFalse();

            shell.settingRow("audio.bufferSize").orElseThrow().setValue(model.getBufferSize());
            String editedBackend = model.getAudioBackend().equals("ASIO")
                    ? "Java Sound" : "ASIO";
            shell.settingRow("audio.backend").orElseThrow().setValue(editedBackend);
            assertThat(shell.isFooterStatusVisible()).isTrue();
            assertThat(shell.isRestartBannerVisible()).isTrue();
            assertThat(shell.restartBannerText()).contains("Audio backend");
            return null;
        });
    }
}
