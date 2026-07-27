package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;

import javafx.scene.control.Button;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/** Realized-scene acceptance for the visible Audio category affordances. */
@ExtendWith(JavaFxToolkitExtension.class)
class AudioSettingsRenderedAcceptanceTest {

    @Test
    void audioRowsBadgesAndGroupResetButtonsAreRealizedAndInteractive() throws Exception {
        Story307TestSupport.onFx(() -> {
            SettingsModel model = Story307TestSupport.model("audioRenderedAcceptance");
            SettingsDialog dialog = new SettingsDialog(model);
            SettingsShell shell = dialog.getShell();
            dialog.selectCategory("audio");
            dialog.show();
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();

            SettingsCatalogue.create().categories().stream()
                    .filter(category -> "audio".equals(category.id()))
                    .findFirst().orElseThrow().groups().stream()
                    .flatMap(group -> group.settings().stream())
                    .forEach(descriptor -> {
                        var row = shell.settingRow(descriptor.id()).orElseThrow();
                        assertThat(row.getParent())
                                .as(descriptor.id() + " is parented in the Audio pane")
                                .isNotNull();
                        assertThat(row.getScene()).isNotNull();
                    });
            assertThat(shell.lookupAll(".setting-row-badge.badge-engine")).isNotEmpty();
            assertThat(shell.lookupAll(".setting-row-badge.badge-restart")).isNotEmpty();
            assertThat(shell.lookup("#reset-group-audio-device")).isInstanceOf(Button.class);
            assertThat(shell.lookup("#reset-group-audio-format")).isInstanceOf(Button.class);
            Button resetPerformance = (Button) shell.lookup(
                    "#reset-group-audio-performance");
            assertThat(resetPerformance).isNotNull();

            var srcQuality = shell.settingRow("audio.srcQuality").orElseThrow();
            QualityTier edited = srcQuality.getValue() == QualityTier.LOW
                    ? QualityTier.HIGH : QualityTier.LOW;
            srcQuality.setValue(edited);
            resetPerformance.fire();
            assertThat(srcQuality.getValue()).isEqualTo(srcQuality.descriptor().defaultValue());
            dialog.hide();
            return null;
        });
    }
}
