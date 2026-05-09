package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationSuppression;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Modal dialog shown after a project file has been migrated through one
 * or more {@link MigrationReport.AppliedMigration}s. Lists what was
 * migrated, offers a "Don't show again for this project" checkbox
 * (via {@link MigrationSuppression}), and — when a rollback action is
 * supplied — a "Roll back…" button that, after a confirmation, reverts
 * the in-memory migrated project to the pre-migration version.
 *
 * <p>The dialog is purely informational — closing it does not commit
 * the migration to disk. Per the original story, migrations stay in
 * memory until the user explicitly saves; the first save then takes a
 * backup of the pre-migration file (handled by
 * {@code com.benesquivelmusic.daw.core.persistence.ProjectManager}).</p>
 */
public final class MigrationReportDialog extends Dialog<Void> {

    /** Roll-back button (Story 247). Only attached when a rollback action is supplied. */
    static final ButtonType ROLLBACK_BUTTON_TYPE =
            new ButtonType("Roll back\u2026", ButtonBar.ButtonData.LEFT);

    private final CheckBox suppressFutureCheckbox;

    /**
     * Creates a dialog for the given report with no roll-back action.
     * Equivalent to {@link #MigrationReportDialog(MigrationReport, Path, Runnable)}
     * with a {@code null} action.
     */
    public MigrationReportDialog(MigrationReport report, Path projectDirectory) {
        this(report, projectDirectory, null);
    }

    /**
     * Creates a dialog for the given report.
     *
     * @param report           the migration report — must be non-null and
     *                         {@link MigrationReport#wasMigrated()}
     * @param projectDirectory the project directory; may be {@code null},
     *                         in which case the suppression checkbox is
     *                         shown but disabled
     * @param rollbackAction   optional action invoked on the FX thread
     *                         when the user clicks "Roll back…" and
     *                         confirms the destructive prompt. When
     *                         {@code null}, no roll-back button is shown.
     */
    public MigrationReportDialog(MigrationReport report,
                                 Path projectDirectory,
                                 Runnable rollbackAction) {
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
                        + "were applied in memory. Nothing has been written to disk yet \u2014 "
                        + "saving the project will commit the migration and back up the "
                        + "original file as a sibling .bak. You can also roll back now to "
                        + "discard the migrated version.");
        intro.setWrapText(true);
        intro.setMaxWidth(480);

        ListView<String> migrationList = new ListView<>();
        report.applied().stream()
                .map(m -> "v" + m.fromVersion() + " \u2192 v" + m.toVersion() + "  \u2014  " + m.description())
                .forEach(migrationList.getItems()::add);
        migrationList.setPrefHeight(Math.min(220, 28 * report.applied().size() + 24));
        migrationList.setPrefWidth(480);

        suppressFutureCheckbox = new CheckBox("Don't show again for this project");
        suppressFutureCheckbox.setDisable(projectDirectory == null);

        VBox content = new VBox(12, intro, migrationList, suppressFutureCheckbox);
        content.setPadding(new Insets(12));

        getDialogPane().setContent(content);
        if (rollbackAction != null) {
            getDialogPane().getButtonTypes().add(ROLLBACK_BUTTON_TYPE);
            // Intercept the roll-back click so the dialog only closes
            // (and the action only runs) if the user confirms the
            // destructive prompt.
            getDialogPane().lookupButton(ROLLBACK_BUTTON_TYPE)
                    .addEventFilter(ActionEvent.ACTION, evt -> {
                        if (!confirmRollback()) {
                            evt.consume();
                        }
                    });
        }
        getDialogPane().getButtonTypes().add(ButtonType.OK);

        DarkThemeHelper.applyTo(this);

        setResultConverter(button -> {
            if (projectDirectory != null && suppressFutureCheckbox.isSelected()) {
                MigrationSuppression.suppress(projectDirectory, report.toVersion());
            }
            if (button == ROLLBACK_BUTTON_TYPE && rollbackAction != null) {
                rollbackAction.run();
            }
            return null;
        });
    }

    /**
     * Shows a confirmation dialog asking the user to confirm discarding
     * the migrated version. Returns {@code true} if the user confirms.
     *
     * <p>Package-private so tests can stub destructive UI, and so the
     * confirmation is part of the dialog's behaviour rather than the
     * caller's.</p>
     */
    boolean confirmRollback() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Roll back migration?");
        confirm.setHeaderText("Discard the migrated version?");
        confirm.setContentText(
                "This will reload the pre-migration project file. Any in-memory "
                        + "changes since the migrated load will be lost.");
        confirm.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        DarkThemeHelper.applyTo(confirm);
        Optional<ButtonType> result = confirm.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
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
        return showIfNeeded(report, projectDirectory, null);
    }

    /**
     * Variant of {@link #showIfNeeded(MigrationReport, Path)} that
     * supplies an optional roll-back action invoked when the user
     * clicks "Roll back…" and confirms.
     */
    public static boolean showIfNeeded(MigrationReport report,
                                       Path projectDirectory,
                                       Runnable rollbackAction) {
        if (report == null || !report.wasMigrated()) {
            return false;
        }
        if (MigrationSuppression.isSuppressed(projectDirectory, report.toVersion())) {
            return false;
        }
        new MigrationReportDialog(report, projectDirectory, rollbackAction).showAndWait();
        return true;
    }
}
