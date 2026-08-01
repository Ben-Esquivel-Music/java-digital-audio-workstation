package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingsProfileSheet;

import javafx.scene.control.Button;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 309 — the §5.9 profile sheet's dismissal lifecycle honours §2.7
 * ("closing the surface cancels in-flight work; a late result is
 * discarded, never applied"). Two guarantees, both dialog-level:
 * dismissing the sheet deadens its I/O — an import driven on the old
 * reference afterwards never reaches the model — and because a closed
 * {@code SettingsProfileIO} never restarts,
 * {@code SettingsDialog.hideProfileSheet()} nulls the field so the next
 * {@code showProfileSheet()} mounts a FRESH sheet rather than the dead
 * disposed one.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProfileSheetDismissalCancelsIoTest {

    @Test
    void hideThenReshowShouldMountAFreshSheet() throws Exception {
        SettingsModel model = Story307TestSupport.model("profileSheetHideReshow");
        SettingsDialog dialog = Story307TestSupport.onFx(() -> {
            SettingsDialog created = new SettingsDialog(model);
            created.showProfileSheet();
            return created;
        });
        try {
            SettingsProfileSheet first = Story307TestSupport.onFx(() ->
                    (SettingsProfileSheet) dialog.getDialogPane()
                            .lookup(".settings-profile-sheet-overlay"));
            assertThat(first)
                    .as("showProfileSheet mounted the sheet in-surface")
                    .isNotNull();

            Story307TestSupport.onFx(() -> {
                dialog.hideProfileSheet();
                assertThat(dialog.getDialogPane()
                        .lookup(".settings-profile-sheet-overlay"))
                        .as("hideProfileSheet unmounted the sheet")
                        .isNull();
                return null;
            });

            SettingsProfileSheet second = Story307TestSupport.onFx(() -> {
                dialog.showProfileSheet();
                return (SettingsProfileSheet) dialog.getDialogPane()
                        .lookup(".settings-profile-sheet-overlay");
            });
            assertThat(second)
                    .as("re-showing mounted a sheet again")
                    .isNotNull();
            assertThat(second)
                    .as("the reopened sheet is a FRESH instance — a closed "
                            + "profile I/O task never restarts, so remounting "
                            + "the disposed sheet would leave Import/Export "
                            + "dead")
                    .isNotSameAs(first);
        } finally {
            Story307TestSupport.onFx(() -> {
                dialog.hideProfileSheet();
                return null;
            });
        }
    }

    @Test
    void dismissalShouldDeadenAnImportDrivenOnTheOldSheet(
            @TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("studio.dawgprofile");
        Files.writeString(file, """
                # dawg-settings-profile v1
                application|appearance.uiScale=1.75
                """, StandardCharsets.UTF_8);

        SettingsModel model = Story307TestSupport.model("profileSheetDismissIo");
        double uiScaleBefore = model.getUiScale();
        assertThat(uiScaleBefore)
                .as("precondition: 1.75 is a NON-default uiScale")
                .isNotEqualTo(1.75);

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

        // The Close button routes through the sheet's private dismiss()
        // — the same path a scrim click takes.
        Story307TestSupport.onFx(() -> {
            Button close = (Button) sheet.lookup("#settings-profile-close");
            assertThat(close).as("the sheet carries its Close button").isNotNull();
            close.fire();
            assertThat(dialog.getDialogPane()
                    .lookup(".settings-profile-sheet-overlay"))
                    .as("dismissal unmounted the sheet from the dialog")
                    .isNull();
            return null;
        });

        // Drive an import on the OLD, dismissed reference — §2.7 says
        // this must never be applied.
        Story307TestSupport.onFx(() -> {
            sheet.importFrom(file);
            return null;
        });

        // A closed SettingsProfileIO returns from startWork synchronously,
        // so nothing should ever arrive; the bounded poll exists only to
        // catch a regression where the import actually proceeds (a local
        // one-line file completes well inside 500 ms).
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (System.nanoTime() < deadline) {
            assertThat(model.getUiScale())
                    .as("the dismissed sheet's import never reaches the model")
                    .isEqualTo(uiScaleBefore);
            Thread.sleep(25);
        }

        Story307TestSupport.onFx(() -> {
            assertThat(sheet.lookup(".settings-profile-progress").isVisible())
                    .as("the dead import never showed progress — the closed "
                            + "task dispatches nothing")
                    .isFalse();
            return null;
        });
        assertThat(model.getUiScale())
                .as("the model still holds its pre-dismissal value")
                .isEqualTo(uiScaleBefore);
    }
}
