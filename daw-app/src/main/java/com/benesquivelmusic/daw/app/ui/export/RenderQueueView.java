package com.benesquivelmusic.daw.app.ui.export;

import com.benesquivelmusic.daw.core.export.RenderQueue;
import com.benesquivelmusic.daw.sdk.export.JobProgress;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Flow;

/**
 * UI panel for the offline {@link RenderQueue}: lists queued / running /
 * completed jobs with per-job progress bars and pause / resume / cancel
 * controls. Supports drag-to-reorder while jobs are still queued.
 *
 * <p>The view subscribes to {@link RenderQueue#progressPublisher()} and
 * marshals updates onto the JavaFX application thread.</p>
 *
 * <p>This class is intentionally lightweight (no FXML) so it can be
 * embedded inside any container — for example as a tab in the main
 * window or as a standalone tool window.</p>
 */
public final class RenderQueueView extends VBox {

    private final RenderQueue queue;
    private final ObservableList<JobRow> rows = FXCollections.observableArrayList();
    private final Map<String, JobRow> rowsById = new HashMap<>();
    private final ListView<JobRow> listView = new ListView<>(rows);

    public RenderQueueView(RenderQueue queue) {
        this.queue = queue;
        setSpacing(8);
        setPadding(new Insets(8));
        getChildren().add(new Label("Render Queue"));
        listView.setCellFactory(lv -> new JobCell());
        listView.setPrefHeight(300);
        VBox.setVgrow(listView, Priority.ALWAYS);
        getChildren().add(listView);

        queue.progressPublisher().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(JobProgress p) { Platform.runLater(() -> applyUpdate(p)); }
            @Override public void onError(Throwable t) { /* ignore */ }
            @Override public void onComplete() { /* ignore */ }
        });
        // Seed with any existing snapshots (e.g., after restart).
        for (var snap : queue.snapshot()) {
            JobRow row = new JobRow(snap.jobId(), snap.displayName());
            row.phase.set(snap.phase());
            row.percent.set(snap.lastPercent());
            row.stage.set(snap.lastStage());
            rowsById.put(snap.jobId(), row);
            rows.add(row);
        }
    }

    private void applyUpdate(JobProgress p) {
        JobRow row = rowsById.computeIfAbsent(p.jobId(), id -> {
            JobRow r = new JobRow(id, id);
            rows.add(r);
            return r;
        });
        row.phase.set(p.phase());
        row.percent.set(p.percent());
        row.stage.set(p.stage());
    }

    /** Mutable per-row state observed by the cell. */
    static final class JobRow {
        final String jobId;
        final SimpleStringProperty displayName;
        final SimpleStringProperty stage = new SimpleStringProperty("");
        final SimpleDoubleProperty percent = new SimpleDoubleProperty(0.0);
        final SimpleObjectProperty<JobProgress.Phase> phase =
                new SimpleObjectProperty<>(JobProgress.Phase.QUEUED);

        JobRow(String jobId, String displayName) {
            this.jobId = jobId;
            this.displayName = new SimpleStringProperty(displayName);
        }
    }

    /** Cell with progress bar + pause/resume/cancel buttons + drag handlers. */
    private final class JobCell extends ListCell<JobRow> {
        private final Label nameLabel = new Label();
        private final Label stageLabel = new Label();
        private final ProgressBar bar = new ProgressBar(0.0);
        private final Button pauseBtn = new Button("Pause");
        private final Button resumeBtn = new Button("Resume");
        private final Button cancelBtn = new Button("Cancel");
        private final HBox box;

        JobCell() {
            bar.setPrefWidth(140);
            stageLabel.setMinWidth(120);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box = new HBox(8, nameLabel, bar, stageLabel, spacer, pauseBtn, resumeBtn, cancelBtn);
            box.setAlignment(Pos.CENTER_LEFT);

            pauseBtn.setOnAction(e -> { if (getItem() != null) queue.pause(getItem().jobId); });
            resumeBtn.setOnAction(e -> { if (getItem() != null) queue.resume(getItem().jobId); });
            cancelBtn.setOnAction(e -> { if (getItem() != null) queue.cancel(getItem().jobId); });

            // Drag-to-reorder
            setOnDragDetected(ev -> {
                if (getItem() == null) return;
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(getItem().jobId);
                db.setContent(cc);
                ev.consume();
            });
            setOnDragOver(ev -> {
                if (ev.getGestureSource() != this && ev.getDragboard().hasString()) {
                    ev.acceptTransferModes(TransferMode.MOVE);
                }
                ev.consume();
            });
            setOnDragDropped(ev -> {
                if (getItem() == null) return;
                String draggedId = ev.getDragboard().getString();
                if (draggedId != null && !draggedId.equals(getItem().jobId)) {
                    queue.moveBefore(draggedId, getItem().jobId);
                    // Reflect in UI
                    rows.stream().filter(r -> r.jobId.equals(draggedId)).findFirst()
                            .ifPresent(dragged -> {
                                rows.remove(dragged);
                                int idx = rows.indexOf(getItem());
                                rows.add(idx < 0 ? rows.size() : idx, dragged);
                            });
                    ev.setDropCompleted(true);
                } else {
                    ev.setDropCompleted(false);
                }
                ev.consume();
            });
        }

        @Override
        protected void updateItem(JobRow item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            nameLabel.textProperty().bind(item.displayName);
            bar.progressProperty().bind(item.percent);
            stageLabel.textProperty().bind(item.stage);
            JobProgress.Phase ph = item.phase.get();
            boolean terminal = ph != null && ph.isTerminal();
            pauseBtn.setDisable(terminal || ph == JobProgress.Phase.PAUSED);
            resumeBtn.setDisable(terminal || ph != JobProgress.Phase.PAUSED);
            cancelBtn.setDisable(terminal);
            setGraphic(box);
            setText(null);
        }
    }
}
