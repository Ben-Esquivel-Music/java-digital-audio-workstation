package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.CheckpointStatusListener;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrigin;
import com.benesquivelmusic.daw.core.persistence.MissingAssetsReport;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.snapshot.SnapshotBrowserService;
import com.benesquivelmusic.daw.core.snapshot.SnapshotDiff;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wires together the snapshot browser workflow surfaced by Story 190 —
 * <em>Snapshot History Browser with Visual Diff Preview</em>.
 *
 * <p>Owns the (data-only) {@link SnapshotBrowserService} and the lazy
 * JavaFX {@link SnapshotBrowser} dialog. Translates the three browser
 * actions — Restore, Compare, Create Checkpoint — into operations on the
 * application state via the {@link Host} callback. The controller is
 * intentionally a thin facade so tests can drive the same code paths
 * without spinning up a full main window.</p>
 *
 * <p>This controller is constructed once during {@code MainController}
 * startup and lives for the entire session. Per the issue, autosave
 * snapshots are retained for 7 days rolling, user checkpoints
 * indefinitely, and undo points session-only — all enforced by
 * {@link SnapshotBrowserService}.</p>
 */
final class SnapshotsController {

    private static final Logger LOG = Logger.getLogger(SnapshotsController.class.getName());

    private static final DateTimeFormatter LABEL_TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * The WARNING of a Create Checkpoint on a project that was never saved
     * (ruling K2): the labelled entry exists in memory only.
     */
    static final String KEPT_IN_MEMORY_UNSAVED_MESSAGE = "Checkpoint kept in memory only, until the application"
            + " closes: save the project to write checkpoints to disk.";

    /**
     * The WARNING of a Create Checkpoint on a project open read-only (ruling
     * K2): the labelled entry exists in memory only.
     */
    static final String KEPT_IN_MEMORY_READ_ONLY_MESSAGE = "Checkpoint kept in memory only, until the application"
            + " closes: this project is open read-only, so no checkpoint is written to its folder.";

    /**
     * The functional dependencies the composition root supplies — story 294
     * replaces the callback-up {@code Host} interface (Control Synchronization
     * Design Book §4.2/§9 "use publish/subscribe, not a callback-up {@code Host}
     * for cross-surface updates") with a record of direct functional deps. The
     * swappable owner stage / current project are {@link Supplier}s read live;
     * the gate a restore passes before it replaces the open project takes the
     * restore as its continuation (delegated to
     * {@code ProjectLifecycleController.whenProjectMayClose}, which refuses
     * while a recording is in flight or a take is being written, waits for a
     * save still being written, then asks about unsaved changes, and runs the
     * continuation only if the project may close: story 331, ruling G1); the
     * restore cascade is a {@link RestoredProjectSink} that swaps the
     * project, resets the undo manager, and rebuilds the UI exactly as for an
     * open-from-disk — and, story 329, loads the restored clips' audio and
     * announces the restore qualified by what they could not find.
     */
    record Deps(
            Supplier<Stage> ownerStage,
            Supplier<DawProject> currentProject,
            Consumer<Runnable> whenProjectMayClose,
            RestoredProjectSink applyRestoredProject) {
        Deps {
            Objects.requireNonNull(ownerStage, "ownerStage must not be null");
            Objects.requireNonNull(currentProject, "currentProject must not be null");
            Objects.requireNonNull(whenProjectMayClose,
                    "whenProjectMayClose must not be null");
            Objects.requireNonNull(applyRestoredProject, "applyRestoredProject must not be null");
        }
    }

    /**
     * Installs a restored snapshot as the live project — the restore cascade
     * of {@link Deps}.
     */
    @FunctionalInterface
    interface RestoredProjectSink {

        /**
         * Installs {@code restored}.
         *
         * @param restored      the deserialized snapshot
         * @param label         the snapshot's label
         * @param missingAssets the clip sources its deserialization found
         *                      missing or unresolvable (story 329), classified
         *                      without decoding anything
         * @param unreadItems   the saved items its deserialization could not
         *                      honour as written (story 330)
         */
        void apply(DawProject restored, String label, MissingAssetsReport missingAssets,
                   List<UnreadProjectItem> unreadItems);
    }

    private final SnapshotBrowserService service;
    private final CheckpointManager checkpointManager;
    private final ProjectManager projectManager;
    private final Deps deps;
    private final ProjectDeserializer deserializer = new ProjectDeserializer();
    private java.util.function.BooleanSupplier projectActionsAllowed = () -> true;

