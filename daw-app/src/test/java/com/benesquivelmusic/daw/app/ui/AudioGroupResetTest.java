package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/** Tier-2 reset changes only rows in the requested Audio group. */
@ExtendWith(JavaFxToolkitExtension.class)
class AudioGroupResetTest {

    @Test
    void performanceResetRestoresItsDefaultsWithoutTouchingFormatOrDevice() throws Exception {
        Story307TestSupport.onFx(() -> {
            SettingsModel model = Story307TestSupport.model("audioGroupReset");
            SettingsDialog dialog = new SettingsDialog(model);
            SettingsShell shell = dialog.getShell();

            SettingRow sampleRate = shell.settingRow("audio.sampleRate").orElseThrow();
            SettingRow backend = shell.settingRow("audio.backend").orElseThrow();
            SettingRow src = shell.settingRow("audio.srcQuality").orElseThrow();
            SettingRow workers = shell.settingRow("audio.workerPoolSize").orElseThrow();
            SettingRow budget = shell.settingRow("audio.masterCpuBudgetFraction").orElseThrow();
            SettingRow policy = shell.settingRow("audio.masterCpuBudgetPolicy").orElseThrow();

            double editedRate = model.getSampleRate() == 48_000 ? 44_100 : 48_000;
            String editedBackend = model.getAudioBackend().equals("ASIO")
                    ? "Java Sound" : "ASIO";
            QualityTier editedQuality = model.getSrcQuality() == QualityTier.LOW
                    ? QualityTier.HIGH : QualityTier.LOW;
            sampleRate.setValue(editedRate);
            backend.setValue(editedBackend);
            src.setValue(editedQuality);
            workers.setValue(model.getWorkerPoolSize() == 1 ? 2 : 1);
            budget.setValue(model.getMasterCpuBudgetFraction() == 0.5 ? 0.75 : 0.5);
            policy.setValue("BypassExpensive");

            shell.resetGroup("audio", "performance");

            assertThat(src.getValue()).isEqualTo(src.descriptor().defaultValue());
            assertThat(workers.getValue()).isEqualTo(workers.descriptor().defaultValue());
            assertThat(budget.getValue()).isEqualTo(budget.descriptor().defaultValue());
            assertThat(policy.getValue()).isEqualTo(policy.descriptor().defaultValue());
            assertThat(sampleRate.getValue()).isEqualTo(editedRate);
            assertThat(backend.getValue()).isEqualTo(editedBackend);
            return null;
        });
    }
}
