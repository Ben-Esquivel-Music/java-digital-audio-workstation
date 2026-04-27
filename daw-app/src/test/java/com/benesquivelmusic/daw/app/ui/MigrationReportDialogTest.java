package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import javafx.application.Platform;
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
        boolean hasOk = onFx(() -> new MigrationReportDialog(sampleReport(), projectDir)
                .getDialogPane().getButtonTypes().contains(ButtonType.OK));
        assertThat(hasOk).isTrue();
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
    void showIfNeededReturnsFalseForNoOpReport(@TempDir Path projectDir) {
        // Pure helper logic — does not need the FX thread because it
        // short-circuits before constructing a dialog.
        assertThat(MigrationReportDialog.showIfNeeded(MigrationReport.noOp(1), projectDir))
                .isFalse();
        assertThat(MigrationReportDialog.showIfNeeded(null, projectDir)).isFalse();
    }

    @Test
    void showIfNeededReturnsFalseWhenSuppressed(@TempDir Path projectDir) {
        com.benesquivelmusic.daw.core.persistence.migration.MigrationSuppression
                .suppress(projectDir, 3);

        assertThat(MigrationReportDialog.showIfNeeded(sampleReport(), projectDir)).isFalse();
    }
}