    void setProjectActionsAllowed(java.util.function.BooleanSupplier allowed) {
        projectActionsAllowed = Objects.requireNonNull(allowed);
    }

    /**
     * The FX-thread marshalling seam (story 289), forwarded to the lazily-built
     * {@link SnapshotBrowser}. May be {@code null} in a pure-unit context (the
     * compatibility constructor defaults it to {@link FxDispatcher#getDefault()});
     * the browser tolerates the null via its own fallback.
     */
    private final FxDispatcher fxDispatcher;

    /**
     * Where a Create Checkpoint that wrote nothing is reported (story 331,
     * rulings G8, G13 #9), and which a restore asks to post the countdown's
     * last re-arm again (ruling M4): installed by {@code MainController};
     * until then — a pure-unit context — such a failure is only logged, at
     * WARNING, and a restore posts nothing.
     */
    private CheckpointStatusListener checkpointStatus;

    /** The last project checkpoint directory registered with the service,
     *  tracked so it can be removed when a different project is opened. */
    private Path lastRegisteredDirectory;
    private Path lastRegisteredNamedSnapshotDirectory;
    private Stage browserStage;
    private SnapshotBrowser browserView;

    SnapshotsController(SnapshotBrowserService service,
                        CheckpointManager checkpointManager,
                        ProjectManager projectManager,
                        Deps deps) {
        this(service, checkpointManager, projectManager, deps, FxDispatcher.getDefault());
    }

