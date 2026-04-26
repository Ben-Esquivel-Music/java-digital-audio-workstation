package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.dsp.reverb.ConvolutionReverbProcessor;
import com.benesquivelmusic.daw.core.dsp.reverb.ImpulseResponseLibrary;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

/**
 * JavaFX view for the {@link ConvolutionReverbProcessor}.
 *
 * <p>Layout:</p>
 * <ul>
 *   <li>Top: IR selector combo + "Load File…" button.</li>
 *   <li>Center: waveform canvas of the loaded IR with two draggable trim
 *       markers (vertical lines) for trim-start and trim-end.</li>
 *   <li>Right: parameter knobs (stretch, predelay, low-cut, high-cut, mix,
 *       width).</li>
 * </ul>
 *
 * <p>All parameter changes are written directly to the processor on the
 * JavaFX application thread.</p>
 */
public final class ConvolutionReverbPluginView extends VBox {

    private final ConvolutionReverbProcessor processor;
    private final Canvas waveform;
    private final Label statusLabel;

    private double trimStartFraction;
    private double trimEndFraction;

    public ConvolutionReverbPluginView(ConvolutionReverbProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.trimStartFraction = processor.getTrimStart();
        this.trimEndFraction = processor.getTrimEnd();

        setSpacing(10);
        setPadding(new Insets(12));
        setAlignment(Pos.TOP_LEFT);
        setStyle("-fx-background-color: #2b2b2b;");

        Label title = new Label("Convolution Reverb");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        // ── IR selector + file loader ─────────────────────────────────
        ComboBox<String> irCombo = new ComboBox<>();
        for (var entry : ImpulseResponseLibrary.ENTRIES) {
            irCombo.getItems().add(entry.displayName());
        }
        int idx = (int) Math.round(processor.getIrSelection());
        if (idx < irCombo.getItems().size()) {
            irCombo.getSelectionModel().select(idx);
        }
        irCombo.setOnAction(_ -> {
            int sel = irCombo.getSelectionModel().getSelectedIndex();
            if (sel >= 0) {
                processor.setIrSelection(sel);
                trimStartFraction = 0.0;
                trimEndFraction = 1.0;
                drawWaveform();
            }
        });

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #aaa;");
        Button loadButton = new Button("Load File…");
        loadButton.setOnAction(_ -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Load Impulse Response");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("WAV files", "*.wav"));
            File f = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
            if (f != null) {
                statusLabel.setText("Loading…");
                processor.loadImpulseResponseFromFileAsync(Path.of(f.getAbsolutePath()))
                        .whenComplete((_, ex) -> javafx.application.Platform.runLater(() -> {
                            if (ex != null) {
                                statusLabel.setText("Failed: " + ex.getMessage());
                            } else {
                                statusLabel.setText("Loaded: " + f.getName());
                                drawWaveform();
                            }
                        }));
            }
        });

        HBox topBar = new HBox(8, new Label("IR:"), irCombo, loadButton);
        topBar.setAlignment(Pos.CENTER_LEFT);

