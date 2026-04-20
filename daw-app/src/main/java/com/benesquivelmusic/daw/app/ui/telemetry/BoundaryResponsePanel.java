package com.benesquivelmusic.daw.app.ui.telemetry;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.telemetry.acoustics.SbirCalculator;
import com.benesquivelmusic.daw.sdk.telemetry.BoundaryKind;
import com.benesquivelmusic.daw.sdk.telemetry.SbirPrediction;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;

import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Compact display of the predicted Speaker Boundary Interference
 * Response (SBIR) frequency-response curve for each speaker in a
 * {@link RoomConfiguration}.
 *
 * <p>Shown inside the &quot;Boundary Response&quot; section of
 * {@link com.benesquivelmusic.daw.app.ui.TelemetrySetupPanel}. Each
 * speaker gets a small SVG-style FR plot overlaid with the worst notch
 * highlighted as a vertical marker and an annotation such as
 * &quot;−8&nbsp;dB at 165&nbsp;Hz — move speaker 0.3&nbsp;m from front
 * wall to mitigate&quot;.</p>
 *
 * <p>Computation is performed live by {@link SbirCalculator}; nothing
 * new is persisted alongside the room configuration.</p>
 */
public final class BoundaryResponsePanel extends VBox {

    private static final String LABEL_STYLE =
            "-fx-text-fill: #b0b0b0; -fx-font-size: 12px;";
    private static final String EMPTY_STYLE =
            "-fx-text-fill: #707080; -fx-font-size: 12px; -fx-font-style: italic;";

    private static final double CURVE_WIDTH = 320;
    private static final double CURVE_HEIGHT = 90;

    /** Plot magnitude range, in dB (top to bottom). */
    private static final double DB_MAX = 6.0;
    private static final double DB_MIN = -18.0;

    /** Plot frequency range, in Hz (left to right) — log scale. */
    private static final double F_MIN = 30.0;
    private static final double F_MAX = 500.0;

    private final SbirCalculator calculator;
    private final List<SbirPrediction> latestPredictions = new ArrayList<>();

    /** Creates an empty panel using a default {@link SbirCalculator}. */
    public BoundaryResponsePanel() {
        this(new SbirCalculator());
    }

    /**
     * Creates an empty panel using the supplied calculator (handy for
     * deterministic frequency grids in tests).
     *
     * @param calculator the SBIR calculator to use
     */
    public BoundaryResponsePanel(SbirCalculator calculator) {
        this.calculator = Objects.requireNonNull(calculator, "calculator must not be null");
        setSpacing(8);
        setPadding(new Insets(4));
        showEmptyState();
    }

    /**
     * Refreshes the panel for the supplied room configuration. Each
     * speaker is plotted with its worst-boundary SBIR prediction.
     *
     * @param config the room configuration (must not be {@code null})
     */
    public void update(RoomConfiguration config) {
        Objects.requireNonNull(config, "config must not be null");
        getChildren().clear();
        latestPredictions.clear();

        if (config.getSoundSources().isEmpty() || config.getMicrophones().isEmpty()) {
            showEmptyState();
            return;
        }

        List<SbirPrediction> predictions = calculator.calculate(config);
        latestPredictions.addAll(predictions);

        for (int i = 0; i < predictions.size(); i++) {
            SoundSource src = config.getSoundSources().get(i);
            SbirPrediction p = predictions.get(i);

            Label title = new Label("\uD83D\uDD0A  " + src.name() + " — "
                    + describeBoundary(p.boundary()));
            title.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12px; -fx-font-weight: bold;");

            Canvas canvas = new Canvas(CURVE_WIDTH, CURVE_HEIGHT);
            drawCurve(canvas.getGraphicsContext2D(), p);

            Label annotation = new Label(formatAnnotation(p));
            annotation.setStyle(p.worstNotchDepthDb() <= -5.0
                    ? "-fx-text-fill: #ff8a65; -fx-font-size: 11px;"
                    : "-fx-text-fill: #80cbc4; -fx-font-size: 11px;");
            annotation.setWrapText(true);

            getChildren().addAll(title, canvas, annotation);
        }
    }

