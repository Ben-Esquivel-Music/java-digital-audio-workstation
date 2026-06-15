package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.ProjectHealthScanner;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHubView;
import com.benesquivelmusic.daw.app.ui.hub.WelcomeView;
import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
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
import javafx.scene.Parent;
import javafx.scene.Scene;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
     * The functional dependencies the composition root supplies — story 294
     * replaces the callback-up {@code Host} interface (Control Synchronization
     * Design Book §4.2/§9 "use publish/subscribe, not a callback-up {@code Host}
     * for cross-surface updates") with a record of direct functional deps. The
     * swappable project / undo manager are {@link Supplier}s read live, so a
     * project load is reflected without rebuilding this controller; the action /
     * cascade hooks are {@link Runnable}/{@link Consumer}.
     *
     * <p>The dirty flag is deliberately <em>not</em> a dep: it now lives on
     * {@link DawProject} as the §1.2 "one dirty bit", so this controller reads
     * {@code project().get().isDirty()} and calls {@code markDirty()}/
     * {@code markClean()} directly through the project supplier — the imperative
     * {@code MainController.projectDirty} boolean it used to poke is gone.</p>
     */
    record Deps(
            Supplier<DawProject> project,
            Consumer<DawProject> setProject,
            Supplier<UndoManager> undoManager,
            Consumer<UndoManager> setUndoManager,
            Runnable resetTrackCounters,
            Runnable rebuildHistoryPanel,
            Consumer<MixerView> onProjectUIRebuild,
            Supplier<String> captureLayoutJson,
            Consumer<String> applyLayoutJson) {
        Deps {
            Objects.requireNonNull(project, "project must not be null");
            Objects.requireNonNull(setProject, "setProject must not be null");
            Objects.requireNonNull(undoManager, "undoManager must not be null");
            Objects.requireNonNull(setUndoManager, "setUndoManager must not be null");
            Objects.requireNonNull(resetTrackCounters, "resetTrackCounters must not be null");
            Objects.requireNonNull(rebuildHistoryPanel, "rebuildHistoryPanel must not be null");
            Objects.requireNonNull(onProjectUIRebuild, "onProjectUIRebuild must not be null");
            Objects.requireNonNull(captureLayoutJson, "captureLayoutJson must not be null");
            Objects.requireNonNull(applyLayoutJson, "applyLayoutJson must not be null");
        }
    }

    private final ProjectManager projectManager;
    private final SessionInterchangeController sessionInterchangeController;
    private final NotificationBar notificationBar;
    /**
     * Story 295 — the single §5.5 project-status model. Save / autosave /
     * archive paths report through this; the {@code statusBarLabel} and
     * {@code checkpointLabel} this controller used to poke are gone (the §8
     * rejection of "status text in {@code Label.setText}"). The Session Status
     * Strip binds to this model. Transient action confirmations and errors
     * still go through {@link #notificationBar} (§8: the notification bar is
     * for things the user must see — errors, takeovers, migrations).
     */
    private final ProjectOperationProgress progress;
    private final BorderPane rootPane;
    private final VBox trackListPanel;
    private final Deps deps;
    /** Story 189 — engine for {@code .dawz} archive save/restore. */
    private final ProjectArchiver projectArchiver;
    /**
     * The FX-thread marshalling seam (story 289), injected on the production
     * path and threaded into the modeless {@link TaskProgressIndicator}s and
     * the rebuilt {@link MixerView}. May be {@code null} in a pure-unit context
     * (the compatibility constructor defaults it to
     * {@link FxDispatcher#getDefault()}); {@link #postFx} tolerates the null.
     */
    private final FxDispatcher fxDispatcher;

    /**
     * Story 296 — supplies the destination <em>parent</em> directory for a
     * brand-new project, replacing the §8/§1.1 silent
     * {@code Files.createTempDirectory} first-save. The default
     * ({@link #promptNewProjectLocation()}) prompts once via a
     * {@link DirectoryChooser} rooted at the §3.1 workspace ({@code ~/DAW
     * Projects}); a {@code null} return means the user cancelled, so no project
     * is created on disk. Tests inject a stub via
     * {@link #setNewProjectLocationPrompt(Supplier)}.
     */
    private Supplier<Path> newProjectLocationPrompt = this::promptNewProjectLocation;

    /**
     * Story 296 — presents the §4.2 Project Hub built by
     * {@link #buildProjectHub()}. The default ({@link #presentProjectHub(ProjectHubView)})
     * opens it in its own themed {@link Stage}; tests inject a capturing
     * presenter via {@link #setProjectHubPresenter(Consumer)} so the headless
     * {@code RecentMenuOpensHubTest} can assert the hub was opened (rather than a
     * filename {@code ContextMenu} built).
     */
    private Consumer<ProjectHubView> projectHubPresenter = this::presentProjectHub;

    /**
     * Story 296 — presents the §4.1 Welcome screen built by
     * {@link #buildWelcomeView()}. The default
     * ({@link #presentWelcome(WelcomeView)}) opens it in its own themed
     * {@link Stage}; tests inject a capturing presenter via
     * {@link #setWelcomePresenter(Consumer)}.
     */
    private Consumer<WelcomeView> welcomePresenter = this::presentWelcome;

    /**
     * Story 296 — the single auxiliary window currently hosting a Hub / Welcome
     * surface (opened by {@link #showInOwnedStage(String, Parent)}), or
     * {@code null} when none is open. Tracked so a repeat "Project Hub…" closes
     * the prior window instead of stacking a second orphan over it.
     */
    private Stage auxiliaryStage;

    ProjectLifecycleController(ProjectManager projectManager,
                               SessionInterchangeController sessionInterchangeController,
                               NotificationBar notificationBar,
                               ProjectOperationProgress progress,
                               BorderPane rootPane,
                               VBox trackListPanel,
                               Deps deps,
                               ProjectArchiver projectArchiver) {
        this(projectManager, sessionInterchangeController, notificationBar,
                progress, rootPane, trackListPanel, deps,
                projectArchiver, FxDispatcher.getDefault());
    }

    ProjectLifecycleController(ProjectManager projectManager,
                               SessionInterchangeController sessionInterchangeController,
                               NotificationBar notificationBar,
                               ProjectOperationProgress progress,
                               BorderPane rootPane,
                               VBox trackListPanel,
                               Deps deps,
                               ProjectArchiver projectArchiver,
                               FxDispatcher fxDispatcher) {
        this.projectManager = Objects.requireNonNull(projectManager, "projectManager must not be null");
        this.sessionInterchangeController = Objects.requireNonNull(sessionInterchangeController,
                "sessionInterchangeController must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        this.progress = Objects.requireNonNull(progress, "progress must not be null");
        this.rootPane = Objects.requireNonNull(rootPane, "rootPane must not be null");
        this.trackListPanel = Objects.requireNonNull(trackListPanel, "trackListPanel must not be null");
        this.deps = Objects.requireNonNull(deps, "deps must not be null");
        this.projectArchiver = Objects.requireNonNull(projectArchiver, "projectArchiver must not be null");
        // May be null in a pure-unit context; postFx() / the leaf views fall
        // back to the static seam, preserving today's behaviour byte-for-byte.
        this.fxDispatcher = fxDispatcher;
    }

    /**
     * Posts {@code work} to the FX thread through the injected
     * {@link FxDispatcher} when present, else the static app-scoped seam — the
     * null branch reproduces today's behaviour exactly (story 289).
     */
    private void postFx(Runnable work) {
        FxDispatcher.runOnFx(fxDispatcher, work);
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
                // §8 / §1.1 — a brand-new project that has never been saved
                // prompts for a real destination once instead of the silent
                // Files.createTempDirectory into the system temp dir (which the
                // next reboot can wipe). A null return means the user cancelled
                // the location prompt, so nothing is saved — but no /tmp leak.
                Path parentDirectory = newProjectLocationPrompt.get();
                if (parentDirectory == null) {
                    return false;
                }
                projectManager.createProject(deps.project().get().getName(), parentDirectory);
            }
            // Story 282 — capture the live layout state into the project
            // model before serialisation. {@code null} from the supplier
            // clears the field so legacy projects round-trip byte-
            // identical when the layout manager is not installed.
            deps.project().get().setLayoutJson(deps.captureLayoutJson().get());
            projectManager.saveDawProject(deps.project().get());
            projectManager.saveProject();
            deps.project().get().markClean();
            // Story 295 — the Saved cell flashing for ~600 ms is the success
            // signal for a fast save (§4.5). The old "Project saved" notification
            // and the statusBarLabel / checkpointLabel "Saved (checkpoint #N)"
            // pokes are retired (Appendix A). The autosave checkpoint listener
            // also re-arms the countdown; this explicit record keeps the
            // manual-save outcome exact even if no checkpoint fired.
            progress.recordSaveSucceeded(Instant.now(), ProjectOperationProgress.SaveScope.FULL);
            LOG.info("Project saved successfully");
            return true;
        } catch (IOException e) {
            // §4.5 — a save failure persists in the Saved cell (it does not
            // auto-dismiss) and is also surfaced as an error notification the
            // user must act on.
            progress.recordSaveFailed();
            notificationBar.show(NotificationLevel.ERROR, "Save failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to save project", e);
            return false;
        }
    }

    void onNewProject() {
        if (!confirmDiscardUnsavedChanges()) {
            return;
        }
        // §8 / §1.1 — prompt for a real destination once, then write the project
        // there immediately. A null return means the user cancelled, so we do
        // NOT silently create an Untitled project that would later first-save
        // into the system temp directory (the §8 rejection of "silent first-save
        // into /tmp"). createProject() writes project.daw, acquires the lock and
        // starts the heartbeat/autosave on the chosen directory.
        Path parentDirectory = newProjectLocationPrompt.get();
        if (parentDirectory == null) {
            return;
        }
        resetProjectState();
        DawProject newProject = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
        deps.setProject().accept(newProject);
        deps.setUndoManager().accept(new UndoManager());
        deps.rebuildHistoryPanel().run();
        deps.resetTrackCounters().run();
        boolean createdOnDisk = false;
        try {
            projectManager.createProject(newProject.getName(), parentDirectory);
            // Persist the full initial state (createProject writes a metadata-only
            // file first); saveDawProject marks the project clean itself, then we
            // flash the Saved cell.
            newProject.setLayoutJson(null);
            projectManager.saveDawProject(newProject);
            progress.recordSaveSucceeded(Instant.now(), ProjectOperationProgress.SaveScope.FULL);
            createdOnDisk = true;
        } catch (IOException e) {
            // The on-disk create failed (e.g. permissions); keep the in-memory
            // project so the UI is still usable and surface the error.
            progress.recordSaveFailed();
            notificationBar.show(NotificationLevel.ERROR,
                    "Could not create project: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to create new project on disk", e);
        }
        rebuildUI();
        // Story 282 — Reset layouts to the built-in Default so that a
        // previously opened project's saved layouts don't leak into the
        // new project.
        deps.applyLayoutJson().accept(null);
        // Only confirm success when the project actually reached disk — the
        // catch above already surfaced the failure, so don't contradict it.
        if (createdOnDisk) {
            notificationBar.show(NotificationLevel.SUCCESS, "New project created");
            LOG.info("Created new project");
        }
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

    /**
     * Opens the §4.2 Project Hub — the replacement for the old filename
     * {@code ContextMenu} of recent projects (§1.4; §8 "a Recent Projects menu
     * that is just filenames"). Appendix A: "menu retains a 'Project Hub…'
     * item". The File-menu entry now opens this full-window browser, which
     * surfaces last-opened, size-on-disk, a health badge, search, and a
     * per-project detail strip instead of bare filenames.
     */
    void onRecentProjects() {
        projectHubPresenter.accept(buildProjectHub());
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
            deps.setProject().accept(new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY));
            deps.setUndoManager().accept(new UndoManager());
            deps.rebuildHistoryPanel().run();
            deps.resetTrackCounters().run();
            sessionInterchangeController.applySessionData(result.sessionData(), deps.project().get());
            deps.project().get().markDirty();
            rebuildUI();

            String summary = sessionInterchangeController.buildImportSummary(result);
            Alert summaryDialog = new Alert(Alert.AlertType.INFORMATION);
            summaryDialog.setTitle("Import Summary");
            summaryDialog.setHeaderText("Session imported successfully");
            summaryDialog.setContentText(summary);
            ThemeManager.getDefault().applyTo(summaryDialog.getDialogPane());
            summaryDialog.showAndWait();

            notificationBar.show(NotificationLevel.SUCCESS,
                    "Imported session: " + result.sessionData().projectName());
            LOG.info("Imported DAWproject session from " + selected.toPath());
        } catch (IOException e) {
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
                    deps.project().get(), selected.toPath(), deps.project().get().getName());

            String message = "Session exported to " + result.outputPath().getFileName();
            if (!result.warnings().isEmpty()) {
                message += " (" + result.warnings().size() + " warnings)";
            }
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
        DawProject current = deps.project().get();
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
            return;
        }

        // Run the potentially expensive ZIP I/O on a background virtual
        // thread so the JavaFX application thread stays responsive.
        // Progress is surfaced through a modeless TaskProgressIndicator and,
        // per story 295, registered as a named operation on the
        // ProjectOperationProgress model (\u00a75.5) so the strip's saving state
        // reflects the in-flight archive.
        Window owner = rootPane.getScene().getWindow();
        TaskProgressIndicator archiveProgress =
                new TaskProgressIndicator(owner, "Archiving project\u2026", fxDispatcher);
        archiveProgress.hideCancelButton();
        archiveProgress.show();
        archiveProgress.update(-1.0, "Writing archive\u2026");
        ProjectOperationProgress.NamedOperation archiveOp =
                new ProjectOperationProgress.NamedOperation("Archiving project\u2026");
        progress.addOperation(archiveOp);

        Path finalArchivePath = archivePath;
        Thread.ofVirtual()
                .name("daw-archive-worker")
                .start(() -> {
                    try {
                        ProjectArchiveSummary summary = projectArchiver.saveAsArchive(
                                current, finalArchivePath);
                        String headline = ArchiveSummaryDialog.formatHeadline(summary);
                        postFx(() -> {
                            progress.removeOperation(archiveOp);
                            archiveProgress.close();
                            notificationBar.show(NotificationLevel.SUCCESS,
                                    headline + " \u2014 "
                                            + ArchiveSummaryDialog.archivePathDisplay(finalArchivePath));
                            ArchiveSummaryDialog.showSummary(summary);
                        });
                        LOG.info(() -> "Archived project to " + summary.outputPath()
                                + " (" + summary.uniqueAssetCount() + " assets, "
                                + summary.totalAssetBytes() + " bytes)");
                    } catch (IOException | IllegalArgumentException e) {
                        postFx(() -> {
                            progress.removeOperation(archiveOp);
                            archiveProgress.close();
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
        // virtual thread. UI updates (load + notification) are marshalled
        // onto the FX thread via the FxDispatcher seam (story 289) once
        // extraction is complete.
        Window owner = rootPane.getScene().getWindow();
        TaskProgressIndicator restoreProgress =
                new TaskProgressIndicator(owner, "Restoring archive\u2026", fxDispatcher);
        restoreProgress.hideCancelButton();
        restoreProgress.show();
        restoreProgress.update(-1.0, "Extracting archive\u2026");
        ProjectOperationProgress.NamedOperation restoreOp =
                new ProjectOperationProgress.NamedOperation("Restoring archive\u2026");
        progress.addOperation(restoreOp);

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
                        postFx(() -> {
                            progress.removeOperation(restoreOp);
                            restoreProgress.close();
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
                        postFx(() -> {
                            progress.removeOperation(restoreOp);
                            restoreProgress.close();
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
        if (!deps.project().get().isDirty()) {
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
            deps.setProject().accept(dawProject);
            deps.setUndoManager().accept(new UndoManager());
            deps.rebuildHistoryPanel().run();
            deps.resetTrackCounters().run();
            deps.project().get().markClean();
            rebuildUI();
            // Story 282 — Mission Control. Apply the layout JSON blob
            // embedded in the project file (may be {@code null} for
            // legacy projects, in which case the layout manager falls
            // back to the Default built-in).
            deps.applyLayoutJson().accept(dawProject.getLayoutJson());
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
            notificationBar.show(NotificationLevel.ERROR,
                    "Migration failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to migrate project on open", e);
            return false;
        } catch (IOException e) {
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
                deps.setProject().accept(new DawProject("Untitled Project",
                        AudioFormat.STUDIO_QUALITY));
                deps.setUndoManager().accept(new UndoManager());
                deps.rebuildHistoryPanel().run();
                deps.resetTrackCounters().run();
                deps.project().get().markClean();
                rebuildUI();
                notificationBar.show(NotificationLevel.SUCCESS,
                        "Discarded in-memory migration; the on-disk file is "
                                + "still the original (v" + report.fromVersion() + ")");
                LOG.info("Rolled back migration by abandoning in-memory project (no .bak yet)");
            }
        } catch (IOException e) {
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
        MixerView newMixerView = new MixerView(deps.project().get(), deps.undoManager().get(), fxDispatcher);
        deps.onProjectUIRebuild().accept(newMixerView);
    }

    // ── Project Hub / Welcome (story 296) ─────────────────────────────────────

    /**
     * Builds the §4.2 Project Hub bound to this manager's recent projects, a
     * fresh {@link ProjectHealthScanner}, and the open / reveal actions. Each
     * card's size + health is scanned on a background virtual thread and
     * marshalled to the FX thread through the {@link FxDispatcher} (§2.1) — the
     * FX thread never walks a project tree.
     *
     * @return a new Project Hub view
     */
    ProjectHubView buildProjectHub() {
        FxDispatcher d = resolveDispatcher();
        // Self-reference so "Open" can dismiss the Hub window before loading;
        // "Reveal" is a side action and leaves the Hub open.
        ProjectHubView[] ref = new ProjectHubView[1];
        ProjectHubView hub = new ProjectHubView(
                projectManager.getRecentProjectPaths(),
                new ProjectHealthScanner(d),
                projectDir -> {
                    if (projectDir != null && confirmDiscardUnsavedChanges()) {
                        dismiss(ref[0]);
                        loadProjectFromPath(projectDir);
                    }
                },
                this::revealInFileBrowser,
                this::clearRecentProjects);
        ref[0] = hub;
        return hub;
    }

    /**
     * Clears the recent-projects list (the Hub's §1.4 "Clear Recent" action,
     * carried over from the retired recent-projects {@code ContextMenu}).
     */
    private void clearRecentProjects() {
        RecentProjectsStore store = projectManager.getRecentProjectsStore();
        if (store != null) {
            store.clear();
        }
        notificationBar.show(NotificationLevel.SUCCESS, "Recent projects cleared");
    }

    /**
     * Builds the §4.1 Welcome screen (Recover → Continue → Start) bound to this
     * manager's recent projects. The four Start tiles route to the existing
     * lifecycle flows; "Recover" / a Continue card route through
     * {@link #openProjectFromHub(Path)} (the Stage-2 surface only lists recover
     * candidates and opens them — the journal-replay recovery dialog is story
     * 298).
     *
     * @return a new Welcome view
     */
    WelcomeView buildWelcomeView() {
        FxDispatcher d = resolveDispatcher();
        // Self-reference so every Welcome action dismisses the launch window
        // first, then runs the chosen flow over the main window.
        WelcomeView[] ref = new WelcomeView[1];
        WelcomeView view = new WelcomeView(
                projectManager.getRecentProjectPaths(),
                new ProjectHealthScanner(d),
                d,
                () -> { dismiss(ref[0]); onNewProject(); },
                projectDir -> { dismiss(ref[0]); openProjectFromHub(projectDir); },
                () -> { dismiss(ref[0]); onOpenProject(); },
                () -> { dismiss(ref[0]); onRestoreFromArchive(); },
                () -> { dismiss(ref[0]); onImportSession(); });
        ref[0] = view;
        return view;
    }

    /** Hides the window hosting {@code surface} (a Hub / Welcome launch surface), if shown. */
    private static void dismiss(javafx.scene.Node surface) {
        if (surface != null && surface.getScene() != null && surface.getScene().getWindow() != null) {
            surface.getScene().getWindow().hide();
        }
    }

    /**
     * Presents the §4.1 Welcome screen (shown on launch when no project is
     * open). Routed through the {@link #welcomePresenter} seam so a test can
     * capture it without opening a window.
     */
    void showWelcome() {
        welcomePresenter.accept(buildWelcomeView());
    }

    /** Confirms any unsaved changes, then opens {@code projectDir} (Hub / Welcome action). */
    private void openProjectFromHub(Path projectDir) {
        if (projectDir != null && confirmDiscardUnsavedChanges()) {
            loadProjectFromPath(projectDir);
        }
    }

    // ── Seam defaults (overridable for tests / custom wiring) ─────────────────

    /**
     * Default {@link #newProjectLocationPrompt}: a {@link DirectoryChooser}
     * rooted at the §3.1 workspace ({@code ~/DAW Projects}). Returns the chosen
     * parent directory, or {@code null} if the user cancelled.
     */
    private Path promptNewProjectLocation() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose a location for the new project");
        Path workspace = workspaceRoot();
        if (workspace != null && Files.isDirectory(workspace)) {
            chooser.setInitialDirectory(workspace.toFile());
        }
        Window owner = rootPane.getScene() != null ? rootPane.getScene().getWindow() : null;
        java.io.File chosen = chooser.showDialog(owner);
        return chosen == null ? null : chosen.toPath();
    }

    /** The §3.1 default workspace root ({@code ~/DAW Projects}); {@code null} if no home. */
    private static Path workspaceRoot() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        return Path.of(home, "DAW Projects");
    }

    /** Default {@link #projectHubPresenter}: open the Hub in its own themed Stage. */
    private void presentProjectHub(ProjectHubView hub) {
        showInOwnedStage("Project Hub", hub);
    }

    /** Default {@link #welcomePresenter}: open the Welcome screen in its own themed Stage. */
    private void presentWelcome(WelcomeView welcome) {
        showInOwnedStage("Welcome", welcome);
    }

    /**
     * Shows {@code content} as the root of a new {@link Stage} owned by the main
     * window, themed exactly like the primary scene (the ordered
     * {@link ThemeManager} stylesheets + the {@link DensityManager} density
     * class) so role tokens resolve and a theme/density switch reaches it.
     */
    private void showInOwnedStage(String title, Parent content) {
        if (!content.getStyleClass().contains("root-pane")) {
            content.getStyleClass().add("root-pane");
        }
        Scene scene = new Scene(content, 1100, 720);
        ThemeManager.getDefault().applyTo(scene);
        DensityManager.getDefault().applyTo(scene);
        Stage stage = new Stage();
        stage.setTitle(title);
        Window owner = rootPane.getScene() != null ? rootPane.getScene().getWindow() : null;
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setScene(scene);
        // Only ever one auxiliary Hub/Welcome window at a time: close any prior one
        // so a repeated "Project Hub…" replaces rather than stacks orphan windows
        // (each running its own background scans). The hidden-handler clears the
        // reference when the user closes the window themselves.
        if (auxiliaryStage != null && auxiliaryStage.isShowing()) {
            auxiliaryStage.close();
        }
        auxiliaryStage = stage;
        stage.setOnHidden(_ -> {
            if (auxiliaryStage == stage) {
                auxiliaryStage = null;
            }
        });
        stage.show();
    }

    /**
     * Reveals {@code projectDir} in the OS file browser (the Hub's "Reveal"
     * action). Runs the launch on a background virtual thread so a slow shell
     * never stalls the FX thread; errors are marshalled back to the
     * notification bar.
     */
    private void revealInFileBrowser(Path projectDir) {
        if (projectDir == null) {
            return;
        }
        Thread.ofVirtual().name("daw-reveal").start(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    Path toOpen = Files.isDirectory(projectDir) ? projectDir : projectDir.getParent();
                    if (toOpen == null) {
                        postFx(() -> notificationBar.show(NotificationLevel.ERROR,
                                "Could not reveal project: no parent directory"));
                        return;
                    }
                    java.awt.Desktop.getDesktop().open(toOpen.toFile());
                } else {
                    postFx(() -> notificationBar.show(NotificationLevel.ERROR,
                            "Reveal is not supported on this platform"));
                }
            } catch (IOException | RuntimeException e) {
                postFx(() -> notificationBar.show(NotificationLevel.ERROR,
                        "Could not reveal project: " + e.getMessage()));
                LOG.log(Level.WARNING, "Failed to reveal project directory", e);
            }
        });
    }

    /**
     * Resolves a non-{@code null} {@link FxDispatcher} for the scanner / views:
     * the injected one, else the app-scoped default, else a fresh instance (the
     * scanner only uses {@link FxDispatcher#onFx(Runnable)}, which works without
     * {@code start()}).
     */
    private FxDispatcher resolveDispatcher() {
        if (fxDispatcher != null) {
            return fxDispatcher;
        }
        FxDispatcher def = FxDispatcher.getDefault();
        return def != null ? def : new FxDispatcher();
    }

    // ── Seam overrides (story 296) ────────────────────────────────────────────

    /**
     * Overrides the new-project location prompt (the §8 "kill the /tmp
     * first-save" seam). Tests inject a stub returning a fixed directory.
     *
     * @param prompt supplies the destination parent directory, or {@code null}
     *               to cancel; must not be {@code null}
     */
    void setNewProjectLocationPrompt(Supplier<Path> prompt) {
        this.newProjectLocationPrompt = Objects.requireNonNull(prompt, "prompt must not be null");
    }

    /**
     * Overrides the Project Hub presenter. Tests inject a capturing consumer to
     * assert the Hub was opened without showing a window.
     *
     * @param presenter the hub presenter; must not be {@code null}
     */
    void setProjectHubPresenter(Consumer<ProjectHubView> presenter) {
        this.projectHubPresenter = Objects.requireNonNull(presenter, "presenter must not be null");
    }

    /**
     * Overrides the Welcome-screen presenter. Tests inject a capturing consumer.
     *
     * @param presenter the welcome presenter; must not be {@code null}
     */
    void setWelcomePresenter(Consumer<WelcomeView> presenter) {
        this.welcomePresenter = Objects.requireNonNull(presenter, "presenter must not be null");
    }
}
