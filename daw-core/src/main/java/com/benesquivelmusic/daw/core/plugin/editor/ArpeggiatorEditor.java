package com.benesquivelmusic.daw.core.plugin.editor;

import java.util.Objects;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin.Pattern;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin.Rate;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.Theme;

/**
 * {@link PluginEditorFactory.Panel} editor for the built-in
 * {@link ArpeggiatorPlugin} — the story 302 (§8.3 item 7) replacement for the
 * retired {@code daw-app} class {@code ArpeggiatorPluginView}, which was
 * orphaned (never reached by the host's view switch) and is folded onto the
 * SDK editor contract here.
 *
 * <p>Lays out the six arpeggiator parameters — rate dropdown (custom cell
 * showing {@link Rate#label()}), pattern dropdown, octave-range / gate / swing
 * sliders, and a latch toggle — plus a 16-cell step-sequence indicator
 * {@link javafx.scene.canvas.Canvas Canvas} that lights the currently-firing
 * step, driven by a
 * scene-gated {@link AnimationTimer} polling
 * {@link ArpeggiatorPlugin#getStepIndex()} (a {@code Panel} has no dispose
 * hook, so the timer starts when the panel enters a scene and stops when it
 * leaves, re-attachable both ways).</p>
 *
 * <p>This class is a reference example of the {@code Panel} contract.
 * Indicator painting reads the resolved {@link Theme} tokens from
 * {@link EditorContext#theme()} and repaints when
 * {@link EditorContext#themeProperty()} changes.</p>
 *
 * <p>Unlike the other built-in MIDI editors, the arpeggiator publishes its six
 * parameters through {@link ArpeggiatorPlugin#getParameters()}, so every
 * control writes <em>both</em> the plugin's typed setter (the audio-side
 * authority the processor reads) <em>and</em> the host's
 * {@code EditorContext#parameterStore()} via {@code writeFromUiById}, keeping
 * the store's mirror — and anything the host hangs off it — in sync
 * (§2.6).</p>
 */
public final class ArpeggiatorEditor implements PluginEditorFactory.Panel {

    /** Number of step lights drawn in the indicator row. */
    static final int INDICATOR_STEPS = 16;

    /** Horizontal pitch of one indicator cell in pixels. */
    static final double INDICATOR_CELL = 14.0;

    // Parameter ids in ArpeggiatorPlugin#getParameters() order:
    // 0 rate (Rate ordinal), 1 pattern (Pattern ordinal), 2 octave, 3 gate,
    // 4 swing, 5 latch.
    private static final int PARAM_RATE = 0;
    private static final int PARAM_PATTERN = 1;
    private static final int PARAM_OCTAVE = 2;
    private static final int PARAM_GATE = 3;
    private static final int PARAM_SWING = 4;
    private static final int PARAM_LATCH = 5;

    private final ArpeggiatorPlugin plugin;

