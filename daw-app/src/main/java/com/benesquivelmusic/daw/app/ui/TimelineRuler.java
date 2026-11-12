package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.theme.UiScale;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTransportIntentHandler;
import com.benesquivelmusic.daw.core.transport.TimeDisplayMode;
import com.benesquivelmusic.daw.core.transport.TimelineRulerModel;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.util.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed;

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
 * <p>Story 328 (book §5.6): the punch region is drawn the same way, with its
 * handles in the LOWER half of the ruler (loop handles sit in the upper
 * half). Punch handles can be dragged, and a Shift+click-drag that starts in
 * the lower half defines a punch region; one that starts in the upper half
 * defines a loop. Punch frames are PROJECT-rate frames, converted with the
 * rate wired through {@link #setSampleRate(double)}.</p>
 *
 * <p>The loop window and the punch region are saved with the project, so a
 * loop or punch gesture that changed either runs the
 * {@linkplain #setOnTransportEdited edit callback} once, at its release (or,
 * if the release never reached the ruler, at its next button release or
 * primary press; PR #984 review).</p>
 *
 * <p>Click-to-seek: clicking anywhere on the ruler fires the registered
 * seek callback with the corresponding beat position.</p>
 */
@HardcodedColorAllowed("story 277 follow-up: migrate Canvas/inline paints to resolved -token CSS")
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
    /**
     * Story 134 — pre-roll shading band leading into the punch-in (or
     * playhead if no punch is armed). A soft amber tone, distinct from
     * the loop ({@link #LOOP_REGION_COLOR}) and punch
     * ({@link #PUNCH_REGION_COLOR}) bands.
     */
    static final Color PRE_ROLL_REGION_COLOR = Color.web("#ffb74d", 0.18);
    /** Story 134 — post-roll shading band trailing out of the punch-out / playhead. */
    static final Color POST_ROLL_REGION_COLOR = Color.web("#4dd0e1", 0.18);

    // Font sizes and vertical geometry at 100% (story 335: the drawing
    // multiplies each by the UI-scale factor).
    private static final String FONT_FAMILY = "Monospaced";
    private static final double LABEL_FONT_PX = 10.0;
    private static final double TEMPO_FONT_PX = 9.0;
    private static final double PUNCH_LABEL_FONT_PX = 8.0;
    /** Gap between text and the tick it labels, and the text's left margin. */
    private static final double TEXT_GAP = 2.0;
    private static final double TEMPO_TEXT_MARGIN = 4.0;
    private static final double TICK_MAJOR_HEIGHT = 12.0;
    private static final double TICK_MINOR_HEIGHT = 6.0;
    private static final double PLAYHEAD_WIDTH = 2.0;
    private static final double LOOP_HANDLE_WIDTH = 6.0;
    private static final double LOOP_HANDLE_HIT_ZONE = 8.0;
    private static final double PUNCH_HANDLE_WIDTH = 6.0;
    /** Story 328 — horizontal hit tolerance around a drawn punch handle (lower half of the ruler). */
    static final double PUNCH_HANDLE_HIT_ZONE = 8.0;
    /** The widest bar-label stride (bars); far beyond any zoom the model offers. */
    private static final int MAX_BAR_LABEL_STRIDE = 1 << 16;

    /**
     * The backing model — replaced wholesale by {@link #rebind(Transport)} on
     * project switch (story 315 / story 314's epoch rule), never mutated in
     * place: {@link TimelineRulerModel} holds its transport in a final field,
     * so a fresh model per epoch is the only way to retarget, and every
     * gesture/paint path reads {@code model.getTransport()} at call time.
     */
    private TimelineRulerModel model;
    private final Canvas canvas;

    private double pixelsPerBeat = BASE_PIXELS_PER_BEAT;
    private double scrollOffsetBeats = 0.0;
    private double playheadPositionBeats = 0.0;
    private double totalBeats = 0.0;
    private boolean autoScroll = true;

    /**
     * Handle/definition drag state. Loop gestures own the UPPER half of the
     * ruler, punch gestures (story 328, book §5.6) the LOWER half — the halves
     * the respective handles are drawn in.
     */
    private enum DragMode {
        NONE,
        LOOP_START_HANDLE, LOOP_END_HANDLE, DEFINING_LOOP,
        PUNCH_IN_HANDLE, PUNCH_OUT_HANDLE, DEFINING_PUNCH
    }
    private DragMode dragMode = DragMode.NONE;
    private double dragAnchorBeat = 0.0;

    /**
     * What a loop or punch gesture's press found, read before the press wrote
     * anything (PR #984 review): the two of the transport's settings a ruler
     * gesture can change, both saved with the project. The gesture's end
     * compares the transport's values with these to decide, once, whether the
     * project was edited.
     */
    private record GestureStart(Transport.LoopWindow loopWindow, PunchRegion punchRegion) {
    }

    /** The loop or punch gesture in progress, or {@code null} when none is. */
    private GestureStart gestureStart;

    /** Runs once per gesture that changed the loop window or the punch region; see {@link #setOnTransportEdited}. */
    private Runnable onTransportEdited = () -> { };

    /**
     * Story 328 draw-truth seam: the x positions at which the punch handles
     * were last drawn ({@code NaN} when the handle was not drawn). Lets a test
     * assert where the region landed without reading canvas pixels.
     */
    private double lastDrawnPunchInX = Double.NaN;
    private double lastDrawnPunchOutX = Double.NaN;

    // Snap configuration
    private boolean snapEnabled = false;
    private GridResolution gridResolution = GridResolution.QUARTER;

    /** Sample rate used to convert punch region frame positions to beats. */
    private double sampleRate = 44_100.0;

    private final List<Consumer<Double>> seekListeners = new ArrayList<>();

    private final Tooltip loopTooltip = new Tooltip();

    /**
     * Story 335 (studio ruling R6, amendments A15 and A20) — the UI-scale
     * factor the ruler's height, tick heights, text offsets and fonts are
     * multiplied by (the time axis is not: it must line up with the
     * arrangement). Observed only while the ruler is in a scene; the fonts
     * are rebuilt only on a scale change, never per draw.
     */
    private double uiScale = 1.0;
    private Font labelFont;
    private Font tempoFont;
    private Font punchLabelFont;
    /** The bar-label font's character advance (the font is monospaced), measured on each scale change. */
    private double labelAdvance;

    /**
     * The scale observation, held for the ruler's lifetime (review 2b-java
     * B2): while the ruler has no scene its conditional binding is not
     * subscribed to the scale property, so nothing else holds it, and a
     * discarded subscription is collected — the ruler then stops following
     * the scale once it joins its scene.
     */
    private final Subscription uiScaleSubscription;

    /**
     * Draw-truth seam (story 335 review, engineer S2): the bar labels drawn by
     * the last {@link #redraw()}, in drawing order. Reused, never shared.
     */
    private final List<DrawnBarLabel> lastDrawnBarLabels = new ArrayList<>();

    /**
     * A bar label as drawn: its text and the x at which the text starts.
     *
     * @param x    the text's left edge in ruler coordinates
     * @param text the label
     */
    record DrawnBarLabel(double x, String text) {
    }

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
        ThemeManager appearance = ThemeManager.getDefault();
        applyUiScale(appearance.getUiScale());
        uiScaleSubscription = appearance.uiScaleProperty()
                .when(sceneProperty().isNotNull())
                .subscribe((_, now) -> {
                    applyUiScale(now);
                    redraw();
                });

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

    /**
     * Re-derives the scaled height and fonts. The height is fixed (pref = min
     * = max) at {@link #DEFAULT_HEIGHT} times the factor.
     */
    private void applyUiScale(UiScale step) {
        uiScale = step.factor();
        labelFont = Font.font(FONT_FAMILY, LABEL_FONT_PX * uiScale);
        tempoFont = Font.font(FONT_FAMILY, TEMPO_FONT_PX * uiScale);
        punchLabelFont = Font.font(FONT_FAMILY, PUNCH_LABEL_FONT_PX * uiScale);
        Text advanceProbe = new Text("0");
        advanceProbe.setFont(labelFont);
        labelAdvance = advanceProbe.getLayoutBounds().getWidth();
        double height = DEFAULT_HEIGHT * uiScale;
        setPrefHeight(height);
        setMinHeight(height);
        setMaxHeight(height);
    }

    /** Returns the underlying ruler model. */
    public TimelineRulerModel getModel() {
        return model;
    }

    /**
     * Retargets the ruler at a different project's transport (story 315; the
     * story-314 epoch rule). The ruler is built once at startup but the
     * project — and with it the {@link Transport} — is swapped on every
     * File&nbsp;▸&nbsp;Open / New; without this rebind the ruler's loop
     * gestures and tempo display would keep reading and writing the dead
     * project's transport. A fresh {@link TimelineRulerModel} is installed
     * (the core model is immutable with respect to its transport), the
     * user's current {@link TimeDisplayMode} is carried over, and the ruler
     * repaints from the new transport immediately.
     *
     * <p>Idempotent (story 314's epoch-guard style): rebinding to the transport
     * the ruler already targets returns without reallocating the model or
     * forcing a repaint. {@code rebuildViewModels()} runs at startup too — right
     * after the ruler was built against that very transport — so without this
     * fast path every launch threw away a just-built model and repainted an
     * unchanged ruler.</p>
     *
     * <p>A loop or punch gesture in progress ends at a rebind to another
     * transport: the rest of its drag writes nothing, and its release runs no
     * {@linkplain #setOnTransportEdited edit callback}.</p>
     *
     * @param transport the live project's transport; must not be {@code null}
     * @throws NullPointerException if {@code transport} is {@code null}
     */
    public void rebind(Transport transport) {
        Objects.requireNonNull(transport, "transport must not be null");
        if (model.getTransport() == transport) {
            return;
        }
        // A gesture still in progress belongs to the project being replaced: it
        // ends here, so it neither writes the new transport nor marks the new
        // project dirty at its release.
        gestureStart = null;
        dragMode = DragMode.NONE;
        TimelineRulerModel fresh = new TimelineRulerModel(transport);
        fresh.setDisplayMode(model.getDisplayMode());
        this.model = fresh;
        redraw();
    }

    /** Adds a listener that is called when the user clicks on the ruler to seek. */
    public void addSeekListener(Consumer<Double> listener) {
        seekListeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    /**
     * Sets what runs when a loop or punch gesture on this ruler has edited the
     * transport — the PROJECT phase of the gesture (Control Synchronization
     * Design Book §5.1, §5.2): the composition root marks the live project
     * dirty. It runs once, at the gesture's release (or, if the release never
     * reached the ruler, at its next button release or primary press), and
     * only when the transport's loop window or punch region then differs from
     * what the gesture's press found. A click to seek, a handle pressed and
     * released without moving, a drag back to the starting value or a punch
     * Shift-drag that never spans the minimum does not run it; an upper-half
     * Shift+click, which defines a loop on the press itself, does whenever
     * that loop differs from the one before. It never runs for a gesture that
     * a {@link #rebind(Transport)} interrupted. The ruler outlives projects,
     * so the callback should look the project up when it runs rather than
     * capture one. FX thread. The default does nothing; {@code () -> { }}
     * restores it.
     *
     * @param onTransportEdited the callback; must not be {@code null}
     * @throws NullPointerException if {@code onTransportEdited} is {@code null}
     */
    public void setOnTransportEdited(Runnable onTransportEdited) {
        this.onTransportEdited = Objects.requireNonNull(onTransportEdited, "onTransportEdited must not be null");
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
        drawPreRollRegion(gc, w, h);
        drawPostRollRegion(gc, w, h);
        drawPunchRegion(gc, w, h);
        drawTempoAndTimeSignature(gc, h);
        drawSubdivisions(gc, w, h);
        drawLoopHandles(gc, h);
        drawPunchHandles(gc, h);
        drawPlayhead(gc, h);
    }

    /**
     * @param h the ruler height
     * @return the baseline of the bar labels: above the major tick, by the text gap
     */
    double barLabelBaseline(double h) {
        return h - (TICK_MAJOR_HEIGHT + TEXT_GAP) * uiScale;
    }

    /**
     * @param h the ruler height
     * @return the baseline of the tempo / time-signature text, one margin above the bottom
     */
    double tempoBaseline(double h) {
        return h - TEMPO_TEXT_MARGIN * uiScale;
    }

    /** @return the tempo text font at the current UI scale */
    Font tempoFont() {
        return tempoFont;
    }

    /** @return the bar-label font at the current UI scale */
    Font labelFont() {
        return labelFont;
    }

    // ── private rendering methods ───────────────────────────────────────────

    private void drawTempoAndTimeSignature(GraphicsContext gc, double h) {
        Transport transport = model.getTransport();
        String tempoText = String.format("%.1f BPM  %d/%d",
                transport.getTempo(),
                transport.getTimeSignatureNumerator(),
                transport.getTimeSignatureDenominator());
        gc.setFont(tempoFont);
        gc.setFill(TEMPO_TEXT_COLOR);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(tempoText, TEMPO_TEXT_MARGIN * uiScale, tempoBaseline(h));
    }

    private void drawSubdivisions(GraphicsContext gc, double w, double h) {
        double subdivision = model.subdivisionForZoom(pixelsPerBeat);
        int beatsPerBar = model.getTransport().getTimeSignatureNumerator();

        double startBeat = Math.floor(scrollOffsetBeats / subdivision) * subdivision;
        double endBeat = scrollOffsetBeats + w / pixelsPerBeat;

        gc.setFont(labelFont);
        gc.setTextAlign(TextAlignment.LEFT);
        double majorTick = TICK_MAJOR_HEIGHT * uiScale;
        double minorTick = TICK_MINOR_HEIGHT * uiScale;
        double gap = TEXT_GAP * uiScale;
        int labelStride = barLabelStride(subdivision, beatsPerBar, Math.max(0, startBeat), endBeat, gap);
        lastDrawnBarLabels.clear();

        for (double beat = startBeat; beat <= endBeat; beat += subdivision) {
            if (beat < 0) {
                continue;
            }
            double x = (beat - scrollOffsetBeats) * pixelsPerBeat;

            boolean isBarLine = isBarBoundary(beat, beatsPerBar);
            if (isBarLine) {
                gc.setStroke(BAR_LINE_COLOR);
                gc.setLineWidth(1.0);
                gc.strokeLine(x, h - majorTick, x, h);

                if (Math.round(beat / beatsPerBar) % labelStride == 0) {
                    gc.setFill(RULER_TEXT_COLOR);
                    String label = model.formatPosition(beat);
                    gc.fillText(label, x + gap, barLabelBaseline(h));
                    lastDrawnBarLabels.add(new DrawnBarLabel(x + gap, label));
                }
            } else {
                gc.setStroke(BEAT_LINE_COLOR);
                gc.setLineWidth(0.5);
                gc.strokeLine(x, h - minorTick, x, h);
            }
        }
    }

    /**
     * Story 335 review (engineer S2) — how many bars apart the bar labels are
     * drawn: 1, or the smallest power of two, counted from bar 1, at which
     * consecutive labels no longer run into one another. The time axis keeps
     * its 100&nbsp;% pixels (it lines up with the arrangement) while the label
     * font follows the UI scale, so at a large scale a zoom that labels every
     * bar at 100&nbsp;% would draw the numbers over each other. Ticks are
     * unaffected. The font is monospaced, so a label is its length times the
     * font's advance wide; the widest visible label is the first or the last.
     *
     * @param subdivision the beats between drawn marks
     * @param beatsPerBar the time signature's numerator
     * @param firstBeat   the first visible beat (not negative)
     * @param lastBeat    the last visible beat
     * @param gap         the scaled gap before a label
     * @return the label stride in bars, a power of two
     */
    private int barLabelStride(double subdivision, int beatsPerBar, double firstBeat, double lastBeat, double gap) {
        double barWidth = beatsPerBar * pixelsPerBeat;
        int stride = (int) Math.max(1, Math.round(subdivision / beatsPerBar));
        if (!(barWidth > 0) || beatsPerBar <= 0) {
            return stride;
        }
        double firstBar = Math.floor(firstBeat / beatsPerBar) * beatsPerBar;
        double lastBar = Math.floor(Math.max(firstBeat, lastBeat) / beatsPerBar) * beatsPerBar;
        int longest = Math.max(model.formatPosition(firstBar).length(), model.formatPosition(lastBar).length());
        double needed = longest * labelAdvance + gap;
        while (stride * barWidth < needed && stride < MAX_BAR_LABEL_STRIDE) {
            stride *= 2;
        }
        return stride;
    }

    /**
     * @return the bar labels the last {@link #redraw()} drew, in drawing order
     *         (draw-truth seam: lets a test check the labels without reading pixels)
     */
    List<DrawnBarLabel> lastDrawnBarLabels() {
        return List.copyOf(lastDrawnBarLabels);
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
        lastDrawnPunchInX = Double.NaN;
        lastDrawnPunchOutX = Double.NaN;
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
            lastDrawnPunchInX = startX;
            gc.setStroke(PUNCH_HANDLE_LINE_COLOR);
            gc.setLineWidth(1.5);
            gc.strokeLine(startX, 0, startX, h);
            gc.setFill(PUNCH_HANDLE_COLOR);
            gc.fillRect(startX - PUNCH_HANDLE_WIDTH / 2.0, h * 0.5, PUNCH_HANDLE_WIDTH, h * 0.5);
            gc.setFont(punchLabelFont);
            gc.setFill(PUNCH_HANDLE_COLOR);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText("I", startX + TEXT_GAP * uiScale, h * 0.5 - TEXT_GAP * uiScale);
        }

        double endX = (punchEndBeats - scrollOffsetBeats) * pixelsPerBeat;
        if (endX >= -PUNCH_HANDLE_WIDTH && endX <= w + PUNCH_HANDLE_WIDTH) {
            lastDrawnPunchOutX = endX;
            gc.setStroke(PUNCH_HANDLE_LINE_COLOR);
            gc.setLineWidth(1.5);
            gc.strokeLine(endX, 0, endX, h);
            gc.setFill(PUNCH_HANDLE_COLOR);
            gc.fillRect(endX - PUNCH_HANDLE_WIDTH / 2.0, h * 0.5, PUNCH_HANDLE_WIDTH, h * 0.5);
            gc.setFont(punchLabelFont);
            gc.setFill(PUNCH_HANDLE_COLOR);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText("O", endX - TEXT_GAP * uiScale, h * 0.5 - TEXT_GAP * uiScale);
        }
    }

    private double framesToBeats(long frames) {
        Transport transport = model.getTransport();
        double seconds = frames / sampleRate;
        return transport.getTempoMap().secondsToBeats(seconds);
    }

    /** Inverse of {@link #framesToBeats(long)}: a beat position as a non-negative project-rate frame. */
    private long beatsToFrames(double beats) {
        Transport transport = model.getTransport();
        double seconds = transport.getTempoMap().beatsToSeconds(Math.max(0.0, beats));
        return Math.max(0L, Math.round(seconds * sampleRate));
    }

    /**
     * Story 328 draw-truth seam — the x at which the punch-in handle was last
     * drawn, or {@code NaN} when no handle was drawn. Package-private for
     * {@code TimelineRulerPunchGestureTest}.
     */
    double lastDrawnPunchInX() {
        return lastDrawnPunchInX;
    }

    /** The x at which the punch-out handle was last drawn, or {@code NaN}; see {@link #lastDrawnPunchInX()}. */
    double lastDrawnPunchOutX() {
        return lastDrawnPunchOutX;
    }

    /**
     * Story 134 — draws the soft-shaded band representing the pre-roll
     * range leading into the punch-in (when armed) or the playhead. The
     * band spans {@code preBars × beatsPerBar} beats and is clamped to
     * the visible viewport. No-op when pre-roll is disabled or
     * {@code preBars == 0}.
     */
    private void drawPreRollRegion(GraphicsContext gc, double w, double h) {
        double[] bounds = computePreRollBounds(w);
        if (bounds == null) {
            return;
        }
        gc.setFill(PRE_ROLL_REGION_COLOR);
        gc.fillRect(bounds[0], 0, bounds[1] - bounds[0], h);
    }

    /**
     * Story 134 — draws the soft-shaded band representing the post-roll
     * range trailing out of the punch-out (when armed) or the playhead.
     * No-op when post-roll is disabled or {@code postBars == 0}.
     */
    private void drawPostRollRegion(GraphicsContext gc, double w, double h) {
        double[] bounds = computePostRollBounds(w);
        if (bounds == null) {
            return;
        }
        gc.setFill(POST_ROLL_REGION_COLOR);
        gc.fillRect(bounds[0], 0, bounds[1] - bounds[0], h);
    }

    /**
     * Computes the on-screen pixel bounds {@code [x1, x2]} of the
     * pre-roll shading band, clamped to {@code [0, w]}. Returns
     * {@code null} when pre-roll is disabled, the bar count is zero,
     * or the band is fully off-screen.
     *
     * <p>Package-private for testing — see {@code TimelineRulerTest}.</p>
     */
    double[] computePreRollBounds(double w) {
        Transport transport = model.getTransport();
        PreRollPostRoll prpr = transport.getPreRollPostRoll();
        if (prpr == null || !prpr.enabled() || prpr.preBars() <= 0) {
            return null;
        }
        double anchor = preRollPostRollAnchor();
        double preBeats = prpr.preBars() * (double) transport.getTimeSignatureNumerator();
        double startBeat = Math.max(0.0, anchor - preBeats);
        double endBeat = anchor;
        if (endBeat <= startBeat) {
            return null;
        }
        double x1 = (startBeat - scrollOffsetBeats) * pixelsPerBeat;
        double x2 = (endBeat - scrollOffsetBeats) * pixelsPerBeat;
        double drawX1 = Math.max(0, x1);
        double drawX2 = Math.min(w, x2);
        if (drawX2 <= drawX1) {
            return null;
        }
        return new double[]{drawX1, drawX2};
    }

    /**
     * Computes the on-screen pixel bounds {@code [x1, x2]} of the
     * post-roll shading band, clamped to {@code [0, w]}. Returns
     * {@code null} when post-roll is disabled, the bar count is zero,
     * or the band is fully off-screen.
     *
     * <p>Package-private for testing — see {@code TimelineRulerTest}.</p>
     */
    double[] computePostRollBounds(double w) {
        Transport transport = model.getTransport();
        PreRollPostRoll prpr = transport.getPreRollPostRoll();
        if (prpr == null || !prpr.enabled() || prpr.postBars() <= 0) {
            return null;
        }
        double anchor = postRollAnchor();
        double postBeats = prpr.postBars() * (double) transport.getTimeSignatureNumerator();
        double startBeat = anchor;
        double endBeat = anchor + postBeats;
        double x1 = (startBeat - scrollOffsetBeats) * pixelsPerBeat;
        double x2 = (endBeat - scrollOffsetBeats) * pixelsPerBeat;
        double drawX1 = Math.max(0, x1);
        double drawX2 = Math.min(w, x2);
        if (drawX2 <= drawX1) {
            return null;
        }
        return new double[]{drawX1, drawX2};
    }

    /**
     * Anchor for the pre-roll band: the punch-in beat when a punch
     * region is armed (independent of its enabled flag — the user has
     * defined boundaries), otherwise the current playhead position.
     */
    private double preRollPostRollAnchor() {
        Transport transport = model.getTransport();
        PunchRegion punch = transport.getPunchRegion();
        if (punch != null) {
            return framesToBeats(punch.startFrames());
        }
        return playheadPositionBeats;
    }

    /**
     * Anchor for the post-roll band: the punch-out beat when a punch
     * region is armed, otherwise the current playhead position.
     */
    private double postRollAnchor() {
        Transport transport = model.getTransport();
        PunchRegion punch = transport.getPunchRegion();
        if (punch != null) {
            return framesToBeats(punch.endFrames());
        }
        return playheadPositionBeats;
    }

    // ── Mouse interaction: loop handles (upper half) and punch handles (lower half) ──

    /**
     * Story 328 (book §5.6): the ruler is split by height. Loop handles are
     * drawn in the upper half, punch handles in the lower half, and a
     * Shift-drag defines a loop when it starts in the upper half and a punch
     * region when it starts in the lower half. A pointer exactly on the
     * midline belongs to the upper (loop) half — the loop gestures behave
     * there exactly as before this story.
     */
    private boolean inPunchHalf(MouseEvent event) {
        return event.getY() > canvas.getHeight() * 0.5;
    }

    private void handleMousePressed(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        // A gesture whose release never reached this ruler is decided now,
        // before this press can begin another.
        finishGesture();

        Transport transport = model.getTransport();
        double beatPos = pixelToScrolledBeat(event.getX());
        boolean punchHalf = inPunchHalf(event);

        if (event.isShiftDown()) {
            double snapped = snapBeat(beatPos);
            dragAnchorBeat = snapped;
            // Before the upper half's loop write below: a Shift+click that
            // defines a loop without dragging has edited the transport.
            beginGesture(transport);
            if (punchHalf) {
                // Shift+click in the lower half: anchor a punch definition.
                // Nothing is written until the drag spans the minimum punch
                // length — a short drag leaves the transport unchanged.
                dragMode = DragMode.DEFINING_PUNCH;
                return;
            }
            // Shift+click in the upper half: define a new loop region — flag
            // and bounds published as one window so the RT clock never sees
            // enabled + stale bounds (story 315 review).
            dragMode = DragMode.DEFINING_LOOP;
            transport.setLoopWindow(true, snapped, snapped + 0.001);
            redraw();
            return;
        }

        // Handle hit-testing follows the drawn split: the half the pointer is
        // in decides which family is tested first where the two overlap.
        DragMode hit = punchHalf
                ? firstHit(hitPunchHandle(event.getX()), hitLoopHandle(event.getX()))
                : firstHit(hitLoopHandle(event.getX()), hitPunchHandle(event.getX()));
        if (hit != DragMode.NONE) {
            beginGesture(transport);
            dragMode = hit;
            return;
        }

        // Default: click-to-seek — a playhead move, not an edit of the project.
        dragMode = DragMode.NONE;
        setPlayheadPositionBeats(beatPos);
        for (Consumer<Double> listener : seekListeners) {
            listener.accept(beatPos);
        }
    }

    private static DragMode firstHit(DragMode preferred, DragMode fallback) {
        return preferred != DragMode.NONE ? preferred : fallback;
    }

    /** Remembers what a loop or punch gesture's press found; called before the press writes anything. */
    private void beginGesture(Transport transport) {
        gestureStart = new GestureStart(transport.getLoopWindow(), transport.getPunchRegion());
    }

    /**
     * Ends the gesture in progress, if any: runs {@link #onTransportEdited}
     * once when the transport's loop window or punch region differs from the
     * press's. Values are compared, not pointer travel — a drag back to where
     * it started edited nothing. The transport compared is the one the press
     * read: {@link #rebind(Transport)}, the only place the model is replaced,
     * ends the gesture first.
     */
    private void finishGesture() {
        GestureStart start = gestureStart;
        gestureStart = null;
        if (start == null) {
            return;
        }
        Transport transport = model.getTransport();
        if (!Objects.equals(transport.getLoopWindow(), start.loopWindow())
                || !Objects.equals(transport.getPunchRegion(), start.punchRegion())) {
            onTransportEdited.run();
        }
    }

    private DragMode hitLoopHandle(double mouseX) {
        Transport transport = model.getTransport();
        if (!transport.isLoopEnabled()) {
            return DragMode.NONE;
        }
        double startX = (transport.getLoopStartInBeats() - scrollOffsetBeats) * pixelsPerBeat;
        double endX = (transport.getLoopEndInBeats() - scrollOffsetBeats) * pixelsPerBeat;
        if (Math.abs(mouseX - startX) <= LOOP_HANDLE_HIT_ZONE) {
            return DragMode.LOOP_START_HANDLE;
        }
        if (Math.abs(mouseX - endX) <= LOOP_HANDLE_HIT_ZONE) {
            return DragMode.LOOP_END_HANDLE;
        }
        return DragMode.NONE;
    }

    private DragMode hitPunchHandle(double mouseX) {
        PunchRegion punch = model.getTransport().getPunchRegion();
        if (punch == null) {
            return DragMode.NONE;
        }
        double inX = (framesToBeats(punch.startFrames()) - scrollOffsetBeats) * pixelsPerBeat;
        double outX = (framesToBeats(punch.endFrames()) - scrollOffsetBeats) * pixelsPerBeat;
        if (Math.abs(mouseX - inX) <= PUNCH_HANDLE_HIT_ZONE) {
            return DragMode.PUNCH_IN_HANDLE;
        }
        if (Math.abs(mouseX - outX) <= PUNCH_HANDLE_HIT_ZONE) {
            return DragMode.PUNCH_OUT_HANDLE;
        }
        return DragMode.NONE;
    }

    private void handleMouseDragged(MouseEvent event) {
        if (dragMode == DragMode.NONE) {
            return;
        }

        Transport transport = model.getTransport();
        double beatPos = snapBeat(Math.max(0.0, pixelToScrolledBeat(event.getX())));

        switch (dragMode) {
            case LOOP_START_HANDLE -> {
                double end = transport.getLoopEndInBeats();
                if (beatPos < end) {
                    transport.setLoopRegion(beatPos, end);
                }
            }
            case LOOP_END_HANDLE -> {
                double start = transport.getLoopStartInBeats();
                if (beatPos > start) {
                    transport.setLoopRegion(start, beatPos);
                }
            }
            case DEFINING_LOOP -> {
                double regionStart = Math.min(dragAnchorBeat, beatPos);
                double regionEnd = Math.max(dragAnchorBeat, beatPos);
                if (regionEnd > regionStart) {
                    transport.setLoopRegion(regionStart, regionEnd);
                }
            }
            case PUNCH_IN_HANDLE -> {
                PunchRegion punch = transport.getPunchRegion();
                if (punch != null) {
                    long in = beatsToFrames(beatPos);
                    if (punch.endFrames() - in >= minPunchFrames()) {
                        transport.setPunchRegion(new PunchRegion(in, punch.endFrames(), punch.enabled()));
                    }
                }
            }
            case PUNCH_OUT_HANDLE -> {
                PunchRegion punch = transport.getPunchRegion();
                if (punch != null) {
                    long out = beatsToFrames(beatPos);
                    if (out - punch.startFrames() >= minPunchFrames()) {
                        transport.setPunchRegion(new PunchRegion(punch.startFrames(), out, punch.enabled()));
                    }
                }
            }
            case DEFINING_PUNCH -> {
                long in = beatsToFrames(Math.min(dragAnchorBeat, beatPos));
                long out = beatsToFrames(Math.max(dragAnchorBeat, beatPos));
                if (out - in >= minPunchFrames()) {
                    // A gesture-defined region is ENABLED, as the Shift-drag
                    // loop is — the user drew it to record into it.
                    transport.setPunchRegion(PunchRegion.enabled(in, out));
                }
            }
            default -> { /* NONE — handled above */ }
        }
        redraw();
    }

    /**
     * The shortest punch region a ruler gesture may install at this ruler's
     * (project) sample rate — the single definition shared with the command
     * cascade, so a dragged region and a shortcut-set region obey one rule.
     */
    private long minPunchFrames() {
        return CoreTransportIntentHandler.minPunchFrames(sampleRate);
    }

    /**
     * Any button's release ends the drag, as it always has; JavaFX delivers
     * the release to the node that received the press even when the pointer
     * has left it. The gesture's PROJECT decision is made here, once.
     */
    private void handleMouseReleased(MouseEvent event) {
        dragMode = DragMode.NONE;
        finishGesture();
    }

    private void handleMouseMoved(MouseEvent event) {
        Transport transport = model.getTransport();
        double mouseX = event.getX();
        boolean punchHalf = inPunchHalf(event);

        DragMode hover = punchHalf
                ? firstHit(hitPunchHandle(mouseX), hitLoopHandle(mouseX))
                : firstHit(hitLoopHandle(mouseX), hitPunchHandle(mouseX));
        String text = switch (hover) {
            case LOOP_START_HANDLE -> "Loop Start: " + model.formatPosition(transport.getLoopStartInBeats());
            case LOOP_END_HANDLE -> "Loop End: " + model.formatPosition(transport.getLoopEndInBeats());
            case PUNCH_IN_HANDLE -> "Punch In: " + punchHandleLabel(transport, true);
            case PUNCH_OUT_HANDLE -> "Punch Out: " + punchHandleLabel(transport, false);
            default -> null;
        };
        if (text == null) {
            Tooltip.uninstall(canvas, loopTooltip);
            return;
        }
        loopTooltip.setText(text + "\nDrag to move. Shift-drag: upper half defines a loop, lower half a punch region.");
        Tooltip.install(canvas, loopTooltip);
    }

    private String punchHandleLabel(Transport transport, boolean inPoint) {
        PunchRegion punch = transport.getPunchRegion();
        if (punch == null) {
            return "";
        }
        long frames = inPoint ? punch.startFrames() : punch.endFrames();
        return model.formatPosition(framesToBeats(frames)) + (punch.enabled() ? "" : " (punch off)");
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
