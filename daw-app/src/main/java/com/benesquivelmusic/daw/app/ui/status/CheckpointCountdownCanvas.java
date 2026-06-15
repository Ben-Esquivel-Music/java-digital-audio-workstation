package com.benesquivelmusic.daw.app.ui.status;

import com.benesquivelmusic.daw.app.ui.marshal.FxAnimationTimerAllowed;
import com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed;

import javafx.animation.AnimationTimer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.CssMetaData;
import javafx.css.StyleConverter;
import javafx.css.Styleable;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.geometry.VPos;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The "next checkpoint" countdown cell of the Session Status Strip — a
 * plain {@link Canvas} that draws "Next checkpoint in M:SS" plus a
 * horizontal progress bar showing the fraction of the autosave interval
 * still remaining (Project Manager Design Book §4.4 / §6, story 295).
 *
 * <p>The draw routine is pure: given the same target instant, interval and
 * geometry it produces the same pixels, so resize / theme / value changes
 * are trivial full redraws ({@link GraphicsContext#clearRect} + re-issue
 * draws — never a new Canvas/Image per frame). No glow, no shadow, no
 * {@link javafx.scene.effect.Effect}.
 *
 * <h2>Lifecycle &amp; Reduce Motion (story 279)</h2>
 *
 * <p>When Reduce Motion is <strong>off</strong>, a 1&nbsp;Hz
 * {@link AnimationTimer} owned by this view drives the countdown so the
 * remaining time ticks down once per second
 * ({@code javafx-application-design} §6: a control owns its own timer; §11:
 * all draws happen on the FX thread). The timer runs <em>iff</em> the view
 * is attached to a {@link Scene} <em>and</em> Reduce Motion is off; on
 * scene-detach or when Reduce Motion is switched on, the timer is stopped
 * so a hidden / reduce-motion cell never spins a frame loop.
 *
 * <p>When Reduce Motion is <strong>on</strong>, <em>no</em>
 * {@link AnimationTimer} runs at all: the cell renders the remaining time
 * as static text that is repainted only when the target instant, interval
 * or a colour token actually changes — there is no per-frame animation.
 *
 * <h2>Tokenized colours</h2>
 *
 * <p>The three paints are forwarded {@code -ccd-*} CSS tokens with
 * Palette-A hex fallbacks, mirroring {@code MixerChannelStrip}: styles.css
 * declares e.g. {@code .checkpoint-countdown { -ccd-text: -text; }} so the
 * role tokens forward without a circular looked-up-colour drop. The Java
 * draw code reads the resolved {@link StyleableObjectProperty} values, never
 * a hex mirror.
 *
 * @see FxAnimationTimerAllowed
 * @see HardcodedColorAllowed
 */
@FxAnimationTimerAllowed(
    "1 Hz checkpoint-countdown redraw owned by this view; gated on scene "
    + "attachment and Reduce Motion (story 279); not a cross-thread marshalling "
    + "seam — story 289 sentinel.")
@HardcodedColorAllowed(
    "story 277 follow-up: Canvas paints use forwarded -ccd-* tokens with hex "
    + "Palette-A fallbacks, mirroring MixerChannelStrip.")
public final class CheckpointCountdownCanvas extends Region {

    /** Stable style class — selectable as {@code .checkpoint-countdown}. */
    public static final String DEFAULT_STYLE_CLASS = "checkpoint-countdown";

    /** Default autosave interval (denominator for the bar fraction). */
    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);

    /** Throttle the {@link AnimationTimer} to exactly 1 Hz. */
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;

    /** One hour in seconds — the switch point to the {@code H:MM:SS} format. */
    private static final long SECONDS_PER_HOUR = 3600L;
    private static final long SECONDS_PER_MINUTE = 60L;

    // ── Preferred / min sizing (px), respecting insets. ───────────────────
    private static final double PREF_WIDTH = 180.0;
    private static final double PREF_HEIGHT = 28.0;
    private static final double MIN_WIDTH = 120.0;

    // ── Drawing constants (pure geometry, no effects). ────────────────────
    private static final double FONT_PX = 11.0;
    private static final double BAR_HEIGHT = 4.0;
    private static final double BAR_GAP = 4.0;

    // ── Palette A fallback colours (Project Manager Design Book §4.4). ─────
    private static final Color FALLBACK_TEXT = Color.web("#B7BCC7");
    private static final Color FALLBACK_TRACK = Color.web("#272A33");
    private static final Color FALLBACK_FILL = Color.web("#7C8CFF");

    private final Canvas canvas = new Canvas();

    private long lastTickNanos = Long.MIN_VALUE;
    private boolean running;
    private int timerStartCount;

    /**
     * The 1&nbsp;Hz redraw loop. A private named inner class (mirroring
     * {@code LevelMeterSkin}) so the class-level
     * {@link FxAnimationTimerAllowed} sentinel covers it. {@code handle()}
     * runs on the FX thread and only redraws — it is never a cross-thread
     * hop.
     */
    private final class CountdownTimer extends AnimationTimer {
        @Override
        public void handle(long now) {
            if (shouldRedraw(lastTickNanos, now)) {
                lastTickNanos = now;
                draw();
            }
        }
    }

    private CountdownTimer timer;

    // ── Observable model properties ───────────────────────────────────────

    /** The moment the next checkpoint fires; {@code null} = none scheduled. */
    private final ObjectProperty<Instant> nextCheckpointInstant =
            new SimpleObjectProperty<>(this, "nextCheckpointInstant", null) {
                @Override
                protected void invalidated() {
                    requestRedraw();
                }
            };

    /** The autosave interval (denominator for the bar fraction). */
    private final ObjectProperty<Duration> interval =
            new SimpleObjectProperty<>(this, "interval", DEFAULT_INTERVAL) {
                @Override
                protected void invalidated() {
                    requestRedraw();
                }
            };

    /** When {@code true}: no {@link AnimationTimer}, static text only (story 279). */
    private final BooleanProperty reduceMotion =
            new SimpleBooleanProperty(this, "reduceMotion", false) {
                @Override
                protected void invalidated() {
                    updateTimerState();
                    requestRedraw();
                }
            };

    /**
     * Creates an empty countdown cell: no checkpoint scheduled, a default
     * {@code 5 min} autosave interval, Reduce Motion off.
     */
    public CheckpointCountdownCanvas() {
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        getChildren().add(canvas);
        // Recompute the timer lifecycle whenever the scene attachment
        // changes — the timer must run only while attached and not in
        // Reduce Motion (mirrors LevelMeterSkin's scene-gating).
        javafx.beans.value.ChangeListener<Scene> sceneListener =
                (obs, oldScene, newScene) -> updateTimerState();
        sceneProperty().addListener(sceneListener);
        // Handle the case where the view is already attached at construction.
        updateTimerState();
    }

    @Override
    public String getUserAgentStylesheet() {
        return Objects.requireNonNull(
                CheckpointCountdownCanvas.class.getResource("checkpoint-countdown.css"),
                "checkpoint-countdown.css not on classpath").toExternalForm();
    }

    // ── nextCheckpointInstant ─────────────────────────────────────────────

    /** @return the next-checkpoint-instant property ({@code null} = none scheduled). */
    public final ObjectProperty<Instant> nextCheckpointInstantProperty() {
        return nextCheckpointInstant;
    }

    /** @return the instant the next checkpoint fires, or {@code null}. */
    public final Instant getNextCheckpointInstant() {
        return nextCheckpointInstant.get();
    }

    /** @param instant the next-checkpoint instant; {@code null} = none scheduled. */
    public final void setNextCheckpointInstant(Instant instant) {
        nextCheckpointInstant.set(instant);
    }

    // ── interval ──────────────────────────────────────────────────────────

    /** @return the autosave-interval property (bar-fraction denominator). */
    public final ObjectProperty<Duration> intervalProperty() {
        return interval;
    }

    /** @return the autosave interval. */
    public final Duration getInterval() {
        return interval.get();
    }

    /** @param interval the autosave interval (bar-fraction denominator). */
    public final void setInterval(Duration interval) {
        this.interval.set(interval);
    }

    // ── reduceMotion ──────────────────────────────────────────────────────

    /** @return the Reduce Motion property; when {@code true} the timer never runs. */
    public final BooleanProperty reduceMotionProperty() {
        return reduceMotion;
    }

    /** @return whether Reduce Motion is on (story 279). */
    public final boolean isReduceMotion() {
        return reduceMotion.get();
    }

    /** @param reduce whether Reduce Motion is on (story 279). */
    public final void setReduceMotion(boolean reduce) {
        reduceMotion.set(reduce);
    }

    // ── Diagnostics (test seams) ──────────────────────────────────────────

    /** @return {@code true} iff the {@link AnimationTimer} is currently started. */
    public boolean isCountdownTimerRunning() {
        return running && timer != null;
    }

    /** @return how many times the timer has been (re)started (test seam). */
    public int timerStartCount() {
        return timerStartCount;
    }

    // ── Timer lifecycle (scene-gated, mirrors LevelMeterSkin) ─────────────

    private void updateTimerState() {
        // The timer runs only while attached to a scene AND not in Reduce
        // Motion. In Reduce Motion the cell is static text repainted on
        // change, so no frame loop is started (story 279).
        boolean shouldRun = getScene() != null && !isReduceMotion();
        if (shouldRun && !running) {
            if (timer == null) {
                timer = new CountdownTimer();
            }
            lastTickNanos = Long.MIN_VALUE;
            timer.start();
            running = true;
            timerStartCount++;
        } else if (!shouldRun && running) {
            if (timer != null) {
                timer.stop();
            }
            timer = null;
            running = false;
        }
    }

    /**
     * Repaints immediately. In Reduce Motion this is the only repaint path
     * (no timer); otherwise the timer covers per-second redraws but a model
     * / colour change still repaints at once so the change is visible
     * without waiting up to a second for the next tick.
     */
    private void requestRedraw() {
        draw();
    }

    // ── Layout / sizing ───────────────────────────────────────────────────

    @Override
    protected void layoutChildren() {
        // Size and position the canvas within the content area so CSS
        // padding/insets are respected and content never overpaints them
        // (mirrors how LevelMeterSkin sizes its canvas to the content box).
        double x = snappedLeftInset();
        double y = snappedTopInset();
        double w = Math.max(0, getWidth() - x - snappedRightInset());
        double h = Math.max(0, getHeight() - y - snappedBottomInset());
        canvas.relocate(x, y);
        canvas.setWidth(w);
        canvas.setHeight(h);
        draw();
    }

    @Override
    protected double computePrefWidth(double height) {
        return PREF_WIDTH + snappedLeftInset() + snappedRightInset();
    }

    @Override
    protected double computePrefHeight(double width) {
        return PREF_HEIGHT + snappedTopInset() + snappedBottomInset();
    }

    @Override
    protected double computeMinWidth(double height) {
        return MIN_WIDTH + snappedLeftInset() + snappedRightInset();
    }

    @Override
    protected double computeMinHeight(double width) {
        return PREF_HEIGHT + snappedTopInset() + snappedBottomInset();
    }

    @Override
    protected double computeMaxWidth(double height) {
        return Double.MAX_VALUE;
    }

    @Override
    protected double computeMaxHeight(double width) {
        return Double.MAX_VALUE;
    }

    // ── Painting (pure: same inputs → same pixels, no effects) ────────────

    private void draw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        Instant next = getNextCheckpointInstant();
        Instant now = Instant.now();
        Duration remaining = (next == null) ? null : Duration.between(now, next);

        // Text row: left-aligned, vertically centred above the bar.
        double barReserve = (remaining != null) ? (BAR_HEIGHT + BAR_GAP) : 0.0;
        double textHeight = Math.max(0, h - barReserve);
        gc.setFont(Font.font(FONT_PX));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFill(getTextColor());
        gc.fillText(countdownText(remaining), 0, textHeight / 2.0);

        if (remaining == null) {
            return;
        }

        // Progress track + remaining-fraction fill, beneath the text. In
        // Reduce Motion the bar is still drawn (statically): it only
        // repaints on model/colour change, never per frame.
        double barY = Math.max(0, h - BAR_HEIGHT);
        gc.setFill(getTrackColor());
        gc.fillRect(0, barY, w, BAR_HEIGHT);

        double fraction = barFraction(now, next, getInterval());
        double fillWidth = fraction * w;
        if (fillWidth > 0) {
            gc.setFill(getFillColor());
            gc.fillRect(0, barY, fillWidth, BAR_HEIGHT);
        }
    }

    // ── Pure static transforms (toolkit-free, unit-testable) ──────────────

    /**
     * Whether the 1&nbsp;Hz redraw loop should repaint at frame timestamp
     * {@code now} given the timestamp of the last redraw, {@code lastTickNanos}
     * ({@link Long#MIN_VALUE} = "no redraw since the last (re)start", so the
     * first tick must always repaint). Pure and toolkit-free so the throttle —
     * and specifically the first-tick sentinel — is unit-testable without the
     * FX thread.
     *
     * <p>The first-tick case is an explicit {@code == Long.MIN_VALUE} test,
     * <strong>not</strong> {@code now - lastTickNanos >= ONE_SECOND_NANOS}: the
     * latter overflows when {@code lastTickNanos == Long.MIN_VALUE} and
     * {@code now} is positive (as {@code AnimationTimer}'s
     * {@code System.nanoTime()}-based timestamp is on Windows) — the subtraction
     * wraps to a negative delta, so the first (and therefore every subsequent)
     * tick failed the gate and the countdown never advanced.</p>
     *
     * @param lastTickNanos timestamp of the last redraw, or {@link Long#MIN_VALUE}
     *                      if none since the last (re)start
     * @param now           the current frame timestamp in nanoseconds
     * @return {@code true} if a redraw should fire at {@code now}
     */
    public static boolean shouldRedraw(long lastTickNanos, long now) {
        return lastTickNanos == Long.MIN_VALUE
                || now - lastTickNanos >= ONE_SECOND_NANOS;
    }

    /**
     * Formats a remaining duration as {@code "M:SS"} (or {@code "H:MM:SS"}
     * when at least one hour). Negative, zero or {@code null} durations
     * clamp to {@code "0:00"}.
     *
     * @param remaining the remaining duration (may be {@code null})
     * @return the formatted countdown, never {@code null}
     */
    public static String formatRemaining(Duration remaining) {
        if (remaining == null || remaining.isZero() || remaining.isNegative()) {
            return "0:00";
        }
        long totalSeconds = remaining.getSeconds();
        if (totalSeconds <= 0) {
            return "0:00";
        }
        long hours = totalSeconds / SECONDS_PER_HOUR;
        long minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        long seconds = totalSeconds % SECONDS_PER_MINUTE;
        if (hours >= 1) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    /**
     * Builds the cell's caption.
     *
     * @param remaining the remaining duration, or {@code null} if no
     *                  checkpoint is scheduled
     * @return {@code "Next checkpoint in M:SS"}, or
     *         {@code "No checkpoint scheduled"} when {@code remaining} is
     *         {@code null}
     */
    public static String countdownText(Duration remaining) {
        if (remaining == null) {
            return "No checkpoint scheduled";
        }
        return "Next checkpoint in " + formatRemaining(remaining);
    }

    /**
     * Computes the fraction of the autosave interval still remaining,
     * clamped to {@code [0, 1]}. Pure with respect to its arguments.
     *
     * @param now      the reference instant
     * @param next     the instant the next checkpoint fires
     * @param interval the autosave interval (the denominator)
     * @return the remaining fraction in {@code [0, 1]}; {@code 0.0} when
     *         {@code next} or {@code interval} is {@code null}, or the
     *         interval is zero / negative
     */
    public static double barFraction(Instant now, Instant next, Duration interval) {
        if (next == null || interval == null
                || interval.isZero() || interval.isNegative()) {
            return 0.0;
        }
        long intervalMillis = interval.toMillis();
        if (intervalMillis <= 0) {
            return 0.0;
        }
        long remainingMillis = Duration.between(now, next).toMillis();
        double fraction = (double) remainingMillis / (double) intervalMillis;
        if (fraction <= 0.0) {
            return 0.0;
        }
        if (fraction >= 1.0) {
            return 1.0;
        }
        return fraction;
    }

    // ── Styleable colour properties (consume forwarded -ccd-* tokens) ─────
    //
    // Internal -ccd-* names so styles.css can forward role tokens without a
    // circular looked-up-colour drop, mirroring MixerChannelStrip's -mcs-*.

    private final StyleableObjectProperty<Color> textColor =
            new StyleableObjectProperty<>(FALLBACK_TEXT) {
                @Override public Object getBean() { return CheckpointCountdownCanvas.this; }
                @Override public String getName() { return "textColor"; }
                @Override public CssMetaData<CheckpointCountdownCanvas, Color> getCssMetaData() {
                    return StyleableProperties.TEXT;
                }
                @Override protected void invalidated() { requestRedraw(); }
            };
    private final StyleableObjectProperty<Color> trackColor =
            new StyleableObjectProperty<>(FALLBACK_TRACK) {
                @Override public Object getBean() { return CheckpointCountdownCanvas.this; }
                @Override public String getName() { return "trackColor"; }
                @Override public CssMetaData<CheckpointCountdownCanvas, Color> getCssMetaData() {
                    return StyleableProperties.TRACK;
                }
                @Override protected void invalidated() { requestRedraw(); }
            };
    private final StyleableObjectProperty<Color> fillColor =
            new StyleableObjectProperty<>(FALLBACK_FILL) {
                @Override public Object getBean() { return CheckpointCountdownCanvas.this; }
                @Override public String getName() { return "fillColor"; }
                @Override public CssMetaData<CheckpointCountdownCanvas, Color> getCssMetaData() {
                    return StyleableProperties.FILL;
                }
                @Override protected void invalidated() { requestRedraw(); }
            };

    /** @return the text styleable colour ({@code -ccd-text}, the countdown caption). */
    public final StyleableObjectProperty<Color> textColorProperty() { return textColor; }
    /** @return the resolved text colour. */
    public final Color getTextColor() { return textColor.get(); }
    /** @param c the text colour. */
    public final void setTextColor(Color c) { textColor.set(c); }

    /** @return the track styleable colour ({@code -ccd-track}, the bar background). */
    public final StyleableObjectProperty<Color> trackColorProperty() { return trackColor; }
    /** @return the resolved track colour. */
    public final Color getTrackColor() { return trackColor.get(); }
    /** @param c the track colour. */
    public final void setTrackColor(Color c) { trackColor.set(c); }

    /** @return the fill styleable colour ({@code -ccd-fill}, the remaining-fraction bar). */
    public final StyleableObjectProperty<Color> fillColorProperty() { return fillColor; }
    /** @return the resolved fill colour. */
    public final Color getFillColor() { return fillColor.get(); }
    /** @param c the fill colour. */
    public final void setFillColor(Color c) { fillColor.set(c); }

    // ── CssMetaData ───────────────────────────────────────────────────────

    private static final class StyleableProperties {

        private static CssMetaData<CheckpointCountdownCanvas, Color> color(
                String name, Color fallback,
                java.util.function.Function<CheckpointCountdownCanvas,
                        StyleableObjectProperty<Color>> accessor) {
            return new CssMetaData<>(name,
                    StyleConverter.getColorConverter(), fallback) {
                @Override
                public boolean isSettable(CheckpointCountdownCanvas s) {
                    return !accessor.apply(s).isBound();
                }
                @Override
                public StyleableProperty<Color> getStyleableProperty(CheckpointCountdownCanvas s) {
                    return accessor.apply(s);
                }
            };
        }

        static final CssMetaData<CheckpointCountdownCanvas, Color> TEXT =
                color("-ccd-text", FALLBACK_TEXT, s -> s.textColor);
        static final CssMetaData<CheckpointCountdownCanvas, Color> TRACK =
                color("-ccd-track", FALLBACK_TRACK, s -> s.trackColor);
        static final CssMetaData<CheckpointCountdownCanvas, Color> FILL =
                color("-ccd-fill", FALLBACK_FILL, s -> s.fillColor);

        static final List<CssMetaData<? extends Styleable, ?>> CSS_META_DATA;

        static {
            List<CssMetaData<? extends Styleable, ?>> list =
                    new ArrayList<>(Region.getClassCssMetaData());
            Collections.addAll(list, TEXT, TRACK, FILL);
            CSS_META_DATA = Collections.unmodifiableList(list);
        }

        private StyleableProperties() {
        }
    }

    /** @return the {@link CssMetaData} for this type, combined with {@link Region}'s. */
    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return StyleableProperties.CSS_META_DATA;
    }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
        return getClassCssMetaData();
    }
}