    /** Returns an immutable snapshot of the most recently computed predictions. */
    public List<SbirPrediction> getLatestPredictions() {
        return Collections.unmodifiableList(new ArrayList<>(latestPredictions));
    }

    // ------------------------------------------------------------------
    // Rendering helpers
    // ------------------------------------------------------------------

    private void showEmptyState() {
        Label empty = new Label("Add at least one speaker and one microphone to see "
                + "the boundary-response prediction.");
        empty.setStyle(EMPTY_STYLE);
        empty.setWrapText(true);
        getChildren().add(empty);
    }

    private static void drawCurve(GraphicsContext gc, SbirPrediction p) {
        double w = gc.getCanvas().getWidth();
        double h = gc.getCanvas().getHeight();

        // Background.
        gc.setFill(Color.web("#0f0f1f"));
        gc.fillRect(0, 0, w, h);

        // Grid: 0 dB and −10 dB horizontal lines.
        gc.setStroke(Color.web("#33334a"));
        gc.setLineWidth(0.7);
        double y0 = dbToY(0, h);
        double yM10 = dbToY(-10, h);
        gc.strokeLine(0, y0, w, y0);
        gc.strokeLine(0, yM10, w, yM10);

        // Axis labels.
        gc.setFill(Color.web("#707080"));
        gc.setFont(Font.font("System", 9));
        gc.fillText("0 dB", 2, y0 - 2);
        gc.fillText("−10 dB", 2, yM10 - 2);
        gc.fillText(((int) F_MIN) + " Hz", 2, h - 2);
        gc.fillText(((int) F_MAX) + " Hz", w - 38, h - 2);

        // Worst-notch vertical highlight.
        double notchX = freqToX(p.worstNotchHz(), w);
        if (notchX >= 0 && notchX <= w) {
            gc.setStroke(Color.web("#ff5252", 0.55));
            gc.setLineWidth(2.0);
            gc.strokeLine(notchX, 4, notchX, h - 12);
        }

        // FR curve.
        gc.setStroke(Color.web("#69f0ae"));
        gc.setLineWidth(1.5);
        double[] freqs = p.frequenciesHz();
        double[] mags = p.magnitudeDb();
        boolean started = false;
        double prevX = 0, prevY = 0;
        for (int i = 0; i < freqs.length; i++) {
            double f = freqs[i];
            if (f < F_MIN || f > F_MAX) continue;
            double x = freqToX(f, w);
            double y = dbToY(mags[i], h);
            if (started) {
                gc.strokeLine(prevX, prevY, x, y);
            }
            prevX = x;
            prevY = y;
            started = true;
        }
    }

    private static double dbToY(double db, double height) {
        double clamped = Math.max(DB_MIN, Math.min(DB_MAX, db));
        double t = (DB_MAX - clamped) / (DB_MAX - DB_MIN);
        return t * height;
    }

    private static double freqToX(double f, double width) {
        double logMin = Math.log(F_MIN);
        double logMax = Math.log(F_MAX);
        double t = (Math.log(Math.max(F_MIN * 0.5, f)) - logMin) / (logMax - logMin);
        return Math.max(0, Math.min(1, t)) * width;
    }

    private static String formatAnnotation(SbirPrediction p) {
        double recommendedDistance =
                SbirCalculator.SPEED_OF_SOUND_M_S
                        / (4.0 * SbirCalculator.SBIR_BAND_LOW_HZ);
        if (p.worstNotchDepthDb() <= -5.0) {
            return "%.1f dB notch at %.0f Hz — move speaker ≥ %.2f m from %s to mitigate"
                    .formatted(p.worstNotchDepthDb(), p.worstNotchHz(),
                            recommendedDistance, describeBoundary(p.boundary()));
        }
        return "Worst notch %.1f dB at %.0f Hz from %s — within acceptable limits."
                .formatted(p.worstNotchDepthDb(), p.worstNotchHz(),
                        describeBoundary(p.boundary()));
    }

    private static String describeBoundary(BoundaryKind kind) {
        return switch (kind) {
            case FRONT_WALL -> "front wall";
            case BACK_WALL  -> "back wall";
            case SIDE_WALL  -> "nearest side wall";
            case FLOOR      -> "floor";
            case CEILING    -> "ceiling";
        };
    }
}
