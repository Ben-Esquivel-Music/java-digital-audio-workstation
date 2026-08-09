package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.BackupSettingsAccess;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/** Story 313 coverage for the wizard's canonical SettingsShell restart signal. */
@ExtendWith(JavaFxToolkitExtension.class)
class FirstRunWizardApplyEditsTest {

    @Test
    void changedBackendReturnsTheCanonicalRestartBanner() {
        runOnFxThread(() -> {
            SettingsModel model = Story307TestSupport.model("wizardChangedBackend");
            String changedBackend = model.getAudioBackend().equals("ASIO")
                    ? "Java Sound" : "ASIO";

            Optional<String> notice = MainController.applyWizardEdits(
                    new SettingsDialog(model), Map.of("audio.backend", changedBackend));

            assertThat(notice).hasValueSatisfying(message -> assertThat(message)
                    .contains("Restart required")
                    .contains("Audio backend")
                    .contains("relaunch"));
            assertThat(model.getAudioBackend()).isEqualTo(changedBackend);
            return null;
        });
    }

    @Test
    void unchangedBackendDoesNotInventARestartNotice() {
        runOnFxThread(() -> {
            SettingsModel model = Story307TestSupport.model("wizardUnchangedBackend");

            Optional<String> notice = MainController.applyWizardEdits(
                    new SettingsDialog(model),
                    Map.of("audio.backend", model.getAudioBackend()));

            assertThat(notice).isEmpty();
            return null;
        });
    }

    @Test
    void hiddenSettingsDialogDetachesBackupWorkAfterApply() {
        runOnFxThread(() -> {
            SettingsModel model = Story307TestSupport.model("wizardBackupCleanup");
            SettingsDialog dialog = new SettingsDialog(model);
            int attachedKeepRecent = BackupRetentionPolicy.DEFAULT.keepRecent() + 7;
            dialog.setBackupSettingsAccess(new BackupSettingsAccess() {
                @Override
                public BackupRetentionPolicy currentPolicy() {
                    return BackupRetentionPolicy.DEFAULT
                            .withKeepRecent(attachedKeepRecent);
                }

                @Override
                public Optional<Path> projectDirectory() {
                    return Optional.empty();
                }

                @Override
                public void applyPolicy(BackupRetentionPolicy policy) {
                    throw new AssertionError("no backup edits should be applied");
                }
            });
            assertThat(dialog.getShell().settingRow("backups.keepRecent")
                    .orElseThrow().getValue()).isEqualTo(attachedKeepRecent);

            MainController.applyWizardEdits(dialog, Map.of());

            assertThat(dialog.getShell().settingRow("backups.keepRecent")
                    .orElseThrow().getValue())
                    .as("detaching backup access re-seeds the hidden dialog from defaults")
                    .isEqualTo(BackupRetentionPolicy.DEFAULT.keepRecent());
            return null;
        });
    }
}
