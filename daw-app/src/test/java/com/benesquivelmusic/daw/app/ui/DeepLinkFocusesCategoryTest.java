package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 308 §7 Stage 4 — the former standalone entry points deep-link
 * into the one settings surface: invoking the metronome context item
 * opens Settings focused on Recording; the Edit ▸ Backup Retention entry
 * focuses Backups. Behavioural half drives {@code selectCategory};
 * source-scan half pins the live wiring literals.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DeepLinkFocusesCategoryTest {

    @Test
    void selectCategoryFocusesTheRailOnRecordingAndBackups() throws Exception {
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(
                    Story307TestSupport.model("deepLinkFocus"));
            dialog.selectCategory("recording");
            assertThat(dialog.getShell().navRail().selectedCategoryIdProperty().get())
                    .isEqualTo("recording");
            dialog.selectCategory("backups");
            assertThat(dialog.getShell().navRail().selectedCategoryIdProperty().get())
                    .isEqualTo("backups");
            return null;
        });
    }

    @Test
    void formerEntryPointsReachTheDeepLinkLiterals() throws IOException {
        Path module = SourceScanSupport.locateDawAppModule();
        Path ui = module.resolve("src/main/java/com/benesquivelmusic/daw/app/ui");

        // The metronome context item runs an injected opener…
        String metronomeController = Files.readString(
                ui.resolve("MetronomeController.java"), StandardCharsets.UTF_8);
        assertThat(metronomeController)
                .contains("clickRoutingSettingsOpener")
                .doesNotContain("new MetronomeSettingsDialog");
        // …whose deep link (and the backup entry's) lives in MainController.
        String mainController = Files.readString(
                ui.resolve("MainController.java"), StandardCharsets.UTF_8);
        assertThat(mainController)
                .contains("setClickRoutingSettingsOpener")
                .contains("dialog.selectCategory(\"recording\")")
                .contains("dialog.selectCategory(\"backups\")");
    }
}