    SnapshotsController(SnapshotBrowserService service,
                        CheckpointManager checkpointManager,
                        ProjectManager projectManager,
                        Deps deps,
                        FxDispatcher fxDispatcher) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.checkpointManager = Objects.requireNonNull(checkpointManager,
                "checkpointManager must not be null");
        this.projectManager = Objects.requireNonNull(projectManager,
                "projectManager must not be null");
        this.deps = Objects.requireNonNull(deps, "deps must not be null");
        // May be null in a pure-unit context; SnapshotBrowser falls back to the
        // static seam, preserving today's behaviour byte-for-byte.
        this.fxDispatcher = fxDispatcher;
    }

    /** Returns the underlying snapshot service (intended for tests). */
    SnapshotBrowserService service() {
        return service;
    }

    /**
     * Installs where a Create Checkpoint that wrote nothing is reported — the
     * strip's checkpoint state and an ERROR notification (story 331) — and
     * what keeps the countdown through a restore (ruling M4).
     *
     * @param status the checkpoint-status listener; must not be {@code null}
     */
    void setCheckpointStatus(CheckpointStatusListener status) {
        this.checkpointStatus = Objects.requireNonNull(status, "status must not be null");
    }

    /** Returns the lazily-created browser view, or {@code null} if never opened (intended for tests). */
    SnapshotBrowser browserView() {
        return browserView;
    }

    /**
     * Wires the currently-open project's {@code checkpoints/} directory
     * into the snapshot service so on-disk autosaves emitted by
     * {@link CheckpointManager} appear in the browser timeline.
     *
     * <p>When the project changes, the previously-registered directory is
     * removed first so the browser never shows snapshots from a different
     * project. In-memory user checkpoints and undo points are also cleared
     * because they belong to the old project's state.</p>
     *
     * <p>Called by {@code MainController} after every project open / new
     * so a freshly loaded project's history is immediately discoverable.</p>
     */
    void registerCurrentProjectDirectory() {
        // Remove the previous project's directory so the browser never
        // mixes snapshot timelines from different projects.
        if (lastRegisteredDirectory != null) {
            service.removeAutosaveDirectory(lastRegisteredDirectory);
            lastRegisteredDirectory = null;
        }
        if (lastRegisteredNamedSnapshotDirectory != null) {
            service.removeNamedSnapshotDirectory(lastRegisteredNamedSnapshotDirectory);
            lastRegisteredNamedSnapshotDirectory = null;
        }
        // Clear in-memory snapshots belonging to the old project.
        service.clearSession();

        ProjectMetadata current = projectManager.getCurrentProject();
        if (current == null || current.projectPath() == null) {
            return;
        }
        Path dir = current.projectPath().resolve("checkpoints");
        service.addAutosaveDirectory(dir);
        lastRegisteredDirectory = dir;
        Path snapshotsDir = current.projectPath().resolve("snapshots");
        service.addNamedSnapshotDirectory(snapshotsDir);
        lastRegisteredNamedSnapshotDirectory = snapshotsDir;
        if (browserView != null) {
            browserView.refresh();
        }
    }

    /**
     * Opens (or focuses) the snapshot browser dialog. The dialog is
     * modeless so the user can keep editing while consulting their
     * history.
     */
    void openBrowser() {
        registerCurrentProjectDirectory();
        if (browserStage == null) {
            browserView = new SnapshotBrowser(service, fxDispatcher);
            browserView.setOnRestore(this::restore);
            browserView.setOnCompare(this::compare);
            browserView.setOnCreateCheckpoint(this::createCheckpoint);
            browserStage = new Stage();
            browserStage.setTitle("Snapshots");
            browserStage.initModality(Modality.NONE);
            Stage owner = deps.ownerStage().get();
            if (owner != null) {
                browserStage.initOwner(owner);
            }
            Scene scene = new Scene(browserView, 480, 520);
            ThemeManager.getDefault().applyTo(scene);
            browserStage.setScene(scene);
        }
        browserView.refresh();
        browserStage.show();
        browserStage.toFront();
    }

    /** Refreshes the visible browser after an external producer writes a snapshot. */
    void refreshBrowser() {
        if (browserView != null) {
            browserView.refresh();
        }
    }

    /**
     * Implements the <em>File → Create Checkpoint</em> action and
     * {@code Ctrl+Alt+S} shortcut. Prompts for an optional label, then
     * captures the current project state both as an in-memory user
     * checkpoint (always available in the browser timeline) and on
     * disk through the {@link CheckpointManager}, so the
     * checkpoint survives a JVM restart.
     */
    void createCheckpoint() {
        if (!projectActionsAllowed.getAsBoolean()) return;
        TextInputDialog dialog = new TextInputDialog("");
        dialog.setTitle("Create Checkpoint");
        dialog.setHeaderText("Save the current state as a recoverable checkpoint.");
        dialog.setContentText("Label (optional):");
        Stage owner = deps.ownerStage().get();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        ThemeManager.getDefault().applyTo(dialog.getDialogPane());
        Optional<String> result = dialog.showAndWait();
        // Empty optional == user cancelled. An empty string == accepted with no label.
        if (result.isEmpty()) {
            return;
        }
        createCheckpointWithLabel(result.get());
    }

    /**
     * Headless entry point used by the keyboard shortcut, the menu item,
     * and tests. Adds an in-memory user checkpoint with the given label
     * and asks {@link CheckpointManager} to persist a numbered file.
     *
     * <p>Story 331 (Persistence Integrity book §4.2; rulings G8, G13 #9): the
     * project is captured and rendered once, here, on the FX thread — the
     * browser entry needs the document at once, and rendering touches no
     * disk — and the same document is handed to
     * {@link CheckpointManager#writeCheckpoint} as a
     * {@link CheckpointOrigin#USER} checkpoint, which is always written. That
     * request is made here too, so it belongs to the project the user asked
     * it for: a project switch right after it can never send this project's
     * document into the next project's {@code checkpoints/}. The call only
     * queues; the write runs on the checkpoint writer. A checkpoint that
     * writes nothing — the capture failed, no checkpoints are running, the
     * write failed, or the project was replaced before it was written — is
     * reported every time: an ERROR notification naming the cause and the
     * strip's checkpoint failure state (of the project it was asked for,
     * never of the one that replaced it).</p>
     *
     * <p>Two projects have no folder a checkpoint may be written to (ruling
     * K2): one that was never saved, and one open read-only. For them the
     * labelled entry is still created — restorable from the browser until the
     * application closes — but nothing is asked of the checkpoints: a WARNING
     * says so every time ({@link #KEPT_IN_MEMORY_UNSAVED_MESSAGE},
     * {@link #KEPT_IN_MEMORY_READ_ONLY_MESSAGE}), logged at INFO, and nothing
     * failed, so no checkpoint failure state is set and nothing counts toward
     * the autosave streak.</p>
     *
     * @param label optional user-supplied label (may be {@code null}/blank)
     * @return the in-memory entry, or {@code null} if the project could not be captured
     */
    SnapshotEntry createCheckpointWithLabel(String label) {
        if (!projectActionsAllowed.getAsBoolean()) return null;
        String trimmed = (label == null) ? "" : label.trim();
        String safeLabel = trimmed.isEmpty()
                ? "Checkpoint " + LABEL_TIME_FMT.format(LocalTime.now())
                : trimmed;
        Consumer<Throwable> reportFailure = failureReporter();
        DawProject project = deps.currentProject().get();
        CheckpointManager.Capture capture;
        String content;
        try {
            capture = checkpointManager.captureStarted(project.getEditRevision());
            // Story 333 (E3-S5) — the open project's own capture: a Save As copy's former roots included.
            content = projectManager.captureOpenProject(project).toXml();
        } catch (Throwable notCaptured) {
            // Anything the capture throws, an Error included, is a checkpoint that wrote nothing: never silent.
            LOG.log(Level.WARNING, "Failed to capture the project for a checkpoint; nothing was written", notCaptured);
            reportFailure.accept(notCaptured);
            return null;
        }
        SnapshotEntry entry = service.createUserCheckpoint(safeLabel, content);
        Optional<String> keptInMemoryOnly = whyNoCheckpointIsWritten();
        if (keptInMemoryOnly.isPresent()) {
            String message = keptInMemoryOnly.get();
            LOG.info(() -> "The checkpoint \"" + safeLabel + "\" is kept in memory only: " + message);
            reportKeptInMemory(message);
        } else {
            writeCheckpoint(content, capture, safeLabel, reportFailure);
        }
        if (browserView != null) {
            browserView.refresh();
        }
        return entry;
    }

    /**
     * Why a user checkpoint of the open project is kept in memory only
     * (ruling K2): it was never saved, or it is open read-only; empty when it
     * has a folder its checkpoints are written to.
     */
    private Optional<String> whyNoCheckpointIsWritten() {
        if (projectManager.getCurrentProject() == null) {
            return Optional.of(KEPT_IN_MEMORY_UNSAVED_MESSAGE);
        }
        if (projectManager.isReadOnly()) {
            return Optional.of(KEPT_IN_MEMORY_READ_ONLY_MESSAGE);
        }
        return Optional.empty();
    }

    /** Shows the kept-in-memory WARNING through the installed checkpoint status, or only logs it. */
    private void reportKeptInMemory(String message) {
        CheckpointStatusListener status = checkpointStatus;
        if (status == null) {
            LOG.warning(() -> "No checkpoint status is installed to show: " + message);
            return;
        }
        status.userCheckpointKeptInMemory(message);
    }

    /** Asks the checkpoints for the user checkpoint and reports it if it writes nothing. FX thread. */
    private void writeCheckpoint(String content, CheckpointManager.Capture capture, String safeLabel, Consumer<Throwable> reportFailure) {
        checkpointManager.writeCheckpoint(content, capture, CheckpointOrigin.USER)
                .whenComplete((file, failure) -> {
                    if (failure == null) {
                        LOG.fine(() -> "Wrote the checkpoint " + file + " (" + safeLabel + ")");
                        return;
                    }
                    Throwable cause = failure instanceof CompletionException wrapped && wrapped.getCause() != null
                            ? wrapped.getCause() : failure;
                    LOG.log(Level.WARNING, cause, () -> "The checkpoint \"" + safeLabel + "\" was not written");
                    reportFailure.accept(cause);
                });
    }

    /**
     * The failure reporter of a checkpoint the user asks for now: the
     * installed checkpoint status, bound to the open project, or a WARNING
     * log line where none is installed (a pure-unit context).
     */
    private Consumer<Throwable> failureReporter() {
        CheckpointStatusListener status = checkpointStatus;
        if (status == null) {
            return cause -> LOG.log(Level.WARNING, "No checkpoint status is installed to report a checkpoint that"
                    + " wrote nothing", cause);
        }
        return status.userCheckpointRequested();
    }

    void restoreEntry(SnapshotEntry entry) {
        restore(entry);
    }

    void compareEntry(SnapshotEntry entry) {
        compare(entry);
    }

    private void restore(SnapshotEntry entry) {
        if (entry == null) {
            return;
        }
        deps.whenProjectMayClose().accept(() -> restoreNow(entry));
    }

    /**
     * Installs {@code entry} once the gate let the restore replace the open
     * project. The project's checkpoints run on through a restore, so in the
     * same FX step the installed checkpoint status posts the countdown's last
     * re-arm again, under the restore's project generation (story 331,
     * ruling M4).
     */
    private void restoreNow(SnapshotEntry entry) {
        try {
            DawProject project = loadFromEntry(entry);
            // Story 333 (E3-N7) — a snapshot is a state of the open project, which keeps its name: a snapshot a
            // Save As copied still carries the original's name, and the copy's name is its folder's (ruling R6).
            DawProject open = deps.currentProject().get();
            if (open != null && !open.getName().equals(project.getName())) {
                project.setName(open.getName());
            }
            // Story 329 — what this deserialization found missing, read right
            // after it (the deserializer is reused: the next load clears it);
            // story 330 — and what it could not honour as written.
            MissingAssetsReport missingAssets = MissingAssetsReport.atOpen(
                    project, deserializer.getMissingFiles(), currentProjectDirectory());
            List<UnreadProjectItem> unreadItems = deserializer.getUnreadItems();
            deps.applyRestoredProject().apply(project, entry.label(), missingAssets, unreadItems);
            CheckpointStatusListener status = checkpointStatus;
            if (status != null) {
                status.snapshotRestored();
            }
            if (browserView != null) {
                browserView.refresh();
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to restore snapshot " + entry.id(), e);
            showError("Restore failed", e);
        }
    }

    private void compare(SnapshotEntry entry) {
        if (entry == null) {
            return;
        }
        try {
            DawProject snapshotProject = loadFromEntry(entry);
            SnapshotDiff diff = SnapshotDiff.between(snapshotProject, deps.currentProject().get());
            showDiffDialog(entry, diff);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to compare snapshot " + entry.id(), e);
            showError("Compare failed", e);
        }
    }

    /**
     * Loads the {@link DawProject} represented by the given entry,
     * deserialising the snapshot's stored XML. Visible for tests so the
     * "restore equals direct load" guarantee can be verified without
     * driving the JavaFX dialog.
     *
     * <p>A snapshot is a checkpoint of the current project, so its
     * project-relative clip references (story 323) resolve against the
     * current project's directory; with no open project directory (unit
     * tests, an unsaved project) references are kept verbatim.</p>
     *
     * <p>When the project directory is known it is also stamped on the
     * loaded project's metadata, mirroring {@code ProjectManager.openProject}.
     * A restored snapshot replaces the live project, and a project without a
     * directory cannot record (the take has no home) and serializes its
     * clip references absolute in every later checkpoint.</p>
     */
    DawProject loadFromEntry(SnapshotEntry entry) throws IOException {
        Objects.requireNonNull(entry, "entry must not be null");
        Path projectDirectory = currentProjectDirectory();
        DawProject project = deserializer.deserialize(entry.loadContent(), projectDirectory);
        if (projectDirectory != null) {
            project.setMetadata(project.getMetadata().withPath(projectDirectory));
        }
        return project;
    }

    /** The open project's directory, against which snapshots resolve; {@code null} when there is none. */
    private Path currentProjectDirectory() {
        ProjectMetadata current = projectManager.getCurrentProject();
        return current != null ? current.projectPath() : null;
    }

    private void showDiffDialog(SnapshotEntry entry, SnapshotDiff diff) {
        TableView<SnapshotDiff.Entry> table = new TableView<>();
        // SnapshotDiff.Entry is a Java record — use explicit cell-value
        // factories (not PropertyValueFactory) because records expose
        // accessors (category()) rather than JavaBean getters (getCategory()).
        TableColumn<SnapshotDiff.Entry, String> categoryCol = new TableColumn<>("Category");
        categoryCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().category()));
        TableColumn<SnapshotDiff.Entry, String> identifierCol = new TableColumn<>("Item");
        identifierCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().identifier()));
        TableColumn<SnapshotDiff.Entry, SnapshotDiff.ChangeType> changeCol = new TableColumn<>("Change");
        changeCol.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().changeType()));
        TableColumn<SnapshotDiff.Entry, String> descriptionCol = new TableColumn<>("Description");
        descriptionCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().description()));
        descriptionCol.setPrefWidth(260);
        table.getColumns().addAll(categoryCol, identifierCol, changeCol, descriptionCol);
        table.getItems().setAll(diff.entries());

        Stage diffStage = new Stage();
        diffStage.setTitle("Compare with current — " + entry.label());
        diffStage.initModality(Modality.NONE);
        Stage owner = deps.ownerStage().get();
        if (owner != null) {
            diffStage.initOwner(owner);
        }
        Scene scene = new Scene(table, 640, 400);
        ThemeManager.getDefault().applyTo(scene);
        diffStage.setScene(scene);
        diffStage.show();
    }

    private void showError(String header, Throwable cause) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Snapshot");
        alert.setHeaderText(header);
        alert.setContentText(cause.getMessage());
        Stage owner = deps.ownerStage().get();
        if (owner != null) {
            alert.initOwner(owner);
        }
        ThemeManager.getDefault().applyTo(alert.getDialogPane());
        alert.showAndWait();
    }
}
