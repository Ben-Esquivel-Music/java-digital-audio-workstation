package com.benesquivelmusic.daw.core.plugin.editor;

import java.util.Objects;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.benesquivelmusic.daw.core.dsp.saturation.ExciterProcessor;
import com.benesquivelmusic.daw.core.plugin.ExciterPlugin;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.editor.Theme;

/**
 * {@link PluginEditorFactory.Panel} editor for the built-in
 * {@link ExciterPlugin}, replacing the retired daw-app
 * {@code ExciterPluginView} (story 302 §8.3): crossover-frequency, drive, mix
 * and output-trim sliders, a mode selector
 * ({@link ExciterProcessor.Mode#CLASS_A_TUBE} /
 * {@link ExciterProcessor.Mode#TRANSFORMER} /
 * {@link ExciterProcessor.Mode#TAPE}), and an "Added Harmonics" mini-FFT
 * canvas that sketches each mode's analytic harmonic signature scaled by
 * drive × mix — pure math redrawn per parameter change, with no audio-thread
 * coupling and no animation timer. It doubles as a reference example of the
 * {@code Panel} contract: standard controls are themed by the host
 * stylesheet, canvas painting reads the resolved {@link Theme} tokens and
 * repaints on {@link EditorContext#themeProperty()} changes, and parameter
 * gestures write the processor <em>and</em> mirror into the host's
 * {@link PluginParameterStore}.
 */
public final class ExciterEditor implements PluginEditorFactory.Panel {

    /** Number of harmonic bins sketched in the mini FFT display. */
    static final int FFT_DISPLAY_HARMONICS = 8;

    // Parameter ids as declared by ExciterPlugin#getParameters().
    private static final int PARAM_FREQUENCY = 0;
    private static final int PARAM_DRIVE = 1;
    private static final int PARAM_MIX = 2;
    private static final int PARAM_OUTPUT_GAIN = 3;
    private static final int PARAM_MODE = 4;

    /** Opacity applied to the theme foreground when stroking the canvas frame. */
    private static final double FRAME_OPACITY = 0.35;

    private final ExciterPlugin plugin;

    // Populated in createPanel (called once, on the FX thread — §4.7).
    private EditorContext context;
    private PluginParameterStore store;
    private ExciterProcessor processor;
    // Fully qualified: the simple name "Canvas" would resolve to the inherited
    // member type PluginEditorFactory.Canvas, which shadows the import.
    private javafx.scene.canvas.Canvas fftCanvas;

