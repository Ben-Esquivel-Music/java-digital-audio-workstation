package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.controls.Knob;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleConsumer;

/**
 * Host-generated §6.2 parameter grid (story 301) — the editor body the host
 * renders when a plugin returned {@code PluginEditorFactory.Declarative}.
 *
 * <p>Plugin View Design Book §6.2: knobs flow in a 16-px-gridded responsive
 * grid where one knob cell is 96&nbsp;×&nbsp;96&nbsp;px and the container
 * width drives the column count — always one of 1&nbsp;/&nbsp;2&nbsp;/
 * 4&nbsp;/&nbsp;6&nbsp;/&nbsp;8, never an arbitrary count, so columns stay
 * aligned to the design grid. This panel supersedes the legacy
 * {@code PluginParameterEditorPanel} slider list for host-generated editors:
 * sliders become {@link Knob}s, halving per-row height so the auto-generated
 * editor visually matches a hand-built one.</p>
 *
 * <h2>Control-type heuristics</h2>
 *
 * <p>{@link PluginParameter} carries no boolean/enum metadata yet (it is a
 * five-component record: id, name, min, max, default), so control types are
 * inferred, checked in this order:</p>
 * <ol>
 *   <li><b>Boolean</b> — the legacy panel's deliberately conservative
 *       heuristic, kept verbatim: {@code min == 0 && max == 1}, the default
 *       is exactly 0 or 1, <em>and</em> the lowercase name contains
 *       {@code "toggle"}. The name guard is load-bearing: a continuous
 *       0..1 "Mix" or "Level" parameter must render as a knob, not
 *       collapse into an ON/OFF toggle — without SDK metadata, range alone
 *       cannot distinguish the two. Renders as the §6.2 "labelled toggle"
 *       (ON/OFF {@link ToggleButton}).</li>
 *   <li><b>Discrete</b> — min, max and default are all mathematically
 *       integral and {@code (max - min)} is between 1 and 7 inclusive
 *       (i.e. 2–8 states, matching §6.2 "max ≤ 8 with integer steps").
 *       Renders as a §6.2 segmented selector — one {@link ToggleButton}
 *       per integer value in a shared {@link ToggleGroup}. The §6.2
 *       mockup's two-state SIDECHAIN (off/EXT) is exactly such a
 *       parameter. Deliberately narrow: any non-integral endpoint or
 *       default, or a span above 7, falls through to a knob.</li>
 *   <li><b>Continuous</b> — everything else: a 96×96 {@link Knob}
 *       configured from the descriptor, with a numeric readout below using
 *       the legacy panel's formatting rules ({@code |v| >= 1000} → one
 *       decimal, otherwise two).</li>
 * </ol>
 *
 * <h2>Single-writer discipline (story 301 technical note)</h2>
 *
 * <p>Exactly one path reports user gestures out of this panel: the
 * {@link ValueSink}. Model→control echo ({@link #updateValue(int, double)} /
 * {@link #setAllValues(Map)} — audio-side drains, A/B swaps, preset loads)
 * moves the controls inside a single panel-wide reentrancy guard so the
 * controls' own change listeners do not re-fire the sink. There is no
 * {@code refreshControls()} re-push feedback loop and no per-control
 * suppress flag — one guard field, one rule.</p>
 *
 * <h2>Responsive layout</h2>
 *
 * <p>This is a plain {@link Region} with custom {@link #layoutChildren()}
 * (not a {@code FlowPane}: a FlowPane's {@code prefWrapLength} is ignored
 * under a {@code fitToWidth} ScrollPane — known project pitfall), placing
 * cells in {@link #computeColumns(double)} columns with {@link #GRID_GAP}
 * gaps and centring the used block horizontally.
 * {@link #computePrefHeight(double)} reports {@code rows × rowHeight +
 * gaps} for the column count at the given width so ScrollPane/VBox hosts
 * size the grid correctly; pref width is a sensible 4-column default and
 * min width is one column.</p>
 *
 * <p>All styling comes from style classes ({@value #STYLE_CLASS},
 * {@code parameter-grid-cell}, {@code parameter-name},
 * {@code parameter-value}, {@code numeric-caption},
 * {@code segmented-selector}) — no hard-coded colors here.</p>
 */
public final class ParameterGridPanel extends Region {

    /** Stable style class — selectable as {@code .parameter-grid} in CSS. */
    public static final String STYLE_CLASS = "parameter-grid";

    /** §6.2 — one knob cell is 96 × 96 px. */
    public static final double CELL_WIDTH = 96;

