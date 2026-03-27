package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.transport.TimeDisplayMode;
import com.benesquivelmusic.daw.core.transport.TimelineRulerModel;
import com.benesquivelmusic.daw.core.transport.Transport;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A timeline ruler component that displays bar numbers, beat subdivisions
 * and a click-to-seek playhead for the arrangement view.
 *
 * <p>The ruler supports two display modes — musical time
 * ({@link TimeDisplayMode#BARS_BEATS_TICKS}) and absolute time
 * ({@link TimeDisplayMode#TIME}) — toggleable via {@link #toggleDisplayMode()}.
 * It also renders the current playhead position as a prominent vertical line
 * and displays the tempo and time signature.</p>
 *
 * <p>Click-to-seek: clicking anywhere on the ruler fires the registered
 * seek callback with the corresponding beat position.</p>
 */
public final class TimelineRuler extends Pane {

    /** Default ruler height in pixels. */
    public static final double DEFAULT_HEIGHT = 32.0;

    /** Default pixels-per-beat at zoom 1.0. */
    public static final double BASE_PIXELS_PER_BEAT = 40.0;

    static final Color RULER_BACKGROUND = Color.web("#1c1c2e");
    static final Color RULER_LINE_COLOR = Color.web("#555577");
    static final Color RULER_TEXT_COLOR = Color.web("#ccccdd");
    static final Color PLAYHEAD_COLOR = Color.web("#ff5555");
    static final Color BAR_LINE_COLOR = Color.web("#7c4dff", 0.6);
    static final Color BEAT_LINE_COLOR = Color.web("#555577", 0.4);
    static final Color TEMPO_TEXT_COLOR = Color.web("#aaaacc");

    private static final Font LABEL_FONT = Font.font("Monospaced", 10);
    private static final Font TEMPO_FONT = Font.font("Monospaced", 9);
    private static final double TICK_MAJOR_HEIGHT = 12.0;
    private static final double TICK_MINOR_HEIGHT = 6.0;
    private static final double PLAYHEAD_WIDTH = 2.0;

    private final TimelineRulerModel model;
    private final Canvas canvas;

    private double pixelsPerBeat = BASE_PIXELS_PER_BEAT;
    private double scrollOffsetBeats = 0.0;
    private double playheadPositionBeats = 0.0;
    private double totalBeats = 0.0;
    private boolean autoScroll = true;

    private final List<Consumer<Double>> seekListeners = new ArrayList<>();

    /**
     * Creates a timeline ruler backed by the given transport.
     *
     * @param transport the transport providing tempo, position and time-signature data
     */
    public TimelineRuler(Transport transport) {
        Objects.requireNonNull(transport, "transport must not be null");
        this.model = new TimelineRulerModel(transport);
        this.canvas = new Canvas();

        getChildren().add(canvas);
        setPrefHeight(DEFAULT_HEIGHT);
        setMinHeight(DEFAULT_HEIGHT);
        setMaxHeight(DEFAULT_HEIGHT);

        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        canvas.widthProperty().addListener((obs, oldV, newV) -> redraw());
        canvas.heightProperty().addListener((obs, oldV, newV) -> redraw());

        canvas.setOnMousePressed(event -> {
            double beatPos = model.pixelToBeats(event.getX() + scrollOffsetBeats * pixelsPerBeat, pixelsPerBeat);
            setPlayheadPositionBeats(beatPos);
            for (Consumer<Double> listener : seekListeners) {
                listener.accept(beatPos);
            }
        });
    }

    /** Returns the underlying ruler model. */
    public TimelineRulerModel getModel() {
        return model;
    }

    /** Adds a listener that is called when the user clicks on the ruler to seek. */
    public void addSeekListener(Consumer<Double> listener) {
        seekListeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    /** Returns the current pixels-per-beat scale. */
    public double getPixelsPerBeat() {
        return pixelsPerBeat;
    }

    /**
     * Sets the horizontal scale in pixels per beat.
     *
     * @param pixelsPerBeat pixels per beat (must be &gt; 0)
     */
    public void setPixelsPerBeat(double pixelsPerBeat) {
        if (pixelsPerBeat <= 0) {
            throw new IllegalArgumentException("pixelsPerBeat must be positive: " + pixelsPerBeat);
        }
        this.pixelsPerBeat = pixelsPerBeat;
        redraw();
    }

    /**
     * Applies the given zoom level to compute the pixels-per-beat scale.
     *
     * @param zoomLevel the zoom level (typically from {@link ZoomLevel#getLevel()})
     */
    public void applyZoom(double zoomLevel) {
        setPixelsPerBeat(BASE_PIXELS_PER_BEAT * zoomLevel);
    }

    /** Returns the scroll offset in beats. */
    public double getScrollOffsetBeats() {
        return scrollOffsetBeats;
    }

    /** Sets the horizontal scroll offset in beats. */
    public void setScrollOffsetBeats(double scrollOffsetBeats) {
        this.scrollOffsetBeats = Math.max(0.0, scrollOffsetBeats);
        redraw();
    }

    /** Returns the playhead position in beats. */
    public double getPlayheadPositionBeats() {
        return playheadPositionBeats;
    }

    /** Sets the playhead position in beats and redraws. */
    public void setPlayheadPositionBeats(double positionInBeats) {
        this.playheadPositionBeats = Math.max(0.0, positionInBeats);
        if (autoScroll) {
            ensurePlayheadVisible();
        }
        redraw();
    }

    /** Returns the total project length in beats. */
    public double getTotalBeats() {
        return totalBeats;
    }

    /** Sets the total project length in beats (for determining ruler extent). */
    public void setTotalBeats(double totalBeats) {
        this.totalBeats = Math.max(0.0, totalBeats);
        redraw();
    }

    /** Returns {@code true} if auto-scroll is enabled. */
    public boolean isAutoScroll() {
        return autoScroll;
    }

    /** Enables or disables auto-scroll following the playhead. */
    public void setAutoScroll(boolean autoScroll) {
        this.autoScroll = autoScroll;
    }

    /** Toggles the time display mode and redraws. */
    public void toggleDisplayMode() {
        model.toggleDisplayMode();
        redraw();
    }

    /** Returns the current time display mode. */
    public TimeDisplayMode getDisplayMode() {
        return model.getDisplayMode();
    }

    /** Forces a full redraw of the ruler. */
    public void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        GraphicsContext gc = canvas.getGraphicsContext2D();

        gc.setFill(RULER_BACKGROUND);
        gc.fillRect(0, 0, w, h);

        drawTempoAndTimeSignature(gc, h);
        drawSubdivisions(gc, w, h);
        drawPlayhead(gc, h);
    }

    // ── private rendering methods ───────────────────────────────────────────

    private void drawTempoAndTimeSignature(GraphicsContext gc, double h) {
        Transport transport = model.getTransport();
        String tempoText = String.format("%.1f BPM  %d/%d",
                transport.getTempo(),
                transport.getTimeSignatureNumerator(),
                transport.getTimeSignatureDenominator());
        gc.setFont(TEMPO_FONT);
        gc.setFill(TEMPO_TEXT_COLOR);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(tempoText, 4, h - 4);
    }

    private void drawSubdivisions(GraphicsContext gc, double w, double h) {
        double subdivision = model.subdivisionForZoom(pixelsPerBeat);
        int beatsPerBar = model.getTransport().getTimeSignatureNumerator();

        double startBeat = Math.floor(scrollOffsetBeats / subdivision) * subdivision;
        double endBeat = scrollOffsetBeats + w / pixelsPerBeat;

        gc.setFont(LABEL_FONT);
        gc.setTextAlign(TextAlignment.LEFT);

        for (double beat = startBeat; beat <= endBeat; beat += subdivision) {
            if (beat < 0) {
                continue;
            }
            double x = (beat - scrollOffsetBeats) * pixelsPerBeat;

            boolean isBarLine = isBarBoundary(beat, beatsPerBar);
            if (isBarLine) {
                gc.setStroke(BAR_LINE_COLOR);
                gc.setLineWidth(1.0);
                gc.strokeLine(x, h - TICK_MAJOR_HEIGHT, x, h);

                gc.setFill(RULER_TEXT_COLOR);
                String label = model.formatPosition(beat);
                gc.fillText(label, x + 2, h - TICK_MAJOR_HEIGHT - 2);
            } else {
                gc.setStroke(BEAT_LINE_COLOR);
                gc.setLineWidth(0.5);
                gc.strokeLine(x, h - TICK_MINOR_HEIGHT, x, h);
            }
        }
    }

    private void drawPlayhead(GraphicsContext gc, double h) {
        double x = (playheadPositionBeats - scrollOffsetBeats) * pixelsPerBeat;
        if (x >= 0 && x <= canvas.getWidth()) {
            gc.setFill(PLAYHEAD_COLOR);
            gc.fillRect(x - PLAYHEAD_WIDTH / 2.0, 0, PLAYHEAD_WIDTH, h);

            double triangleSize = 5.0;
            gc.fillPolygon(
                    new double[]{x - triangleSize, x + triangleSize, x},
                    new double[]{0, 0, triangleSize},
                    3);
        }
    }

    private boolean isBarBoundary(double beat, int beatsPerBar) {
        double remainder = beat % beatsPerBar;
        return Math.abs(remainder) < 1e-9 || Math.abs(remainder - beatsPerBar) < 1e-9;
    }

    private void ensurePlayheadVisible() {
        double viewWidthBeats = canvas.getWidth() / pixelsPerBeat;
        if (viewWidthBeats <= 0) {
            return;
        }
        if (playheadPositionBeats < scrollOffsetBeats) {
            scrollOffsetBeats = playheadPositionBeats;
        } else if (playheadPositionBeats > scrollOffsetBeats + viewWidthBeats * 0.9) {
            scrollOffsetBeats = playheadPositionBeats - viewWidthBeats * 0.1;
        }
    }
}
