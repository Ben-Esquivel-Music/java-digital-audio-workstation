package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.dsp.saturation.ExciterProcessor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.Objects;

/**
 * JavaFX view for the built-in {@link ExciterProcessor}.
 *
 * <p>Provides a crossover-frequency sweep slider (1–16&nbsp;kHz), drive (0–100%),
 * mix (0–100%), output trim (-12 to +12 dB), and a mode cycler
 * ({@link ExciterProcessor.Mode#CLASS_A_TUBE CLASS_A_TUBE} /
 * {@link ExciterProcessor.Mode#TRANSFORMER TRANSFORMER} /
 * {@link ExciterProcessor.Mode#TAPE TAPE}) plus a mini static FFT panel that
 * sketches the harmonic signature added by the currently-selected mode (drawn
 * once per parameter change — no audio-thread coupling, no animation timer
 * required).</p>
 *
 * <p>Parameter changes are written directly to the processor on the JavaFX
 * application thread; the processor picks them up on its next audio-thread
 * buffer.</p>
 */
public final class ExciterPluginView extends VBox {

    /** Number of harmonic bins sketched in the mini FFT display. */
    static final int FFT_DISPLAY_HARMONICS = 8;

    private final ExciterProcessor processor;
    private final Canvas fftCanvas;

    /**
     * Creates a new exciter view bound to the given processor.
     *
     * @param processor the processor to control; must not be {@code null}
     */
    public ExciterPluginView(ExciterProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor must not be null");

        setSpacing(10);
        setPadding(new Insets(12));
        setAlignment(Pos.TOP_CENTER);
        setStyle("-fx-background-color: #2b2b2b;");

        Label title = new Label("Harmonic Exciter");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        // ── Crossover frequency sweep slider (1–16 kHz) ─────────────
        Slider frequency = slider(ExciterProcessor.MIN_FREQUENCY_HZ,
                ExciterProcessor.MAX_FREQUENCY_HZ,
                processor.getFrequencyHz());
        frequency.valueProperty().addListener((_, _, v) -> {
            processor.setFrequencyHz(v.doubleValue());
            redrawFft();
        });

        // ── Drive (0–100%) ───────────────────────────────────────────
        Slider drive = slider(ExciterProcessor.MIN_DRIVE_PERCENT,
                ExciterProcessor.MAX_DRIVE_PERCENT,
                processor.getDrivePercent());
        drive.valueProperty().addListener((_, _, v) -> {
            processor.setDrivePercent(v.doubleValue());
            redrawFft();
        });

        // ── Mix (0–100%) ─────────────────────────────────────────────
        Slider mix = slider(ExciterProcessor.MIN_MIX_PERCENT,
                ExciterProcessor.MAX_MIX_PERCENT,
                processor.getMixPercent());
        mix.valueProperty().addListener((_, _, v) -> {
            processor.setMixPercent(v.doubleValue());
            redrawFft();
        });

        // ── Output trim (-12 to +12 dB) ──────────────────────────────
        Slider output = slider(ExciterProcessor.MIN_OUTPUT_GAIN_DB,
                ExciterProcessor.MAX_OUTPUT_GAIN_DB,
                processor.getOutputGainDb());
        output.valueProperty().addListener(
                (_, _, v) -> processor.setOutputGainDb(v.doubleValue()));

        // ── Mode cycler ──────────────────────────────────────────────
        ComboBox<ExciterProcessor.Mode> mode = new ComboBox<>();
        mode.getItems().addAll(ExciterProcessor.Mode.values());
        mode.setValue(processor.getMode());
        mode.valueProperty().addListener((_, _, v) -> {
            if (v != null) {
                processor.setMode(v);
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
        fftCanvas = new Canvas(280, 120);
        Label fftLabel = new Label("Added Harmonics");
        fftLabel.setStyle("-fx-text-fill: #ccc;");
        VBox fftBox = new VBox(4, fftLabel, fftCanvas);
        fftBox.setAlignment(Pos.CENTER);

        getChildren().addAll(title, controls, fftBox);

        redrawFft();
    }

    /**
     * No-op placeholder for symmetry with other plugin views; this view does
     * not start any animation timers, so there is no resource to release.
     */
    public void dispose() {
        // No animation timer / scheduled tasks to stop — the static FFT
        // sketch is repainted only on parameter changes.
    }

    private static Slider slider(double min, double max, double initial) {
        Slider s = new Slider(min, max, initial);
        s.setPrefWidth(140);
        s.setShowTickMarks(true);
        return s;
    }

    private static VBox labelled(String text, javafx.scene.Node node) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #ccc;");
        VBox box = new VBox(2, l, node);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * Sketches the relative magnitudes of the harmonic series added by the
     * currently-selected mode. Magnitudes are computed analytically from the
     * known polynomial structure of each waveshaper (see
     * {@link #harmonicMagnitudes(ExciterProcessor.Mode)}) and scaled by
     * drive × mix so the user gets immediate visual feedback when sweeping
     * any parameter.
     */
    void redrawFft() {
        GraphicsContext g = fftCanvas.getGraphicsContext2D();
        double w = fftCanvas.getWidth();
        double h = fftCanvas.getHeight();

        g.setFill(Color.rgb(20, 20, 20));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.rgb(70, 70, 70));
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
            g.setFill(k == 0 ? Color.rgb(120, 160, 220) : Color.rgb(230, 140, 50));
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
}