    /**
     * Creates the editor factory for the given plugin instance. The plugin —
     * not its processor — is captured because the host may call
     * {@code editorFactory()} before or after (re)initialisation; the live
     * processor is resolved once in {@link #createPanel(EditorContext)}.
     *
     * @param plugin the plugin whose processor this editor edits; must not be
     *               {@code null}
     */
    public ExciterEditor(ExciterPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    @Override
    public Region createPanel(EditorContext context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.store = context.parameterStore();
        ExciterProcessor p = plugin.getProcessor();
        if (p == null) {
            throw new IllegalStateException(
                    "ExciterPlugin has no processor — initialize() the plugin before opening its editor");
        }
        this.processor = p;

        Label title = new Label("Harmonic Exciter");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        // ── Crossover frequency sweep slider (1–16 kHz) ─────────────
        Slider frequency = slider(ExciterProcessor.MIN_FREQUENCY_HZ,
                ExciterProcessor.MAX_FREQUENCY_HZ,
                processor.getFrequencyHz());
        frequency.valueProperty().addListener((_, _, v) -> {
            processor.setFrequencyHz(v.doubleValue());
            store.writeFromUiById(PARAM_FREQUENCY, processor.getFrequencyHz());
            redrawFft();
        });

        // ── Drive (0–100%) ───────────────────────────────────────────
        Slider drive = slider(ExciterProcessor.MIN_DRIVE_PERCENT,
                ExciterProcessor.MAX_DRIVE_PERCENT,
                processor.getDrivePercent());
        drive.valueProperty().addListener((_, _, v) -> {
            processor.setDrivePercent(v.doubleValue());
            store.writeFromUiById(PARAM_DRIVE, processor.getDrivePercent());
            redrawFft();
        });

        // ── Mix (0–100%) ─────────────────────────────────────────────
        Slider mix = slider(ExciterProcessor.MIN_MIX_PERCENT,
                ExciterProcessor.MAX_MIX_PERCENT,
                processor.getMixPercent());
        mix.valueProperty().addListener((_, _, v) -> {
            processor.setMixPercent(v.doubleValue());
            store.writeFromUiById(PARAM_MIX, processor.getMixPercent());
            redrawFft();
        });

        // ── Output trim (-12 to +12 dB) ──────────────────────────────
        Slider output = slider(ExciterProcessor.MIN_OUTPUT_GAIN_DB,
                ExciterProcessor.MAX_OUTPUT_GAIN_DB,
                processor.getOutputGainDb());
        output.valueProperty().addListener((_, _, v) -> {
            processor.setOutputGainDb(v.doubleValue());
            store.writeFromUiById(PARAM_OUTPUT_GAIN, processor.getOutputGainDb());
        });

        // ── Mode cycler ──────────────────────────────────────────────
        ComboBox<ExciterProcessor.Mode> mode = new ComboBox<>();
        mode.getItems().addAll(ExciterProcessor.Mode.values());
        mode.setValue(processor.getMode());
        mode.valueProperty().addListener((_, _, v) -> {
            if (v != null) {
                processor.setMode(v);
                store.writeFromUiById(PARAM_MODE, processor.getMode().ordinal());
                redrawFft();
            }
        });

        HBox controls = new HBox(12,
                labelled("Frequency (Hz)", frequency),
                labelled("Drive (%)",      drive),
                labelled("Mix (%)",        mix),
                labelled("Output (dB)",    output),
                labelled("Mode",           mode));
        controls.setAlignment(Pos.CENTER_LEFT);

        // ── Mini FFT display showing added harmonics ────────────────
        fftCanvas = new javafx.scene.canvas.Canvas(280, 120);
        VBox fftBox = new VBox(4, new Label("Added Harmonics"), fftCanvas);
        fftBox.setAlignment(Pos.CENTER);

        VBox root = new VBox(10, title, controls, fftBox);
        root.setPadding(new Insets(12));
        root.setAlignment(Pos.TOP_CENTER);

        context.themeProperty().addListener((_, _, _) -> redrawFft());
        redrawFft();
        return root;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Slightly smaller than {@link EditorHints#standard()}: 640 × 360 fits
     * the five-control row plus the 280 × 120 harmonics sketch.</p>
     */
    @Override
    public EditorHints hints() {
        return EditorHints.standard().withSize(640, 360);
    }

    /**
     * Sketches the relative magnitudes of the harmonic series added by the
     * currently-selected mode, painted with the resolved {@link Theme} tokens
     * (§2.5). Magnitudes are computed analytically from the known polynomial
     * structure of each waveshaper (see
     * {@link #harmonicMagnitudes(ExciterProcessor.Mode)}) and scaled by
     * drive × mix so the user gets immediate visual feedback when sweeping any
     * parameter — a static sketch repainted per change, never an animation
     * loop.
     */
    private void redrawFft() {
        GraphicsContext g = fftCanvas.getGraphicsContext2D();
        Theme theme = context.theme();
        double w = fftCanvas.getWidth();
        double h = fftCanvas.getHeight();

        g.setFill(theme.background());
        g.fillRect(0, 0, w, h);
        g.setLineWidth(1.0);
        g.setStroke(theme.foreground().deriveColor(0.0, 1.0, 1.0, FRAME_OPACITY));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        // Frequency axis label: 1×, 2×, ..., FFT_DISPLAY_HARMONICS×.
        double[] mags = harmonicMagnitudes(processor.getMode());
        double scale = (processor.getDrivePercent() / 100.0) * (processor.getMixPercent() / 100.0);

        double margin = 8.0;
        double barAreaW = w - 2 * margin;
        double barW = barAreaW / FFT_DISPLAY_HARMONICS;
        double barAreaH = h - 2 * margin;

        // Fundamental is shown at full height (the dry signal); subsequent
        // bars show the *added* harmonic energy.
        for (int k = 0; k < FFT_DISPLAY_HARMONICS; k++) {
            double m = (k == 0) ? 1.0 : mags[k] * scale;
            double bh = Math.max(1.0, Math.min(1.0, m) * barAreaH);
            double x = margin + k * barW + 1.0;
            double y = h - margin - bh;
            g.setFill(k == 0 ? theme.accent() : theme.meterWarn());
            g.fillRect(x, y, barW - 2.0, bh);
        }
    }

    /**
     * Returns a normalized harmonic-magnitude vector ({@code [fundamental,
     * H2, H3, …]}) that approximately characterizes each mode's spectral
     * signature for the static visualization.
     *
     * <p>These values are illustrative — they convey relative shape, not
     * absolute amplitude — and intentionally match the qualitative
     * description in the {@link ExciterProcessor.Mode} javadoc:</p>
     * <ul>
     *   <li>{@code CLASS_A_TUBE} — emphasises 2nd-order;</li>
     *   <li>{@code TRANSFORMER} — emphasises 3rd-order;</li>
     *   <li>{@code TAPE} — mixed 2nd + 3rd, with mild higher harmonics.</li>
     * </ul>
     */
    static double[] harmonicMagnitudes(ExciterProcessor.Mode mode) {
        double[] out = new double[FFT_DISPLAY_HARMONICS];
        out[0] = 1.0; // fundamental
        switch (mode) {
            case CLASS_A_TUBE -> {
                out[1] = 0.50; out[2] = 0.10; out[3] = 0.06; out[4] = 0.03;
                out[5] = 0.02; out[6] = 0.01; out[7] = 0.005;
            }
            case TRANSFORMER -> {
                out[1] = 0.05; out[2] = 0.45; out[3] = 0.04; out[4] = 0.10;
                out[5] = 0.02; out[6] = 0.04; out[7] = 0.01;
            }
            case TAPE -> {
                out[1] = 0.30; out[2] = 0.30; out[3] = 0.10; out[4] = 0.06;
                out[5] = 0.03; out[6] = 0.02; out[7] = 0.01;
            }
        }
        return out;
    }

    // ── Small layout helpers ─────────────────────────────────────────────

    private static Slider slider(double min, double max, double initial) {
        Slider s = new Slider(min, max, initial);
        s.setPrefWidth(140);
        s.setShowTickMarks(true);
        return s;
    }

    private static VBox labelled(String text, javafx.scene.Node node) {
        VBox box = new VBox(2, new Label(text), node);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }
}
