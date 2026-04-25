package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.dsp.eq.MatchEqProcessor;
import com.benesquivelmusic.daw.core.plugin.MatchEqPlugin;
import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * JavaFX view for the built-in {@link MatchEqPlugin} / {@link MatchEqProcessor}.
 *
 * <p>Displays the captured source spectrum, reference spectrum, and the
 * current target match curve overlaid on a log-frequency / dB plot. Provides
 * controls to:</p>
 *
 * <ul>
 *   <li>Select FFT size, smoothing mode, phase mode;</li>
 *   <li>Adjust the match amount (0 – 100%);</li>
 *   <li>Load a one-off reference audio file (WAV/FLAC/AIFF/OGG/MP3);</li>
 *   <li>Capture a source spectrum from a live playback accumulator;</li>
 *   <li>Apply the match (rebuilding the filter).</li>
 * </ul>
 *
 * <p>All parameter changes are written directly to the underlying processor on
 * the JavaFX application thread; the processor picks them up on its next audio
 * buffer (scalar writes are safe for the primitives involved).</p>
 */
public final class MatchEqPluginView extends VBox {

    /** Plot Y axis range in dB (centered at 0). The curve is clipped to ±DB_RANGE. */
    static final double DB_RANGE = 24.0;

    /** Lower frequency edge of the plot, in Hz. */
    static final double MIN_FREQUENCY_HZ = 20.0;

    /** Upper frequency edge of the plot, in Hz. */
    static final double MAX_FREQUENCY_HZ = 20_000.0;

    /** Plot canvas width in pixels. */
    static final double PLOT_WIDTH = 560.0;

    /** Plot canvas height in pixels. */
    static final double PLOT_HEIGHT = 240.0;

    private final MatchEqPlugin plugin;
    private final MatchEqProcessor processor;
    private final Canvas plotCanvas;
    private final Label statusLabel;

