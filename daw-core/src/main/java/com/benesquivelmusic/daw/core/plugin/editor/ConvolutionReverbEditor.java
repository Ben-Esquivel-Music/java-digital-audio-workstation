package com.benesquivelmusic.daw.core.plugin.editor;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import com.benesquivelmusic.daw.core.dsp.reverb.ConvolutionReverbProcessor;
import com.benesquivelmusic.daw.core.dsp.reverb.ImpulseResponseLibrary;
import com.benesquivelmusic.daw.core.plugin.ConvolutionReverbPlugin;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.editor.Theme;

/**
 * {@link PluginEditorFactory.Panel} editor for the built-in
 * {@link ConvolutionReverbPlugin}, replacing the retired daw-app
 * {@code ConvolutionReverbPluginView} (story 302 §8.3): an IR selector over
 * {@link ImpulseResponseLibrary#ENTRIES} plus a WAV "Load File…" chooser, a
 * waveform canvas with draggable trim markers (primary-button drag moves trim
 * start, secondary-button drag moves trim end, {@value #MIN_TRIM_GAP} minimum
 * gap), and stretch / predelay / low-cut / high-cut / mix / width sliders. It
 * doubles as a reference example of the {@code Panel} contract: standard
 * controls are themed by the host stylesheet, canvas painting reads the
 * resolved {@link Theme} tokens and repaints on
 * {@link EditorContext#themeProperty()} changes, parameter gestures write the
 * processor <em>and</em> mirror into the host's {@link PluginParameterStore},
 * and the async IR file load marshals its completion back to the FX thread
 * with {@link Platform#runLater(Runnable)} (§4.7) — no animation timer, the
 * waveform repaints only on events.
 */
public final class ConvolutionReverbEditor implements PluginEditorFactory.Panel {

    // Parameter ids as declared by ConvolutionReverbPlugin#getParameters().
    private static final int PARAM_IR = 0;
    private static final int PARAM_STRETCH = 1;
    private static final int PARAM_PREDELAY = 2;
    private static final int PARAM_LOW_CUT = 3;
    private static final int PARAM_HIGH_CUT = 4;
    private static final int PARAM_MIX = 5;
    private static final int PARAM_WIDTH = 6;
    private static final int PARAM_TRIM_START = 7;
    private static final int PARAM_TRIM_END = 8;

    /** Minimum fraction of the IR kept between the two trim markers. */
    static final double MIN_TRIM_GAP = 0.01;

    /** Opacity applied to the theme foreground when stroking the canvas frame. */
    private static final double FRAME_OPACITY = 0.35;

    private final ConvolutionReverbPlugin plugin;

    // Populated in createPanel (called once, on the FX thread — §4.7).
    private EditorContext context;
    private PluginParameterStore store;
    private ConvolutionReverbProcessor processor;
    private VBox root;
    // Fully qualified: the simple name "Canvas" would resolve to the inherited
    // member type PluginEditorFactory.Canvas, which shadows the import.
    private javafx.scene.canvas.Canvas waveform;
    private Label statusLabel;

    private double trimStartFraction;
    private double trimEndFraction;

    /** Cached downsampled (min, max) pairs per pixel column, recomputed only when the IR changes. */
    private float[] cachedMinPerColumn;
    private float[] cachedMaxPerColumn;
    private int cachedIrLength = -1;
    private String cachedIrSourceId;

