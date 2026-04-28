package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionService;
import com.benesquivelmusic.daw.core.persistence.backup.ProjectDiskUsage;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.PieChart;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Modal dialog that edits a {@link BackupRetentionPolicy} with sliders /
 * spinners for each parameter and a live "what would be kept" preview based
 * on the project's current autosave directory. Also displays a per-project
 * disk-usage pie chart broken down into autosaves / archives / assets.
 *
 * <p>Introduced for the auto-backup-rotation feature: the surrounding
 * {@code BackupRetentionService} runs the grandfather-father-son rotation,
 * and this dialog lets the user tune the policy without touching the JSON
 * config file directly.</p>
 *
 * <p>On Apply the dialog resolves to the new {@link BackupRetentionPolicy};
 * on Cancel (or close) it resolves to {@code null}.</p>
 */
public final class BackupSettingsDialog extends Dialog<BackupRetentionPolicy> {

    private static final int MAX_RECENT = 100;
    private static final int MAX_HOURLY = 168;   // one week of hours
    private static final int MAX_DAILY = 60;     // ~2 months of days
    private static final int MAX_WEEKLY = 52;    // one year of weeks
    private static final int MAX_AGE_DAYS = 365;
    private static final long MAX_BYTES_GIB = 64L; // upper slider bound

    private final Spinner<Integer> recentSpinner;
    private final Spinner<Integer> hourlySpinner;
    private final Spinner<Integer> dailySpinner;
    private final Spinner<Integer> weeklySpinner;
    private final Slider maxAgeSlider;
    private final Label maxAgeLabel;
    private final Slider maxBytesSlider;
    private final Label maxBytesLabel;
    private final Label previewSummary;
    private final PieChart diskPie;

    private final List<BackupRetentionService.Snapshot> snapshots;

    /**
     * Creates a dialog pre-populated from {@code current}.
     *
     * @param current          the current policy; must not be null
     * @param projectDirectory the project root used to compute the disk-usage
     *                         pie chart, or {@code null} to skip it
     * @param checkpointDir    the directory holding existing autosave
     *                         snapshots used for the live preview, or
     *                         {@code null} to skip the preview
     */
    public BackupSettingsDialog(BackupRetentionPolicy current,
                                Path projectDirectory,
                                Path checkpointDir) {
        Objects.requireNonNull(current, "current must not be null");

        setTitle("Backup Retention Settings");
        setHeaderText("Auto-Backup Rotation Policy");
        setGraphic(IconNode.of(DawIcon.FOLDER, 24));

        recentSpinner = new Spinner<>(0, MAX_RECENT, current.keepRecent());
        recentSpinner.setEditable(true);
        recentSpinner.setPrefWidth(90);

        hourlySpinner = new Spinner<>(0, MAX_HOURLY, current.keepHourly());
        hourlySpinner.setEditable(true);
        hourlySpinner.setPrefWidth(90);

        dailySpinner = new Spinner<>(0, MAX_DAILY, current.keepDaily());
        dailySpinner.setEditable(true);
        dailySpinner.setPrefWidth(90);

        weeklySpinner = new Spinner<>(0, MAX_WEEKLY, current.keepWeekly());
        weeklySpinner.setEditable(true);
        weeklySpinner.setPrefWidth(90);

        long currentDays = current.maxAge() == null ? 0 : current.maxAge().toDays();
        maxAgeSlider = new Slider(0, MAX_AGE_DAYS, Math.min(currentDays, MAX_AGE_DAYS));
        maxAgeSlider.setShowTickLabels(true);
        maxAgeSlider.setShowTickMarks(true);
        maxAgeSlider.setMajorTickUnit(60);
        maxAgeSlider.setPrefWidth(260);
        maxAgeLabel = new Label(formatDays((long) maxAgeSlider.getValue()));
        maxAgeSlider.valueProperty().addListener(
                (_, _, v) -> maxAgeLabel.setText(formatDays(v.longValue())));

        long currentGiB = current.maxBytes() / (1024L * 1024L * 1024L);
        maxBytesSlider = new Slider(0, MAX_BYTES_GIB, Math.min(currentGiB, MAX_BYTES_GIB));
        maxBytesSlider.setShowTickLabels(true);
        maxBytesSlider.setShowTickMarks(true);
        maxBytesSlider.setMajorTickUnit(8);
        maxBytesSlider.setPrefWidth(260);
        maxBytesLabel = new Label(formatGiB((long) maxBytesSlider.getValue()));
        maxBytesSlider.valueProperty().addListener(
                (_, _, v) -> maxBytesLabel.setText(formatGiB(v.longValue())));

        previewSummary = new Label();
        previewSummary.setWrapText(true);
        previewSummary.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 11px;");

        diskPie = new PieChart();
        diskPie.setLegendVisible(true);
        diskPie.setLabelsVisible(false);
        diskPie.setPrefSize(260, 200);

        snapshots = scanSnapshots(checkpointDir);
        refreshPreview();
        refreshDiskUsage(projectDirectory);

        recentSpinner.valueProperty().addListener((_, _, _) -> refreshPreview());
        hourlySpinner.valueProperty().addListener((_, _, _) -> refreshPreview());
        dailySpinner.valueProperty().addListener((_, _, _) -> refreshPreview());
        weeklySpinner.valueProperty().addListener((_, _, _) -> refreshPreview());
        maxAgeSlider.valueProperty().addListener((_, _, _) -> refreshPreview());
        maxBytesSlider.valueProperty().addListener((_, _, _) -> refreshPreview());

        getDialogPane().setContent(buildContent());
        getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(620);

        setResultConverter(button -> button == ButtonType.APPLY ? buildPolicy() : null);
    }

