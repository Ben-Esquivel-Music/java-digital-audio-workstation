package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
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
import java.util.Objects;
import java.util.Optional;
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
     * Callback interface implemented by {@code MainController} so the
     * snapshot workflow can read project state, prompt the user, and
     * apply a restored project without depending on the host class
     * directly.
     */
    interface Host {
        /** The window that should own modal/secondary stages, may be {@code null} during startup. */
        Stage ownerStage();

        /** The currently-loaded project, never {@code null} once initialised. */
        DawProject currentProject();

        /**
         * Prompts the user to save / discard / cancel any unsaved
         * changes before a destructive operation (typically delegated to
         * {@code ProjectLifecycleController.confirmDiscardUnsavedChanges()}).
         *
         * @return {@code true} if the operation should proceed
         */
        boolean confirmDiscardUnsavedChanges();

        /**
         * Replaces the currently-open project with the one restored from
         * a snapshot. Implementations should swap the project, reset the
         * undo manager, and rebuild the UI exactly as for an open-from-disk.
         */
        void applyRestoredProject(DawProject project, String label);
    }

    private final SnapshotBrowserService service;
    private final CheckpointManager checkpointManager;
    private final ProjectManager projectManager;
    private final Host host;
    private final ProjectSerializer serializer = new ProjectSerializer();
    private final ProjectDeserializer deserializer = new ProjectDeserializer();

    /**
     * The FX-thread marshalling seam (story 289), forwarded to the lazily-built
     * {@link SnapshotBrowser}. May be {@code null} in a pure-unit context (the
     * compatibility constructor defaults it to {@link FxDispatcher#getDefault()});
     * the browser tolerates the null via its own fallback.
     */
    private final FxDispatcher fxDispatcher;

    /** The last project checkpoint directory registered with the service,
     *  tracked so it can be removed when a different project is opened. */
    private Path lastRegisteredDirectory;
    private Stage browserStage;
    private SnapshotBrowser browserView;

    SnapshotsController(SnapshotBrowserService service,
                        CheckpointManager checkpointManager,
                        ProjectManager projectManager,
                        Host host) {
        this(service, checkpointManager, projectManager, host, FxDispatcher.getDefault());
    }

    SnapshotsController(SnapshotBrowserService service,
                        CheckpointManager checkpointManager,
                        ProjectManager projectManager,
                        Host host,
                        FxDispatcher fxDispatcher) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.checkpointManager = Objects.requireNonNull(checkpointManager,
                "checkpointManager must not be null");
        this.projectManager = Objects.requireNonNull(projectManager,
                "projectManager must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");
        // May be null in a pure-unit context; SnapshotBrowser falls back to the
        // static seam, preserving today's behaviour byte-for-byte.
        this.fxDispatcher = fxDispatcher;
    }

    /** Returns the underlying snapshot service (intended for tests). */
    SnapshotBrowserService service() {
        return service;
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
        // Clear in-memory snapshots belonging to the old project.
        service.clearSession();

        ProjectMetadata current = projectManager.getCurrentProject();
        if (current == null || current.projectPath() == null) {
            return;
        }
        Path dir = current.projectPath().resolve("checkpoints");
        service.addAutosaveDirectory(dir);
        lastRegisteredDirectory = dir;
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
            Stage owner = host.ownerStage();
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

    /**
     * Implements the <em>File → Create Checkpoint</em> action and
     * {@code Ctrl+Alt+S} shortcut. Prompts for an optional label, then
     * captures the current project state both as an in-memory user
     * checkpoint (always available in the browser timeline) and on
     * disk via the periodic {@link CheckpointManager}, so the
     * checkpoint survives a JVM restart.
     */
    void createCheckpoint() {
        TextInputDialog dialog = new TextInputDialog("");
        dialog.setTitle("Create Checkpoint");
        dialog.setHeaderText("Save the current state as a recoverable checkpoint.");
        dialog.setContentText("Label (optional):");
        Stage owner = host.ownerStage();
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
     * @param label optional user-supplied label (may be {@code null}/blank)
     * @return the in-memory entry, or {@code null} if serialisation failed
     */
    SnapshotEntry createCheckpointWithLabel(String label) {
        String trimmed = (label == null) ? "" : label.trim();
        String safeLabel = trimmed.isEmpty()
                ? "Checkpoint " + LABEL_TIME_FMT.format(LocalTime.now())
                : trimmed;
        String content;
        try {
            content = serializer.serialize(host.currentProject());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to serialize project for checkpoint", e);
            return null;
        }
        SnapshotEntry entry = service.createUserCheckpoint(safeLabel, content);
        // Best-effort: also write a numbered checkpoint file so the
        // user-checkpoint survives a JVM restart. performCheckpoint() is
        // a no-op when the manager has no project directory yet.
        try {
            checkpointManager.performCheckpoint();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "On-disk checkpoint not persisted", e);
        }
        if (browserView != null) {
            browserView.refresh();
        }
        return entry;
    }

    private void restore(SnapshotEntry entry) {
        if (entry == null) {
            return;
        }
        if (!host.confirmDiscardUnsavedChanges()) {
            return;
        }
        try {
            DawProject project = loadFromEntry(entry);
            host.applyRestoredProject(project, entry.label());
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
            SnapshotDiff diff = SnapshotDiff.between(snapshotProject, host.currentProject());
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
     */
    DawProject loadFromEntry(SnapshotEntry entry) throws IOException {
        Objects.requireNonNull(entry, "entry must not be null");
        return deserializer.deserialize(entry.loadContent());
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
        Stage owner = host.ownerStage();
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
        Stage owner = host.ownerStage();
        if (owner != null) {
            alert.initOwner(owner);
        }
        ThemeManager.getDefault().applyTo(alert.getDialogPane());
        alert.showAndWait();
    }
}
