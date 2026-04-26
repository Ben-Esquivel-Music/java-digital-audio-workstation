package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.snapshot.SnapshotBrowserService;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;
import com.benesquivelmusic.daw.core.snapshot.SnapshotKind;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Side panel showing the timeline of all available project snapshots —
 * autosaves, user-created checkpoints, and undo points — and letting the
 * user preview, restore, or compare any of them with the current project.
 *
 * <p>Implements the snapshot history browser called for in the issue
 * <em>Snapshot History Browser with Visual Diff Preview</em>. The panel
 * is a thin presentation layer on top of {@link SnapshotBrowserService};
 * all retention and aggregation logic lives in {@code daw-core} so it can
 * be tested without JavaFX.</p>
 *
 * <ul>
 *   <li>Each row shows a timestamp, the trigger (autosave / user-checkpoint
 *       / undo-point), and the entry's label / change summary.</li>
 *   <li>Clicking a row populates a read-only preview area with the
 *       serialised project state at that point in time.</li>
 *   <li>The <em>Restore</em> button asks the host application (via the
 *       configured callback) to load the selected snapshot into the
 *       current project, after the host has prompted to save the current
 *       state.</li>
 *   <li>The <em>Compare with current</em> button asks the host to display
 *       a diff of tracks, clips, and plugin parameters that differ between
 *       the selected snapshot and the current project.</li>
 *   <li>The <em>Purge old autosaves</em> button removes all autosave
 *       files older than the service's retention window for the project
 *       (and globally if the user chose the global cleanup mode).</li>
 * </ul>
 */
public final class SnapshotBrowser extends VBox {

    private static final double DEFAULT_WIDTH = 320.0;
    private static final double MIN_WIDTH = 240.0;
    private static final double ICON_SIZE = 14.0;
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private final SnapshotBrowserService service;
    private final ListView<SnapshotEntry> list;
    private final ObservableList<SnapshotEntry> items;
    private final TextArea preview;
    private final Button restoreButton;
    private final Button compareButton;
    private final Button checkpointButton;
    private final Button purgeButton;

    private Consumer<SnapshotEntry> onRestore = entry -> {};
    private Consumer<SnapshotEntry> onCompare = entry -> {};
    private Runnable onCreateCheckpoint = () -> {};