    /** §6.2 — the grid is 16-px gridded. */
    public static final double GRID_GAP = 16;

    /** Allowed column counts (§6.2) — checked widest-first. */
    private static final int[] ALLOWED_COLUMN_COUNTS = {8, 6, 4, 2};

    /** Pref-width default column count (§6.2 mid-size layout). */
    private static final int PREF_COLUMNS = 4;

    /** Legacy reset affordance, kept verbatim from the panel this supersedes. */
    private static final String RESET_TOOLTIP = "Double-click to reset to default";

    private static final String LABEL_ON = "ON";
    private static final String LABEL_OFF = "OFF";

    /** Vertical spacing inside a cell (name / control / readout) — 4-px grid. */
    private static final double CELL_SPACING = 4;

    /**
     * FX-side single-writer sink: the ONE place a user gesture reports a
     * new value. Echo entering through {@link #updateValue(int, double)} or
     * {@link #setAllValues(Map)} never reaches this sink.
     */
    @FunctionalInterface
    public interface ValueSink {

        /**
         * A user gesture changed a parameter's value.
         *
         * @param parameterId the {@link PluginParameter#id()} of the
         *        changed parameter
         * @param newValue the new value, inside the descriptor's
         *        {@code [min, max]}
         */
        void valueChanged(int parameterId, double newValue);
    }

    /** Cells in descriptor order — also this Region's children. */
    private final List<VBox> cells = new ArrayList<>();

    /**
     * Echo appliers by parameter id — how {@link #updateValue(int, double)}
     * moves each cell's control. Callers are responsible for wrapping the
     * call in the reentrancy guard. If two descriptors share an id (not a
     * supported configuration, tolerated like the legacy panel), both cells
     * render but the last-registered one receives the echo.
     */
    private final Map<Integer, DoubleConsumer> echoByParameterId = new LinkedHashMap<>();

    private final ValueSink sink;

    /**
     * THE single reentrancy guard (story 301): {@code true} while this
     * panel is writing values into its own controls (echo, deselection
     * restore, initial selection), so the controls' change listeners do
     * not report those programmatic writes to the {@link #sink}.
     */
    private boolean syncingFromModel;

