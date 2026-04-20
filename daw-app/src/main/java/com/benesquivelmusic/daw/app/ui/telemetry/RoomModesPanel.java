package com.benesquivelmusic.daw.app.ui.telemetry;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.telemetry.acoustics.RoomModeCalculator;
import com.benesquivelmusic.daw.sdk.telemetry.ModeKind;
import com.benesquivelmusic.daw.sdk.telemetry.ModeSpectrum;
import com.benesquivelmusic.daw.sdk.telemetry.RoomMode;
import com.benesquivelmusic.daw.sdk.telemetry.TelemetrySuggestion;

import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;
import java.util.Objects;

/**
 * Room-modes plot for the
 * {@link com.benesquivelmusic.daw.app.ui.TelemetrySetupPanel}.
 *
 * <p>Draws a linear-frequency axis (20–500&nbsp;Hz by default) with one
 * vertical line per mode, colour-coded by {@link ModeKind}: axial red,
 * tangential orange, oblique yellow. A dashed vertical at the Schroeder
 * frequency marks the transition between the modal regime and the
 * diffuse-field regime. Line height encodes the modal magnitude at the
 * listening position — shorter lines mean the listener sits in a
 * partial null for that mode.</p>
 *
 * <p>Computation is performed live by {@link RoomModeCalculator};
 * nothing new is persisted alongside the room configuration.</p>
 */
public final class RoomModesPanel extends VBox {

    private static final String EMPTY_STYLE =
            "-fx-text-fill: #707080; -fx-font-size: 12px; -fx-font-style: italic;";
    private static final String ANNOTATION_STYLE =
            "-fx-text-fill: #80cbc4; -fx-font-size: 11px;";
    private static final String WARNING_STYLE =
            "-fx-text-fill: #ff8a65; -fx-font-size: 11px;";

    /** Canvas width in px. */
    private static final double PLOT_WIDTH = 320;
    /** Canvas height in px. */
    private static final double PLOT_HEIGHT = 140;

    /** Lower edge of the plotted frequency band (Hz). */
    private static final double F_MIN = 20.0;
    /** Upper edge of the plotted frequency band (Hz). */
    private static final double F_MAX = 500.0;

    /** Magnitude range encoded as bar height (dB). */
    private static final double DB_RANGE = 30.0;

    // Colour palette — keep in sync with RoomTelemetryDisplay heatmap.
    private static final Color AXIAL_COLOR      = Color.web("#ff5252"); // red
    private static final Color TANGENTIAL_COLOR = Color.web("#ffab40"); // orange
    private static final Color OBLIQUE_COLOR    = Color.web("#ffee58"); // yellow
    private static final Color SCHROEDER_COLOR  = Color.web("#81d4fa"); // light blue

    private final RoomModeCalculator calculator;
    private ModeSpectrum latestSpectrum;

    /** Creates a panel using the default {@link RoomModeCalculator}. */
    public RoomModesPanel() {
        this(new RoomModeCalculator());
    }

    /**
     * Creates a panel using the supplied calculator (handy for
     * deterministic orders in tests).
     *
     * @param calculator the room-mode calculator to use
     */
    public RoomModesPanel(RoomModeCalculator calculator) {
        this.calculator = Objects.requireNonNull(calculator, "calculator must not be null");
        setSpacing(6);
        setPadding(new Insets(4));
        showEmptyState();
    }

    /**
     * Refreshes the plot for the supplied room configuration.
     *
     * @param config the room configuration (must not be {@code null})
     */
    public void update(RoomConfiguration config) {
        Objects.requireNonNull(config, "config must not be null");
        getChildren().clear();

        ModeSpectrum spectrum;
        try {
            spectrum = calculator.calculate(config);
        } catch (RuntimeException ex) {
            // Degenerate room (e.g. zero absorption) — show empty state
            // rather than propagate a stack trace to the UI.
            showEmptyState();
            return;
        }
        latestSpectrum = spectrum;

        Canvas canvas = new Canvas(PLOT_WIDTH, PLOT_HEIGHT);
        drawPlot(canvas.getGraphicsContext2D(), spectrum);

        Label axialSummary = new Label(
                "Axial modes below Schroeder (%.0f Hz): %d"
                        .formatted(spectrum.schroederHz(),
                                countBelow(spectrum.axialModes(), spectrum.schroederHz())));
        axialSummary.setStyle(ANNOTATION_STYLE);

        List<TelemetrySuggestion> warnings = calculator.suggestMitigations(
                config.getDimensions(), spectrum);

        getChildren().addAll(canvas, axialSummary);
        for (TelemetrySuggestion s : warnings) {
            Label warn = new Label("⚠ " + s.description());
            warn.setStyle(WARNING_STYLE);
            warn.setWrapText(true);
            getChildren().add(warn);
        }
    }

