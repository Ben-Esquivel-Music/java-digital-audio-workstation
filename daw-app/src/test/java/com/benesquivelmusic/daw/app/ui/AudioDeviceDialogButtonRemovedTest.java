package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Rejection-#1 guard: settings never opens a nested audio-device dialog. */
class AudioDeviceDialogButtonRemovedTest {

    @Test
    void legacyDialogAndNestedLaunchPathAreRemovedAndLiveActionDeepLinks() throws IOException {
        Path module = SourceScanSupport.locateDawAppModule();
        Path ui = module.resolve("src/main/java/com/benesquivelmusic/daw/app/ui");
        Path legacyDialog = ui.resolve("AudioSettingsDialog.java");
        String settings = Files.readString(ui.resolve("SettingsDialog.java"),
                StandardCharsets.UTF_8);
        String mainController = Files.readString(ui.resolve("MainController.java"),
                StandardCharsets.UTF_8);

        assertThat(legacyDialog).doesNotExist();
        assertThat(settings)
                .doesNotContain("Audio Device Settings…")
                .doesNotContain("openAudioDeviceDialog")
                .doesNotContain("new AudioSettingsDialog");
        assertThat(mainController)
                .doesNotContain("new AudioSettingsDialog")
                .contains("dialog.selectCategory(\"audio\")");
    }
}
