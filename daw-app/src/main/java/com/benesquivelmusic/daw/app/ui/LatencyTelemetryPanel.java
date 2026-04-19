package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.performance.LatencyTelemetryCollector;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.sdk.audio.LatencyTelemetry;
import com.benesquivelmusic.daw.sdk.audio.LatencyTelemetry.NodeKind;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * UI panel that surfaces plugin and track latency compensation data.
 *
 * <p>Shows a tree view of the mixer graph (Tracks → Inserts → Sends,
 * plus return buses and the master bus) with per-node sample counts,
 * millisecond equivalents, and a bar graph proportional to the total
 * session PDC. Rows whose latency changed since the previous refresh
 * flash briefly so engineers can spot plugin-bypass or oversampling
 * toggles at a glance.</p>
 *
 * <p>The panel hosts the global "Constrain Delay Compensation" toggle
 * and its sample-threshold spinner; the work of actually bypassing
 * lookahead-heavy plugins is delegated to the
 * {@link LatencyTelemetryCollector}.</p>
 *
 * <p>This panel is driven from the JavaFX application thread. The
 * {@link LatencyTelemetryCollector#telemetryEvents() telemetry publisher}
 * delivers on its own executor — call {@link #refresh(Mixer)} from the
 * FX thread (or hop to it) to apply a snapshot.</p>
 */
public final class LatencyTelemetryPanel extends BorderPane {

    /** Width of the inline sample-count bar graph, in pixels. */
    private static final double BAR_WIDTH_PX = 120.0;

    private final LatencyTelemetryCollector collector;
    private final double sampleRateHz;
    private final TreeView<String> tree;
    private final Label totalPdcLabel;
    private final CheckBox constrainToggle;
    private final Spinner<Integer> thresholdSpinner;

    /** Tracks tree-items by nodeId so refreshes can mutate in place and flash on change. */
    private final Map<String, TreeItem<String>> itemsByNodeId = new HashMap<>();

    /**
     * Creates a panel backed by {@code collector}.
     *
     * @param collector    the collector (used for constrain-mode wiring
     *                     and as the snapshot source)
     * @param sampleRateHz sample rate used for ms conversion in row labels;
     *                     must be positive
     */
    public LatencyTelemetryPanel(LatencyTelemetryCollector collector, double sampleRateHz) {
        this.collector = Objects.requireNonNull(collector, "collector must not be null");
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sampleRateHz must be positive: " + sampleRateHz);
        }
        this.sampleRateHz = sampleRateHz;

        this.totalPdcLabel = new Label("Session PDC: 0 samples (0.00 ms)");
        this.totalPdcLabel.getStyleClass().add("latency-total-pdc");

        this.constrainToggle = new CheckBox("Constrain Delay Compensation");
        this.constrainToggle.setSelected(collector.isConstrainDelayCompensationEnabled());

        this.thresholdSpinner = new Spinner<>(0, 100_000,
                collector.getConstrainThresholdSamples(), 32);
        this.thresholdSpinner.setEditable(true);
        this.thresholdSpinner.setPrefWidth(90);
        this.thresholdSpinner.valueProperty().addListener((obs, old, v) -> {
            if (v != null) {
                collector.setConstrainThresholdSamples(v);
            }
        });

        Label thresholdLabel = new Label("Threshold (samples):");
        HBox controls = new HBox(8, constrainToggle, thresholdLabel, thresholdSpinner);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(6));

        this.tree = new TreeView<>(new TreeItem<>("Mixer"));
        this.tree.setShowRoot(false);

        VBox top = new VBox(4, totalPdcLabel, controls);
        top.setPadding(new Insets(6));
        setTop(top);
        setCenter(tree);
    }

    /** Returns the Constrain Delay Compensation checkbox, primarily for tests. */
    public CheckBox constrainToggle() {
        return constrainToggle;
    }

    /** Returns the threshold spinner, primarily for tests. */
    public Spinner<Integer> thresholdSpinner() {
        return thresholdSpinner;
    }

    /**
     * Wires the constrain checkbox to the collector and re-renders on
     * every user interaction. Call once after construction, passing the
     * live mixer so toggles take effect immediately.
     */
    public void attachTo(Mixer mixer) {
        Objects.requireNonNull(mixer, "mixer must not be null");
        constrainToggle.selectedProperty().addListener((obs, old, enabled) -> {
            collector.setConstrainDelayCompensationEnabled(Boolean.TRUE.equals(enabled), mixer);
            refresh(mixer);
        });
        refresh(mixer);
    }

    /**
     * Publishes a fresh snapshot from {@code mixer} and updates the tree.
     * Safe to call from any thread — it marshals onto the FX thread.
     */
    public void refresh(Mixer mixer) {
        Objects.requireNonNull(mixer, "mixer must not be null");
        List<LatencyTelemetry> snapshot = collector.publish(mixer);
        List<String> changed = collector.nodesChangedSinceLastSnapshot();
        if (Platform.isFxApplicationThread()) {
            applySnapshot(snapshot, changed);
        } else {
            Platform.runLater(() -> applySnapshot(snapshot, changed));
        }
    }

    /**
     * Formats the session PDC for the transport bar counter.
     *
     * @param samples      total session PDC in sample frames
     * @param sampleRateHz current sample rate
     * @return a short human-readable string (e.g., {@code "PDC 2048 sp / 42.67 ms"})
     */
    public static String formatSessionPdc(int samples, double sampleRateHz) {
        double ms = sampleRateHz > 0 ? (samples / sampleRateHz) * 1_000.0 : 0.0;
        return String.format(Locale.ROOT, "PDC %d sp / %.2f ms", samples, ms);
    }

    // ---------------- Internals ----------------

    private void applySnapshot(List<LatencyTelemetry> snapshot, List<String> changedNodeIds) {
        int totalPdc = LatencyTelemetryCollector.totalSessionPdcSamples(snapshot);
        totalPdcLabel.setText(formatSessionPdc(totalPdc, sampleRateHz));

        TreeItem<String> root = new TreeItem<>("Mixer");
        root.setExpanded(true);
        Map<String, TreeItem<String>> newItems = new HashMap<>();

        TreeItem<String> currentAggregate = null;
        for (LatencyTelemetry t : snapshot) {
            String text = renderRow(t, totalPdc);
            TreeItem<String> item = new TreeItem<>(text);
            newItems.put(t.nodeId(), item);
            switch (t.kind()) {
                case PLUGIN, SEND -> {
                    if (currentAggregate != null) {
                        currentAggregate.getChildren().add(item);
                    } else {
                        root.getChildren().add(item);
                    }
                }
                case TRACK, BUS, MASTER -> {
                    // Move any plugin/send rows emitted BEFORE the aggregate under it,
                    // since snapshot() emits plugins first, then the aggregate, then sends.
                    // Simpler: start a fresh aggregate and re-parent pending children.
                    currentAggregate = item;
                    item.setExpanded(true);
                    root.getChildren().add(item);
                }
            }
        }

        // Re-parent: snapshot emits plugins before the aggregate; move them under
        // their aggregate so the tree reads Track → plugins/sends.
        reparentChildrenUnderAggregates(root);

        tree.setRoot(root);
        tree.setShowRoot(false);
        itemsByNodeId.clear();
        itemsByNodeId.putAll(newItems);

        // Flash changed rows.
        for (String id : changedNodeIds) {
            TreeItem<String> item = newItems.get(id);
            if (item != null) {
                flash(item);
            }
        }
    }

    private static void reparentChildrenUnderAggregates(TreeItem<String> root) {
        // Walk root children left-to-right; a TRACK/BUS/MASTER item absorbs
        // every preceding PLUGIN/SEND sibling until the next aggregate.
        // We identify aggregates heuristically by the "[TRACK]"/"[BUS]"/"[MASTER]"
        // tag in the label.
        var children = root.getChildren();
        int i = 0;
        while (i < children.size()) {
            TreeItem<String> it = children.get(i);
            if (isAggregateLabel(it.getValue())) {
                // Pull preceding non-aggregate siblings into this aggregate.
                int start = i - 1;
                while (start >= 0 && !isAggregateLabel(children.get(start).getValue())) {
                    start--;
                }
                start++;
                int moveCount = i - start;
                for (int m = 0; m < moveCount; m++) {
                    TreeItem<String> moved = children.remove(start);
                    it.getChildren().add(moved);
                }
                // After moving, current aggregate is now at position `start`.
                i = start + 1;
            } else {
                i++;
            }
        }
    }

    private static boolean isAggregateLabel(String s) {
        return s != null && (s.contains("[TRACK]") || s.contains("[BUS]") || s.contains("[MASTER]"));
    }

    private String renderRow(LatencyTelemetry t, int totalPdc) {
        double ms = t.millis(sampleRateHz);
        String bar = renderBar(t.samples(), totalPdc);
        return String.format(Locale.ROOT, "[%s] %s — %d sp / %.2f ms %s",
                t.kind().name(), t.nodeId(), t.samples(), ms, bar);
    }

    private static String renderBar(int samples, int totalPdc) {
        if (totalPdc <= 0 || samples <= 0) {
            return "";
        }
        int filled = (int) Math.round((samples * 20.0) / totalPdc);
        filled = Math.max(0, Math.min(20, filled));
        StringBuilder sb = new StringBuilder(22);
        sb.append('[');
        for (int i = 0; i < 20; i++) {
            sb.append(i < filled ? '█' : ' ');
        }
        sb.append(']');
        return sb.toString();
    }

    private static void flash(TreeItem<String> item) {
        // JavaFX TreeItem has no opacity — attach a graphic rectangle that fades.
        Rectangle flash = new Rectangle(8, 8, Color.web("#f0c420"));
        item.setGraphic(flash);
        FadeTransition ft = new FadeTransition(Duration.millis(600), flash);
        ft.setFromValue(1.0);
        ft.setToValue(0.0);
        ft.setOnFinished(e -> item.setGraphic(null));
        ft.play();
    }

    /** Exposes the bar-width constant for layout callers. */
    public static double barWidthPx() {
        return BAR_WIDTH_PX;
    }

    /** @return the underlying tree view (for tests and custom styling) */
    Region treeNode() {
        return tree;
    }
}