    /**
     * Creates the editor factory for the given plugin instance.
     *
     * @param plugin the arpeggiator plugin to edit; must not be {@code null}
     */
    public ArpeggiatorEditor(ArpeggiatorPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    @Override
    public Region createPanel(EditorContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return new ArpeggiatorPane(plugin, context);
    }

    @Override
    public EditorHints hints() {
        return EditorHints.standard().withSize(640, 260);
    }

    // ── Pure indicator math (package-private static, headlessly testable) ─

    /**
     * Maps the plugin's running step counter onto the indicator cell to light:
     * {@code -1} (idle) lights nothing; any other value wraps onto
     * {@code [0, INDICATOR_STEPS)}.
     *
     * @param step the plugin's step counter, or a negative value for idle
     * @return the active cell index, or {@code -1} for none
     */
    static int activeIndicatorCell(int step) {
        return step < 0 ? -1 : Math.floorMod(step, INDICATOR_STEPS);
    }

    // ── The panel ──────────────────────────────────────────────────────────

    /** The editor's root region — one instance per {@code createPanel} call. */
    private static final class ArpeggiatorPane extends VBox {

        private final ArpeggiatorPlugin plugin;
        private final EditorContext context;
        // Fully qualified: the inherited member type PluginEditorFactory.Canvas
        // shadows a javafx.scene.canvas.Canvas import inside this class body.
        private final javafx.scene.canvas.Canvas indicator;
        private final AnimationTimer timer;

        /** Last step drawn — the timer repaints only when the step moves. */
        private int lastStep = -1;

        ArpeggiatorPane(ArpeggiatorPlugin plugin, EditorContext context) {
            this.plugin = plugin;
            this.context = context;

            setSpacing(8);
            setPadding(new Insets(10));
            setAlignment(Pos.TOP_CENTER);

            Label title = new Label("Arpeggiator");
            title.setFont(Font.font(null, FontWeight.BOLD, 14));

            // ── Rate dropdown (custom cell showing Rate#label()) ─────────────
            ComboBox<Rate> rate = new ComboBox<>();
            rate.getItems().addAll(Rate.values());
            rate.setValue(plugin.getRate());
            rate.setCellFactory(_ -> new RateCell());
            rate.setButtonCell(new RateCell());
            rate.valueProperty().addListener((_, _, v) -> {
                if (v != null) {
                    plugin.setRate(v);
                    context.parameterStore().writeFromUiById(PARAM_RATE, v.ordinal());
                }
            });

            // ── Pattern dropdown ─────────────────────────────────────────────
            ComboBox<Pattern> pattern = new ComboBox<>();
            pattern.getItems().addAll(Pattern.values());
            pattern.setValue(plugin.getPattern());
            pattern.valueProperty().addListener((_, _, v) -> {
                if (v != null) {
                    plugin.setPattern(v);
                    context.parameterStore().writeFromUiById(PARAM_PATTERN, v.ordinal());
                }
            });

            // ── Octave range slider (snaps to whole octaves) ─────────────────
            Slider octave = slider(ArpeggiatorPlugin.MIN_OCTAVE, ArpeggiatorPlugin.MAX_OCTAVE,
                    plugin.getOctaveRange());
            octave.setMajorTickUnit(1);
            octave.setMinorTickCount(0);
            octave.setSnapToTicks(true);
            octave.setShowTickLabels(true);
            octave.valueProperty().addListener((_, _, v) -> {
                int range = v.intValue();
                plugin.setOctaveRange(range);
                context.parameterStore().writeFromUiById(PARAM_OCTAVE, range);
            });

            // ── Gate slider (10–200%) ────────────────────────────────────────
            Slider gate = slider(ArpeggiatorPlugin.MIN_GATE, ArpeggiatorPlugin.MAX_GATE,
                    plugin.getGate());
            gate.valueProperty().addListener((_, _, v) -> {
                plugin.setGate(v.doubleValue());
                context.parameterStore().writeFromUiById(PARAM_GATE, v.doubleValue());
            });

            // ── Swing slider (0–75%) ─────────────────────────────────────────
            Slider swing = slider(ArpeggiatorPlugin.MIN_SWING, ArpeggiatorPlugin.MAX_SWING,
                    plugin.getSwing());
            swing.valueProperty().addListener((_, _, v) -> {
                plugin.setSwing(v.doubleValue());
                context.parameterStore().writeFromUiById(PARAM_SWING, v.doubleValue());
            });

            // ── Latch toggle ─────────────────────────────────────────────────
            ToggleButton latch = new ToggleButton("LATCH");
            latch.setSelected(plugin.isLatch());
            latch.selectedProperty().addListener((_, _, v) -> {
                plugin.setLatch(v);
                context.parameterStore().writeFromUiById(PARAM_LATCH, v ? 1.0 : 0.0);
            });

            HBox controls = new HBox(10,
                    labelled("Rate",      rate),
                    labelled("Pattern",   pattern),
                    labelled("Octave",    octave),
                    labelled("Gate (%)",  gate),
                    labelled("Swing (%)", swing),
                    latch);
            controls.setAlignment(Pos.CENTER_LEFT);

            // ── Step-sequence indicator ──────────────────────────────────────
            indicator = new javafx.scene.canvas.Canvas(INDICATOR_STEPS * INDICATOR_CELL + 4, 14);
            Label stepLabel = new Label("Step");
            VBox stepBox = new VBox(2, stepLabel, indicator);
            stepBox.setAlignment(Pos.CENTER_LEFT);

            getChildren().addAll(title, controls, stepBox);

            timer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    int step = plugin.getStepIndex();
                    if (step != lastStep) {
                        lastStep = step;
                        drawIndicator(step);
                    }
                }
            };

            // Scene-gated lifecycle (a Panel has no dispose hook): poll while
            // in a scene, stop when removed, re-attachable both ways.
            sceneProperty().addListener((_, _, newScene) -> {
                if (newScene != null) {
                    drawIndicator(lastStep);
                    timer.start();
                } else {
                    timer.stop();
                }
            });

            // Repaint the indicator with the new palette on theme change.
            context.themeProperty().addListener((_, _, _) -> drawIndicator(lastStep));

            drawIndicator(lastStep);
        }

        private static Slider slider(double min, double max, double initial) {
            Slider s = new Slider(min, max, initial);
            s.setPrefWidth(120);
            s.setShowTickMarks(true);
            return s;
        }

        private static VBox labelled(String text, Node node) {
            VBox box = new VBox(2, new Label(text), node);
            box.setAlignment(Pos.CENTER_LEFT);
            return box;
        }

        // ── Painting (colours from the resolved host Theme tokens, §2.5) ───

        private void drawIndicator(int currentStep) {
            Theme theme = context.theme();
            Color track = theme.background();
            Color idleCell = theme.background().interpolate(theme.foreground(), 0.25);
            Color activeCell = theme.accent();

            GraphicsContext g = indicator.getGraphicsContext2D();
            double w = indicator.getWidth();
            double h = indicator.getHeight();
            g.setFill(track);
            g.fillRect(0, 0, w, h);
            int active = activeIndicatorCell(currentStep);
            for (int i = 0; i < INDICATOR_STEPS; i++) {
                double x = 2 + i * INDICATOR_CELL;
                g.setFill(i == active ? activeCell : idleCell);
                g.fillRect(x, 2, 10, h - 4);
            }
        }
    }

    private static final class RateCell extends ListCell<Rate> {
        @Override
        protected void updateItem(Rate item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.label());
        }
    }
}
