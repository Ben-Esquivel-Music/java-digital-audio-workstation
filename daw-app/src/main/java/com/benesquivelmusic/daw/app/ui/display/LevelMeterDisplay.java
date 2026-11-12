package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.theme.UiScale;
import com.benesquivelmusic.daw.fx.GpuCanvas;
import com.benesquivelmusic.daw.fx.GpuRenderContext;
import com.benesquivelmusic.daw.sdk.visualization.LevelData;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.util.Subscription;
import com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed;

/**
 * Animated peak/RMS level meter display with professional ballistics.
 *
 * <p>Renders a vertical or horizontal level meter with gradient bar
 * (green → yellow → red), peak-hold indicator, RMS bar, clip indicator,
 * and dB scale markings.
 *
 * <p>The clip indicator is a <em>latch</em> (story 318): one clipping
 * {@link #update(LevelData) update} lights it and it stays lit across any
 * number of quiet updates until {@link #resetClip()} — or a primary-button
 * click on the clip bar itself (the {@value #CLIP_BAR_PX}&nbsp;px strip at
 * the top of a vertical meter / right edge of a horizontal one; clicking the
 * meter body is a no-op so a stray click cannot wipe clip state) — clears
 * it. This mirrors {@link InputMeterStrip}'s latch-and-click-to-reset so the
 * two meter families behave identically.</p>
 *
 * <p>This display composes a {@link GpuCanvas} from the {@code daw-fx}
 * module: the GpuCanvas owns the size binding, per-frame
 * {@link javafx.animation.AnimationTimer}, scene-attachment gating, and
 * background clear, so the display itself only contributes the per-frame
 * draw routine. The {@link MeterAnimator} ballistics are advanced from
 * {@link GpuRenderContext#deltaSeconds()} on each frame.
 *
 * <p>Supports the metering requirements from the mastering-techniques
 * research (§4 — Dynamics Processing, §8 — Loudness Standards).</p>
 */
@HardcodedColorAllowed("story 277 follow-up: migrate Canvas/inline paints to resolved -token CSS")
public final class LevelMeterDisplay extends GpuCanvasView {

    private static final Color BACKGROUND = Color.web("#0d0d1a");
    private static final Color CLIP_COLOR = Color.web("#ff1744");
    private static final Color PEAK_INDICATOR_COLOR = Color.web("#ffffff");
    private static final Color SCALE_COLOR = Color.web("#ffffff", 0.3);

    private static final double MIN_DB = -60.0;
    private static final double MAX_DB = 6.0;

    /** Thickness of the clip bar — the render size AND the click-to-reset hit zone. */
    static final double CLIP_BAR_PX = 4.0;

    /**
     * Delta applied to the ballistics by a one-off render ({@code GpuCanvas}
     * hands {@code deltaSeconds == 0.0} to renders it did not drive from its
     * timer). One nominal 60 fps step keeps a detached / one-shot display
     * moving the way it always has; the timer-driven frames use the real
     * delta.
     */
    private static final long ONE_SHOT_FRAME_NANOS = 16_666_667L;

    /** Cross-axis inset of the bars (px at 100%). */
    private static final double BAR_INSET_PX = 2.0;
    /** Length of a dB scale tick (px at 100%). */
    private static final double TICK_LENGTH_PX = 3.0;
    /** dB scale font size (px at 100%). */
    private static final double SCALE_FONT_PX = 8.0;

    private final MeterAnimator rmsAnimator;
    private final MeterAnimator peakAnimator;
    private final boolean vertical;

    private double pendingRmsDb = -120.0;
    private double pendingPeakDb = -120.0;
    /** The clip latch: set by a clipping update, cleared only by {@link #resetClip()}. */
    private boolean clipLatched;

    /**
     * Story 335 (studio ruling R6, amendments A15 and A20) — the UI-scale
     * factor the bar geometry, the clip bar (render and hit zone) and the
     * scale font are multiplied by; stroke widths are not. Observed only
     * while the display is in a scene; cancelled by {@link #dispose()}.
     */
    private double uiScale = 1.0;
    /** The scale font at the current UI scale, rebuilt only on a scale change (never per frame). */
    private Font scaleFont;
    private final Subscription uiScaleSubscription;

