package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshotManager;
import com.benesquivelmusic.daw.core.mixer.snapshot.RecallSnapshotAction;
import com.benesquivelmusic.daw.core.mixer.snapshot.SaveSnapshotAction;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * Side panel that surfaces {@link MixerSnapshotManager}'s saved mixer-scene
 * snapshots in the {@link MixerView}.
 *
 * <p>The panel is the user-facing companion to Story 103 — "Mixer Scene
 * Snapshots and A/B Recall for Mix Comparison" — and lets the engineer:</p>
 *
 * <ul>
 *   <li>Save the current mixer state as a named snapshot
 *       ({@link SaveSnapshotAction}).</li>
 *   <li>Recall any saved snapshot as a single compound undoable step
 *       ({@link RecallSnapshotAction}).</li>
 *   <li>Delete snapshots that are no longer needed.</li>
 * </ul>
 *
 * <p>The "Save current state…" button is disabled when the project has
 * reached {@link MixerSnapshotManager#MAX_SNAPSHOTS} snapshots and shows a
 * clear tooltip explaining the limit.</p>
 *
 * <p>Recall integrates with the project's {@link UndoManager} so the user
 * can undo a recall in one step, restoring the pre-recall mixer state for
 * every channel, insert, and send.</p>
 */
public final class MixerSnapshotsPanel extends VBox {

    private static final double PANEL_WIDTH = 240.0;
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private final MixerSnapshotManager manager;
    private final Mixer mixer;
    private final UndoManager undoManager;
    private final ObservableList<MixerSnapshot> items = FXCollections.observableArrayList();
    private final ListView<MixerSnapshot> list;
    private final Button saveButton;
    private Runnable onChange = () -> { };

    /**
     * Creates a snapshots panel bound to the given manager and mixer.
     *
     * @param manager     the snapshot manager (must not be {@code null})
     * @param mixer       the mixer the snapshots capture/recall (must not be {@code null})
     * @param undoManager the undo manager that recall/save go through; may be
     *                    {@code null}, in which case mutations bypass undo
     */
    public MixerSnapshotsPanel(MixerSnapshotManager manager, Mixer mixer, UndoManager undoManager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.mixer = Objects.requireNonNull(mixer, "mixer must not be null");
        this.undoManager = undoManager;

        getStyleClass().add("mixer-snapshots-panel");
        setPadding(new Insets(8));
        setSpacing(6);
        setPrefWidth(PANEL_WIDTH);
        setMinWidth(PANEL_WIDTH);

        Label header = new Label("Snapshots");
        header.getStyleClass().add("panel-header");

        saveButton = new Button("Save current state…");
        saveButton.setMaxWidth(Double.MAX_VALUE);
        saveButton.setOnAction(_ -> promptAndSave());

        list = new ListView<>(items);
        list.setCellFactory(_ -> buildCell());
        VBox.setVgrow(list, Priority.ALWAYS);

        getChildren().addAll(header, saveButton, list);
        refresh();
    }

    /**
     * Sets a callback invoked whenever the panel mutates the snapshot
     * manager (save, delete, recall). The host (e.g. {@link MixerView})
     * uses this to refresh dependent UI like A/B button highlights.
     *
     * @param onChange the callback (must not be {@code null})
     */
    public void setOnChange(Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "onChange must not be null");
    }

    /** Returns the underlying snapshot list for testing and UI binding. */
    public ListView<MixerSnapshot> getSnapshotList() {
        return list;
    }

    /** Returns the "Save current state…" button. Visible for testing. */
    public Button getSaveButton() {
        return saveButton;
    }

    /**
     * Reloads the panel from the manager. Call after any external change to
     * the snapshot list (e.g. project load, undo/redo).
     */
    public void refresh() {
        items.setAll(manager.getSnapshots());
        boolean atCap = manager.getSnapshotCount() >= MixerSnapshotManager.MAX_SNAPSHOTS;
        saveButton.setDisable(atCap);
        if (atCap) {
            saveButton.setTooltip(new Tooltip(
                    "Maximum of " + MixerSnapshotManager.MAX_SNAPSHOTS
                            + " snapshots reached — delete one to save another."));
        } else {
            saveButton.setTooltip(new Tooltip(
                    "Capture the current mixer state as a named snapshot."));
        }
    }

    /**
     * Captures the current mixer state under the given name. Used by
     * {@link MixerView} for the "Save current state to A/B" right-click
     * menus and exposed for testing.
     *
     * @param name the snapshot name
     * @return the captured snapshot
     */
    public MixerSnapshot saveSnapshot(String name) {
        Objects.requireNonNull(name, "name must not be null");
        SaveSnapshotAction action = new SaveSnapshotAction(manager, mixer, name);
        if (undoManager != null) {
            undoManager.execute(action);
        } else {
            action.execute();
        }
        refresh();
        onChange.run();
        return action.getSavedSnapshot();
    }

    /**
     * Recalls the given snapshot as a single compound undoable action,
     * restoring all per-channel volumes, pans, mutes, solos, insert
     * parameters, and send levels.
     *
     * @param snapshot the snapshot to recall
     */
    public void recallSnapshot(MixerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        RecallSnapshotAction action = new RecallSnapshotAction(mixer, snapshot);
        if (undoManager != null) {
            undoManager.execute(action);
        } else {
            action.execute();
        }
        onChange.run();
    }

    private void promptAndSave() {
        TextInputDialog dialog = new TextInputDialog("Snapshot " + (manager.getSnapshotCount() + 1));
        dialog.setTitle("Save Mixer Snapshot");
        dialog.setHeaderText("Save the current mixer state as a snapshot.");
        dialog.setContentText("Name:");
        Optional<String> result = dialog.showAndWait();
        result.map(String::trim)
                .filter(s -> !s.isEmpty())
                .ifPresent(this::saveSnapshot);
    }

    private ListCell<MixerSnapshot> buildCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(MixerSnapshot snapshot, boolean empty) {
                super.updateItem(snapshot, empty);
                if (empty || snapshot == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(snapshot.name());
                name.getStyleClass().add("mixer-snapshot-name");
                Label time = new Label(TIMESTAMP_FMT.format(snapshot.timestamp()));
                time.getStyleClass().add("mixer-snapshot-time");
                VBox info = new VBox(2, name, time);
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                Button recallBtn = new Button("Recall");
                recallBtn.setTooltip(new Tooltip("Recall this snapshot (undoable)."));
                recallBtn.setOnAction(_ -> recallSnapshot(snapshot));

                Button deleteBtn = new Button("✕");
                deleteBtn.setTooltip(new Tooltip("Delete this snapshot."));
                deleteBtn.setOnAction(_ -> {
                    manager.removeSnapshot(snapshot);
                    refresh();
                    onChange.run();
                });

                HBox row = new HBox(6, info, spacer, recallBtn, deleteBtn);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        };
    }
}
