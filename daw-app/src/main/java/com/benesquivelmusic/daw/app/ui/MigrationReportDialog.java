package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationSuppression;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;

import java.nio.file.Path;

/**
 * Modal dialog shown after a project file has been migrated through one
 * or more {@link MigrationReport.AppliedMigration}s. Lists what was
 * migrated and offers a "Don't show again for this project" checkbox
 * which records the user's preference via {@link MigrationSuppression}.
 *
 * <p>The dialog is purely informational — closing it does not commit
 * the migration to disk. Per the issue, migrations stay in memory until
 * the user explicitly saves; the first save then takes a backup of the
 * pre-migration file (handled by
 * {@code com.benesquivelmusic.daw.core.persistence.ProjectManager}).</p>
 */
public final class MigrationReportDialog extends Dialog<Void> {

    private final CheckBox suppressFutureCheckbox;

    /**
     * Creates a dialog for the given report. {@code projectDirectory}
     * may be {@code null}, in which case the suppression checkbox is
     * still shown but ticking it has no effect (we have nowhere to
     * record the preference).
     */
    public MigrationReportDialog(MigrationReport report, Path projectDirectory) {
        if (report == null || !report.wasMigrated()) {
            throw new IllegalArgumentException(
                    "MigrationReportDialog requires a non-empty migration report");
        }

        setTitle("Project Migrated");
        setHeaderText("This project was migrated from schema version "
                + report.fromVersion() + " to " + report.toVersion());
        setGraphic(IconNode.of(DawIcon.INFO, 24));

        Label intro = new Label(
                "The project file uses an older schema. The following migrations "
                        + "were applied in memory. Nothing has been written to disk yet — "
                        + "saving the project will commit the migration and back up the "
                        + "original file as a sibling .bak.");
        intro.setWrapText(true);
        intro.setMaxWidth(480);

        ListView<String> migrationList = new ListView<>();
        report.applied().stream()
                .map(m -> "v" + m.fromVersion() + " → v" + m.toVersion() + "  —  " + m.description())
                .forEach(migrationList.getItems()::add);
        migrationList.setPrefHeight(Math.min(220, 28 * report.applied().size() + 24));
        migrationList.setPrefWidth(480);

        suppressFutureCheckbox = new CheckBox("Don't show again for this project");
        suppressFutureCheckbox.setDisable(projectDirectory == null);

        VBox content = new VBox(12, intro, migrationList, suppressFutureCheckbox);
        content.setPadding(new Insets(12));

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.OK);

        DarkThemeHelper.applyTo(this);

        setResultConverter(button -> {
            if (projectDirectory != null && suppressFutureCheckbox.isSelected()) {
                MigrationSuppression.suppress(projectDirectory, report.toVersion());
            }
            return null;
        });
    }

    /**
     * Convenience helper that wires the standard "show after load"
     * behaviour: if the report indicates migrations were applied and
     * the user has not previously suppressed the dialog for this
     * project, build and {@link #showAndWait()} the dialog.
     *
     * <p>Returns {@code true} if the dialog was actually shown.</p>
     */
    public static boolean showIfNeeded(MigrationReport report, Path projectDirectory) {
        if (report == null || !report.wasMigrated()) {
            return false;
        }
        if (MigrationSuppression.isSuppressed(projectDirectory, report.toVersion())) {
            return false;
        }
        new MigrationReportDialog(report, projectDirectory).showAndWait();
        return true;
    }
}