    /**
     * Creates a level meter display.
     *
     * @param vertical {@code true} for vertical orientation, {@code false} for horizontal
     */
    public LevelMeterDisplay(boolean vertical) {
        super(BACKGROUND, true);
        this.vertical = vertical;
        rmsAnimator = new MeterAnimator(0.01, 0.15, 0);
        peakAnimator = new MeterAnimator(0.001, 0.5, 1.5);

        ThemeManager appearance = ThemeManager.getDefault();
        applyUiScale(appearance.getUiScale());
        uiScaleSubscription = appearance.uiScaleProperty()
                .when(sceneProperty().isNotNull())
                .subscribe((_, now) -> {
                    applyUiScale(now);
                    requestRender();
                });

        setRenderer(this::renderFrame);

        setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            // Only the clip bar resets — the InputMeterStrip hit-zone rule.
            if (!isInClipZone(event.getX(), event.getY())) {
                return;
            }
            resetClip();
        });
    }

    private void applyUiScale(UiScale step) {
        uiScale = step.factor();
        scaleFont = Font.font(SCALE_FONT_PX * uiScale);
    }

    /** @return the clip bar's thickness at the current UI scale (render size and hit zone) */
    double clipBarPx() {
        return CLIP_BAR_PX * uiScale;
    }

    /** @return the dB-scale tick length at the current UI scale (the length the draw code strokes) */
    double tickLengthPx() {
        return TICK_LENGTH_PX * uiScale;
    }

    private double inset() {
        return BAR_INSET_PX * uiScale;
    }

    /**
     * Stops the render loop, releases the pixel surface and the UI-scale
     * subscription. Safe to call multiple times; FX thread.
     */
    @Override
    public void dispose() {
        uiScaleSubscription.unsubscribe();
        super.dispose();
    }

    /**
     * Creates a vertical level meter display.
     */
    public LevelMeterDisplay() {
        this(true);
    }

    /**
     * Updates the meter with a new level snapshot.
     *
     * <p>Stores the snapshot for the next render frame; the GpuCanvas-driven
     * per-frame loop advances the {@link MeterAnimator} ballistics using
     * {@link GpuRenderContext#deltaSeconds()}.
     *
     * @param data the current level data
     */
    public void update(LevelData data) {
        if (data == null) return;
        update(data.peakDb(), data.rmsDb(), data.clipping());
    }

    /**
     * The allocation-free ingest: the same update as
     * {@link #update(LevelData)} without the caller having to build a
     * {@link LevelData} record. The tap-bus sinks run on every FX pulse for
     * every visible meter, so they use this form
     * ({@code javafx-application-design} §6 — no per-frame allocation).
     *
     * @param peakDb   peak level in dBFS ({@code -Infinity} for silence)
     * @param rmsDb    RMS level in dBFS ({@code -Infinity} for silence)
     * @param clipping {@code true} to light (and latch) the clip indicator
     */
    public void update(double peakDb, double rmsDb, boolean clipping) {
        if (isDisposed()) return;
        pendingRmsDb = rmsDb;
        pendingPeakDb = peakDb;
        if (clipping) {
            clipLatched = true;
        }
        // When the animation timer is running (scene-attached), the next
        // timer frame will pick up the new snapshot — no extra render needed.
        // When the timer is gated off (e.g. one-shot updates from tests),
        // request an immediate render so the value is visible.
        requestRender();
    }

    /**
     * Clears the clip latch and repaints immediately (without waiting for the
     * next timer frame). The click-to-reset gesture on the clip bar calls
     * this; a host may also call it from a "clear all clips" action.
     */
    public void resetClip() {
        clipLatched = false;
        if (!isDisposed()) {
            gpuCanvas().requestRender();
        }
    }

    /** {@code true} while the clip latch is lit. */
    public boolean isClipLatched() {
        return clipLatched;
    }

    /**
     * The peak level, in dBFS, of the most recent {@link #update(LevelData)} —
     * the raw ingest value, <em>before</em> the {@link MeterAnimator}
     * ballistics the next frame applies. Stays at the −120 dB floor until the
     * first update, so it is also the honest answer for a display that has no
     * feed. Story 318 exposes it so a host (or a test) can tell "this meter is
     * receiving frames" from "this meter is dark" without reaching into the
     * render loop.
     *
     * @return the last submitted peak dBFS
     */
    public double getPendingPeakDb() {
        return pendingPeakDb;
    }

    /**
     * The RMS level, in dBFS, of the most recent {@link #update(LevelData)}.
     * See {@link #getPendingPeakDb()}.
     *
     * @return the last submitted RMS dBFS
     */
    public double getPendingRmsDb() {
        return pendingRmsDb;
    }

    /**
     * The click-to-reset hit test: the clip bar only, in either orientation.
     * Both axes are bounded, because the bar is painted inset by 2 px on its
     * cross axis ({@code fillRect(inset, 0, w - 2 * inset, clipBar)} vertically,
     * {@code fillRect(w - clipBar, inset, clipBar, h - 2 * inset)} horizontally;
     * both lengths at 100&nbsp;% times the UI scale, story 335) — the hit
     * zone is the painted LED, nothing more, so a click just past its end
     * cannot wipe clip state.
     */
    boolean isInClipZone(double x, double y) {
        return vertical
                ? y >= 0 && y <= clipBarPx() && x >= inset() && x <= getWidth() - inset()
                : x <= getWidth() && x >= getWidth() - clipBarPx()
                        && y >= inset() && y <= getHeight() - inset();
    }

    /**
     * Returns the embedded {@link GpuCanvas} that owns the per-frame render
     * loop and off-heap pixel surface. Visible for tests.
     */
    GpuCanvas getGpuCanvas() {
        return gpuCanvas();
    }

    /** Returns the RMS ballistic animator. Visible for tests. */
    MeterAnimator getRmsAnimator() {
        return rmsAnimator;
    }

    /** Returns the peak ballistic animator. Visible for tests. */
    MeterAnimator getPeakAnimator() {
        return peakAnimator;
    }

    private void renderFrame(GpuRenderContext ctx) {
        // Advance ballistics from the host's per-frame delta. The MeterAnimator
        // API operates on deltaNanos, so convert from deltaSeconds; a one-off
        // render (delta 0) takes one nominal frame step.
        long deltaNanos = ctx.deltaSeconds() > 0.0
                ? (long) (ctx.deltaSeconds() * 1_000_000_000.0)
                : ONE_SHOT_FRAME_NANOS;
        double rmsNorm = dbToNormalized(pendingRmsDb);
        double peakNorm = dbToNormalized(pendingPeakDb);
        rmsAnimator.update(rmsNorm, deltaNanos);
        peakAnimator.update(peakNorm, deltaNanos);

        renderInto(ctx.gc(), ctx.width(), ctx.height());
    }

    /**
     * Renders the meter into the supplied graphics context. Background fill
     * is provided by {@link GpuCanvas#setClearColor(Color)} so we do not
     * issue a redundant background {@code fillRect} here.
     */
    private void renderInto(GraphicsContext gc, double w, double h) {
        if (w <= 0 || h <= 0) return;

        double rmsLevel = rmsAnimator.getCurrentValue();
        double peakLevel = peakAnimator.getCurrentValue();
        double peakHold = peakAnimator.getPeakValue();

        LinearGradient meterGradient;
        if (vertical) {
            meterGradient = new LinearGradient(0, 1, 0, 0, true, CycleMethod.NO_CYCLE,
                    new Stop(0.0, Color.web("#00e676")),
                    new Stop(0.6, Color.web("#00e676")),
                    new Stop(0.8, Color.web("#ffea00")),
                    new Stop(0.95, Color.web("#ff9100")),
                    new Stop(1.0, CLIP_COLOR)
            );
        } else {
            meterGradient = new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                    new Stop(0.0, Color.web("#00e676")),
                    new Stop(0.6, Color.web("#00e676")),
                    new Stop(0.8, Color.web("#ffea00")),
                    new Stop(0.95, Color.web("#ff9100")),
                    new Stop(1.0, CLIP_COLOR)
            );
        }

        double inset = inset();
        double clipBar = clipBarPx();
        if (vertical) {
            // RMS bar
            double rmsHeight = rmsLevel * h;
            gc.setFill(meterGradient);
            gc.setGlobalAlpha(0.5);
            gc.fillRect(inset, h - rmsHeight, w - 2 * inset, rmsHeight);

            // Peak bar
            double peakHeight = peakLevel * h;
            gc.setGlobalAlpha(1.0);
            gc.fillRect(inset, h - peakHeight, w - 2 * inset, peakHeight);

            // Peak hold indicator
            if (peakHold > 0.001) {
                double holdY = h - peakHold * h;
                gc.setStroke(PEAK_INDICATOR_COLOR);
                gc.setLineWidth(2.0);
                gc.strokeLine(inset, holdY, w - inset, holdY);
            }

            // Clip indicator (latched)
            if (clipLatched) {
                gc.setFill(CLIP_COLOR);
                gc.fillRect(inset, 0, w - 2 * inset, clipBar);
            }

            // dB scale
            gc.setGlobalAlpha(1.0);
            gc.setStroke(SCALE_COLOR);
            gc.setLineWidth(0.5);
            gc.setFill(SCALE_COLOR);
            gc.setFont(scaleFont);
            double tick = tickLengthPx();
            for (double db = MIN_DB; db <= MAX_DB; db += 6) {
                double y = h - dbToNormalized(db) * h;
                gc.strokeLine(0, y, tick, y);
            }
        } else {
            // Horizontal orientation
            double rmsWidth = rmsLevel * w;
            gc.setFill(meterGradient);
            gc.setGlobalAlpha(0.5);
            gc.fillRect(0, inset, rmsWidth, h - 2 * inset);

            double peakWidth = peakLevel * w;
            gc.setGlobalAlpha(1.0);
            gc.fillRect(0, inset, peakWidth, h - 2 * inset);

            if (peakHold > 0.001) {
                double holdX = peakHold * w;
                gc.setStroke(PEAK_INDICATOR_COLOR);
                gc.setLineWidth(2.0);
                gc.strokeLine(holdX, inset, holdX, h - inset);
            }

            if (clipLatched) {
                gc.setFill(CLIP_COLOR);
                gc.fillRect(w - clipBar, inset, clipBar, h - 2 * inset);
            }

            gc.setGlobalAlpha(1.0);
        }
    }

    /**
     * Converts a dB value to a normalized [0, 1] range for display.
     */
    static double dbToNormalized(double db) {
        if (db <= MIN_DB) return 0.0;
        if (db >= MAX_DB) return 1.0;
        return (db - MIN_DB) / (MAX_DB - MIN_DB);
    }

}
