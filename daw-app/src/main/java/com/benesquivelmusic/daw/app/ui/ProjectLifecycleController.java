package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.session.SessionExportResult;
import com.benesquivelmusic.daw.sdk.session.SessionImportResult;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.scene.control.Button;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages project lifecycle actions: creating, opening, saving, browsing
 * recent projects, and importing/exporting sessions.
 *
 * <p>Extracted from {@link MainController} to isolate all project
 * open/save/import/export operations, their associated dialog interactions,
 * and the state resets required when switching between projects into a
 * dedicated, independently testable class. All dependencies are received
 * via constructor injection.</p>
 */
final class ProjectLifecycleController {

    private static final Logger LOG = Logger.getLogger(ProjectLifecycleController.class.getName());

    /** Icon size for panel-header labels. */
    private static final double PANEL_ICON_SIZE = 16;

    /**
     * Callback interface implemented by the host controller to provide
     * mutable project state access and coordination methods that remain
     * in the top-level controller.
     */
    interface Host {
        DawProject project();
        void setProject(DawProject project);
        UndoManager undoManager();
        void setUndoManager(UndoManager undoManager);
        boolean isProjectDirty();
        void setProjectDirty(boolean dirty);
        void resetTrackCounters();
        void rebuildHistoryPanel();
        void onProjectUIRebuild(MixerView mixerView);
    }

    private final ProjectManager projectManager;
    private final SessionInterchangeController sessionInterchangeController;
    private final NotificationBar notificationBar;
    private final Label statusBarLabel;
    private final Label checkpointLabel;
    private final BorderPane rootPane;
    private final Button recentProjectsButton;
    private final VBox trackListPanel;
    private final Host host;