    /**
     * Builds a grid with one cell per descriptor, in list order.
     *
     * <p>Every control starts at its descriptor's
     * {@link PluginParameter#defaultValue()}; construction reports nothing
     * to the sink.</p>
     *
     * @param parameters the parameter descriptors to generate cells for;
     *        may be empty (an empty grid — no crash)
     * @param sink the single-writer gesture sink
     * @throws NullPointerException if {@code parameters}, any of its
     *         elements, or {@code sink} is {@code null}
     */
    public ParameterGridPanel(List<PluginParameter> parameters, ValueSink sink) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
        getStyleClass().add(STYLE_CLASS);
        for (PluginParameter parameter : parameters) {
            Objects.requireNonNull(parameter, "parameters must not contain null");
            VBox cell = buildCell(parameter);
            cells.add(cell);
            getChildren().add(cell);
        }
    }

    // ── Echo (model → control) ────────────────────────────────────────────

    /**
     * Model→control echo (audio-side drain / A/B / preset load): moves the
     * control rendering {@code parameterId} to {@code value}.
     *
     * <p>MUST NOT re-fire the sink — and does not: the write happens inside
     * the panel's reentrancy guard. Unknown parameter ids are tolerated and
     * ignored (a stale drain must not throw). Out-of-range values are
     * coerced by the receiving control (the knob clamps at the property
     * level; toggles threshold at 0.5; segments clamp to the nearest
     * segment).</p>
     *
     * @param parameterId the {@link PluginParameter#id()} to update
     * @param value the authoritative model value
     */
    public void updateValue(int parameterId, double value) {
        DoubleConsumer echo = echoByParameterId.get(parameterId);
        if (echo == null) {
            return;
        }
        runSyncing(() -> echo.accept(value));
    }

    /**
     * Bulk echo — same no-re-fire guarantee as
     * {@link #updateValue(int, double)}, applied to every entry under one
     * guard. Unknown ids and {@code null} values are skipped.
     *
     * @param valuesById model values keyed by {@link PluginParameter#id()}
     * @throws NullPointerException if {@code valuesById} is {@code null}
     */
    public void setAllValues(Map<Integer, Double> valuesById) {
        Objects.requireNonNull(valuesById, "valuesById must not be null");
        runSyncing(() -> {
            for (Map.Entry<Integer, Double> entry : valuesById.entrySet()) {
                DoubleConsumer echo = echoByParameterId.get(entry.getKey());
                Double value = entry.getValue();
                if (echo != null && value != null) {
                    echo.accept(value);
                }
            }
        });
    }

    /** Runs {@code write} with the single reentrancy guard raised. */
    private void runSyncing(Runnable write) {
        boolean was = syncingFromModel;
        syncingFromModel = true;
        try {
            write.run();
        } finally {
            syncingFromModel = was;
        }
    }

    /**
     * The ONE call site discipline: every gesture handler reports through
     * here, and echo (guard raised) is silently swallowed.
     */
    private void fireSink(int parameterId, double newValue) {
        if (!syncingFromModel) {
            sink.valueChanged(parameterId, newValue);
        }
    }

    // ── Column math (§6.2) ────────────────────────────────────────────────

    /**
     * Pure column math, unit-testable with no toolkit: the largest of
     * {@code {8, 6, 4, 2, 1}} such that
     * {@code n * CELL_WIDTH + (n - 1) * GRID_GAP <= availableWidth} —
     * never an arbitrary count (§6.2). Returns 1 for anything too narrow
     * (including zero and negative widths).
     *
     * @param availableWidth the width available for cells, in px
     * @return the §6.2 column count: 8, 6, 4, 2 or 1
     */
    public static int computeColumns(double availableWidth) {
        for (int columns : ALLOWED_COLUMN_COUNTS) {
            if (blockWidth(columns) <= availableWidth) {
                return columns;
            }
        }
        return 1;
    }

    /** @return the width of {@code columns} cells plus the gaps between them. */
    private static double blockWidth(int columns) {
        return columns * CELL_WIDTH + (columns - 1) * GRID_GAP;
    }

    // ── Responsive layout ─────────────────────────────────────────────────

    @Override
    protected void layoutChildren() {
        if (cells.isEmpty()) {
            return;
        }
        double left = snappedLeftInset();
        double top = snappedTopInset();
        double contentWidth = getWidth() - left - snappedRightInset();
        int columns = computeColumns(contentWidth);
        double rowHeight = maxCellPrefHeight();
        // Centre the used block horizontally (§6.2 — the grid floats in
        // the editor body rather than hugging the left edge).
        double xOrigin = left + Math.max(0.0, (contentWidth - blockWidth(columns)) / 2.0);
        for (int i = 0; i < cells.size(); i++) {
            int column = i % columns;
            int row = i / columns;
            double x = xOrigin + column * (CELL_WIDTH + GRID_GAP);
            double y = top + row * (rowHeight + GRID_GAP);
            layoutInArea(cells.get(i), x, y, CELL_WIDTH, rowHeight,
                    0, HPos.CENTER, VPos.TOP);
        }
    }

    @Override
    protected double computePrefWidth(double height) {
        // A sensible 4-column default (§6.2 mid-size layout), shrunk to the
        // cell count when the grid holds fewer than four cells.
        int columns = Math.max(1, Math.min(PREF_COLUMNS, cells.size()));
        return snappedLeftInset() + snappedRightInset() + blockWidth(columns);
    }

    @Override
    protected double computeMinWidth(double height) {
        // One column — the grid never renders narrower than a single cell.
        return snappedLeftInset() + snappedRightInset() + CELL_WIDTH;
    }

    @Override
    protected double computePrefHeight(double width) {
        double verticalInsets = snappedTopInset() + snappedBottomInset();
        if (cells.isEmpty()) {
            return verticalInsets;
        }
        // Report rows × rowHeight + gaps for the column count at THIS width
        // so fitToWidth ScrollPane / VBox hosts size the grid correctly as
        // it re-wraps. A negative width means "unconstrained" — use the
        // pref-width column count.
        int columns = width < 0
                ? Math.max(1, Math.min(PREF_COLUMNS, cells.size()))
                : computeColumns(width - snappedLeftInset() - snappedRightInset());
        int rows = (cells.size() + columns - 1) / columns;
        return verticalInsets + rows * maxCellPrefHeight() + (rows - 1) * GRID_GAP;
    }

    /** @return the uniform row height: the tallest cell's pref height at cell width. */
    private double maxCellPrefHeight() {
        double max = 0.0;
        for (VBox cell : cells) {
            max = Math.max(max, cell.prefHeight(CELL_WIDTH));
        }
        return max;
    }

    // ── Cell construction ─────────────────────────────────────────────────

    private VBox buildCell(PluginParameter parameter) {
        VBox cell = new VBox(CELL_SPACING);
        cell.getStyleClass().add("parameter-grid-cell");
        cell.setAlignment(Pos.TOP_CENTER);
        cell.setMinWidth(CELL_WIDTH);
        cell.setPrefWidth(CELL_WIDTH);
        cell.setMaxWidth(CELL_WIDTH);

        Label name = new Label(parameter.name());
        name.getStyleClass().add("parameter-name");
        name.setMaxWidth(CELL_WIDTH); // long names truncate with ellipsis
        cell.getChildren().add(name);

        if (isBooleanParameter(parameter)) {
            buildToggleCell(cell, parameter);
        } else if (isDiscreteParameter(parameter)) {
            buildSegmentedCell(cell, parameter);
        } else {
            buildKnobCell(cell, parameter);
        }
        return cell;
    }

    /**
     * Continuous parameter → 96×96 {@link Knob} plus a numeric readout.
     *
     * <p>Sized in code ({@code setMinSize/setPrefSize/setMaxSize}) — the
     * {@code .size-*} style classes top out at 48 px and {@code KnobSkin}
     * caps its computed max at 64 px, so explicit overrides are required
     * for the §6.2 96-px cell. {@code KnobSkin} renders the formatted
     * value only into the accessible text, not as a visible readout, so
     * the readout is a separate {@code parameter-value} Label; the knob's
     * own formatter is aligned with it so screen-reader narration matches
     * the visible text. Double-click reset to default is built into
     * {@code KnobSkin} (as is Ctrl/Cmd-click and the {@code 0} key).</p>
     */
    private void buildKnobCell(VBox cell, PluginParameter parameter) {
        Knob knob = Knob.create()
                .min(parameter.minValue())
                .max(parameter.maxValue())
                .defaultValue(parameter.defaultValue())
                .build();
        knob.setMinSize(CELL_WIDTH, CELL_WIDTH);
        knob.setPrefSize(CELL_WIDTH, CELL_WIDTH);
        knob.setMaxSize(CELL_WIDTH, CELL_WIDTH);
        knob.setValueFormatter(ParameterGridPanel::formatContinuous);
        knob.setTooltip(new Tooltip(RESET_TOOLTIP));

        Label value = new Label(formatContinuous(parameter.defaultValue()));
        // Literal class names (story 266 numeric-typography audit friendly):
        // parameter values are inline numeric readouts and use tabular
        // figures so they don't jiggle as the user drags the knob.
        value.getStyleClass().addAll("parameter-value", "numeric-caption");

        knob.valueProperty().addListener((obs, oldValue, newValue) -> {
            double v = newValue.doubleValue();
            // The readout tracks EVERY change (gesture and echo alike);
            // only genuine gestures pass the guard inside fireSink.
            value.setText(formatContinuous(v));
            fireSink(parameter.id(), v);
        });

        cell.getChildren().addAll(knob, value);
        echoByParameterId.put(parameter.id(), knob::setValue);
    }

    /**
     * Boolean parameter → §6.2 "labelled toggle": an ON/OFF
     * {@link ToggleButton}. Selected ⇒ 1.0, deselected ⇒ 0.0. Double-click
     * resets to the descriptor default (legacy semantics kept verbatim).
     */
    private void buildToggleCell(VBox cell, PluginParameter parameter) {
        boolean initiallyOn = parameter.defaultValue() >= 0.5;
        ToggleButton toggle = new ToggleButton(initiallyOn ? LABEL_ON : LABEL_OFF);
        toggle.setSelected(initiallyOn);
        toggle.setTooltip(new Tooltip(RESET_TOOLTIP));
        toggle.setOnAction(e -> {
            boolean on = toggle.isSelected();
            toggle.setText(on ? LABEL_ON : LABEL_OFF);
            fireSink(parameter.id(), on ? 1.0 : 0.0);
        });
        toggle.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                // setSelected does not fire onAction, so report explicitly
                // (same shape as the legacy panel's double-click reset).
                boolean on = parameter.defaultValue() >= 0.5;
                toggle.setSelected(on);
                toggle.setText(on ? LABEL_ON : LABEL_OFF);
                fireSink(parameter.id(), parameter.defaultValue());
            }
        });

        cell.getChildren().add(toggle);
        echoByParameterId.put(parameter.id(), v -> {
            boolean on = v >= 0.5;
            toggle.setSelected(on);
            toggle.setText(on ? LABEL_ON : LABEL_OFF);
        });
    }

    /**
     * Discrete parameter → §6.2 segmented selector: one
     * {@link ToggleButton} per integer value {@code min..max} in a single
     * {@link ToggleGroup}, labelled with the value.
     *
     * <p>All sink reporting flows through the group's selected-toggle
     * listener, so each actual value change is reported exactly once. The
     * listener also guards the standard ToggleGroup pitfall: clicking the
     * already-selected segment tries to deselect it (selected toggle →
     * {@code null}) — the guard re-selects it inside the reentrancy guard,
     * keeping one segment always active without re-reporting. Double-click
     * selects the default segment (the selection listener reports the
     * change if there is one).</p>
     */
    private void buildSegmentedCell(VBox cell, PluginParameter parameter) {
        long lo = Math.round(parameter.minValue());
        long hi = Math.round(parameter.maxValue());

        ToggleGroup group = new ToggleGroup();
        HBox selector = new HBox();
        selector.getStyleClass().add("segmented-selector");
        selector.setAlignment(Pos.CENTER);

        List<ToggleButton> segments = new ArrayList<>();
        for (long v = lo; v <= hi; v++) {
            ToggleButton segment = new ToggleButton(Long.toString(v));
            segment.setToggleGroup(group);
            segment.setUserData((double) v);
            segment.setTooltip(new Tooltip(RESET_TOOLTIP));
            segment.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    // Reset to default — reported (once) by the selection
                    // listener iff the selection actually changes.
                    selectSegment(segments, lo, parameter.defaultValue());
                }
            });
            segments.add(segment);
            selector.getChildren().add(segment);
        }

        group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                // Deselection guard: clicking the selected segment must
                // keep it selected. Restore inside the reentrancy guard so
                // the restore is not reported as a gesture.
                if (oldToggle != null) {
                    runSyncing(() -> group.selectToggle(oldToggle));
                }
                return;
            }
            fireSink(parameter.id(), (Double) newToggle.getUserData());
        });

        // Initial selection = default, silently (construction reports nothing).
        runSyncing(() -> selectSegment(segments, lo, parameter.defaultValue()));

        cell.getChildren().add(selector);
        echoByParameterId.put(parameter.id(), v -> selectSegment(segments, lo, v));
    }

    /** Selects the segment nearest {@code value} (clamped into the range). */
    private static void selectSegment(List<ToggleButton> segments, long lo, double value) {
        long index = Math.round(value) - lo;
        index = Math.max(0, Math.min(segments.size() - 1, index));
        Toggle segment = segments.get((int) index);
        segment.setSelected(true);
    }

    // ── Heuristics & formatting (legacy PluginParameterEditorPanel) ───────

    /**
     * The legacy panel's deliberately conservative boolean heuristic, kept
     * verbatim: unit range, integral default, AND the name mentions
     * "toggle". Without the name guard a continuous 0..1 parameter that
     * happens to default to 0 or 1 (a "Mix", a "Level") would silently
     * collapse into an ON/OFF toggle — the SDK has no boolean metadata yet
     * to decide otherwise.
     */
    private static boolean isBooleanParameter(PluginParameter parameter) {
        return parameter.minValue() == 0.0 && parameter.maxValue() == 1.0
                && (parameter.defaultValue() == 0.0 || parameter.defaultValue() == 1.0)
                && parameter.name().toLowerCase(Locale.ROOT).contains("toggle");
    }

    /**
     * Discrete heuristic (§6.2 "max ≤ 8 with integer steps"), applied only
     * when {@link #isBooleanParameter(PluginParameter)} said no: all three
     * range values are mathematically integral and the span covers 2–8
     * integer states. A heuristic by necessity — the SDK has no enum
     * metadata yet.
     */
    private static boolean isDiscreteParameter(PluginParameter parameter) {
        double span = parameter.maxValue() - parameter.minValue();
        return isIntegral(parameter.minValue())
                && isIntegral(parameter.maxValue())
                && isIntegral(parameter.defaultValue())
                && span >= 1.0 && span <= 7.0;
    }

    private static boolean isIntegral(double v) {
        return Double.isFinite(v) && v == Math.rint(v);
    }

    /**
     * The legacy panel's continuous formatting rules: {@code |v| >= 1000}
     * → one decimal, otherwise two ({@link Locale#ROOT}, matching the
     * controls-package formatter convention).
     */
    private static String formatContinuous(double value) {
        if (Math.abs(value) >= 1000) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
