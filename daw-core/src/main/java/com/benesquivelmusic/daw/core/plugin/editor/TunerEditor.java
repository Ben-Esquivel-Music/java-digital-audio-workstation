package com.benesquivelmusic.daw.core.plugin.editor;

import java.util.Locale;
import java.util.Objects;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import com.benesquivelmusic.daw.core.plugin.TunerPlugin;
import com.benesquivelmusic.daw.core.plugin.TunerPlugin.TuningResult;
import com.benesquivelmusic.daw.sdk.editor.ChromePolicy;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.Theme;

/**
 * {@link PluginEditorFactory.Panel} editor for the built-in
 * {@link TunerPlugin} — the story 302 (§8.3) replacement for the retired
 * {@code daw-app} window wrapper {@code TunerDisplayWindow}. (The richer
 * {@code TunerDisplay} control itself survives in {@code daw-app} for its
 * other consumers; this editor ports only the window's reference-pitch
 * spinner semantics plus a compact readout.)
 *
 * <p>Shows an editable A4 reference-pitch {@link Spinner} (clamped to
 * {@link TunerPlugin#MIN_REFERENCE_PITCH_HZ}..{@link TunerPlugin#MAX_REFERENCE_PITCH_HZ}),
 * a large note-name + octave label, a ±50-cent deviation bar drawn on a small
 * {@link javafx.scene.canvas.Canvas Canvas}, and cents / frequency labels. When
 * {@link TunerPlugin#getLastResult()} is {@code null} the readout shows
 * placeholder dashes.</p>
 *
 * <p>This class is a reference example of the {@code Panel} contract with
 * {@link ChromePolicy#MINIMAL MINIMAL} chrome. Canvas painting reads the
 * resolved {@link Theme} tokens from {@link EditorContext#theme()} — the
 * needle uses the accent token while in tune and {@code meterWarn} otherwise —
 * and repaints when {@link EditorContext#themeProperty()} changes. A
 * showing-window-gated ({@link ShowingWindowGate}) {@link AnimationTimer}
 * polls {@code getLastResult()} each frame and refreshes the readout only
 * when the result reference changed; a {@code Panel} has no dispose hook, so
 * the timer runs while the panel sits in a scene whose window is showing and
 * stops when the panel leaves the scene or the window hides (re-attachable
 * both ways).</p>
 *
 * <p>The {@code TunerPlugin} exposes no {@code PluginParameter}-backed state,
 * so the spinner commits through the plugin's own
 * {@link TunerPlugin#setReferencePitchHz(double)} accessor — the plugin
 * editing its own state is within the contract (§2.6).</p>
 */
public final class TunerEditor implements PluginEditorFactory.Panel {

    /** Full-scale cents deviation shown by the bar (±50 cents = ±half semitone). */
    static final double CENTS_RANGE = 50.0;

    private static final double CENTS_BAR_WIDTH = 260;
    private static final double CENTS_BAR_HEIGHT = 26;

    private final TunerPlugin plugin;