    ProjectLifecycleController(ProjectManager projectManager,
                               SessionInterchangeController sessionInterchangeController,
                               NotificationBar notificationBar,
                               Label statusBarLabel,
                               Label checkpointLabel,
                               BorderPane rootPane,
                               Button recentProjectsButton,
                               VBox trackListPanel,
                               Host host) {
        this.projectManager = Objects.requireNonNull(projectManager, "projectManager must not be null");
        this.sessionInterchangeController = Objects.requireNonNull(sessionInterchangeController,
                "sessionInterchangeController must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        this.statusBarLabel = Objects.requireNonNull(statusBarLabel, "statusBarLabel must not be null");
        this.checkpointLabel = Objects.requireNonNull(checkpointLabel, "checkpointLabel must not be null");
        this.rootPane = Objects.requireNonNull(rootPane, "rootPane must not be null");
        this.recentProjectsButton = Objects.requireNonNull(recentProjectsButton,
                "recentProjectsButton must not be null");
        this.trackListPanel = Objects.requireNonNull(trackListPanel, "trackListPanel must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");
    }

    // ── Project action handlers ──────────────────────────────────────────────

    void onSaveProject() {
        try {
            if (projectManager.getCurrentProject() == null) {
                Path tempDir = Files.createTempDirectory("daw-project-");
                projectManager.createProject(host.project().getName(), tempDir.getParent());
            }
            projectManager.saveProject();
            host.setProjectDirty(false);
            int count = projectManager.getCheckpointManager().getCheckpointCount();
            checkpointLabel.setText("Saved (checkpoint #" + count + ")");
            checkpointLabel.setGraphic(IconNode.of(DawIcon.SUCCESS, 12));
            statusBarLabel.setText("Project saved");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.UPLOAD, 12));
            notificationBar.show(NotificationLevel.SUCCESS, "Project saved");
            LOG.info("Project saved successfully");
        } catch (IOException e) {
            statusBarLabel.setText("Save failed: " + e.getMessage());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            notificationBar.show(NotificationLevel.ERROR, "Save failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to save project", e);
        }
    }

    void onNewProject() {
        if (!confirmDiscardUnsavedChanges()) {
            return;
        }
        resetProjectState();
        host.setProject(new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY));
        host.setUndoManager(new UndoManager());
        host.rebuildHistoryPanel();
        host.resetTrackCounters();
        host.setProjectDirty(false);
        rebuildUI();
        statusBarLabel.setText("New project created");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.FOLDER, 12));
        notificationBar.show(NotificationLevel.SUCCESS, "New project created");
        LOG.info("Created new project");
    }

    void onOpenProject() {
        if (!confirmDiscardUnsavedChanges()) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Project");
        Stage stage = (Stage) rootPane.getScene().getWindow();
        java.io.File selected = chooser.showDialog(stage);
        if (selected == null) {
            return;
        }
        loadProjectFromPath(selected.toPath());
    }

    void onRecentProjects() {
        List<Path> recentPaths = projectManager.getRecentProjectPaths();
        ContextMenu menu = new ContextMenu();
        if (recentPaths.isEmpty()) {
            MenuItem emptyItem = new MenuItem("No recent projects");
            emptyItem.setDisable(true);
            menu.getItems().add(emptyItem);
        } else {
            for (Path path : recentPaths) {
                MenuItem item = new MenuItem(path.getFileName().toString());
                item.setOnAction(_ -> {
                    if (confirmDiscardUnsavedChanges()) {
                        loadProjectFromPath(path);
                    }
                });
                menu.getItems().add(item);
            }
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem clearItem = new MenuItem("Clear Recent Projects");
            clearItem.setOnAction(_ -> {
                RecentProjectsStore store = projectManager.getRecentProjectsStore();
                if (store != null) {
                    store.clear();
                }
                statusBarLabel.setText("Recent projects cleared");
                statusBarLabel.setGraphic(IconNode.of(DawIcon.DELETE, 12));
            });
            menu.getItems().add(clearItem);
        }
        menu.show(recentProjectsButton,
                javafx.geometry.Side.RIGHT, 0, 0);
    }

    // ── Session Import/Export ────────────────────────────────────────────────

    void onImportSession() {
        if (!confirmDiscardUnsavedChanges()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Session");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("DAWproject Files", "*.dawproject", "*.xml"));
        Stage stage = (Stage) rootPane.getScene().getWindow();
        java.io.File selected = chooser.showOpenDialog(stage);
        if (selected == null) {
            return;
        }
        try {
            SessionImportResult result = sessionInterchangeController.importSession(selected.toPath());
            resetProjectState();
            host.setProject(new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY));
            host.setUndoManager(new UndoManager());
            host.rebuildHistoryPanel();
            host.resetTrackCounters();
            sessionInterchangeController.applySessionData(result.sessionData(), host.project());
            host.setProjectDirty(true);
            rebuildUI();

            String summary = sessionInterchangeController.buildImportSummary(result);
            Alert summaryDialog = new Alert(Alert.AlertType.INFORMATION);
            summaryDialog.setTitle("Import Summary");
            summaryDialog.setHeaderText("Session imported successfully");
            summaryDialog.setContentText(summary);
            DarkThemeHelper.applyTo(summaryDialog);
            summaryDialog.showAndWait();

            statusBarLabel.setText("Imported session: " + result.sessionData().projectName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.DOWNLOAD, 12));
            notificationBar.show(NotificationLevel.SUCCESS,
                    "Imported session: " + result.sessionData().projectName());
            LOG.info("Imported DAWproject session from " + selected.toPath());
        } catch (IOException e) {
            statusBarLabel.setText("Import failed: " + e.getMessage());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            notificationBar.show(NotificationLevel.ERROR,
                    "Import failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to import session", e);
        }
    }

    void onExportSession() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Export Session");
        Stage stage = (Stage) rootPane.getScene().getWindow();
        java.io.File selected = chooser.showDialog(stage);
        if (selected == null) {
            return;
        }
        try {
            SessionExportResult result = sessionInterchangeController.exportSession(
                    host.project(), selected.toPath(), host.project().getName());

            String message = "Session exported to " + result.outputPath().getFileName();
            if (!result.warnings().isEmpty()) {
                message += " (" + result.warnings().size() + " warnings)";
            }
            statusBarLabel.setText(message);
            statusBarLabel.setGraphic(IconNode.of(DawIcon.UPLOAD, 12));
            notificationBar.show(NotificationLevel.SUCCESS, message);

            if (!result.warnings().isEmpty()) {
                StringBuilder warningText = new StringBuilder("Export completed with warnings:\n\n");
                for (String warning : result.warnings()) {
                    warningText.append("  \u2022 ").append(warning).append("\n");
                }
                Alert warningDialog = new Alert(Alert.AlertType.WARNING);
                warningDialog.setTitle("Export Warnings");
                warningDialog.setHeaderText("Session exported with warnings");
                warningDialog.setContentText(warningText.toString());
                DarkThemeHelper.applyTo(warningDialog);
                warningDialog.showAndWait();
            }

            LOG.info("Exported DAWproject session to " + result.outputPath());
        } catch (IOException e) {
            statusBarLabel.setText("Export failed: " + e.getMessage());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            notificationBar.show(NotificationLevel.ERROR,
                    "Export failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to export session", e);
        }
    }

    // ── Supporting methods ───────────────────────────────────────────────────

    /**
     * Prompts the user to save unsaved changes before a destructive operation.
     *
     * @return {@code true} if the operation should proceed (saved, discarded, or no changes),
     *         {@code false} if the user cancelled
     */
    boolean confirmDiscardUnsavedChanges() {
        if (!host.isProjectDirty()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Changes");
        alert.setHeaderText("You have unsaved changes.");
        alert.setContentText("Do you want to save before continuing?");
        ButtonType saveBtn = new ButtonType("Save");
        ButtonType discardBtn = new ButtonType("Discard");
        ButtonType cancelBtn = ButtonType.CANCEL;
        alert.getButtonTypes().setAll(saveBtn, discardBtn, cancelBtn);
        DarkThemeHelper.applyTo(alert);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == cancelBtn) {
            return false;
        }
        if (result.get() == saveBtn) {
            onSaveProject();
        }
        return true;
    }

    void loadProjectFromPath(Path projectDir) {
        try {
            resetProjectState();
            projectManager.openProject(projectDir);
            host.setProject(new DawProject(
                    projectManager.getCurrentProject().name(),
                    AudioFormat.STUDIO_QUALITY));
            host.setUndoManager(new UndoManager());
            host.rebuildHistoryPanel();
            host.resetTrackCounters();
            host.setProjectDirty(false);
            rebuildUI();
            statusBarLabel.setText("Opened: " + projectDir.getFileName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.FOLDER, 12));
            notificationBar.show(NotificationLevel.SUCCESS,
                    "Opened project: " + projectDir.getFileName());
            LOG.info("Opened project from " + projectDir);
        } catch (IOException e) {
            statusBarLabel.setText("Open failed: " + e.getMessage());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            notificationBar.show(NotificationLevel.ERROR,
                    "Open failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to open project", e);
        }
    }

    void resetProjectState() {
        try {
            if (projectManager.getCurrentProject() != null) {
                projectManager.closeProject();
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to close current project", e);
        }
    }

    private void rebuildUI() {
        trackListPanel.getChildren().clear();
        Label header = new Label("TRACKS");
        header.getStyleClass().add("panel-header");
        header.setGraphic(IconNode.of(DawIcon.MIXER, PANEL_ICON_SIZE));
        trackListPanel.getChildren().add(header);
        MixerView newMixerView = new MixerView(host.project());
        host.onProjectUIRebuild(newMixerView);
    }
}