    /**
     * Creates a new Match EQ view bound to the given plugin.
     *
     * @param plugin the plugin whose processor should be driven; must already
     *               be initialized (its processor must be non-null)
     */
    public MatchEqPluginView(MatchEqPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.processor = Objects.requireNonNull(plugin.getProcessor(),
                "plugin must be initialized before creating a view");

        setSpacing(10);
        setPadding(new Insets(12));
        setAlignment(Pos.TOP_LEFT);
        setStyle("-fx-background-color: #2b2b2b;");

        Label title = new Label("Match EQ — Spectrum Match");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        // ── Plot canvas ───────────────────────────────────────────────
        plotCanvas = new Canvas(PLOT_WIDTH, PLOT_HEIGHT);

        // ── FFT Size ──────────────────────────────────────────────────
        ComboBox<MatchEqProcessor.FftSize> fftCombo = new ComboBox<>();
        for (MatchEqProcessor.FftSize f : MatchEqProcessor.FftSize.values()) {
            fftCombo.getItems().add(f);
        }
        fftCombo.setValue(processor.getFftSize());
        fftCombo.valueProperty().addListener((_, _, v) -> {
            if (v != null) {
                processor.setFftSize(v);
                redraw();
            }
        });

        // ── Smoothing ─────────────────────────────────────────────────
        ComboBox<MatchEqProcessor.Smoothing> smoothingCombo = new ComboBox<>();
        for (MatchEqProcessor.Smoothing s : MatchEqProcessor.Smoothing.values()) {
            smoothingCombo.getItems().add(s);
        }
        smoothingCombo.setValue(processor.getSmoothing());
        smoothingCombo.valueProperty().addListener((_, _, v) -> {
            if (v != null) processor.setSmoothing(v);
        });

        // ── Phase mode ────────────────────────────────────────────────
        ComboBox<MatchEqProcessor.PhaseMode> phaseCombo = new ComboBox<>();
        for (MatchEqProcessor.PhaseMode p : MatchEqProcessor.PhaseMode.values()) {
            phaseCombo.getItems().add(p);
        }
        phaseCombo.setValue(processor.getPhaseMode());
        phaseCombo.valueProperty().addListener((_, _, v) -> {
            if (v != null) processor.setPhaseMode(v);
        });

        // ── Amount ────────────────────────────────────────────────────
        Slider amount = new Slider(0.0, 1.0, processor.getAmount());
        amount.setPrefWidth(180);
        amount.setShowTickMarks(true);
        amount.valueProperty().addListener((_, _, v) -> {
            processor.setAmount(v.doubleValue());
            redraw();
        });

        HBox controls = new HBox(12,
                labelled("FFT Size",  fftCombo),
                labelled("Smoothing", smoothingCombo),
                labelled("Phase",     phaseCombo),
                labelled("Amount",    amount));
        controls.setAlignment(Pos.CENTER_LEFT);

        // ── Reference + capture + apply buttons ──────────────────────
        Button loadRefButton = new Button("Load Reference…");
        loadRefButton.setOnAction(e -> onLoadReferenceFile());

        ToggleButton liveCaptureToggle = new ToggleButton("Capture Live");
        liveCaptureToggle.setOnAction(e -> {
            if (liveCaptureToggle.isSelected()) {
                processor.startLiveCapture();
                liveCaptureToggle.setText("Stop & Capture");
                setStatus("Live capture started");
            } else {
                boolean captured = processor.captureSource();
                processor.stopLiveCapture();
                liveCaptureToggle.setText("Capture Live");
                setStatus(captured
                        ? "Source spectrum captured."
                        : "Not enough audio accumulated to capture.");
                redraw();
            }
        });

        Button applyButton = new Button("Apply Match");
        applyButton.setOnAction(e -> {
            processor.updateMatch();
            setStatus(processor.isMatchActive()
                    ? "Match applied."
                    : "Need both source and reference spectra.");
            redraw();
        });

        HBox actions = new HBox(8, loadRefButton, liveCaptureToggle, applyButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #ccc;");

        getChildren().addAll(title, plotCanvas, controls, actions, statusLabel);
        redraw();
    }

    /**
     * Replaces the spectrum plot contents with the latest source/reference/
     * target curves from the processor. Safe to call from the JavaFX thread.
     */
    public void redraw() {
        drawPlot(plotCanvas.getGraphicsContext2D(),
                plotCanvas.getWidth(), plotCanvas.getHeight(),
                processor.getSourceSpectrum(),
                processor.getReferenceSpectrum(),
                processor.getTargetCurve(),
                processor.getFftSize().value(),
                processor.getSampleRate());
    }

    private void onLoadReferenceFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load Reference Audio");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Audio",
                        "*.wav", "*.flac", "*.aiff", "*.aif", "*.ogg", "*.mp3"));
        File selected = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (selected == null) return;
        loadReferenceFile(selected.toPath());
    }

    /**
     * Loads a reference file directly (exposed for host wiring and testing).
     *
     * @param file the file to load; must be a supported audio format
     */
    public void loadReferenceFile(Path file) {
        Objects.requireNonNull(file, "file must not be null");
        try {
            ReferenceTrack track = plugin.loadReferenceFile(file);
            setStatus("Loaded reference: " + track.getName());
            redraw();
        } catch (IOException | IllegalArgumentException ex) {
            setStatus("Failed to load reference: " + ex.getMessage());
        }
    }

    private void setStatus(String text) {
        if (Platform.isFxApplicationThread()) {
            statusLabel.setText(text);
        } else {
            Platform.runLater(() -> statusLabel.setText(text));
        }
    }

    private static VBox labelled(String text, javafx.scene.Node node) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #ccc;");
        VBox box = new VBox(2, l, node);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    // ── Drawing helpers (package-private for headless test coverage) ──

    static void drawPlot(GraphicsContext g, double w, double h,
                         double[] source, double[] reference, double[] target,
                         int fftSize, double sampleRate) {
        // Background
        g.setFill(Color.rgb(20, 20, 20));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.rgb(70, 70, 70));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        // Horizontal grid: 0 dB centre + ±6 / ±12 / ±18 dB.
        g.setStroke(Color.rgb(50, 50, 50));
        for (int db = -18; db <= 18; db += 6) {
            double y = dbToY(db, h);
            g.strokeLine(0, y, w, y);
        }
        g.setStroke(Color.rgb(80, 80, 80));
        double zeroY = dbToY(0, h);
        g.strokeLine(0, zeroY, w, zeroY);

        // Vertical grid: decade lines (100 Hz, 1 kHz, 10 kHz).
        for (double freq : new double[] {100.0, 1_000.0, 10_000.0}) {
            double x = freqToX(freq, w);
            g.strokeLine(x, 0, x, h);
        }

        // Curves.
        double nyquist = sampleRate * 0.5;
        int half = fftSize / 2 + 1;
        if (source != null && source.length == half) {
            strokeCurve(g, w, h, source, sampleRate, nyquist,
                    Color.rgb(160, 160, 160));       // grey
        }
        if (reference != null && reference.length == half) {
            strokeCurve(g, w, h, reference, sampleRate, nyquist,
                    Color.rgb(100, 170, 255));       // blue
        }
        if (target != null && target.length == half) {
            strokeTargetCurve(g, w, h, target, sampleRate, nyquist,
                    Color.rgb(230, 140, 50));        // amber
        }
    }

    private static void strokeCurve(GraphicsContext g, double w, double h,
                                    double[] mag, double sampleRate, double nyquist,
                                    Color color) {
        g.setStroke(color);
        g.setLineWidth(1.2);
        int half = mag.length;
        double maxMag = 0.0;
        for (double v : mag) if (v > maxMag) maxMag = v;
        if (maxMag <= 0.0) return;
        g.beginPath();
        boolean first = true;
        for (int k = 1; k < half; k++) {
            double freq = (double) k * sampleRate / ((half - 1) * 2.0);
            if (freq < MIN_FREQUENCY_HZ || freq > nyquist) continue;
            double db = 20.0 * Math.log10(Math.max(1e-9, mag[k] / maxMag));
            double x = freqToX(freq, w);
            double y = dbToY(db, h);
            if (first) {
                g.moveTo(x, y);
                first = false;
            } else {
                g.lineTo(x, y);
            }
        }
        g.stroke();
    }

    private static void strokeTargetCurve(GraphicsContext g, double w, double h,
                                          double[] target, double sampleRate,
                                          double nyquist, Color color) {
        g.setStroke(color);
        g.setLineWidth(1.8);
        int half = target.length;
        g.beginPath();
        boolean first = true;
        for (int k = 1; k < half; k++) {
            double freq = (double) k * sampleRate / ((half - 1) * 2.0);
            if (freq < MIN_FREQUENCY_HZ || freq > nyquist) continue;
            double db = 20.0 * Math.log10(Math.max(1e-9, target[k]));
            double x = freqToX(freq, w);
            double y = dbToY(db, h);
            if (first) {
                g.moveTo(x, y);
                first = false;
            } else {
                g.lineTo(x, y);
            }
        }
        g.stroke();
    }

    /** Maps a frequency in Hz to the plot's X pixel (log scale). */
    static double freqToX(double freqHz, double width) {
        double logMin = Math.log10(MIN_FREQUENCY_HZ);
        double logMax = Math.log10(MAX_FREQUENCY_HZ);
        double f = Math.max(MIN_FREQUENCY_HZ, Math.min(MAX_FREQUENCY_HZ, freqHz));
        double t = (Math.log10(f) - logMin) / (logMax - logMin);
        return t * width;
    }

    /** Maps a dB value to the plot's Y pixel (0 dB at vertical centre). */
    static double dbToY(double db, double height) {
        double clamped = Math.max(-DB_RANGE, Math.min(DB_RANGE, db));
        double t = (clamped + DB_RANGE) / (2.0 * DB_RANGE);    // 0..1 bottom..top
        return height - t * height;
    }
}