    /**
     * Creates the editor factory for the given plugin instance.
     *
     * @param plugin the tuner plugin to edit; must not be {@code null}
     */
    public TunerEditor(TunerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    @Override
    public Region createPanel(EditorContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return new TunerPane(plugin, context);
    }

    @Override
    public EditorHints hints() {
        return EditorHints.standard().withSize(360, 220).withChrome(ChromePolicy.MINIMAL);
    }

    // ── Pure layout math (package-private static, headlessly testable) ────

    /**
     * Maps a cents offset onto an x position across a bar of the given width:
     * {@code -CENTS_RANGE} → {@code 0}, {@code 0} → {@code width / 2},
     * {@code +CENTS_RANGE} → {@code width}. Offsets beyond the range clamp to
     * the bar's edges.
     *
     * @param cents the cents offset (sharp positive, flat negative)
     * @param width the bar width in pixels
     * @return the x position of the needle
     */
    static double centsToX(double cents, double width) {
        double clamped = Math.max(-CENTS_RANGE, Math.min(CENTS_RANGE, cents));
        return (clamped + CENTS_RANGE) / (2.0 * CENTS_RANGE) * width;
    }

    // ── The panel ──────────────────────────────────────────────────────────

    /** The editor's root region — one instance per {@code createPanel} call. */
    private static final class TunerPane extends VBox {

        private final TunerPlugin plugin;
        private final EditorContext context;
        private final Spinner<Integer> refPitchSpinner;
        private final Label noteLabel;
        private final Label centsLabel;
        private final Label freqLabel;
        // Fully qualified: the inherited member type PluginEditorFactory.Canvas
        // shadows a javafx.scene.canvas.Canvas import inside this class body.
        private final javafx.scene.canvas.Canvas centsCanvas;
        private final AnimationTimer poller;

        /** Last result rendered — the poller repaints only on reference change. */
        private TuningResult lastSeen;

        TunerPane(TunerPlugin plugin, EditorContext context) {
            this.plugin = plugin;
            this.context = context;

            setSpacing(10);
            setPadding(new Insets(12));
            setAlignment(Pos.TOP_CENTER);

            // ── Reference pitch spinner (ported from TunerDisplayWindow) ───
            int minPitch = (int) TunerPlugin.MIN_REFERENCE_PITCH_HZ;
            int maxPitch = (int) TunerPlugin.MAX_REFERENCE_PITCH_HZ;
            int initialPitch = (int) Math.round(plugin.getReferencePitchHz());

            refPitchSpinner = new Spinner<>();
            refPitchSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                    minPitch, maxPitch, initialPitch));
            refPitchSpinner.setPrefWidth(80);
            refPitchSpinner.setEditable(true);
            refPitchSpinner.valueProperty().addListener((_, _, newVal) -> {
                if (newVal != null) {
                    // Clamp-on-edit: a typed value outside the legal range is
                    // coerced back into it (the re-set re-enters this listener
                    // once with the clamped value and then commits).
                    int clamped = Math.max(minPitch, Math.min(maxPitch, newVal));
                    if (clamped != newVal) {
                        refPitchSpinner.getValueFactory().setValue(clamped);
                        return;
                    }
                    plugin.setReferencePitchHz(clamped);
                }
            });

            HBox toolbar = new HBox(6, new Label("A4 ="), refPitchSpinner, new Label("Hz"));
            toolbar.setAlignment(Pos.CENTER_LEFT);

            // ── Readout ────────────────────────────────────────────────────
            noteLabel = new Label();
            noteLabel.setFont(Font.font(null, FontWeight.BOLD, 40));

            centsCanvas = new javafx.scene.canvas.Canvas(CENTS_BAR_WIDTH, CENTS_BAR_HEIGHT);

            centsLabel = new Label();
            freqLabel = new Label();

            getChildren().addAll(toolbar, noteLabel, centsCanvas, centsLabel, freqLabel);

            // Poll the plugin's latest result each frame; refresh the readout
            // only when the result reference changed.
            poller = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    TuningResult result = plugin.getLastResult();
                    if (result != lastSeen) {
                        lastSeen = result;
                        updateReadout(result);
                    }
                }
            };

            // Showing-window-gated lifecycle (a Panel has no dispose hook):
            // poll while visibly on screen, stop when the panel leaves the
            // scene or its window hides, re-attachable both ways.
            ShowingWindowGate.install(this,
                    () -> {
                        refreshReadout();
                        poller.start();
                    },
                    poller::stop);

            // Repaint the deviation bar with the new palette on theme change.
            context.themeProperty().addListener((_, _, _) -> paintCentsBar(lastSeen));

            refreshReadout();
        }

        /** Forces a readout refresh from the plugin's current state. */
        private void refreshReadout() {
            lastSeen = plugin.getLastResult();
            updateReadout(lastSeen);
        }

        private void updateReadout(TuningResult result) {
            if (result == null) {
                noteLabel.setText("--");
                centsLabel.setText("-- ¢");
                freqLabel.setText("-- Hz");
            } else {
                noteLabel.setText(result.noteName() + result.octave());
                centsLabel.setText(String.format(Locale.ROOT, "%+.1f ¢", result.centsOffset()));
                freqLabel.setText(String.format(Locale.ROOT, "%.1f Hz", result.frequencyHz()));
            }
            paintCentsBar(result);
        }

        // ── Painting (colours from the resolved host Theme tokens, §2.5) ───

        private void paintCentsBar(TuningResult result) {
            Theme theme = context.theme();
            GraphicsContext g = centsCanvas.getGraphicsContext2D();
            double w = centsCanvas.getWidth();
            double h = centsCanvas.getHeight();

            Color track = theme.background();
            Color mid = theme.background().interpolate(theme.foreground(), 0.35);

            g.setFill(track);
            g.fillRect(0, 0, w, h);
            g.setStroke(mid);
            g.setLineWidth(1);
            g.strokeRect(0.5, 0.5, w - 1, h - 1);

            // Centre (in-tune) tick
            double cx = centsToX(0, w);
            g.strokeLine(cx, 0, cx, h);

            // Needle: accent while in tune, warning colour otherwise
            if (result != null) {
                double x = centsToX(result.centsOffset(), w);
                g.setStroke(result.inTune() ? theme.accent() : theme.meterWarn());
                g.setLineWidth(3);
                g.strokeLine(x, 2, x, h - 2);
            }
        }
    }
}
