package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.transport.TimeDisplayMode;
import com.benesquivelmusic.daw.core.transport.TimelineRulerModel;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
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
 * <p>When loop mode is enabled on the transport, the ruler renders the loop
 * region as a semi-transparent colored bar between the loop start and end
 * positions, with draggable locator handles at each boundary. Users can
 * Shift+click-drag on the ruler to define a new loop region in a single
 * gesture.</p>
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
    static final Color LOOP_REGION_COLOR = Color.web("#b388ff", 0.3);
    static final Color LOOP_HANDLE_COLOR = Color.web("#b388ff", 0.9);
    static final Color LOOP_HANDLE_LINE_COLOR = Color.web("#b388ff", 0.7);
    static final Color PUNCH_REGION_COLOR = Color.web("#ff5555", 0.25);
    static final Color PUNCH_HANDLE_COLOR = Color.web("#ff5555", 0.9);
    static final Color PUNCH_HANDLE_LINE_COLOR = Color.web("#ff5555", 0.7);

    private static final Font LABEL_FONT = Font.font("Monospaced", 10);
    private static final Font TEMPO_FONT = Font.font("Monospaced", 9);
    private static final Font PUNCH_LABEL_FONT = Font.font("Monospaced", 8);
    private static final double TICK_MAJOR_HEIGHT = 12.0;
    private static final double TICK_MINOR_HEIGHT = 6.0;
    private static final double PLAYHEAD_WIDTH = 2.0;
    private static final double LOOP_HANDLE_WIDTH = 6.0;
    private static final double LOOP_HANDLE_HIT_ZONE = 8.0;
    private static final double PUNCH_HANDLE_WIDTH = 6.0;

    private final TimelineRulerModel model;
    private final Canvas canvas;

    private double pixelsPerBeat = BASE_PIXELS_PER_BEAT;
    private double scrollOffsetBeats = 0.0;
    private double playheadPositionBeats = 0.0;
    private double totalBeats = 0.0;
    private boolean autoScroll = true;

    // Loop handle dragging state
    private enum LoopDragMode { NONE, START_HANDLE, END_HANDLE, DEFINING_REGION }
    private LoopDragMode loopDragMode = LoopDragMode.NONE;
    private double loopDragAnchorBeat = 0.0;

    // Snap configuration
    private boolean snapEnabled = false;
    private GridResolution gridResolution = GridResolution.QUARTER;

    /** Sample rate used to convert punch region frame positions to beats. */
    private double sampleRate = 44_100.0;

    private final List<Consumer<Double>> seekListeners = new ArrayList<>();

    private final Tooltip loopTooltip = new Tooltip();

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

        canvas.setOnMousePressed(this::handleMousePressed);
        canvas.setOnMouseDragged(this::handleMouseDragged);
        canvas.setOnMouseReleased(this::handleMouseReleased);
        canvas.setOnMouseMoved(this::handleMouseMoved);
        canvas.setOnMouseExited(event -> Tooltip.uninstall(canvas, loopTooltip));
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

    /** Returns {@code true} if snap-to-grid is enabled for loop locator dragging. */
    public boolean isSnapEnabled() {
        return snapEnabled;
    }

    /** Sets whether snap-to-grid is applied when dragging loop locators. */
    public void setSnapEnabled(boolean snapEnabled) {
        this.snapEnabled = snapEnabled;
    }

    /** Returns the current grid resolution used for snap-to-grid. */
    public GridResolution getGridResolution() {
        return gridResolution;
    }

    /** Sets the grid resolution used for snap-to-grid on loop locators. */
    public void setGridResolution(GridResolution gridResolution) {
        this.gridResolution = Objects.requireNonNull(gridResolution, "gridResolution must not be null");
    }

    /** Returns the sample rate used for punch region frame-to-beat conversion. */
    public double getSampleRate() {
        return sampleRate;
    }

    /**
     * Sets the sample rate used to convert punch region frame positions to
     * beat positions for rendering.
     *
     * @param sampleRate the sample rate in Hz (must be &gt; 0)
     */
    public void setSampleRate(double sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.sampleRate = sampleRate;
        redraw();
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

        drawLoopRegion(gc, w, h);
        drawPunchRegion(gc, w, h);
        drawTempoAndTimeSignature(gc, h);
        drawSubdivisions(gc, w, h);
        drawLoopHandles(gc, h);
        drawPunchHandles(gc, h);
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

    private void drawLoopRegion(GraphicsContext gc, double w, double h) {
        Transport transport = model.getTransport();
        if (!transport.isLoopEnabled()) {
            return;
        }
        double loopStart = transport.getLoopStartInBeats();
        double loopEnd = transport.getLoopEndInBeats();
        double x1 = (loopStart - scrollOffsetBeats) * pixelsPerBeat;
        double x2 = (loopEnd - scrollOffsetBeats) * pixelsPerBeat;

        double drawX1 = Math.max(0, x1);
        double drawX2 = Math.min(w, x2);
        if (drawX2 > drawX1) {
            gc.setFill(LOOP_REGION_COLOR);
            gc.fillRect(drawX1, 0, drawX2 - drawX1, h);
        }
    }

    private void drawLoopHandles(GraphicsContext gc, double h) {
        Transport transport = model.getTransport();
        if (!transport.isLoopEnabled()) {
            return;
        }
        double loopStart = transport.getLoopStartInBeats();
        double loopEnd = transport.getLoopEndInBeats();
        double w = canvas.getWidth();

        double startX = (loopStart - scrollOffsetBeats) * pixelsPerBeat;
        if (startX >= -LOOP_HANDLE_WIDTH && startX <= w + LOOP_HANDLE_WIDTH) {
            gc.setStroke(LOOP_HANDLE_LINE_COLOR);
            gc.setLineWidth(1.5);
            gc.strokeLine(startX, 0, startX, h);
            gc.setFill(LOOP_HANDLE_COLOR);
            gc.fillRect(startX - LOOP_HANDLE_WIDTH / 2.0, 0, LOOP_HANDLE_WIDTH, h * 0.5);
        }

        double endX = (loopEnd - scrollOffsetBeats) * pixelsPerBeat;
        if (endX >= -LOOP_HANDLE_WIDTH && endX <= w + LOOP_HANDLE_WIDTH) {
            gc.setStroke(LOOP_HANDLE_LINE_COLOR);
            gc.setLineWidth(1.5);
            gc.strokeLine(endX, 0, endX, h);
            gc.setFill(LOOP_HANDLE_COLOR);
            gc.fillRect(endX - LOOP_HANDLE_WIDTH / 2.0, 0, LOOP_HANDLE_WIDTH, h * 0.5);
        }
    }

    private void drawPunchRegion(GraphicsContext gc, double w, double h) {
        Transport transport = model.getTransport();
        PunchRegion punch = transport.getPunchRegion();
        if (punch == null) {
            return;
        }
        double punchStartBeats = framesToBeats(punch.startFrames());
        double punchEndBeats = framesToBeats(punch.endFrames());
        double x1 = (punchStartBeats - scrollOffsetBeats) * pixelsPerBeat;
        double x2 = (punchEndBeats - scrollOffsetBeats) * pixelsPerBeat;

        double drawX1 = Math.max(0, x1);
        double drawX2 = Math.min(w, x2);
        if (drawX2 > drawX1) {
            gc.setFill(PUNCH_REGION_COLOR);
            gc.fillRect(drawX1, 0, drawX2 - drawX1, h);
        }
    }

    private void drawPunchHandles(GraphicsContext gc, double h) {
        Transport transport = model.getTransport();
        PunchRegion punch = transport.getPunchRegion();
        if (punch == null) {
            return;
        }
        double w = canvas.getWidth();
        double punchStartBeats = framesToBeats(punch.startFrames());
        double punchEndBeats = framesToBeats(punch.endFrames());

        double startX = (punchStartBeats - scrollOffsetBeats) * pixelsPerBeat;
        if (startX >= -PUNCH_HANDLE_WIDTH && startX <= w + PUNCH_HANDLE_WIDTH) {
            gc.setStroke(PUNCH_HANDLE_LINE_COLOR);
            gc.setLineWidth(1.5);
            gc.strokeLine(startX, 0, startX, h);
            gc.setFill(PUNCH_HANDLE_COLOR);
            gc.fillRect(startX - PUNCH_HANDLE_WIDTH / 2.0, h * 0.5, PUNCH_HANDLE_WIDTH, h * 0.5);
            gc.setFont(PUNCH_LABEL_FONT);
            gc.setFill(PUNCH_HANDLE_COLOR);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText("I", startX + 2, h * 0.5 - 2);
        }

        double endX = (punchEndBeats - scrollOffsetBeats) * pixelsPerBeat;
        if (endX >= -PUNCH_HANDLE_WIDTH && endX <= w + PUNCH_HANDLE_WIDTH) {
            gc.setStroke(PUNCH_HANDLE_LINE_COLOR);
            gc.setLineWidth(1.5);
            gc.strokeLine(endX, 0, endX, h);
            gc.setFill(PUNCH_HANDLE_COLOR);
            gc.fillRect(endX - PUNCH_HANDLE_WIDTH / 2.0, h * 0.5, PUNCH_HANDLE_WIDTH, h * 0.5);
            gc.setFont(PUNCH_LABEL_FONT);
            gc.setFill(PUNCH_HANDLE_COLOR);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText("O", endX - 2, h * 0.5 - 2);
        }
    }

    private double framesToBeats(long frames) {
        Transport transport = model.getTransport();
        double seconds = frames / sampleRate;
        return transport.getTempoMap().secondsToBeats(seconds);
    }

    // ── Mouse interaction for loop handles ──────────────────────────────────

    private void handleMousePressed(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }

        Transport transport = model.getTransport();
        double beatPos = pixelToScrolledBeat(event.getX());

        // Shift+click: define a new loop region
        if (event.isShiftDown()) {
            double snapped = snapBeat(beatPos);
            loopDragMode = LoopDragMode.DEFINING_REGION;
            loopDragAnchorBeat = snapped;
            transport.setLoopEnabled(true);
            transport.setLoopRegion(snapped, snapped + 0.001);
            redraw();
            return;
        }

        // Check if click is near a loop handle (when loop is enabled)
        if (transport.isLoopEnabled()) {
            double startX = (transport.getLoopStartInBeats() - scrollOffsetBeats) * pixelsPerBeat;
            double endX = (transport.getLoopEndInBeats() - scrollOffsetBeats) * pixelsPerBeat;

            if (Math.abs(event.getX() - startX) <= LOOP_HANDLE_HIT_ZONE) {
                loopDragMode = LoopDragMode.START_HANDLE;
                return;
            }
            if (Math.abs(event.getX() - endX) <= LOOP_HANDLE_HIT_ZONE) {
                loopDragMode = LoopDragMode.END_HANDLE;
                return;
            }
        }

        // Default: click-to-seek
        loopDragMode = LoopDragMode.NONE;
        setPlayheadPositionBeats(beatPos);
        for (Consumer<Double> listener : seekListeners) {
            listener.accept(beatPos);
        }
    }

    private void handleMouseDragged(MouseEvent event) {
        if (loopDragMode == LoopDragMode.NONE) {
            return;
        }

        Transport transport = model.getTransport();
        double beatPos = snapBeat(Math.max(0.0, pixelToScrolledBeat(event.getX())));

        switch (loopDragMode) {
            case START_HANDLE -> {
                double end = transport.getLoopEndInBeats();
                if (beatPos < end) {
                    transport.setLoopRegion(beatPos, end);
                }
            }
            case END_HANDLE -> {
                double start = transport.getLoopStartInBeats();
                if (beatPos > start) {
                    transport.setLoopRegion(start, beatPos);
                }
            }
            case DEFINING_REGION -> {
                double regionStart = Math.min(loopDragAnchorBeat, beatPos);
                double regionEnd = Math.max(loopDragAnchorBeat, beatPos);
                if (regionEnd > regionStart) {
                    transport.setLoopRegion(regionStart, regionEnd);
                }
            }
            default -> { /* NONE — handled above */ }
        }
        redraw();
    }

    private void handleMouseReleased(MouseEvent event) {
        loopDragMode = LoopDragMode.NONE;
    }

    private void handleMouseMoved(MouseEvent event) {
        Transport transport = model.getTransport();
        if (!transport.isLoopEnabled()) {
            Tooltip.uninstall(canvas, loopTooltip);
            return;
        }

        double startX = (transport.getLoopStartInBeats() - scrollOffsetBeats) * pixelsPerBeat;
        double endX = (transport.getLoopEndInBeats() - scrollOffsetBeats) * pixelsPerBeat;
        double mouseX = event.getX();

        if (Math.abs(mouseX - startX) <= LOOP_HANDLE_HIT_ZONE) {
            String label = model.formatPosition(transport.getLoopStartInBeats());
            loopTooltip.setText("Loop Start: " + label);
            Tooltip.install(canvas, loopTooltip);
        } else if (Math.abs(mouseX - endX) <= LOOP_HANDLE_HIT_ZONE) {
            String label = model.formatPosition(transport.getLoopEndInBeats());
            loopTooltip.setText("Loop End: " + label);
            Tooltip.install(canvas, loopTooltip);
        } else {
            Tooltip.uninstall(canvas, loopTooltip);
        }
    }

    private double pixelToScrolledBeat(double pixelX) {
        return model.pixelToBeats(pixelX + scrollOffsetBeats * pixelsPerBeat, pixelsPerBeat);
    }

    private double snapBeat(double beat) {
        if (snapEnabled) {
            return SnapQuantizer.quantize(beat, gridResolution,
                    model.getTransport().getTimeSignatureNumerator());
        }
        return beat;
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