    /**
     * Creates a new snapshot browser bound to the given service.
     *
     * @param service the snapshot service that supplies entries
     */
    public SnapshotBrowser(SnapshotBrowserService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");

        getStyleClass().add("browser-panel");
        setPrefWidth(DEFAULT_WIDTH);
        setMinWidth(MIN_WIDTH);
        setSpacing(6);
        setPadding(new Insets(8));

        Label header = new Label("SNAPSHOT HISTORY");
        header.getStyleClass().add("panel-header");
        header.setGraphic(IconNode.of(DawIcon.HISTORY, ICON_SIZE));

        items = FXCollections.observableArrayList();
        list = new ListView<>(items);
        list.setPlaceholder(new Label("No snapshots yet"));
        list.getStyleClass().add("browser-list");
        list.setCellFactory(lv -> new SnapshotCell());
        VBox.setVgrow(list, Priority.ALWAYS);

        preview = new TextArea();
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.setPrefRowCount(8);
        preview.getStyleClass().add("snapshot-preview");

        list.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldEntry, newEntry) -> updatePreview(newEntry));

        checkpointButton = new Button("Create Checkpoint");
        checkpointButton.setOnAction(e -> onCreateCheckpoint.run());

        restoreButton = new Button("Restore");
        restoreButton.setDisable(true);
        restoreButton.setOnAction(e -> {
            SnapshotEntry sel = list.getSelectionModel().getSelectedItem();
            if (sel != null) {
                onRestore.accept(sel);
            }
        });

        compareButton = new Button("Compare with current");
        compareButton.setDisable(true);
        compareButton.setOnAction(e -> {
            SnapshotEntry sel = list.getSelectionModel().getSelectedItem();
            if (sel != null) {
                onCompare.accept(sel);
            }
        });

        purgeButton = new Button("Purge old autosaves");
        purgeButton.setOnAction(e -> {
            service.purgeExpiredAutosaves();
            refresh();
        });

        list.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldEntry, newEntry) -> {
                    boolean has = newEntry != null;
                    restoreButton.setDisable(!has);
                    compareButton.setDisable(!has);
                });

        HBox actionRow = new HBox(6,
                checkpointButton, restoreButton, compareButton);
        actionRow.setPadding(new Insets(4, 0, 4, 0));

        HBox cleanupRow = new HBox(6, purgeButton);

        getChildren().addAll(header, list, preview, actionRow, cleanupRow);
        refresh();
    }

    /**
     * Sets the action invoked when the user clicks <em>Restore</em>. The
     * host should prompt to save the current project before loading the
     * snapshot and update the application state accordingly.
     */
    public void setOnRestore(Consumer<SnapshotEntry> handler) {
        this.onRestore = Objects.requireNonNullElse(handler, e -> {});
    }

    /**
     * Sets the action invoked when the user clicks
     * <em>Compare with current</em>. The host should display a diff
     * computed via
     * {@link com.benesquivelmusic.daw.core.snapshot.SnapshotDiff}.
     */
    public void setOnCompare(Consumer<SnapshotEntry> handler) {
        this.onCompare = Objects.requireNonNullElse(handler, e -> {});
    }

    /**
     * Sets the action invoked when the user clicks
     * <em>Create Checkpoint</em> (or presses {@code Ctrl+Alt+S}).
     */
    public void setOnCreateCheckpoint(Runnable handler) {
        this.onCreateCheckpoint = Objects.requireNonNullElse(handler, () -> {});
    }

    /** Returns the underlying list view (intended for tests). */
    public ListView<SnapshotEntry> getListView() {
        return list;
    }

    /** Returns the preview text area (intended for tests). */
    public TextArea getPreviewArea() {
        return preview;
    }

    /** Returns the <em>Restore</em> button (intended for tests). */
    public Button getRestoreButton() {
        return restoreButton;
    }

    /** Returns the <em>Compare with current</em> button (intended for tests). */
    public Button getCompareButton() {
        return compareButton;
    }

    /** Returns the <em>Create Checkpoint</em> button (intended for tests). */
    public Button getCheckpointButton() {
        return checkpointButton;
    }

    /** Returns the <em>Purge old autosaves</em> button (intended for tests). */
    public Button getPurgeButton() {
        return purgeButton;
    }

    /** Re-reads the snapshot list from the service. */
    public void refresh() {
        if (Platform.isFxApplicationThread()) {
            doRefresh();
        } else {
            Platform.runLater(this::doRefresh);
        }
    }

    private void doRefresh() {
        SnapshotEntry selected = list.getSelectionModel().getSelectedItem();
        List<SnapshotEntry> entries = service.getEntries();
        items.setAll(entries);
        if (selected != null) {
            for (SnapshotEntry entry : entries) {
                if (entry.id().equals(selected.id())) {
                    list.getSelectionModel().select(entry);
                    return;
                }
            }
        }
        if (!entries.isEmpty()) {
            list.getSelectionModel().select(entries.size() - 1);
        }
    }

    private void updatePreview(SnapshotEntry entry) {
        if (entry == null) {
            preview.clear();
            return;
        }
        try {
            String content = entry.loadContent();
            preview.setText(content == null ? "" : content);
        } catch (RuntimeException ex) {
            preview.setText("Failed to load snapshot: " + ex.getMessage());
        }
    }

    private static String iconLabel(SnapshotKind kind) {
        return switch (kind) {
            case AUTOSAVE -> "AUTO";
            case USER_CHECKPOINT -> "USER";
            case UNDO_POINT -> "UNDO";
        };
    }

    /**
     * Renders a single snapshot row showing timestamp, trigger badge, and
     * the entry's label / summary.
     */
    private static final class SnapshotCell extends ListCell<SnapshotEntry> {

        @Override
        protected void updateItem(SnapshotEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            String when = TIMESTAMP_FMT.format(item.timestamp());
            String ago = friendlyAgo(item.timestamp());
            String summary = item.summary() != null
                    ? item.summary()
                    : item.label();
            setText(String.format("[%s] %s  (%s)%n  %s",
                    iconLabel(item.kind()), when, ago, summary));
            setGraphic(IconNode.of(iconFor(item.kind()), 12));
        }

        private static DawIcon iconFor(SnapshotKind kind) {
            return switch (kind) {
                case AUTOSAVE -> DawIcon.CLOCK;
                case USER_CHECKPOINT -> DawIcon.HISTORY;
                case UNDO_POINT -> DawIcon.TIMER;
            };
        }

        private static String friendlyAgo(Instant timestamp) {
            Duration d = Duration.between(timestamp, Instant.now());
            if (d.isNegative()) return "just now";
            long secs = d.getSeconds();
            if (secs < 60) return secs + "s ago";
            if (secs < 3600) return (secs / 60) + "m ago";
            if (secs < 86400) return (secs / 3600) + "h ago";
            return (secs / 86400) + "d ago";
        }
    }
}