    /**
     * Creates the editor factory for the given plugin instance. The plugin —
     * not its processor — is captured because the host may call
     * {@code editorFactory()} before or after (re)initialisation; the live
     * processor is resolved once in {@link #createPanel(EditorContext)}.
     *
     * @param plugin the plugin whose processor this editor edits; must not be
     *               {@code null}
     */
    public ConvolutionReverbEditor(ConvolutionReverbPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    @Override
    public Region createPanel(EditorContext context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.store = context.parameterStore();
        ConvolutionReverbProcessor p = plugin.getProcessor();
        if (p == null) {
            throw new IllegalStateException(
                    "ConvolutionReverbPlugin has no processor — initialize() the plugin before opening its editor");
        }
        this.processor = p;
        this.trimStartFraction = p.getTrimStart();
        this.trimEndFraction = p.getTrimEnd();

        Label title = new Label("Convolution Reverb");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

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
                store.writeFromUiById(PARAM_IR, processor.getIrSelection());
                trimStartFraction = 0.0;
                trimEndFraction = 1.0;
                drawWaveform();
            }
        });

        statusLabel = new Label("");
        Button loadButton = new Button("Load File…");
        loadButton.setOnAction(_ -> openImpulseResponseFile());

        HBox topBar = new HBox(8, new Label("IR:"), irCombo, loadButton);
        topBar.setAlignment(Pos.CENTER_LEFT);

        // ── Waveform canvas with draggable trim markers ───────────────
        waveform = new javafx.scene.canvas.Canvas(520, 120);
        waveform.setOnMouseDragged(e -> {
            double raw = e.getX() / waveform.getWidth();
            // Left button = trim start, right button = trim end.
            if (e.getButton() == MouseButton.PRIMARY) {
                double accepted = dragTrimStart(raw, trimStartFraction, trimEndFraction);
                if (accepted != trimStartFraction) {
                    trimStartFraction = accepted;
                    processor.setTrimStart(accepted);
                    store.writeFromUiById(PARAM_TRIM_START, processor.getTrimStart());
                }
            } else if (e.getButton() == MouseButton.SECONDARY) {
                double accepted = dragTrimEnd(raw, trimStartFraction, trimEndFraction);
                if (accepted != trimEndFraction) {
                    trimEndFraction = accepted;
                    processor.setTrimEnd(accepted);
                    store.writeFromUiById(PARAM_TRIM_END, processor.getTrimEnd());
                }
            }
            drawWaveform();
        });

        // ── Parameter sliders ─────────────────────────────────────────
        Slider stretch = slider(0.5, 2.0, processor.getStretch());
        stretch.valueProperty().addListener((_, _, v) -> {
            processor.setStretch(v.doubleValue());
            store.writeFromUiById(PARAM_STRETCH, processor.getStretch());
            drawWaveform();
        });

        Slider predelay = slider(0.0, 200.0, processor.getPredelayMs());
        predelay.valueProperty().addListener((_, _, v) -> {
            processor.setPredelayMs(v.doubleValue());
            store.writeFromUiById(PARAM_PREDELAY, processor.getPredelayMs());
        });

        Slider lowCut = slider(20.0, 1000.0, processor.getLowCutHz());
        lowCut.valueProperty().addListener((_, _, v) -> {
            processor.setLowCutHz(v.doubleValue());
            store.writeFromUiById(PARAM_LOW_CUT, processor.getLowCutHz());
        });

        Slider highCut = slider(1000.0, 20000.0, processor.getHighCutHz());
        highCut.valueProperty().addListener((_, _, v) -> {
            processor.setHighCutHz(v.doubleValue());
            store.writeFromUiById(PARAM_HIGH_CUT, processor.getHighCutHz());
        });

        Slider mix = slider(0.0, 1.0, processor.getMix());
        mix.valueProperty().addListener((_, _, v) -> {
            processor.setMix(v.doubleValue());
            store.writeFromUiById(PARAM_MIX, processor.getMix());
        });

        Slider width = slider(0.0, 2.0, processor.getStereoWidth());
        width.valueProperty().addListener((_, _, v) -> {
            processor.setStereoWidth(v.doubleValue());
            store.writeFromUiById(PARAM_WIDTH, processor.getStereoWidth());
        });

        HBox sliders = new HBox(12,
                labelled("Stretch",       stretch),
                labelled("Predelay (ms)", predelay),
                labelled("Low Cut (Hz)",  lowCut),
                labelled("High Cut (Hz)", highCut),
                labelled("Mix",           mix),
                labelled("Width",         width));
        sliders.setAlignment(Pos.CENTER_LEFT);

        root = new VBox(10, title, topBar, waveform, statusLabel, sliders);
        root.setPadding(new Insets(12));
        root.setAlignment(Pos.TOP_LEFT);

        context.themeProperty().addListener((_, _, _) -> drawWaveform());
        drawWaveform();
        return root;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Roomier than {@link EditorHints#standard()}: 720 × 400 fits the
     * 520 × 120 waveform strip plus the six-slider row.</p>
     */
    @Override
    public EditorHints hints() {
        return EditorHints.standard().withSize(720, 400);
    }

    /**
     * Opens the WAV file chooser and hands the picked file to the processor's
     * asynchronous IR loader; the completion marshals back to the FX thread
     * with {@link Platform#runLater(Runnable)} (§4.7) to update the status
     * label and repaint the waveform.
     */
    private void openImpulseResponseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load Impulse Response");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("WAV files", "*.wav"));
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        File f = chooser.showOpenDialog(owner);
        if (f != null) {
            statusLabel.setText("Loading…");
            processor.loadImpulseResponseFromFileAsync(Path.of(f.getAbsolutePath()))
                    .whenComplete((_, ex) -> Platform.runLater(() -> {
                        if (ex != null) {
                            statusLabel.setText("Failed: " + ex.getMessage());
                        } else {
                            statusLabel.setText("Loaded: " + f.getName());
                            drawWaveform();
                        }
                    }));
        }
    }

    // ── Trim-drag math (pure; unit-tested without the FX toolkit) ────────

    /**
     * Pure trim-drag math for a PRIMARY-button drag on the waveform: clamps
     * the raw pointer fraction into {@code [0, 1]} and accepts it as the new
     * trim-start only when it stays at least {@link #MIN_TRIM_GAP} below the
     * current trim-end. A rejected gesture returns {@code currentStart}
     * unchanged.
     *
     * @param rawFraction  pointer x divided by canvas width (may be outside 0..1)
     * @param currentStart the trim-start fraction currently shown
     * @param trimEnd      the trim-end fraction currently shown
     * @return the accepted new trim-start, or {@code currentStart} if rejected
     */
    static double dragTrimStart(double rawFraction, double currentStart, double trimEnd) {
        double frac = clampFraction(rawFraction);
        return frac < trimEnd - MIN_TRIM_GAP ? frac : currentStart;
    }

    /**
     * Pure trim-drag math for a SECONDARY-button drag on the waveform: clamps
     * the raw pointer fraction into {@code [0, 1]} and accepts it as the new
     * trim-end only when it stays at least {@link #MIN_TRIM_GAP} above the
     * current trim-start. A rejected gesture returns {@code currentEnd}
     * unchanged.
     *
     * @param rawFraction pointer x divided by canvas width (may be outside 0..1)
     * @param trimStart   the trim-start fraction currently shown
     * @param currentEnd  the trim-end fraction currently shown
     * @return the accepted new trim-end, or {@code currentEnd} if rejected
     */
    static double dragTrimEnd(double rawFraction, double trimStart, double currentEnd) {
        double frac = clampFraction(rawFraction);
        return frac > trimStart + MIN_TRIM_GAP ? frac : currentEnd;
    }

    /** Clamps a raw pointer-position fraction into {@code [0, 1]}. */
    static double clampFraction(double rawFraction) {
        return Math.max(0.0, Math.min(1.0, rawFraction));
    }

    // ── Waveform painting ────────────────────────────────────────────────

    /**
     * Renders the current IR's waveform onto the canvas with the trim markers
     * overlaid as draggable vertical lines, painted with the resolved
     * {@link Theme} tokens (§2.5). Reuses a cached min/max-per-pixel-column
     * buffer; only refreshes the cache when the IR itself changes (length or
     * source id), not on every drag / slider move.
     */
    private void drawWaveform() {
        GraphicsContext g = waveform.getGraphicsContext2D();
        Theme theme = context.theme();
        double w = waveform.getWidth();
        double h = waveform.getHeight();
        g.setFill(theme.background());
        g.fillRect(0, 0, w, h);
        g.setLineWidth(1.0);
        g.setStroke(theme.foreground().deriveColor(0.0, 1.0, 1.0, FRAME_OPACITY));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        int len = processor.getImpulseResponseLength();
        String sourceId = processor.getImpulseResponseSourceId();
        int pixels = (int) w;
        boolean cacheValid = cachedMinPerColumn != null
                && cachedMinPerColumn.length == pixels
                && cachedIrLength == len
                && Objects.equals(cachedIrSourceId, sourceId);
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
                    float min = 0;
                    float max = 0;
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
            g.setStroke(theme.meterSafe());
            g.setLineWidth(1.0);
            for (int x = 0; x < pixels; x++) {
                double y0 = mid - (cachedMaxPerColumn[x] / peak) * (h * 0.45);
                double y1 = mid - (cachedMinPerColumn[x] / peak) * (h * 0.45);
                g.strokeLine(x + 0.5, y0, x + 0.5, y1);
            }
        }

        // Trim markers
        g.setStroke(theme.meterWarn());
        g.setLineWidth(2.0);
        double xs = trimStartFraction * w;
        double xe = trimEndFraction * w;
        g.strokeLine(xs, 0, xs, h);
        g.strokeLine(xe, 0, xe, h);
    }

    // ── Small layout helpers ─────────────────────────────────────────────

    private static Slider slider(double min, double max, double initial) {
        Slider s = new Slider(min, max, initial);
        s.setPrefWidth(110);
        s.setShowTickMarks(true);
        return s;
    }

    private static VBox labelled(String text, javafx.scene.Node node) {
        VBox box = new VBox(2, new Label(text), node);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }
}
