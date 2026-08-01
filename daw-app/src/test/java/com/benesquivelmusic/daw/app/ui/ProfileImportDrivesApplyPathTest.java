package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingsProfileSheet;

import javafx.scene.Node;
import javafx.scene.control.Label;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 309 §6.3 — the COMPOSED import-apply loop: the §5.9 sheet's
 * import outcome flows through {@code SettingsDialog.showProfileSheet()}'s
 * apply callback (seed shell rows as pending edits →
 * {@code applySettings()}) into the PERSISTED {@link SettingsModel} —
 * "applies only the valid ones". Exercises the REAL
 * {@code startImport(Path, Set)} file read with the production
 * {@code FxDispatcher} (complementing {@code ProfileIoOffFxThreadTest}'s
 * seam-level coverage): a real profile file with two non-default valid
 * values persists both, while a validator-rejected entry (tempo guard
 * 20..999) is NOT applied and IS surfaced in the sheet UI.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProfileImportDrivesApplyPathTest {

    @Test
    void importedProfileShouldPersistValidValuesAndSurfaceTheReject(
            @TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("studio.dawgprofile");
        Files.writeString(file, """
                # dawg-settings-profile v1
                application|appearance.uiScale=1.75
                application|project.useJournaledPersistence=true
                projectDefaults|project.defaultTempo=5000
                """, StandardCharsets.UTF_8);

        SettingsModel model = Story307TestSupport.model("profileImportApplyPath");
        double defaultTempo = model.getDefaultTempo();
        assertThat(model.getUiScale())
                .as("precondition: 1.75 is a NON-default uiScale")
                .isNotEqualTo(1.75);
        assertThat(model.isUseJournaledPersistence())
                .as("precondition: journaled persistence defaults off")
                .isFalse();

        SettingsDialog dialog = Story307TestSupport.onFx(() -> {
            SettingsDialog created = new SettingsDialog(model);
            created.showProfileSheet();
            return created;
        });
        SettingsProfileSheet sheet = Story307TestSupport.onFx(() ->
                (SettingsProfileSheet) dialog.getDialogPane()
                        .lookup(".settings-profile-sheet-overlay"));
        assertThat(sheet)
                .as("showProfileSheet mounted the sheet in-surface")
                .isNotNull();

        try {
            Story307TestSupport.onFx(() -> {
                sheet.importFrom(file);
                return null;
            });

            // The worker reads the file, the outcome crosses the REAL
            // FxDispatcher, the apply callback seeds the shell rows and
            // runs applySettings() — wait for PERSISTENCE, the end of
            // the composed loop.
            waitUntil(() -> model.getUiScale() == 1.75
                    && model.isUseJournaledPersistence());

            assertThat(model.getUiScale())
                    .as("valid appearance.uiScale PERSISTED through applySettings()")
                    .isEqualTo(1.75);
            assertThat(model.isUseJournaledPersistence())
                    .as("valid project.useJournaledPersistence PERSISTED")
                    .isTrue();
            assertThat(model.getDefaultTempo())
                    .as("the validator-rejected tempo (guard 20..999) was NOT applied")
                    .isCloseTo(defaultTempo, within(0.001));

            Story307TestSupport.onFx(() -> {
                assertThat(dialog.getShell().pendingEdits())
                        .as("the loop drove the real Apply path — nothing "
                                + "is left pending")
                        .isEmpty();
                Node reject = sheet.lookup(".settings-profile-reject-warn");
                assertThat(reject)
                        .as("the validator reject is surfaced in the sheet UI "
                                + "with the -warn severity")
                        .isInstanceOf(Label.class);
                assertThat(((Label) reject).getText())
                        .contains("project.defaultTempo");
                return null;
            });
        } finally {
            Story307TestSupport.onFx(() -> {
                sheet.dispose();
                return null;
            });
        }
    }

    /** Polls {@code condition} up to ten seconds; returns as soon as it holds. */
    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !condition.getAsBoolean()) {
            Thread.sleep(10);
        }
    }
}
