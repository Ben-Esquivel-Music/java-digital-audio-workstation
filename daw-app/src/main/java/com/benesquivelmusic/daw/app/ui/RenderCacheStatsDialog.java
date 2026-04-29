package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.cache.RenderCacheStats;
import com.benesquivelmusic.daw.core.audio.cache.RenderedTrackCache;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Modal dialog that displays {@link RenderedTrackCache} statistics
 * (total bytes used, session hit rate, and per-project size) and
 * provides a "Clear cache" action.
 *
 * <p>The dialog reads stats once on construction and on any successful
 * "Clear cache" action; it does not auto-refresh while open. Closing
 * and reopening it recomputes from disk.</p>
 *
 * <p>This is a UI-only convenience class; all behaviour is delegated
 * to {@link RenderedTrackCache}, which is fully unit-tested in
 * {@code daw-core}.</p>
 */
public final class RenderCacheStatsDialog {

    private final Stage stage;
    private final RenderedTrackCache cache;
    private final Label totalSizeLabel = new Label();
    private final Label hitRateLabel = new Label();
    private final TableView<ProjectRow> table = new TableView<>();

    public RenderCacheStatsDialog(RenderedTrackCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.stage = new Stage();
        stage.setTitle("Render Cache Stats");
        stage.initModality(Modality.APPLICATION_MODAL);

        TableColumn<ProjectRow, String> projectCol = new TableColumn<>("Project UUID");
        projectCol.setCellValueFactory(new PropertyValueFactory<>("projectUuid"));
        projectCol.setPrefWidth(360);

        TableColumn<ProjectRow, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(new PropertyValueFactory<>("sizeFormatted"));
        sizeCol.setPrefWidth(120);

        table.getColumns().add(projectCol);
        table.getColumns().add(sizeCol);
        table.setPlaceholder(new Label("Cache is empty."));

        Button clearButton = new Button("Clear cache");
        clearButton.setOnAction(_ -> onClear());

        Button closeButton = new Button("Close");
        closeButton.setOnAction(_ -> stage.close());

        HBox actions = new HBox(8, clearButton, closeButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(8,
                totalSizeLabel,
                hitRateLabel,
                new Label("Per-project size:"),
                table,
                actions);
        root.setPadding(new Insets(12));
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);

        refresh();

        stage.setScene(new Scene(root, 520, 360));
    }

    /** Brings the dialog to the front, creating/showing the stage as needed. */
    public void showDialog() {
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
        stage.requestFocus();
    }

    private void onClear() {
        Alert confirm = new Alert(AlertType.CONFIRMATION,
                "Delete every cached frozen-track render? This cannot be undone.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Clear render cache");
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(_ -> {
            try {
                cache.clearAll();
                cache.resetSessionCounters();
                refresh();
            } catch (IOException e) {
                new Alert(AlertType.ERROR,
                        "Failed to clear cache: " + e.getMessage(),
                        ButtonType.OK).showAndWait();
            }
        });
    }

    private void refresh() {
        RenderCacheStats stats;
        try {
            stats = cache.stats();
        } catch (IOException e) {
            totalSizeLabel.setText("Total size: <error: " + e.getMessage() + ">");
            hitRateLabel.setText("Hit rate this session: —");
            table.getItems().clear();
            return;
        }
        totalSizeLabel.setText("Total size: " + formatBytes(stats.totalSizeBytes()));
        hitRateLabel.setText(String.format(
                "Hit rate this session: %.1f%%  (%d hits / %d misses)",
                stats.hitRate() * 100.0, stats.sessionHits(), stats.sessionMisses()));

        List<ProjectRow> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : stats.perProjectSizeBytes().entrySet()) {
            rows.add(new ProjectRow(e.getKey(), e.getValue()));
        }
        rows.sort((a, b) -> Long.compare(b.sizeBytes(), a.sizeBytes()));
        table.getItems().setAll(rows);
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KiB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MiB", mb);
        return String.format("%.2f GiB", mb / 1024.0);
    }

    /** Row in the per-project size table. Public getters required by JavaFX cell factories. */
    public static final class ProjectRow {
        private final String projectUuid;
        private final long sizeBytes;

        public ProjectRow(String projectUuid, long sizeBytes) {
            this.projectUuid = projectUuid;
            this.sizeBytes = sizeBytes;
        }

        public String getProjectUuid() { return projectUuid; }
        public long sizeBytes() { return sizeBytes; }
        public String getSizeFormatted() { return formatBytes(sizeBytes); }
    }
}