    /** Returns the most recent computed spectrum, or {@code null}. */
    public ModeSpectrum getLatestSpectrum() {
        return latestSpectrum;
    }

    // ------------------------------------------------------------------
    // Rendering helpers
    // ------------------------------------------------------------------

    private void showEmptyState() {
        latestSpectrum = null;
        Label empty = new Label("Enter valid room dimensions to see the room-mode plot.");
        empty.setStyle(EMPTY_STYLE);
        empty.setWrapText(true);
        getChildren().add(empty);
    }

    private static void drawPlot(GraphicsContext gc, ModeSpectrum spectrum) {
        double w = gc.getCanvas().getWidth();
        double h = gc.getCanvas().getHeight();

        // Background.
        gc.setFill(Color.web("#0f0f1f"));
        gc.fillRect(0, 0, w, h);

        // Baseline and frequency tick-marks at 100 / 200 / 300 / 400 Hz.
        gc.setStroke(Color.web("#33334a"));
        gc.setLineWidth(0.7);
        gc.strokeLine(0, h - 12, w, h - 12);
        gc.setFill(Color.web("#707080"));
        gc.setFont(Font.font("System", 9));
        for (int f = 100; f <= 400; f += 100) {
            double x = freqToX(f, w);
            gc.strokeLine(x, h - 14, x, h - 10);
            gc.fillText(f + " Hz", x - 12, h - 1);
        }

        // Modes — oblique first (drawn behind), tangential, axial on top
        // so the most important modes are visible.
        drawKind(gc, spectrum.obliqueModes(),    OBLIQUE_COLOR,    w, h, 1.0);
        drawKind(gc, spectrum.tangentialModes(), TANGENTIAL_COLOR, w, h, 1.3);
        drawKind(gc, spectrum.axialModes(),      AXIAL_COLOR,      w, h, 1.8);

        // Schroeder dashed vertical.
        double sx = freqToX(spectrum.schroederHz(), w);
        if (sx >= 0 && sx <= w) {
            gc.setStroke(SCHROEDER_COLOR);
            gc.setLineWidth(1.2);
            gc.setLineDashes(4, 4);
            gc.strokeLine(sx, 4, sx, h - 14);
            gc.setLineDashes(null);
            gc.setFill(SCHROEDER_COLOR);
            gc.fillText("f_s", sx + 2, 12);
        }
    }

    private static void drawKind(GraphicsContext gc, List<RoomMode> modes,
                                 Color color, double w, double h, double lineWidth) {
        gc.setStroke(color);
        gc.setLineWidth(lineWidth);
        double baseline = h - 12;
        for (RoomMode m : modes) {
            double f = m.frequencyHz();
            if (f < F_MIN || f > F_MAX) continue;
            double x = freqToX(f, w);
            // Clip magnitude into [-DB_RANGE, 0] → bar height.
            double db = Math.max(-DB_RANGE, Math.min(0.0, m.magnitudeDb()));
            double t = (db + DB_RANGE) / DB_RANGE; // 1 = full bar, 0 = deep null
            double bar = Math.max(2.0, t * (baseline - 4));
            gc.strokeLine(x, baseline, x, baseline - bar);
        }
    }

    private static double freqToX(double f, double width) {
        double t = (f - F_MIN) / (F_MAX - F_MIN);
        return Math.max(0, Math.min(1, t)) * width;
    }

    private static int countBelow(List<RoomMode> modes, double schroederHz) {
        int n = 0;
        for (RoomMode m : modes) if (m.frequencyHz() <= schroederHz) n++;
        return n;
    }
}
