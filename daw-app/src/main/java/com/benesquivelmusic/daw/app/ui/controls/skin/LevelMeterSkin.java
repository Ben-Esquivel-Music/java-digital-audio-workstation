package com.benesquivelmusic.daw.app.ui.controls.skin;

import com.benesquivelmusic.daw.app.ui.controls.LevelMeter;

import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.SkinBase;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Skin for {@link LevelMeter} — renders discrete LED-style segments onto a
 * plain {@link Canvas}.
 *
 * <p>The draw routine is pure: given the same control property values and
 * geometry it produces the same pixels, so resize / theme / value changes
 * are trivial full redraws ({@link GraphicsContext#clearRect} + re-issue
 * draws — never a new Canvas/Image per frame). No glow, no shadow, no
 * {@link javafx.scene.effect.Effect} (UI Design Book §5.7 / §7.10).
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The per-frame {@link AnimationTimer} runs only while the control is
 * attached to a {@link Scene} <em>and</em> {@link LevelMeter#isAnimated()}
 * is {@code true}. When not animated, repaints happen on a value-change
 * {@link ChangeListener} instead (the audio-relay is still pumped on that
 * path). {@link #dispose()} stops the timer and removes every listener.
 *
 * <h2>Peak-hold</h2>
 *
 * <p>Peak-hold is 2 s, tracked via {@link System#nanoTime()}. The decay
 * logic is factored into {@link #currentPeakHoldDb(long)} /
 * {@link #tick(long)} so tests can drive it deterministically with
 * synthetic timestamps (no real sleeps).
 */
public final class LevelMeterSkin extends SkinBase<LevelMeter> {

    /** Peak-hold duration in nanoseconds (2 s). */
    static final long PEAK_HOLD_NANOS = 2_000_000_000L;

    /** dBFS range mapped onto the segment column. */
    private static final double MIN_DB = -60.0;
    private static final double MAX_DB = 0.0;

    /** Segment colour-region thresholds (dBFS). */
    private static final double LOW_MAX_DB = -18.0;
    private static final double MID_MAX_DB = -6.0;

    /** RMS overlay reuses the region colour at this brightness (0..1). */
    private static final double RMS_BRIGHTNESS = 0.55;
    /** Tick-label gutter: at most this many px, capped to this fraction. */
    private static final double TICK_GUTTER_MAX_PX = 24.0;
    private static final double TICK_GUTTER_FRACTION = 0.3;
    /** Per-channel column inset: this fraction of the column, capped (px). */
    private static final double COLUMN_INSET_FRACTION = 0.1;
    private static final double COLUMN_INSET_MAX_PX = 1.0;
    /** Tick label spacing (dB) and font size (px). */
    private static final double TICK_STEP_DB = 12.0;
    private static final double TICK_FONT_PX = 8.0;

    private final Canvas canvas;

    // ── Listeners (held so dispose() can remove exactly what was added) ───
    private final ChangeListener<Scene> sceneListener;
    private final ChangeListener<Object> repaintListener;
    private final ChangeListener<Object> relayAndRepaintListener;
    private final ChangeListener<Boolean> animatedListener;

    private final AnimationTimer timer;

    private boolean timerRunning;
    private boolean disposed;
    private long frameCount;
    private int registeredListenerCount;

    /**
     * Monotonic time source. Defaults to {@link System#nanoTime()}; tests
     * install a synthetic clock so the timer-/listener-driven peak-hold
     * paths are deterministic (the parameterized {@link #tick(long)} /
     * {@link #currentPeakHoldDb(long)} seams remain directly callable too).
     */
    private LongSupplier clock = System::nanoTime;

    // Peak-hold state (FX thread only).
    private double peakHoldDb = -120.0;
    private long peakHoldStampNanos = Long.MIN_VALUE;

    /**
     * @param control the {@link LevelMeter} this skin renders
     */
    public LevelMeterSkin(LevelMeter control) {
        super(control);

        canvas = new Canvas();
        // Bind canvas size to the control's size. Region#resize alone does
        // NOT drive layoutChildren outside a live Scene, so binding is the
        // robust pattern (vs. resizing only in layoutChildren).
        canvas.widthProperty().bind(control.widthProperty());
        canvas.heightProperty().bind(control.heightProperty());
        getChildren().add(canvas);

        // Double-click clears peak-hold (the only in-scope interaction).
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                clearPeakHold();
            }
        });

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                pumpOnce(clock.getAsLong());
            }
        };

        // Repaint-only: geometry / colour / orientation / channel changes.
        repaintListener = (obs, o, n) -> {
            if (!disposed) {
                paint();
            }
        };
        // Relay-and-repaint: used on the value path when NOT animated, so a
        // submitLevels() call still becomes visible without the timer.
        relayAndRepaintListener = (obs, o, n) -> {
            if (disposed) {
                return;
            }
            tick(clock.getAsLong());
            paint();
        };

        LevelMeter c = control;
        addRepaint(c.orientationProperty());
        addRepaint(c.channelCountProperty());
        addRepaint(c.meterLowProperty());
        addRepaint(c.meterMidProperty());
        addRepaint(c.meterHiProperty());
        addRepaint(c.meterClipProperty());
        addRepaint(c.meterBackgroundProperty());
        addRepaint(c.meterSegmentGapProperty());
        addRepaint(c.meterSegmentHeightProperty());
        addRepaint(c.meterTickMarksProperty());
        // Value properties: track peak-hold and (when not animated) repaint.
        addRelay(c.peakDbProperty());
        addRelay(c.rmsDbProperty());
        addRepaint(c.peakHoldDbProperty());

        animatedListener = (obs, was, now) -> updateTimerState();
        c.animatedProperty().addListener(animatedListener);
        registeredListenerCount++;

        sceneListener = (obs, oldScene, newScene) -> updateTimerState();
        c.sceneProperty().addListener(sceneListener);
        registeredListenerCount++;

        // Handle the case where the control is already attached at skin
        // construction time.
        updateTimerState();
    }

    private void addRepaint(ObservableValue<?> v) {
        @SuppressWarnings("unchecked")
        ChangeListener<Object> l = (ChangeListener<Object>) repaintListener;
        v.addListener(l);
        registeredListenerCount++;
    }

    private void addRelay(ObservableValue<?> v) {
        @SuppressWarnings("unchecked")
        ChangeListener<Object> l = (ChangeListener<Object>) relayAndRepaintListener;
        v.addListener(l);
        registeredListenerCount++;
    }

    private void removeRepaint(ObservableValue<?> v) {
        @SuppressWarnings("unchecked")
        ChangeListener<Object> l = (ChangeListener<Object>) repaintListener;
        v.removeListener(l);
    }

    private void removeRelay(ObservableValue<?> v) {
        @SuppressWarnings("unchecked")
        ChangeListener<Object> l = (ChangeListener<Object>) relayAndRepaintListener;
        v.removeListener(l);
    }

    // ── Timer / lifecycle ─────────────────────────────────────────────────

    private void updateTimerState() {
        if (disposed) {
            return;
        }
        LevelMeter c = getSkinnable();
        boolean shouldRun = c.getScene() != null && c.isAnimated();
        if (shouldRun && !timerRunning) {
            timer.start();
            timerRunning = true;
        } else if (!shouldRun && timerRunning) {
            timer.stop();
            timerRunning = false;
        }
        // Repaint once on any state transition so a value submitted while
        // not animated (or after attach) is reflected immediately.
        paint();
    }

    /**
     * One full frame: relay the audio atomics into the properties, advance
     * peak-hold to {@code nowNanos}, and repaint. This is exactly what the
     * {@link AnimationTimer#handle(long)} body runs; tests drive it with
     * synthetic timestamps to avoid depending on JavaFX pulse scheduling.
     *
     * @param nowNanos the reference timestamp (nanoseconds)
     */
    public void pumpOnce(long nowNanos) {
        if (disposed) {
            return;
        }
        frameCount++;
        relayFromAtomics();
        tick(nowNanos);
        paint();
    }

    private void relayFromAtomics() {
        LevelMeter c = getSkinnable();
        // Only bridge audio→FX once audio has actually submitted; before
        // that a value set directly via setPeakDb() is authoritative (the
        // direct setters are a first-class path until the first feed).
        if (!c.hasSubmission()) {
            return;
        }
        // A per-channel-only feed leaves the aggregate slot unsubmitted
        // (NaN); don't poison the aggregate property with the sentinel.
        double peak = c.consumeSubmittedPeakDb();
        if (!Double.isNaN(peak)) {
            c.setPeakDb(peak);
        }
        double rms = c.consumeSubmittedRmsDb();
        if (!Double.isNaN(rms)) {
            c.setRmsDb(rms);
        }
    }

    /** @return whether the per-frame timer is currently running (test seam). */
    public boolean isTimerRunning() {
        return timerRunning;
    }

    /** @return cumulative {@code handle()} invocations (test seam). */
    public long frameCount() {
        return frameCount;
    }

    /** @return whether {@link #dispose()} has run (test seam). */
    public boolean isDisposed() {
        return disposed;
    }

    /** @return the number of still-registered listeners (test seam). */
    public int registeredListenerCount() {
        return registeredListenerCount;
    }

    /** @return the backing canvas (test seam). */
    public Canvas canvas() {
        return canvas;
    }

    /**
     * Installs a synthetic monotonic clock so the timer-/listener-driven
     * peak-hold paths are deterministic in tests (test seam).
     *
     * @param clock the replacement time source (nanoseconds)
     */
    public void setClock(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ── Peak-hold (deterministic, timestamp-driven) ───────────────────────

    /**
     * Advances the peak-hold model to {@code nowNanos}: captures a new hold
     * when the current peak rises above the held value, and expires the
     * hold after {@link #PEAK_HOLD_NANOS}. Pure w.r.t. its timestamp
     * argument so tests can drive it with synthetic values.
     *
     * @param nowNanos the reference timestamp (nanoseconds)
     */
    public void tick(long nowNanos) {
        LevelMeter c = getSkinnable();
        double peak = c.getPeakDb();
        if (peak > peakHoldDb || peakHoldStampNanos == Long.MIN_VALUE) {
            peakHoldDb = peak;
            peakHoldStampNanos = nowNanos;
        } else if (nowNanos - peakHoldStampNanos >= PEAK_HOLD_NANOS) {
            peakHoldDb = peak;
            peakHoldStampNanos = nowNanos;
        }
        c.setPeakHoldDb(currentPeakHoldDb(nowNanos));
    }

    /**
     * @param nowNanos the reference timestamp (nanoseconds)
     * @return the peak-hold dBFS that should be displayed at
     *         {@code nowNanos}: the held peak if still within the 2 s
     *         window, otherwise the live peak.
     */
    public double currentPeakHoldDb(long nowNanos) {
        if (peakHoldStampNanos == Long.MIN_VALUE) {
            return getSkinnable().getPeakDb();
        }
        if (nowNanos - peakHoldStampNanos >= PEAK_HOLD_NANOS) {
            return getSkinnable().getPeakDb();
        }
        return peakHoldDb;
    }

    private void clearPeakHold() {
        peakHoldDb = getSkinnable().getPeakDb();
        peakHoldStampNanos = Long.MIN_VALUE;
        getSkinnable().setPeakHoldDb(peakHoldDb);
        paint();
    }

    // ── Geometry ──────────────────────────────────────────────────────────

    private boolean isVertical() {
        return getSkinnable().getOrientation() == Orientation.VERTICAL;
    }

    private double longAxisSize(double w, double h) {
        return isVertical() ? h : w;
    }

    /**
     * @param w available width
     * @param h available height
     * @return the number of discrete segments along the long axis given
     *         the configured segment height and gap (never negative).
     */
    public int segmentCount(double w, double h) {
        double seg = Math.max(1.0, getSkinnable().getMeterSegmentHeight());
        double gap = Math.max(0.0, getSkinnable().getMeterSegmentGap());
        double axis = longAxisSize(w, h);
        if (axis <= 0) {
            return 0;
        }
        int n = (int) Math.floor((axis + gap) / (seg + gap));
        return Math.max(0, n);
    }

    /**
     * @param db a dBFS value
     * @return the normalised position in {@code [0, 1]} of {@code db} along
     *         the meter ({@code 0} = floor, {@code 1} = full scale).
     */
    static double normalize(double db) {
        if (db <= MIN_DB) {
            return 0.0;
        }
        if (db >= MAX_DB) {
            return 1.0;
        }
        return (db - MIN_DB) / (MAX_DB - MIN_DB);
    }

    /**
     * The single source of truth for the lit-bar cutoff. Every render path
     * (peak bar, RMS overlay, peak-hold tick) and both test seams
     * ({@link #topLitSegmentIndex} / {@link #colorAt}) go through this, so
     * the rendered pixels and the asserted model cannot diverge.
     *
     * @param normalized a level in {@code [0, 1]} (see {@link #normalize})
     * @param n          the total segment count
     * @return the index of the topmost lit segment, or {@code -1} if none
     */
    static int litTopIndex(double normalized, int n) {
        if (n <= 0) {
            return -1;
        }
        int lit = (int) Math.floor(normalized * n);
        return Math.max(-1, Math.min(n, lit) - 1);
    }

    /**
     * @param segment a zero-based segment index
     * @param n       the total segment count
     * @return the region colour ({@code -meter-low/-mid/-hi/-clip}) a lit
     *         segment at this position renders with, derived by mapping the
     *         segment back to its dBFS band.
     */
    private Color regionColorForSegment(int segment, int n) {
        double frac = (segment + 1.0) / n;
        return regionColor(MIN_DB + frac * (MAX_DB - MIN_DB));
    }

    /**
     * @param channel the channel index
     * @param w        available width
     * @param h        available height
     * @return the index of the topmost lit segment for {@code channel}
     *         given the live peak, or {@code -1} if none are lit.
     */
    public int topLitSegmentIndex(int channel, double w, double h) {
        return litTopIndex(normalize(peakForChannel(channel)), segmentCount(w, h));
    }

    private double peakForChannel(int channel) {
        LevelMeter c = getSkinnable();
        double perCh = c.consumeSubmittedPeakDb(channel);
        // NaN == "no per-channel feed" → fall back to the aggregate peak.
        // An exact NaN test (not a magnitude band) so a legitimately low
        // level near the noise floor is never misread as "no feed".
        return Double.isNaN(perCh) ? c.getPeakDb() : perCh;
    }

    /**
     * @param channel the channel index
     * @param segment the zero-based segment index (0 = floor)
     * @param w       available width
     * @param h       available height
     * @return the colour the given segment should render with for the
     *         current peak value (a test seam exercising the draw model).
     */
    public Color colorAt(int channel, int segment, double w, double h) {
        int n = segmentCount(w, h);
        if (n <= 0 || segment > topLitSegmentIndex(channel, w, h)) {
            return getSkinnable().getMeterBackground();
        }
        return regionColorForSegment(segment, n);
    }

    private Color regionColor(double db) {
        LevelMeter c = getSkinnable();
        if (db >= MAX_DB) {
            return c.getMeterClip();
        }
        if (db >= MID_MAX_DB) {
            return c.getMeterHi();
        }
        if (db >= LOW_MAX_DB) {
            return c.getMeterMid();
        }
        return c.getMeterLow();
    }

    private static Color darker(Color c) {
        // Darker secondary indicator for RMS — no Effect, just a tint.
        return c.deriveColor(0, 1.0, RMS_BRIGHTNESS, 1.0);
    }

    // ── Painting ──────────────────────────────────────────────────────────

    @Override
    protected void layoutChildren(double x, double y, double w, double h) {
        // Canvas size is bound to the control; just (re)paint with the
        // current geometry. All offsets are derived from w/h — never
        // hard-coded for the default size.
        paint();
    }

    private void paint() {
        if (disposed) {
            return;
        }
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        LevelMeter c = getSkinnable();
        gc.setFill(c.getMeterBackground());
        gc.fillRect(0, 0, w, h);

        int channels = Math.max(1, c.getChannelCount());
        boolean vertical = isVertical();
        double seg = Math.max(1.0, c.getMeterSegmentHeight());
        double gap = Math.max(0.0, c.getMeterSegmentGap());
        int n = segmentCount(w, h);
        if (n <= 0) {
            return;
        }

        boolean ticks = c.isMeterTickMarks();
        double tickArea = ticks
                ? Math.min(TICK_GUTTER_MAX_PX, (vertical ? w : h) * TICK_GUTTER_FRACTION)
                : 0.0;

        // Per-channel column geometry on the short axis.
        double shortAxis = (vertical ? w : h) - tickArea;
        double colSpan = shortAxis / channels;
        double colInset = Math.min(COLUMN_INSET_MAX_PX, colSpan * COLUMN_INSET_FRACTION);

        // Peak-hold is a single (non per-channel) value; resolve its tick
        // index once, with the SAME cutoff math as the lit bar so it sits
        // exactly atop the (decayed) peak rather than one LED low.
        int holdTop = litTopIndex(normalize(c.getPeakHoldDb()), n);

        for (int ch = 0; ch < channels; ch++) {
            double colStart = tickArea + ch * colSpan + colInset;
            double colSize = colSpan - 2 * colInset;
            // Same seam the tests assert against — one source of truth.
            int peakTop = topLitSegmentIndex(ch, w, h);
            int rmsTop = litTopIndex(normalize(rmsForChannel(ch)), n);

            for (int s = 0; s < n; s++) {
                Color color;
                if (s <= peakTop) {
                    color = regionColorForSegment(s, n);
                    if (s <= rmsTop) {
                        // RMS overlays the lower region as a darker tint.
                        color = darker(color);
                    }
                } else if (s == holdTop) {
                    color = regionColorForSegment(s, n);
                } else {
                    // Unlit — already painted as background; skip the draw
                    // to keep the canvas cheap.
                    continue;
                }

                double along = s * (seg + gap);
                double sx;
                double sy;
                double sw;
                double sh;
                if (vertical) {
                    // Segment 0 at the bottom.
                    sx = colStart;
                    sy = h - along - seg;
                    sw = colSize;
                    sh = seg;
                } else {
                    sx = along;
                    sy = colStart;
                    sw = seg;
                    sh = colSize;
                }
                gc.setFill(color);
                gc.fillRect(sx, sy, sw, sh);
            }
        }

        if (ticks) {
            paintTicks(gc, w, h, vertical, tickArea);
        }
    }

    private double rmsForChannel(int channel) {
        LevelMeter c = getSkinnable();
        double perCh = c.consumeSubmittedRmsDb(channel);
        return Double.isNaN(perCh) ? c.getRmsDb() : perCh;
    }

    private void paintTicks(GraphicsContext gc, double w, double h,
            boolean vertical, double tickArea) {
        gc.setFill(getSkinnable().getMeterLow().deriveColor(0, 1.0, 1.0, 0.6));
        gc.setFont(Font.font(TICK_FONT_PX));
        gc.setTextAlign(TextAlignment.LEFT);
        for (double db = MIN_DB; db <= MAX_DB; db += TICK_STEP_DB) {
            double pos = normalize(db);
            String label = String.format(Locale.ROOT, "%.0f", db);
            if (vertical) {
                double ty = h - pos * h;
                gc.fillText(label, 1, Math.min(h - 1, Math.max(TICK_FONT_PX, ty)));
            } else {
                double tx = pos * w;
                gc.fillText(label, Math.min(w - 12, tx), h - 1);
            }
        }
    }

    // ── Sizing ────────────────────────────────────────────────────────────

    private SizeVariant activeVariant() {
        var classes = getSkinnable().getStyleClass();
        if (classes.contains("size-performance")) {
            return SizeVariant.PERFORMANCE;
        }
        if (classes.contains("size-master")) {
            return SizeVariant.MASTER;
        }
        if (classes.contains("size-channel")) {
            return SizeVariant.CHANNEL;
        }
        return SizeVariant.INLINE;
    }

    private enum SizeVariant {
        INLINE(4, 16, 2, 8),
        CHANNEL(8, 64, 4, 16),
        MASTER(12, 36, 6, 18),
        PERFORMANCE(24, 320, 12, 64);

        final double prefW;
        final double prefH;
        final double minW;
        final double minH;

        SizeVariant(double prefW, double prefH, double minW, double minH) {
            this.prefW = prefW;
            this.prefH = prefH;
            this.minW = minW;
            this.minH = minH;
        }
    }

    @Override
    protected double computePrefWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        SizeVariant v = activeVariant();
        return isVertical() ? v.prefW : v.prefH;
    }

    @Override
    protected double computePrefHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        SizeVariant v = activeVariant();
        return isVertical() ? v.prefH : v.prefW;
    }

    @Override
    protected double computeMinWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        SizeVariant v = activeVariant();
        return isVertical() ? v.minW : v.minH;
    }

    @Override
    protected double computeMinHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        SizeVariant v = activeVariant();
        return isVertical() ? v.minH : v.minW;
    }

    @Override
    protected double computeMaxWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        // The long axis is unbounded so a parent can stretch the meter.
        return isVertical()
                ? computePrefWidth(height, topInset, rightInset, bottomInset, leftInset)
                : Double.MAX_VALUE;
    }

    @Override
    protected double computeMaxHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return isVertical()
                ? Double.MAX_VALUE
                : computePrefHeight(width, topInset, rightInset, bottomInset, leftInset);
    }

    // ── Dispose ───────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;

        timer.stop();
        timerRunning = false;

        LevelMeter c = getSkinnable();
        if (c != null) {
            c.sceneProperty().removeListener(sceneListener);
            c.animatedProperty().removeListener(animatedListener);
            removeRepaint(c.orientationProperty());
            removeRepaint(c.channelCountProperty());
            removeRepaint(c.meterLowProperty());
            removeRepaint(c.meterMidProperty());
            removeRepaint(c.meterHiProperty());
            removeRepaint(c.meterClipProperty());
            removeRepaint(c.meterBackgroundProperty());
            removeRepaint(c.meterSegmentGapProperty());
            removeRepaint(c.meterSegmentHeightProperty());
            removeRepaint(c.meterTickMarksProperty());
            removeRelay(c.peakDbProperty());
            removeRelay(c.rmsDbProperty());
            removeRepaint(c.peakHoldDbProperty());

            canvas.widthProperty().unbind();
            canvas.heightProperty().unbind();
        }
        registeredListenerCount = 0;

        super.dispose();
    }
}