    private Node buildContent() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        int row = 0;
        Label header = new Label("Retention Buckets");
        header.setStyle("-fx-font-weight: bold;");
        grid.add(header, 0, row, 3, 1); row++;
        grid.add(new Separator(), 0, row, 3, 1); row++;

        grid.add(new Label("Keep recent:"), 0, row);
        grid.add(recentSpinner, 1, row);
        grid.add(new Label("most recent autosaves verbatim"), 2, row); row++;

        grid.add(new Label("Keep hourly:"), 0, row);
        grid.add(hourlySpinner, 1, row);
        grid.add(new Label("one per hour milestone"), 2, row); row++;

        grid.add(new Label("Keep daily:"), 0, row);
        grid.add(dailySpinner, 1, row);
        grid.add(new Label("one per calendar day"), 2, row); row++;

        grid.add(new Label("Keep weekly:"), 0, row);
        grid.add(weeklySpinner, 1, row);
        grid.add(new Label("one per ISO week"), 2, row); row++;

        grid.add(new Separator(), 0, row, 3, 1); row++;
        grid.add(new Label("Max age:"), 0, row);
        grid.add(maxAgeSlider, 1, row);
        grid.add(maxAgeLabel, 2, row); row++;

        grid.add(new Label("Max disk usage:"), 0, row);
        grid.add(maxBytesSlider, 1, row);
        grid.add(maxBytesLabel, 2, row); row++;

        grid.add(new Separator(), 0, row, 3, 1); row++;

        Label previewHeader = new Label("Live Preview");
        previewHeader.setStyle("-fx-font-weight: bold;");
        grid.add(previewHeader, 0, row, 3, 1); row++;
        grid.add(previewSummary, 0, row, 3, 1); row++;

        grid.add(new Separator(), 0, row, 3, 1); row++;
        Label diskHeader = new Label("Project Disk Usage");
        diskHeader.setStyle("-fx-font-weight: bold;");
        grid.add(diskHeader, 0, row, 3, 1); row++;

        VBox left = new VBox(8, grid);
        HBox container = new HBox(20, left, diskPie);
        container.setPadding(new Insets(0));
        return container;
    }

    private BackupRetentionPolicy buildPolicy() {
        return new BackupRetentionPolicy(
                recentSpinner.getValue(),
                hourlySpinner.getValue(),
                dailySpinner.getValue(),
                weeklySpinner.getValue(),
                Duration.ofDays((long) maxAgeSlider.getValue()),
                ((long) maxBytesSlider.getValue()) * 1024L * 1024L * 1024L);
    }

    private void refreshPreview() {
        BackupRetentionPolicy candidate = buildPolicy();
        BackupRetentionService.Plan plan =
                BackupRetentionService.plan(snapshots, candidate, Instant.now());
        previewSummary.setText(
                "Snapshots scanned : " + snapshots.size() + "\n"
                        + "Recent kept       : " + plan.count(BackupRetentionService.Bucket.RECENT) + "\n"
                        + "Hourly kept       : " + plan.count(BackupRetentionService.Bucket.HOURLY) + "\n"
                        + "Daily  kept       : " + plan.count(BackupRetentionService.Bucket.DAILY) + "\n"
                        + "Weekly kept       : " + plan.count(BackupRetentionService.Bucket.WEEKLY) + "\n"
                        + "Total kept bytes  : " + formatBytes(plan.keptBytes()));
    }

    private void refreshDiskUsage(Path projectDirectory) {
        ProjectDiskUsage usage;
        try {
            usage = projectDirectory == null
                    ? new ProjectDiskUsage(0, 0, 0)
                    : ProjectDiskUsage.compute(projectDirectory);
        } catch (IOException e) {
            usage = new ProjectDiskUsage(0, 0, 0);
        }
        ObservableList<PieChart.Data> data = FXCollections.observableArrayList();
        data.add(new PieChart.Data("Autosaves (" + formatBytes(usage.autosavesBytes()) + ")",
                usage.autosavesBytes()));
        data.add(new PieChart.Data("Archives ("  + formatBytes(usage.archivesBytes())  + ")",
                usage.archivesBytes()));
        data.add(new PieChart.Data("Assets ("    + formatBytes(usage.assetsBytes())    + ")",
                usage.assetsBytes()));
        diskPie.setData(data);
    }

    private static List<BackupRetentionService.Snapshot> scanSnapshots(Path checkpointDir) {
        List<BackupRetentionService.Snapshot> out = new ArrayList<>();
        if (checkpointDir == null || !Files.isDirectory(checkpointDir)) {
            return out;
        }
        try (Stream<Path> stream = Files.list(checkpointDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                try {
                    out.add(new BackupRetentionService.Snapshot(
                            p,
                            Files.getLastModifiedTime(p).toInstant(),
                            Files.size(p)));
                } catch (IOException ignored) {
                    // skip unreadable file
                }
            }
        } catch (IOException ignored) {
            // empty list
        }
        return out;
    }

    private static String formatDays(long days) {
        return days == 0 ? "unlimited" : days + " day" + (days == 1 ? "" : "s");
    }

    private static String formatGiB(long gib) {
        return gib == 0 ? "unlimited" : gib + " GiB";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double k = bytes / 1024.0;
        if (k < 1024) return String.format("%.1f KiB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format("%.1f MiB", m);
        return String.format("%.2f GiB", m / 1024.0);
    }
}
