package com.benesquivelmusic.daw.app.ui.spatial;

import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.core.reference.ReferenceTrackManager;
import com.benesquivelmusic.daw.core.spatial.qc.AbComparisonResult;
import com.benesquivelmusic.daw.core.spatial.qc.AtmosAbComparator;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.List;
import java.util.Objects;

/**
 * UI panel that displays an A/B comparison between the DAW's current Atmos
 * render and a multi-channel reference mix.
 *
 * <p>The view shows:</p>
 * <ul>
 *   <li>Side-by-side per-channel level bars (mix vs reference, in dB).</li>
 *   <li>A per-channel delta column in dB.</li>
 *   <li>An overall colour-coded match score.</li>
 *   <li>A scrubbable waveform difference plot summed across channels.</li>
 * </ul>
 *
 * <p>The {@code `} (backtick) key toggles the active reference track on and
 * off via the supplied {@link ReferenceTrackManager}, performing a brief
 * gain-matched crossfade so the user hears like-for-like. The crossfade is
 * applied by the audio engine controller — this view is purely the trigger
 * surface for the toggle.</p>
 *
 * <p>The optional auto-trim button computes per-channel gain trims using
 * {@link AtmosAbComparator#estimateAutoTrim} and stores them on the
 * reference track so they can be persisted by the project serializer.</p>
 *
 * <p>This class deliberately accepts the mix and reference buffers and the
 * sample rate via {@link #updateBuffers(float[][], float[][], double)} so it
 * has no direct dependency on the audio engine — the embedding controller
 * pumps fresh buffers in whenever the user requests a comparison.</p>
 */
public final class AtmosAbView extends BorderPane {

    /** The single key used to toggle A/B monitoring. */
    public static final KeyCodeCombination AB_TOGGLE_KEY =
            new KeyCodeCombination(KeyCode.BACK_QUOTE);

    private final ReferenceTrackManager manager;
    private final GridPane channelGrid = new GridPane();
    private final Canvas waveformCanvas = new Canvas(420, 96);
    private final Label scoreLabel = new Label("Match: —");
    private final Label alignmentLabel = new Label("Alignment: —");
    private final Label bedDeltaLabel = new Label("Bed RMS Δ: —");
    private final Button compareButton = new Button("Compare");
    private final Button autoTrimButton = new Button("Auto-Trim");
    private final CheckBox abToggle = new CheckBox("B (reference)");
    private final StringProperty status = new SimpleStringProperty("");

    private float[][] mixBuffer;
    private float[][] referenceBuffer;
    private double sampleRate = 48_000.0;
    private List<SpeakerLabel> speakerLabels;
    private AbComparisonResult lastResult;

    /**
     * Creates a new A/B comparison view bound to the given manager.
     *
     * @param manager the reference-track manager that holds the active
     *                reference track and the A/B toggle state
     */
    public AtmosAbView(ReferenceTrackManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        setPadding(new Insets(8));
        setTop(buildHeader());
        setCenter(channelGrid);
        setBottom(buildFooter());
        channelGrid.setHgap(8);
        channelGrid.setVgap(2);
        compareButton.setOnAction(e -> doCompare());
        autoTrimButton.setOnAction(e -> doAutoTrim());
        abToggle.setSelected(manager.isReferenceActive());
        abToggle.setOnAction(e -> manager.setReferenceActive(abToggle.isSelected()));
        // Bind a key handler for the single-key A/B toggle.  Embedders
        // typically install this on the parent scene.
        addEventHandler(KeyEvent.KEY_PRESSED, this::onKeyPressed);
    }

    /**
     * Updates the buffers backing this view. Both buffers must use a
     * {@code [channel][sample]} layout and share the same channel count.
     *
     * @param mix         the current mix render
     * @param reference   the multi-channel reference mix
     * @param sampleRate  the sample rate in Hz
     */
    public void updateBuffers(float[][] mix, float[][] reference, double sampleRate) {
        this.mixBuffer = mix;
        this.referenceBuffer = reference;
        this.sampleRate = sampleRate;
    }

