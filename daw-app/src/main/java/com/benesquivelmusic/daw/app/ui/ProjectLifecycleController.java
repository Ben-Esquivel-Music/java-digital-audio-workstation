package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationException;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.persistence.archive.ArchivedProject;
import com.benesquivelmusic.daw.core.persistence.archive.MissingAssetResolver;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiveSummary;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.session.SessionExportResult;
import com.benesquivelmusic.daw.sdk.session.SessionImportResult;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

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

        /**
         * Story 282 — Mission Control. Captures the live layout state
         * to embed in the project file before save. Implementations
         * return {@code LayoutManager.toJson()}, or {@code null} when
         * the layout manager has not been installed (early startup /
         * headless tests). Default returns {@code null}.
         */
        default String captureLayoutJson() { return null; }

        /**
         * Story 282 — applies a previously-persisted layout JSON blob
         * after a project load. Implementations call
         * {@code LayoutManager.fromJson(json)}; {@code null} is a valid
         * input meaning "no persisted layout — fall back to Default".
         * Default is a no-op.
         */
        default void applyLayoutJson(String json) { /* no-op */ }
    }

    private final ProjectManager projectManager;
    private final SessionInterchangeController sessionInterchangeController;
    private final NotificationBar notificationBar;
    private final Label statusBarLabel;
    private final Label checkpointLabel;
    private final BorderPane rootPane;
    private final VBox trackListPanel;
    private final Host host;
    /** Story 189 — engine for {@code .dawz} archive save/restore. */
    private final ProjectArchiver projectArchiver;

    ProjectLifecycleController(ProjectManager projectManager,
                               SessionInterchangeController sessionInterchangeController,
                               NotificationBar notificationBar,
                               Label statusBarLabel,
                               Label checkpointLabel,
                               BorderPane rootPane,
                               VBox trackListPanel,
                               Host host,
                               ProjectArchiver projectArchiver) {
        this.projectManager = Objects.requireNonNull(projectManager, "projectManager must not be null");
        this.sessionInterchangeController = Objects.requireNonNull(sessionInterchangeController,
                "sessionInterchangeController must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        this.statusBarLabel = Objects.requireNonNull(statusBarLabel, "statusBarLabel must not be null");
        this.checkpointLabel = Objects.requireNonNull(checkpointLabel, "checkpointLabel must not be null");
        this.rootPane = Objects.requireNonNull(rootPane, "rootPane must not be null");
        this.trackListPanel = Objects.requireNonNull(trackListPanel, "trackListPanel must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.projectArchiver = Objects.requireNonNull(projectArchiver, "projectArchiver must not be null");
    }

    // ── Project action handlers ──────────────────────────────────────────────

    void onSaveProject() {
        trySaveProject();
    }

    /**
     * Attempts to save the current project.
     *
     * @return {@code true} if the save succeeded, {@code false} if it failed
     */
    private boolean trySaveProject() {
        try {
            if (projectManager.getCurrentProject() == null) {
                Path tempDir = Files.createTempDirectory("daw-project-");
                projectManager.createProject(host.project().getName(), tempDir);
            }
            // Story 282 — capture the live layout state into the project
            // model before serialisation. {@code null} from the host
            // clears the field so legacy projects round-trip byte-
            // identical when the layout manager is not installed.
            host.project().setLayoutJson(host.captureLayoutJson());
            projectManager.saveDawProject(host.project());
            projectManager.saveProject();
            host.setProjectDirty(false);
            int count = projectManager.getCheckpointManager().getCheckpointCount();
            checkpointLabel.setText("Saved (checkpoint #" + count + ")");
            checkpointLabel.setGraphic(IconNode.of(DawIcon.SUCCESS, 12));
            statusBarLabel.setText("Project saved");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.UPLOAD, 12));
            notificationBar.show(NotificationLevel.SUCCESS, "Project saved");
            LOG.info("Project saved successfully");
            return true;
        } catch (IOException e) {
            statusBarLabel.setText("Save failed: " + e.getMessage());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            notificationBar.show(NotificationLevel.ERROR, "Save failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to save project", e);
            return false;
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
        menu.show(rootPane.getScene().getWindow());
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
            ThemeManager.getDefault().applyTo(summaryDialog.getDialogPane());
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
                ThemeManager.getDefault().applyTo(warningDialog.getDialogPane());
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

    // ── Project Archive (Story 189 — Project Archive (ZIP With Assets)) ────

    /**
     * "File → Archive Project…" — bundles the current project together
     * with every referenced asset into a single self-contained
     * {@code .dawz} ZIP at a user-chosen location.
     *
     * <p>Walks every {@link AudioClip} and {@link SoundFontAssignment}
     * referenced by the project to detect missing asset files. If any
     * are missing, asks the user whether to abort or proceed with those
     * assets simply omitted from the archive. The ZIP I/O runs on a
     * background virtual thread with a modeless
     * {@link TaskProgressIndicator} so the UI stays responsive. On
     * success, surfaces a notification with the asset count and total
     * size.</p>
     */
    void onArchiveProject() {
        DawProject current = host.project();
        if (current == null) {
            notificationBar.show(NotificationLevel.ERROR, "No project to archive");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Archive Project");
        chooser.setInitialFileName(safeFileName(current.getName()) + ProjectArchiver.ARCHIVE_EXTENSION);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("DAW Archive (*.dawz)", "*.dawz"));
        Stage stage = (Stage) rootPane.getScene().getWindow();
        java.io.File selected = chooser.showSaveDialog(stage);
        if (selected == null) {
            return;
        }
        Path archivePath = selected.toPath();
        // FileChooser on some platforms does not append the extension.
        if (!archivePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                .endsWith(ProjectArchiver.ARCHIVE_EXTENSION)) {
            archivePath = archivePath.resolveSibling(
                    archivePath.getFileName() + ProjectArchiver.ARCHIVE_EXTENSION);
        }

        // Pre-archive plan: detect missing referenced assets and let the
        // user abort or continue. Missing assets are simply omitted by
        // ProjectArchiver, so this dialog is purely informational.
        List<String> missing = collectMissingAssetPaths(current);
        if (!missing.isEmpty() && !ArchiveSummaryDialog.confirmMissingAssets(missing)) {
            statusBarLabel.setText("Archive cancelled");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            return;
        }

        // Run the potentially expensive ZIP I/O on a background virtual
        // thread so the JavaFX application thread stays responsive.
        // Progress is surfaced through a modeless TaskProgressIndicator
        // following the same pattern used by TrackFreezeController.
        Window owner = rootPane.getScene().getWindow();
        TaskProgressIndicator progress = new TaskProgressIndicator(owner, "Archiving project\u2026");
        progress.hideCancelButton();
        progress.show();
        progress.update(-1.0, "Writing archive\u2026");

        Path finalArchivePath = archivePath;
        Thread.ofVirtual()
                .name("daw-archive-worker")
                .start(() -> {
                    try {
                        ProjectArchiveSummary summary = projectArchiver.saveAsArchive(
                                current, finalArchivePath);
                        String headline = ArchiveSummaryDialog.formatHeadline(summary);
                        Platform.runLater(() -> {
                            progress.close();
                            statusBarLabel.setText(headline);
                            statusBarLabel.setGraphic(IconNode.of(DawIcon.UPLOAD, 12));
                            notificationBar.show(NotificationLevel.SUCCESS,
                                    headline + " \u2014 "
                                            + ArchiveSummaryDialog.archivePathDisplay(finalArchivePath));
                            ArchiveSummaryDialog.showSummary(summary);
                        });
                        LOG.info(() -> "Archived project to " + summary.outputPath()
                                + " (" + summary.uniqueAssetCount() + " assets, "
                                + summary.totalAssetBytes() + " bytes)");
                    } catch (IOException | IllegalArgumentException e) {
                        Platform.runLater(() -> {
                            progress.close();
                            statusBarLabel.setText("Archive failed: " + e.getMessage());
                            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
                            notificationBar.show(NotificationLevel.ERROR,
                                    "Archive failed: " + e.getMessage());
                        });
                        LOG.log(Level.WARNING, "Failed to archive project", e);
                    }
                });
    }

    /**
     * "File → Restore from Archive…" — opens a {@code .dawz} archive,
     * extracts it into a user-chosen destination directory, and loads
     * the restored project (which goes into the recent-projects list
     * via {@link ProjectManager#openProject}).
     *
     * <p>The extraction and XML-rewrite work runs on a background
     * virtual thread with a modeless {@link TaskProgressIndicator} so
     * large archives do not freeze the UI. The restored project is
     * then loaded on the JavaFX application thread via
     * {@link #loadProjectFromPath}, and the success notification is
     * only shown if that load actually succeeds.</p>
     */
    void onRestoreFromArchive() {
        if (!confirmDiscardUnsavedChanges()) {
            return;
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Restore from Archive");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("DAW Archive (*.dawz)", "*.dawz"));
        Stage stage = (Stage) rootPane.getScene().getWindow();
        java.io.File archiveSelected = fileChooser.showOpenDialog(stage);
        if (archiveSelected == null) {
            return;
        }
        Path archivePath = archiveSelected.toPath();

        // Default destination: ~/Documents/<archive-stem>-<timestamp>/
        Path defaultRoot = defaultRestoreRoot();
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Choose restore destination directory");
        if (defaultRoot != null && Files.isDirectory(defaultRoot)) {
            dirChooser.setInitialDirectory(defaultRoot.toFile());
        }
        java.io.File parentDir = dirChooser.showDialog(stage);
        if (parentDir == null) {
            return;
        }
        String stem = archivePath.getFileName().toString();
        if (stem.toLowerCase(java.util.Locale.ROOT).endsWith(ProjectArchiver.ARCHIVE_EXTENSION)) {
            stem = stem.substring(0, stem.length() - ProjectArchiver.ARCHIVE_EXTENSION.length());
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path destination = parentDir.toPath().resolve(stem + "-" + stamp);

        // Run the potentially expensive ZIP extraction on a background
        // virtual thread. UI updates (load + notification) happen via
        // Platform.runLater once extraction is complete.
        Window owner = rootPane.getScene().getWindow();
        TaskProgressIndicator progress = new TaskProgressIndicator(owner, "Restoring archive\u2026");
        progress.hideCancelButton();
        progress.show();
        progress.update(-1.0, "Extracting archive\u2026");

        Thread.ofVirtual()
                .name("daw-restore-worker")
                .start(() -> {
                    try {
                        ArchivedProject archived = projectArchiver.openArchive(
                                archivePath, destination, MissingAssetResolver.none());
                        // openArchive resolves the project's relative
                        // "assets/<sha>_*" references to absolute paths inside
                        // the destination directory. We must write this
                        // resolved project document back to
                        // {destination}/project.daw so that
                        // ProjectManager.openProject — which only reads the
                        // file from disk — picks up the absolute asset paths
                        // instead of the archive-internal relative ones.
                        String xml = new ProjectSerializer().serialize(archived.project());
                        Files.writeString(destination.resolve("project.daw"), xml);

                        int missingCount = archived.missingAssets().size();
                        String projectName = archived.header().projectName();

                        // Load the restored project on the FX thread; only
                        // show the success notification if loading succeeded.
                        Platform.runLater(() -> {
                            progress.close();
                            boolean loaded = loadProjectFromPath(destination);
                            if (loaded) {
                                String message = "Restored archive: " + projectName
                                        + (missingCount == 0 ? ""
                                        : " (" + missingCount + " missing assets)");
                                notificationBar.show(
                                        missingCount == 0 ? NotificationLevel.SUCCESS
                                                : NotificationLevel.WARNING,
                                        message);
                            }
                        });
                        LOG.info(() -> "Restored archive " + archivePath
                                + " into " + destination);
                    } catch (IOException e) {
                        Platform.runLater(() -> {
                            progress.close();
                            statusBarLabel.setText("Restore failed: " + e.getMessage());
                            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
                            notificationBar.show(NotificationLevel.ERROR,
                                    "Restore failed: " + e.getMessage());
                        });
                        LOG.log(Level.WARNING, "Failed to restore archive", e);
                    }
                });
    }

    /**
     * Walks the project for asset references whose path no longer points
     * at a regular file on disk. Mirrors the iteration order of
     * {@link ProjectArchiver} so the user sees the same set the archiver
     * will end up skipping.
     *
     * <p>Package-private for unit tests.</p>
     */
    static List<String> collectMissingAssetPaths(DawProject project) {
        List<String> missing = new ArrayList<>();
        for (Track track : project.getTracks()) {
            for (AudioClip clip : track.getClips()) {
                String p = clip.getSourceFilePath();
                if (p != null && !p.isBlank() && !isRegularFile(p)) {
                    missing.add(p);
                }
            }
            SoundFontAssignment sf = track.getSoundFontAssignment();
            if (sf != null && sf.soundFontPath() != null) {
                Path sfPath = sf.soundFontPath();
                if (!Files.isRegularFile(sfPath)) {
                    missing.add(sfPath.toString());
                }
            }
        }
        return missing;
    }

    private static boolean isRegularFile(String path) {
        try {
            return Files.isRegularFile(Paths.get(path));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "project";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static Path defaultRestoreRoot() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        Path docs = Paths.get(home, "Documents");
        return Files.isDirectory(docs) ? docs : Paths.get(home);
    }

    // ── Supporting methods ───────────────────────────────────────────────────

    /**
     * Prompts the user to save unsaved changes before a destructive operation.
     *
     * @return {@code true} if the operation should proceed (saved, discarded, or no changes),
     *         {@code false} if the user cancelled or saving failed
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
        ThemeManager.getDefault().applyTo(alert.getDialogPane());

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == cancelBtn) {
            return false;
        }
        if (result.get() == saveBtn) {
            return trySaveProject();
        }
        return true;
    }

    /**
     * Loads a project from the given directory.
     *
     * @return {@code true} if the project loaded successfully,
     *         {@code false} if it failed (error is already surfaced
     *         in the status bar and notification bar)
     */
    boolean loadProjectFromPath(Path projectDir) {
        try {
            resetProjectState();
            projectManager.openProject(projectDir);
            DawProject dawProject = projectManager.getCurrentDawProject();
            if (dawProject == null) {
                dawProject = new DawProject(
                        projectManager.getCurrentProject().name(),
                        AudioFormat.STUDIO_QUALITY);
            }
            host.setProject(dawProject);
            host.setUndoManager(new UndoManager());
            host.rebuildHistoryPanel();
            host.resetTrackCounters();
            host.setProjectDirty(false);
            rebuildUI();
            // Story 282 — Mission Control. Apply the layout JSON blob
            // embedded in the project file (may be {@code null} for
            // legacy projects, in which case the layout manager falls
            // back to the Default built-in).
            host.applyLayoutJson(dawProject.getLayoutJson());
            statusBarLabel.setText("Opened: " + projectDir.getFileName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.FOLDER, 12));
            notificationBar.show(NotificationLevel.SUCCESS,
                    "Opened project: " + projectDir.getFileName());
            LOG.info("Opened project from " + projectDir);
            // Story 247 — surface the migration report dialog when the
            // load triggered any registry-driven schema migrations. The
            // dialog itself short-circuits when the user has already
            // chosen "Don't show again" for this project.
            maybeShowMigrationReport(projectDir);
            return true;
        } catch (MigrationException e) {
            // Unmapped or broken migration chain — surface as an error
            // notification rather than letting the runtime exception
            // bubble out of the menu/action handler.
            statusBarLabel.setText("Migration failed: " + e.getMessage());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            notificationBar.show(NotificationLevel.ERROR,
                    "Migration failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to migrate project on open", e);
            return false;
        } catch (IOException e) {
            statusBarLabel.setText("Open failed: " + e.getMessage());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            notificationBar.show(NotificationLevel.ERROR,
                    "Open failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to open project", e);
            return false;
        }
    }

    /**
     * Invokes {@link MigrationReportDialog#showIfNeeded} for the just-loaded
     * project when the load triggered any schema migrations. Wired with
     * a roll-back action that restores the most recent sibling
     * {@code project.daw.v<n>.*.bak} (created by the first save after a
     * previous migrated load) and reloads the project. When no backup
     * exists yet, rolling back simply abandons the in-memory migrated
     * state — the on-disk file is still the pre-migration original.
     *
     * <p>Package-private so tests can drive it directly without going
     * through a directory chooser.</p>
     */
    void maybeShowMigrationReport(Path projectDir) {
        MigrationReport report = projectManager.getLastMigrationReport();
        if (report == null || !report.wasMigrated()) {
            return;
        }
        MigrationReportDialog.showIfNeeded(report, projectDir,
                () -> rollbackMigration(projectDir, report));
    }

    /**
     * Rolls back to the pre-migration version. If a sibling
     * {@code project.daw.v<fromVersion>.*.bak} exists, the newest one
     * is copied over {@code project.daw} and the project is reloaded
     * (which will re-trigger the migration in memory, but the on-disk
     * file once again carries the user's original schema). When no
     * backup is found, the in-memory migrated project is discarded —
     * the original file on disk has not been overwritten yet.
     */
    void rollbackMigration(Path projectDir, MigrationReport report) {
        Path projectFile = projectDir.resolve("project.daw");
        Optional<Path> newestBackup = findNewestBackup(projectDir, report.fromVersion());
        try {
            if (newestBackup.isPresent()) {
                Files.copy(newestBackup.get(), projectFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                LOG.info("Rolled back migration: restored " + newestBackup.get()
                        + " over " + projectFile);
                if (loadProjectFromPath(projectDir)) {
                    notificationBar.show(NotificationLevel.SUCCESS,
                            "Restored on-disk project file to v"
                                    + report.fromVersion()
                                    + " (in-memory migrations were re-applied)");
                }
            } else {
                // No backup yet: the migrated DawProject lives only in
                // memory, so abandoning is a complete rollback.
                resetProjectState();
                host.setProject(new DawProject("Untitled Project",
                        AudioFormat.STUDIO_QUALITY));
                host.setUndoManager(new UndoManager());
                host.rebuildHistoryPanel();
                host.resetTrackCounters();
                host.setProjectDirty(false);
                rebuildUI();
                statusBarLabel.setText("Rolled back: discarded migrated project");
                statusBarLabel.setGraphic(IconNode.of(DawIcon.INFO, 12));
                notificationBar.show(NotificationLevel.SUCCESS,
                        "Discarded in-memory migration; the on-disk file is "
                                + "still the original (v" + report.fromVersion() + ")");
                LOG.info("Rolled back migration by abandoning in-memory project (no .bak yet)");
            }
        } catch (IOException e) {
            statusBarLabel.setText("Roll back failed: " + e.getMessage());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            notificationBar.show(NotificationLevel.ERROR,
                    "Roll back failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to roll back migration", e);
        }
    }

    /**
     * Finds the newest sibling {@code project.daw.v<fromVersion>.*.bak}
     * file in the project directory, or empty if none exists.
     */
    private static Optional<Path> findNewestBackup(Path projectDir, int fromVersion) {
        if (projectDir == null || !Files.isDirectory(projectDir)) {
            return Optional.empty();
        }
        String prefix = "project.daw.v" + fromVersion + ".";
        try (Stream<Path> entries = Files.list(projectDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".bak");
                    })
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    void resetProjectState() {
        if (projectManager.getCurrentProject() != null) {
            projectManager.abandonProject();
        }
    }

    private void rebuildUI() {
        trackListPanel.getChildren().clear();
        Label header = new Label("TRACKS");
        header.getStyleClass().add("panel-header");
        // No icon-next-to-label per UI Design Book §2.4.
        trackListPanel.getChildren().add(header);
        MixerView newMixerView = new MixerView(host.project(), host.undoManager());
        host.onProjectUIRebuild(newMixerView);
    }
}
