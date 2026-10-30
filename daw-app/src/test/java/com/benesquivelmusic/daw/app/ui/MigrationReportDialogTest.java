package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(JavaFxToolkitExtension.class)
class MigrationReportDialogTest {

    private static MigrationReport sampleReport() {
        return new MigrationReport(1, 3, List.of(
                new MigrationReport.AppliedMigration(1, 2, "rename pan-law attribute"),
                new MigrationReport.AppliedMigration(2, 3, "add bed-bus channel-gains")
        ), Instant.now());
    }

    private <T> T onFx(Callable<T> c) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(c.call());
            } catch (Exception e) {
                err.set(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        if (err.get() != null) throw err.get();
        return ref.get();
    }

    @Test
    void titleAndHeaderReflectVersionRange(@TempDir Path projectDir) throws Exception {
        String header = onFx(() -> new MigrationReportDialog(sampleReport(), projectDir).getHeaderText());
        assertThat(header).contains("1").contains("3");
    }

    @Test
    void listsEachAppliedMigration(@TempDir Path projectDir) throws Exception {
        @SuppressWarnings("unchecked")
        ListView<String> list = onFx(() -> {
            MigrationReportDialog dialog = new MigrationReportDialog(sampleReport(), projectDir);
            DialogPane pane = dialog.getDialogPane();
            VBox content = (VBox) pane.getContent();
            return (ListView<String>) content.getChildren().get(1);
        });

        assertThat(list.getItems())
                .containsExactly(
                        "v1 → v2  —  rename pan-law attribute",
                        "v2 → v3  —  add bed-bus channel-gains");
    }

    @Test
    void hasOkButton(@TempDir Path projectDir) throws Exception {
        List<ButtonType> types = onFx(() -> List.copyOf(new MigrationReportDialog(sampleReport(), projectDir)
                .getDialogPane().getButtonTypes()));
        assertThat(types).contains(MigrationReportDialog.OK_BUTTON_TYPE);
        assertThat(MigrationReportDialog.OK_BUTTON_TYPE.getText()).isEqualTo(ButtonType.OK.getText());
        assertThat(MigrationReportDialog.OK_BUTTON_TYPE.getButtonData())
                .as("OK is also the dialog's cancel, so Esc and the close button mean OK")
                .isEqualTo(ButtonBar.ButtonData.CANCEL_CLOSE);
    }

    @Test
    void suppressionCheckboxDisabledWhenNoProjectDir() throws Exception {
        boolean disabled = onFx(() -> {
            MigrationReportDialog dialog = new MigrationReportDialog(sampleReport(), null);
            VBox content = (VBox) dialog.getDialogPane().getContent();
            CheckBox cb = (CheckBox) content.getChildren().get(2);
            return cb.isDisabled();
        });
        assertThat(disabled).isTrue();
    }

    @Test
    void rejectsNullOrEmptyReport(@TempDir Path projectDir) throws Exception {
        Exception caught = onFx(() -> {
            try {
                new MigrationReportDialog(null, projectDir);
                return null;
            } catch (Exception e) {
                return e;
            }
        });
        assertThat(caught).isInstanceOf(IllegalArgumentException.class);

        Exception caught2 = onFx(() -> {
            try {
                new MigrationReportDialog(MigrationReport.noOp(1), projectDir);
                return null;
            } catch (Exception e) {
                return e;
            }
        });
        assertThat(caught2).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void showUnlessSuppressedReturnsFalseForNoOpReport(@TempDir Path projectDir) {
        // Pure helper logic — does not need the FX thread because it
        // short-circuits before constructing a dialog.
        assertThat(MigrationReportDialog.showUnlessSuppressed(MigrationReport.noOp(1), projectDir, false, null))
                .isFalse();
        assertThat(MigrationReportDialog.showUnlessSuppressed(null, projectDir, false, null)).isFalse();
    }

    @Test
    void showUnlessSuppressedReturnsFalseForAReportWhoseStepsAreAllFormatOnly(@TempDir Path projectDir) {
        // Story 330 — a migration that only re-expresses the file format has
        // nothing to review or roll back: no dialog, even though the report
        // was migrated (its backup, history and badge follow wasMigrated()).
        // Short-circuits before constructing a dialog, so no FX thread.
        MigrationReport formatOnly = new MigrationReport(1, 3, List.of(
                new MigrationReport.AppliedMigration(1, 2, "key MIDI effects by id", false),
                new MigrationReport.AppliedMigration(2, 3, "add per-track MIDI clips", false)
        ), Instant.now());
        assertThat(formatOnly.wasMigrated()).as("fixture: it was migrated").isTrue();

        assertThat(MigrationReportDialog.showUnlessSuppressed(formatOnly, projectDir, false, null)).isFalse();
        assertThat(MigrationReportDialog.showUnlessSuppressed(formatOnly, projectDir, false, () -> { })).isFalse();
    }

    @Test
    void showUnlessSuppressedReturnsFalseWhenSuppressed(@TempDir Path projectDir) {
        com.benesquivelmusic.daw.core.persistence.migration.MigrationSuppression
                .suppress(projectDir, 3);

        assertThat(MigrationReportDialog.showUnlessSuppressed(sampleReport(), projectDir,
                com.benesquivelmusic.daw.core.persistence.migration.MigrationSuppression.isSuppressed(projectDir, 3), null)).isFalse();
    }

    @Test
    void rollbackButtonAbsentWhenNoActionSupplied(@TempDir Path projectDir) throws Exception {
        boolean hasRollback = onFx(() -> new MigrationReportDialog(sampleReport(), projectDir)
                .getDialogPane().getButtonTypes()
                .contains(MigrationReportDialog.ROLLBACK_BUTTON_TYPE));
        assertThat(hasRollback).isFalse();
    }

    @Test
    void rollbackButtonPresentWhenActionSupplied(@TempDir Path projectDir) throws Exception {
        boolean hasRollback = onFx(() -> new MigrationReportDialog(
                sampleReport(), projectDir, () -> { /* no-op */ })
                .getDialogPane().getButtonTypes()
                .contains(MigrationReportDialog.ROLLBACK_BUTTON_TYPE));
        assertThat(hasRollback).isTrue();
    }

    @Test
    void resultConverterOnlyRecordsAPickedRollbackWhichRunsOnceAfterward(@TempDir Path projectDir)
            throws Exception {
        java.util.concurrent.atomic.AtomicInteger invoked =
                new java.util.concurrent.atomic.AtomicInteger();
        List<Integer> rollbacks = onFx(() -> {
            MigrationReportDialog dialog = new MigrationReportDialog(
                    sampleReport(), projectDir, invoked::incrementAndGet);
            // Simulate the user choosing the Roll back button by going
            // through the dialog's result converter directly — avoids
            // showAndWait()/event-loop blocking in headless tests. A close
            // converts again with the cancel button, which is the dialog's OK.
            dialog.getResultConverter().call(MigrationReportDialog.ROLLBACK_BUTTON_TYPE);
            dialog.getResultConverter().call(MigrationReportDialog.OK_BUTTON_TYPE);
            int byTheConverter = invoked.get();
            // What showUnlessSuppressed does once showAndWait() returned.
            dialog.runConfirmedRollback();
            dialog.runConfirmedRollback();
            return List.of(byTheConverter, invoked.get());
        });
        assertThat(rollbacks).as("the converter only records it; it runs once, after the dialog closed")
                .containsExactly(0, 1);
    }

    @Test
    void resultConverterDoesNotRunRollbackForOkButton(@TempDir Path projectDir) throws Exception {
        java.util.concurrent.atomic.AtomicInteger invoked =
                new java.util.concurrent.atomic.AtomicInteger();
        onFx(() -> {
            MigrationReportDialog dialog = new MigrationReportDialog(
                    sampleReport(), projectDir, invoked::incrementAndGet);
            dialog.getResultConverter().call(MigrationReportDialog.OK_BUTTON_TYPE);
            dialog.runConfirmedRollback();
            return null;
        });
        assertThat(invoked.get()).isZero();
    }
}