        // ── Waveform canvas with draggable trim markers ───────────────
        waveform = new Canvas(520, 120);
        waveform.setOnMouseDragged(e -> {
            double frac = Math.max(0.0, Math.min(1.0, e.getX() / waveform.getWidth()));
            // Left button = trim start, Right button = trim end
            if (e.getButton() == MouseButton.PRIMARY) {
                if (frac < trimEndFraction - 0.01) {
                    trimStartFraction = frac;
                    try {
                        processor.setTrimStart(frac);
                    } catch (IllegalArgumentException ignored) {}
                }
            } else if (e.getButton() == MouseButton.SECONDARY) {
                if (frac > trimStartFraction + 0.01) {
                    trimEndFraction = frac;
                    try {
                        processor.setTrimEnd(frac);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
            drawWaveform();
        });

        // ── Parameter sliders ─────────────────────────────────────────
        Slider stretch = slider(0.5, 2.0, processor.getStretch());
        stretch.valueProperty().addListener((_, _, v) -> {
            try { processor.setStretch(v.doubleValue()); drawWaveform(); }
            catch (IllegalArgumentException ignored) {}
        });

        Slider predelay = slider(0.0, 200.0, processor.getPredelayMs());
        predelay.valueProperty().addListener((_, _, v) -> processor.setPredelayMs(v.doubleValue()));

        Slider lowCut = slider(20.0, 1000.0, processor.getLowCutHz());
        lowCut.valueProperty().addListener((_, _, v) -> processor.setLowCutHz(v.doubleValue()));

        Slider highCut = slider(1000.0, 20000.0, processor.getHighCutHz());
        highCut.valueProperty().addListener((_, _, v) -> processor.setHighCutHz(v.doubleValue()));

        Slider mix = slider(0.0, 1.0, processor.getMix());
        mix.valueProperty().addListener((_, _, v) -> processor.setMix(v.doubleValue()));

        Slider width = slider(0.0, 2.0, processor.getStereoWidth());
        width.valueProperty().addListener((_, _, v) -> processor.setStereoWidth(v.doubleValue()));

        HBox sliders = new HBox(12,
                labelled("Stretch",      stretch),
                labelled("Predelay (ms)", predelay),
                labelled("Low Cut (Hz)", lowCut),
                labelled("High Cut (Hz)", highCut),
                labelled("Mix",          mix),
                labelled("Width",        width));
        sliders.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(title, topBar, waveform, statusLabel, sliders);
        drawWaveform();
    }

    private static Slider slider(double min, double max, double initial) {
        Slider s = new Slider(min, max, initial);
        s.setPrefWidth(110);
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

    /** Cached downsampled (min, max) pairs per pixel column, recomputed only when the IR changes. */
    private float[] cachedMinPerColumn;
    private float[] cachedMaxPerColumn;
    private int cachedIrLength = -1;
    private String cachedIrSourceId;

    /**
     * Renders the current IR's waveform onto the canvas with the trim
     * markers overlaid as draggable vertical lines. Reuses a cached
     * min/max-per-pixel-column buffer; only refreshes the cache when the
     * IR itself changes (length or source id), not on every drag/slider.
     */
    void drawWaveform() {
        GraphicsContext g = waveform.getGraphicsContext2D();
        double w = waveform.getWidth();
        double h = waveform.getHeight();
        g.setFill(Color.rgb(20, 20, 20));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.rgb(70, 70, 70));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        int len = processor.getImpulseResponseLength();
        String sourceId = processor.getImpulseResponseSourceId();
        int pixels = (int) w;
        boolean cacheValid = cachedMinPerColumn != null
                && cachedMinPerColumn.length == pixels
                && cachedIrLength == len
                && java.util.Objects.equals(cachedIrSourceId, sourceId);
        if (!cacheValid && len > 0) {
            float[][] ir = processor.getImpulseResponseSnapshot();
            if (ir.length > 0 && ir[0].length > 0) {
                float[] ch = ir[0];
                int n = ch.length;
                cachedMinPerColumn = new float[pixels];
                cachedMaxPerColumn = new float[pixels];
                for (int x = 0; x < pixels; x++) {
                    int srcStart = (int) ((double) x * n / pixels);
                    int srcEnd = Math.max(srcStart + 1, (int) ((double) (x + 1) * n / pixels));
                    float min = 0, max = 0;
                    for (int i = srcStart; i < srcEnd && i < n; i++) {
                        if (ch[i] < min) min = ch[i];
                        if (ch[i] > max) max = ch[i];
                    }
                    cachedMinPerColumn[x] = min;
                    cachedMaxPerColumn[x] = max;
                }
                cachedIrLength = len;
                cachedIrSourceId = sourceId;
            }
        }

        if (cachedMaxPerColumn != null && cachedMaxPerColumn.length == pixels) {
            float peak = 1f;
            for (int x = 0; x < pixels; x++) {
                if (Math.abs(cachedMaxPerColumn[x]) > peak) peak = Math.abs(cachedMaxPerColumn[x]);
                if (Math.abs(cachedMinPerColumn[x]) > peak) peak = Math.abs(cachedMinPerColumn[x]);
            }
            double mid = h * 0.5;
            g.setStroke(Color.rgb(110, 200, 130));
            g.setLineWidth(1.0);
            for (int x = 0; x < pixels; x++) {
                double y0 = mid - (cachedMaxPerColumn[x] / peak) * (h * 0.45);
                double y1 = mid - (cachedMinPerColumn[x] / peak) * (h * 0.45);
                g.strokeLine(x + 0.5, y0, x + 0.5, y1);
            }
        }

        // Trim markers
        g.setStroke(Color.rgb(230, 180, 60));
        g.setLineWidth(2.0);
        double xs = trimStartFraction * w;
        double xe = trimEndFraction * w;
        g.strokeLine(xs, 0, xs, h);
        g.strokeLine(xe, 0, xe, h);
    }

    /** Invalidates the cached waveform so the next {@link #drawWaveform} re-samples from the processor. */
    private void invalidateWaveformCache() {
        cachedMinPerColumn = null;
        cachedMaxPerColumn = null;
        cachedIrLength = -1;
        cachedIrSourceId = null;
    }

    /** Releases UI resources. Currently a no-op — present for symmetry with other views. */
    public void dispose() {
        // nothing to release
    }

    /** @return the trim-start fraction currently shown by the UI (for tests) */
    double getViewTrimStart() { return trimStartFraction; }
    /** @return the trim-end fraction currently shown by the UI (for tests) */
    double getViewTrimEnd() { return trimEndFraction; }
}
