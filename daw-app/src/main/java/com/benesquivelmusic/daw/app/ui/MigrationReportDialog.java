package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationSuppression;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
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
@com.benesquivelmusic.daw.app.ui.dialogs.LegacyDialog(
        "migrate to DawgDialog — story 276 follow-up")
public final class MigrationReportDialog extends Dialog<Void> {

    /** Roll-back button (Story 247). Only attached when a rollback action is supplied. */
    static final ButtonType ROLLBACK_BUTTON_TYPE =
            new ButtonType("Roll back\u2026", ButtonBar.ButtonData.LEFT);

    /**
     * The dialog's OK, which is also its cancel (story 329, decision D32): a
     * dialog whose result is {@code null} closes only through a cancel-data
     * button, so with {@code Roll back…} beside a plain
     * {@link ButtonType#OK} nothing could close it — not OK, not a confirmed
     * roll-back, not Esc, not the window's close button. Esc and the close
     * button now mean OK: the suppression checkbox is honoured, nothing is
     * rolled back. Enter still presses it.
     */
    static final ButtonType OK_BUTTON_TYPE =
            new ButtonType(ButtonType.OK.getText(), ButtonBar.ButtonData.CANCEL_CLOSE);

    private final CheckBox suppressFutureCheckbox;

    /**
     * The roll-back action, run by {@link #runConfirmedRollback()} — never by
     * the dialog while it is showing — or {@code null} when none was supplied.
     */
    private final Runnable rollbackAction;

    /**
     * Whether the result converter did its work (story 329, decision D42):
     * a close converts twice — the pressed button, then, as the result is
     * {@code null}, the cancel button — and only the first counts.
     */
    private boolean converted;

    /** Whether the user confirmed {@code Roll back…}; the roll-back has not run yet. */
    private boolean rollbackConfirmed;

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
     * @param rollbackAction   optional action run on the FX thread, by
     *                         {@link #runConfirmedRollback()} once the
     *                         dialog closed, when the user clicked
     *                         "Roll back…" and confirmed the destructive
     *                         prompt. When {@code null}, no roll-back button
     *                         is shown.
     */
    public MigrationReportDialog(MigrationReport report,
                                 Path projectDirectory,
                                 Runnable rollbackAction) {
        if (report == null || !report.wasMigrated()) {
            throw new IllegalArgumentException(
                    "MigrationReportDialog requires a non-empty migration report");
        }
        this.rollbackAction = rollbackAction;

        setTitle("Project Migrated");
        setHeaderText("This project was migrated from schema version "
                + report.fromVersion() + " to " + report.toVersion());
        setGraphic(IconNode.of(DawIcon.INFO, 24));

        String introText = "The project file uses an older schema. The following migrations "
                + "were applied in memory. Nothing has been written to disk yet \u2014 "
                + "saving the project will commit the migration and back up the "
                + "original file as a sibling .bak.";
        if (rollbackAction != null) {
            introText += " You can also roll back now to discard the migrated version.";
        }
        Label intro = new Label(introText);
        intro.setWrapText(true);
        intro.setMaxWidth(480);

        ListView<String> migrationList = createMigrationList(report);

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
        getDialogPane().getButtonTypes().add(OK_BUTTON_TYPE);
        ((Button) getDialogPane().lookupButton(OK_BUTTON_TYPE)).setDefaultButton(true);

        ThemeManager.getDefault().applyTo(getDialogPane());

        // Story 329 (D42) — the converter does its work once per dialog, and
        // only records a confirmed roll-back: the roll-back reloads the
        // project, and may show this dialog's successor, so it runs once this
        // dialog closed (showIfNeeded), never inside its close.
        setResultConverter(button -> {
            if (converted) {
                return null;
            }
            converted = true;
            if (projectDirectory != null && suppressFutureCheckbox.isSelected()) {
                MigrationSuppression.suppress(projectDirectory, report.toVersion());
            }
            rollbackConfirmed = button == ROLLBACK_BUTTON_TYPE && rollbackAction != null;
            return null;
        });
    }

    /**
     * Runs the roll-back action when the user confirmed {@code Roll back…},
     * once: a second call does nothing. {@link #showIfNeeded} calls it after
     * the dialog closed. FX thread.
     */
    void runConfirmedRollback() {
        if (!rollbackConfirmed) {
            return;
        }
        rollbackConfirmed = false;
        rollbackAction.run();
    }

    /**
     * Creates the standard migration-step list used by the modal dialog and
     * historical migration rows.
     */
    static ListView<String> createMigrationList(MigrationReport report) {
        ListView<String> migrationList = new ListView<>();
        migrationList.getItems().setAll(formatAppliedMigrations(report));
        migrationList.setPrefHeight(Math.min(220, 28 * report.applied().size() + 24));
        migrationList.setPrefWidth(480);
        return migrationList;
    }

    /**
     * Formats every applied migration row in the same presentation style the
     * report dialog has always used.
     */
    static List<String> formatAppliedMigrations(MigrationReport report) {
        if (report == null) {
            return List.of();
        }
        return report.applied().stream()
                .map(MigrationReportDialog::formatAppliedMigration)
                .toList();
    }

    /**
     * Formats one migration step as a compact row label.
     */
    static String formatAppliedMigration(MigrationReport.AppliedMigration migration) {
        return "v" + migration.fromVersion() + " \u2192 v" + migration.toVersion()
                + "  \u2014  " + migration.description();
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
                "If a pre-migration backup (.bak) exists on disk, it will be "
                        + "restored and the project will be reloaded. Otherwise, the "
                        + "in-memory migrated project will be discarded (the on-disk "
                        + "file has not been overwritten yet). Any unsaved changes "
                        + "will be lost.");
        confirm.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        ThemeManager.getDefault().applyTo(confirm.getDialogPane());
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
     * supplies an optional roll-back action, run when the user clicked
     * "Roll back…" and confirmed — after the dialog closed, so the
     * reload it starts never runs under this dialog.
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
        MigrationReportDialog dialog = new MigrationReportDialog(report, projectDirectory, rollbackAction);
        dialog.showAndWait();
        dialog.runConfirmedRollback();
        return true;
    }
}
