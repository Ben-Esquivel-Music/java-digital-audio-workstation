package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every persisted audio preference is visible in the unified Audio category. */
@ExtendWith(JavaFxToolkitExtension.class)
class AllAudioSettingsHaveRowsTest {

    @Test
    void audioGroupsHaveTheDesignBookOrderAndEveryAudioKeyHasOneRow() throws Exception {
        SettingsCatalogue.Category audio = SettingsCatalogue.create().categories().stream()
                .filter(category -> category.id().equals("audio"))
                .findFirst().orElseThrow();

        assertThat(audio.groups()).extracting(SettingsCatalogue.Group::id)
                .containsExactly("format", "device", "performance");
        assertThat(ids(audio.groups().get(0))).containsExactly(
                "audio.sampleRate", "audio.bitDepth", "audio.bufferSize",
                "audio.mixPrecision");
        assertThat(ids(audio.groups().get(1))).containsExactly(
                "audio.backend", "audio.inputDevice", "audio.outputDevice",
                "audio.applyLatencyCompensation");
        assertThat(ids(audio.groups().get(2))).containsExactly(
                "audio.srcQuality", "audio.workerPoolSize",
                "audio.masterCpuBudgetFraction", "audio.masterCpuBudgetPolicy");

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(
                    Story307TestSupport.model("allAudioSettingsHaveRows"));
            assertThat(List.of(
                    "audio.mixPrecision", "audio.backend", "audio.inputDevice",
                    "audio.outputDevice", "audio.applyLatencyCompensation",
                    "audio.srcQuality", "audio.workerPoolSize",
                    "audio.masterCpuBudgetFraction", "audio.masterCpuBudgetPolicy"))
                    .allSatisfy(id -> assertThat(dialog.getShell().settingRow(id)).isPresent());
            return null;
        });
    }

    private static List<String> ids(SettingsCatalogue.Group group) {
        return group.settings().stream().map(setting -> setting.id()).toList();
    }
}