    /**
     * Sets the speaker labels used for the per-channel rows.  When
     * unspecified, channels are labelled "Ch 1"–"Ch N".
     */
    public void setSpeakerLabels(List<SpeakerLabel> labels) {
        this.speakerLabels = labels;
    }

    /** Returns the most recent comparison result, or {@code null}. */
    public AbComparisonResult getLastResult() {
        return lastResult;
    }

    /** Status text for binding into a parent status bar. */
    public StringProperty statusProperty() {
        return status;
    }

    /**
     * Public hook for embedders that prefer to dispatch a single-key A/B
     * toggle from a higher level (e.g. a global accelerator). Crossfading
     * between A and B with level-matched output is the responsibility of
     * the audio engine controller observing
     * {@link ReferenceTrackManager#isReferenceActive()}.
     */
    public void toggleAb() {
        manager.toggleAB();
        abToggle.setSelected(manager.isReferenceActive());
        status.set(manager.isReferenceActive() ? "Monitoring: B (reference)"
                : "Monitoring: A (mix)");
    }

    private void onKeyPressed(KeyEvent e) {
        if (AB_TOGGLE_KEY.match(e)) {
            toggleAb();
            e.consume();
        }
    }

    private Region buildHeader() {
        HBox header = new HBox(8, compareButton, autoTrimButton, abToggle,
                new Label(" "), bedDeltaLabel, alignmentLabel, scoreLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 8, 0));
        HBox.setHgrow(scoreLabel, Priority.ALWAYS);
        return header;
    }

    private Region buildFooter() {
        VBox footer = new VBox(4, new Label("Mix − Reference (waveform diff)"),
                waveformCanvas);
        footer.setPadding(new Insets(8, 0, 0, 0));
        return footer;
    }

    private void doCompare() {
        if (mixBuffer == null || referenceBuffer == null) {
            status.set("No buffers available for comparison.");
            return;
        }
        ReferenceTrack active = manager.getActiveReferenceTrack();
        if (active == null) {
            status.set("No active reference track.");
            return;
        }
        AbComparisonResult r;
        try {
            r = new AtmosAbComparator(sampleRate)
                    .compare(mixBuffer, referenceBuffer);
        } catch (IllegalArgumentException iae) {
            status.set("Comparison failed: " + iae.getMessage());
            return;
        }
        this.lastResult = r;
        renderChannelGrid(r);
        renderWaveformDifference();
        bedDeltaLabel.setText(String.format("Bed RMS Δ: %+.2f dB", r.bedRmsDeltaDb()));
        alignmentLabel.setText(String.format("Alignment: %+.2f ms (%d samples)",
                r.alignmentMs(), r.alignmentSamples()));
        scoreLabel.setText(String.format("Match: %.0f%%", r.matchScore() * 100));
        scoreLabel.setStyle("-fx-text-fill: " + colourForScore(r.matchScore()) + ";");
    }

    private void doAutoTrim() {
        if (mixBuffer == null || referenceBuffer == null) {
            status.set("No buffers available for auto-trim.");
            return;
        }
        ReferenceTrack active = manager.getActiveReferenceTrack();
        if (active == null) {
            status.set("No active reference track.");
            return;
        }
        try {
            double[] trim = new AtmosAbComparator(sampleRate)
                    .estimateAutoTrim(mixBuffer, referenceBuffer);
            // Persist on the reference track so ProjectSerializer saves it.
            active.setPerChannelTrimDb(trim);
            status.set("Auto-trim applied to active reference.");
            doCompare();  // refresh the view
        } catch (IllegalArgumentException iae) {
            status.set("Auto-trim failed: " + iae.getMessage());
        }
    }

    private void renderChannelGrid(AbComparisonResult r) {
        channelGrid.getChildren().clear();
        channelGrid.add(new Label("Ch"), 0, 0);
        channelGrid.add(new Label("Mix dB"), 1, 0);
        channelGrid.add(new Label("Ref dB"), 2, 0);
        channelGrid.add(new Label("Δ dB"), 3, 0);
        channelGrid.add(new Label("Corr"), 4, 0);
        channelGrid.add(new Label("Levels"), 5, 0);

        for (int c = 0; c < r.channelCount(); c++) {
            int row = c + 1;
            channelGrid.add(new Label(channelLabel(c)), 0, row);
            channelGrid.add(new Label(String.format("%+.1f", r.mixRmsDb()[c])), 1, row);
            channelGrid.add(new Label(String.format("%+.1f", r.refRmsDb()[c])), 2, row);
            Label delta = new Label(String.format("%+.2f", r.deltasDb()[c]));
            delta.setStyle("-fx-text-fill: " + colourForDelta(r.deltasDb()[c]) + ";");
            channelGrid.add(delta, 3, row);
            channelGrid.add(new Label(String.format("%+.2f", r.correlations()[c])), 4, row);
            channelGrid.add(buildLevelBars(r.mixRmsDb()[c], r.refRmsDb()[c]), 5, row);
        }
    }

    private Region buildLevelBars(double mixDb, double refDb) {
        // Map [-60, 0] dB to [0, 120] px width.
        double mixW = Math.max(0, Math.min(120, (mixDb + 60) * 2));
        double refW = Math.max(0, Math.min(120, (refDb + 60) * 2));
        Rectangle mixBar = new Rectangle(mixW, 8, Color.web("#4ec9b0"));  // mix = teal
        Rectangle refBar = new Rectangle(refW, 8, Color.web("#dcdcaa"));  // ref = pale yellow
        VBox bars = new VBox(2, mixBar, refBar);
        bars.setPrefWidth(120);
        return bars;
    }

    private void renderWaveformDifference() {
        GraphicsContext g = waveformCanvas.getGraphicsContext2D();
        double w = waveformCanvas.getWidth();
        double h = waveformCanvas.getHeight();
        g.setFill(Color.web("#1e1e1e"));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.web("#3c3c3c"));
        g.strokeLine(0, h / 2, w, h / 2);

        if (mixBuffer == null || referenceBuffer == null) return;
        int channels = Math.min(mixBuffer.length, referenceBuffer.length);
        if (channels == 0) return;
        int frames = Integer.MAX_VALUE;
        for (int c = 0; c < channels; c++) {
            frames = Math.min(frames, Math.min(mixBuffer[c].length, referenceBuffer[c].length));
        }
        if (frames <= 0) return;

        int columns = (int) w;
        double samplesPerColumn = Math.max(1.0, (double) frames / columns);
        g.setStroke(Color.web("#f48771"));
        for (int x = 0; x < columns; x++) {
            int start = (int) (x * samplesPerColumn);
            int end = Math.min(frames, (int) ((x + 1) * samplesPerColumn));
            float peak = 0f;
            for (int i = start; i < end; i++) {
                for (int c = 0; c < channels; c++) {
                    float diff = mixBuffer[c][i] - referenceBuffer[c][i];
                    if (Math.abs(diff) > Math.abs(peak)) peak = diff;
                }
            }
            double y = h / 2 - peak * (h / 2);
            g.strokeLine(x, h / 2, x, y);
        }
    }

    private String channelLabel(int index) {
        if (speakerLabels != null && index < speakerLabels.size()) {
            return speakerLabels.get(index).name();
        }
        return "Ch " + (index + 1);
    }

    private static String colourForDelta(double deltaDb) {
        double a = Math.abs(deltaDb);
        if (a < 0.5) return "#73c991";   // green
        if (a < 1.5) return "#dcdcaa";   // yellow
        if (a < 3.0) return "#ce9178";   // orange
        return "#f48771";                // red
    }

    private static String colourForScore(double score) {
        if (score >= 0.9) return "#73c991";
        if (score >= 0.75) return "#dcdcaa";
        if (score >= 0.5) return "#ce9178";
        return "#f48771";
    }
}
