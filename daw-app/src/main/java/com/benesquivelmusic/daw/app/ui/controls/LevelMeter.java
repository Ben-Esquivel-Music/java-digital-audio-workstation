package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.controls.skin.LevelMeterSkin;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import javafx.css.CssMetaData;
import javafx.css.StyleConverter;
import javafx.css.Styleable;
import javafx.css.StyleableBooleanProperty;
import javafx.css.StyleableDoubleProperty;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.geometry.Orientation;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed;

/**
 * A discrete LED-style segment level meter {@link Control}.
 *
 * <p>This is the first member of the {@code controls} package and the
 * <em>template</em> for the Knob (story 268), Fader (story 269) and
 * channel/master strips (stories 270/271). It establishes the
 * {@code Control} + {@code SkinBase} + {@code StyleableProperty} +
 * package-resource user-agent stylesheet file-layout convention.
 *
 * <p>The meter renders peak / RMS / peak-hold as a column of discrete
 * segments on a plain {@link javafx.scene.canvas.Canvas} (NOT the
 * {@code daw-fx} GPU canvas — that backend swap is story 251's job and is
 * intentionally untouched here). The visual language follows the UI
 * Design Book §2.5 (motion restraint), §2.6 (colour semantics), §5.7
 * (metering) and §7.10 (no glow / no shadow / no {@link javafx.scene.effect.Effect}).
 *
 * <h2>Audio → FX relay contract</h2>
 *
 * <p>The audio thread must <strong>never</strong> touch the scene graph or
 * call a JavaFX property setter. Audio code calls
 * {@link #submitLevels(double, double)} (or
 * {@link #submitLevels(int, double, double)}), which writes only to
 * lock-free {@link AtomicLong} fields that encode the {@code double} via
 * {@link Double#doubleToLongBits(double)} — no per-update allocation. The
 * skin's per-frame {@link javafx.animation.AnimationTimer} (FX thread)
 * reads those atomics each pulse and propagates them into
 * {@link #peakDbProperty()} / {@link #rmsDbProperty()}. The timer runs
 * whenever the control is attached to a {@link javafx.scene.Scene},
 * regardless of {@link #animatedProperty()}: stopping it would strand
 * audio-thread submissions on the non-observable atomics. The
 * {@link #animatedProperty()} flag is currently a no-op for this skin
 * (segments are discrete with no smooth transitions to suppress); it is
 * kept for API parity with the rest of the controls package and the
 * global Reduce Motion setting (story 279). Once any level has been
 * submitted the relay owns
 * {@link #peakDbProperty()} / {@link #rmsDbProperty()}; calling
 * {@link #setPeakDb} directly is only meaningful <em>before</em> the first
 * {@link #submitLevels} call (see {@link #hasSubmission()}).
 *
 * <h2>Construction paths</h2>
 *
 * <p>The public no-arg constructor plus the standard property setters are
 * one fully-supported construction path. The fluent {@link #create()}
 * builder is an equivalent convenience path — neither is privileged:
 * <pre>{@code
 * LevelMeter a = new LevelMeter();
 * a.setChannelCount(2);
 *
 * LevelMeter b = LevelMeter.create()
 *         .channels(2)
 *         .orientation(Orientation.VERTICAL)
 *         .size("inline")
 *         .build();
 * }</pre>
 */
@HardcodedColorAllowed("story 277 follow-up: migrate Canvas/inline paints to resolved -token CSS")
public final class LevelMeter extends Control {

    /** Stable style class — selectable as {@code .level-meter} in CSS. */
    public static final String DEFAULT_STYLE_CLASS = "level-meter";

    /** Minimum channels supported. */
    public static final int MIN_CHANNELS = 1;
    /** Maximum channels supported. */
    public static final int MAX_CHANNELS = 8;

    // ── Hard-coded fallback colours (UI Design Book Palette A) ────────────
    // These match level-meter.css and styles.css so the control renders
    // correctly even when NO stylesheet is loaded (plugin-GUI window case).
    private static final Color FALLBACK_LOW = Color.web("#3FBF7F");
    private static final Color FALLBACK_MID = Color.web("#B6D451");
    private static final Color FALLBACK_HI = Color.web("#E6B450");
    private static final Color FALLBACK_CLIP = Color.web("#E5484D");
    private static final Color FALLBACK_BACKGROUND = Color.web("#1D1F26");

    // ── Plain observable properties ───────────────────────────────────────

    private final DoubleProperty peakDb = new SimpleDoubleProperty(this, "peakDb", -120.0);
    private final DoubleProperty rmsDb = new SimpleDoubleProperty(this, "rmsDb", -120.0);
    private final DoubleProperty peakHoldDb =
            new SimpleDoubleProperty(this, "peakHoldDb", -120.0);
    private final ObjectProperty<Orientation> orientation =
            new SimpleObjectProperty<>(this, "orientation", Orientation.VERTICAL) {
                @Override
                public void set(Orientation newValue) {
                    super.set(Objects.requireNonNull(newValue, "orientation"));
                }
            };
    private final IntegerProperty channelCount =
            new SimpleIntegerProperty(this, "channelCount", 2) {
                // Clamp on BOTH the write and read path so the [1, 8]
                // invariant is enforced regardless of how the property is
                // mutated. A direct .set(int) call clamps before storing.
                // A bind(...) call routes the source observable's value
                // through SimpleIntegerProperty's binding machinery, which
                // (for invalidated properties) recomputes via get() — so
                // get() must clamp too. The skin's getChannelCount() goes
                // through here, so it never sees an out-of-range value.
                @Override
                public void set(int newValue) {
                    super.set(clamp(newValue));
                }
                @Override
                public int get() {
                    return clamp(super.get());
                }
                private int clamp(int v) {
                    return Math.max(MIN_CHANNELS, Math.min(MAX_CHANNELS, v));
                }
            };
    // ── Animated flag — two-mechanism design (story 279) ──────────────────
    // The per-control opt-out flag (set via setAnimated / builder.animated);
    // unchanged behaviour. The publicly-observable `animated` property is a
    // read-only COMBINED value: localAnimated AND NOT global Reduce Motion.
    private final BooleanProperty localAnimated =
            new SimpleBooleanProperty(this, "localAnimated", true);
    private final ReadOnlyBooleanWrapper animated =
            new ReadOnlyBooleanWrapper(this, "animated", true);
    // Strong field reference so the listener lives exactly as long as this
    // control; registered on the process-lifetime MotionManager singleton
    // wrapped in a WeakChangeListener so the singleton never pins the
    // control (story 277/278 sanctioned weak-listener pattern, story 279).
    private final ChangeListener<Boolean> reduceMotionListener =
            (obs, was, now) -> recomputeAnimated();
    // Captured once at construction so the WeakChangeListener registration
    // and recomputeAnimated() always read the SAME MotionManager: a
    // getDefault() swap mid-life (setDefaultForTest) cannot make them
    // diverge — the listener firing on instance A while the recompute
    // reads instance B's flag.
    private final MotionManager motionManager = MotionManager.getDefault();

    // ── Lock-free audio → FX relay ────────────────────────────────────────
    // One AtomicLong per double value (NOT AtomicReference<Double> — avoids
    // per-update boxing/allocation on the audio thread). Index 0 is the
    // aggregate (single-channel API); 1..MAX_CHANNELS are per-channel.
    private final AtomicLong[] submittedPeakBits = newBitsArray();
    private final AtomicLong[] submittedRmsBits = newBitsArray();

    /**
     * {@code true} once {@link #submitLevels} has ever been called.
     *
     * <p>Before the first submission the direct setters
     * ({@link #setPeakDb} / {@link #setRmsDb}) are authoritative: the skin
     * does not relay the sentinel atomics, so a value set directly is shown
     * as-is. <strong>After</strong> the first submission an audio feed is
     * assumed and the relay becomes the source of truth — a subsequent
     * direct {@code setPeakDb(...)} is overwritten on the next FX pulse.
     * The constructor/setter path and the audio-relay path are equivalent
     * only up to the first {@code submitLevels} call.
     */
    private volatile boolean hasSubmission;

    /**
     * Sentinel meaning "nothing has been submitted into this slot yet".
     * {@code NaN} cannot collide with any real dBFS value, so a reader
     * distinguishes "no feed" from a legitimately low level (e.g.
     * {@code -119.5 dBFS}) with an exact {@link Double#isNaN} test rather
     * than a fragile magnitude band near the noise floor.
     */
    private static final double NO_SUBMISSION = Double.NaN;

    private static AtomicLong[] newBitsArray() {
        AtomicLong[] a = new AtomicLong[MAX_CHANNELS + 1];
        long sentinel = Double.doubleToLongBits(NO_SUBMISSION);
        for (int i = 0; i < a.length; i++) {
            a[i] = new AtomicLong(sentinel);
        }
        return a;
    }

    /**
     * Creates a level meter with default state: vertical, stereo (2
     * channels), animated, all levels at {@code -120 dBFS}.
     */
    public LevelMeter() {
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        setAccessibleRole(AccessibleRole.PROGRESS_INDICATOR);
        setAccessibleRoleDescription("Level meter");
        // The accessible text starts static; the skin updates it
        // dynamically in tick() with the current peak/RMS/clip state
        // so screen readers announce level information, not just the
        // role description.
        setAccessibleText("Level meter: no signal");
        setFocusTraversable(false);

        // Combined animated = localAnimated AND NOT global Reduce Motion
        // (story 279). Recompute when either input changes; the global
        // listener is weak so the MotionManager singleton cannot pin this
        // control (the strong reduceMotionListener field dies with it).
        localAnimated.addListener((obs, was, now) -> recomputeAnimated());
        motionManager.reduceMotionProperty()
                .addListener(new WeakChangeListener<>(reduceMotionListener));
        recomputeAnimated();
    }

    /**
     * Recomputes the combined {@link #animatedProperty()} value:
     * {@code localAnimated AND NOT reduceMotion} (story 279).
     */
    private void recomputeAnimated() {
        animated.set(localAnimated.get()
                && !motionManager.isReduceMotion());
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new LevelMeterSkin(this);
    }

    @Override
    public String getUserAgentStylesheet() {
        return LevelMeter.class.getResource("level-meter.css").toExternalForm();
    }

    // ── peakDb ────────────────────────────────────────────────────────────

    /** @return the current peak dBFS property (typically negative). */
    public final DoubleProperty peakDbProperty() {
        return peakDb;
    }

    /** @return the current peak dBFS value. */
    public final double getPeakDb() {
        return peakDb.get();
    }

    /** @param value the new peak dBFS value. */
    public final void setPeakDb(double value) {
        peakDb.set(value);
    }

    // ── rmsDb ─────────────────────────────────────────────────────────────

    /** @return the current RMS dBFS property (darker secondary indicator). */
    public final DoubleProperty rmsDbProperty() {
        return rmsDb;
    }

    /** @return the current RMS dBFS value. */
    public final double getRmsDb() {
        return rmsDb.get();
    }

    /** @param value the new RMS dBFS value. */
    public final void setRmsDb(double value) {
        rmsDb.set(value);
    }

    // ── peakHoldDb ────────────────────────────────────────────────────────

    /**
     * @return the peak-hold dBFS property. The skin clears this on a 2 s
     *         timer (see {@link LevelMeterSkin}); a double-click on the
     *         meter also clears it.
     */
    public final DoubleProperty peakHoldDbProperty() {
        return peakHoldDb;
    }

    /** @return the current peak-hold dBFS value. */
    public final double getPeakHoldDb() {
        return peakHoldDb.get();
    }

    /** @param value the new peak-hold dBFS value. */
    public final void setPeakHoldDb(double value) {
        peakHoldDb.set(value);
    }

    // ── orientation ───────────────────────────────────────────────────────

    /** @return the orientation property (default {@link Orientation#VERTICAL}). */
    public final ObjectProperty<Orientation> orientationProperty() {
        return orientation;
    }

    /** @return the orientation. */
    public final Orientation getOrientation() {
        return orientation.get();
    }

    /** @param value the new orientation; must not be {@code null}. */
    public final void setOrientation(Orientation value) {
        orientation.set(Objects.requireNonNull(value, "orientation"));
    }

    // ── channelCount ──────────────────────────────────────────────────────

    /** @return the channel-count property (default 2, range 1..8). */
    public final IntegerProperty channelCountProperty() {
        return channelCount;
    }

    /** @return the channel count. */
    public final int getChannelCount() {
        return channelCount.get();
    }

    /**
     * @param value the new channel count; clamped to {@code [1, 8]}.
     *              The clamp is enforced at the property level, so direct
     *              binds/sets via {@link #channelCountProperty()} are also
     *              constrained.
     */
    public final void setChannelCount(int value) {
        channelCount.set(value);
    }

    // ── animated ──────────────────────────────────────────────────────────

    /**
     * @return the combined animated property (default {@code true}):
     *         {@code true} only when this control's per-control flag is
     *         set <em>and</em> global Reduce Motion is off (story 279).
     *         Read-only — write the per-control flag via
     *         {@link #setAnimated(boolean)}. Currently a no-op for this
     *         skin: segments are discrete with no smooth transitions, so
     *         the per-frame timer always runs while attached to keep
     *         audio-thread {@link #submitLevels} writes visible. The flag
     *         is kept for API parity with the rest of the controls
     *         package and the global Reduce Motion setting.
     */
    public final ReadOnlyBooleanProperty animatedProperty() {
        return animated.getReadOnlyProperty();
    }

    /**
     * @return whether the meter is animated — the combined value
     *         ({@code localAnimated AND NOT reduceMotion}, story 279)
     */
    public final boolean isAnimated() {
        return animated.get();
    }

    /**
     * Sets this control's per-control animation opt-out flag. The
     * effective {@link #isAnimated()} value also depends on the global
     * Reduce Motion setting (story 279) — this writes only the
     * per-control half of the two-mechanism gate.
     *
     * @param value whether the meter should animate (per-control flag)
     */
    public final void setAnimated(boolean value) {
        localAnimated.set(value);
    }

    // ── Audio → FX relay (thread-safe ingest) ─────────────────────────────

    /**
     * Submits an aggregate level snapshot from any thread (typically the
     * real-time audio thread). Lock-free and allocation-free: writes only
     * to {@link AtomicLong} fields. Does NOT touch the scene graph. The
     * skin's FX-thread timer reads these values and updates the observable
     * properties.
     *
     * <p>Calling this method also <strong>clears any previously submitted
     * per-channel slots</strong> (resets them to {@link Double#NaN}). The
     * skin prefers a non-NaN per-channel value over the aggregate, so
     * leaving stale per-channel data alone would cause a meter that
     * switches from {@link #submitLevels(int, double, double)} to this
     * aggregate API to keep displaying the old per-channel levels
     * indefinitely. Resetting them here makes the aggregate
     * unambiguously authoritative.
     *
     * @param peakDb current peak dBFS
     * @param rmsDb  current RMS dBFS
     */
    public void submitLevels(double peakDb, double rmsDb) {
        submittedPeakBits[0].set(Double.doubleToLongBits(peakDb));
        submittedRmsBits[0].set(Double.doubleToLongBits(rmsDb));
        // Wipe per-channel slots so the aggregate is the sole source of
        // truth; otherwise the skin's "per-channel wins over aggregate"
        // rule would surface stale data after a feed-mode switch.
        long nanBits = Double.doubleToLongBits(Double.NaN);
        for (int i = 1; i <= MAX_CHANNELS; i++) {
            submittedPeakBits[i].set(nanBits);
            submittedRmsBits[i].set(nanBits);
        }
        hasSubmission = true;
    }

    /**
     * Submits a per-channel level snapshot from any thread. Lock-free and
     * allocation-free. The hard cap is {@link #MAX_CHANNELS}: a {@code
     * channel} outside {@code [0, MAX_CHANNELS)} is ignored. A channel that
     * is {@code >=} the current {@link #getChannelCount()} but still in
     * range is stored and becomes visible only if the channel count grows.
     *
     * @param channel zero-based channel index ({@code 0..MAX_CHANNELS-1})
     * @param peakDb  current peak dBFS for the channel
     * @param rmsDb   current RMS dBFS for the channel
     */
    public void submitLevels(int channel, double peakDb, double rmsDb) {
        if (channel < 0 || channel >= MAX_CHANNELS) {
            return;
        }
        submittedPeakBits[channel + 1].set(Double.doubleToLongBits(peakDb));
        submittedRmsBits[channel + 1].set(Double.doubleToLongBits(rmsDb));
        hasSubmission = true;
    }

    /**
     * @return {@code true} once {@link #submitLevels} has ever been
     *         called. The skin relays the atomics into the observable
     *         properties only when this is {@code true}, so a value set
     *         directly via {@link #setPeakDb} (with no audio feed) is not
     *         clobbered by the sentinel.
     */
    public boolean hasSubmission() {
        return hasSubmission;
    }

    /**
     * Reads the last submitted aggregate peak dBFS. Called by the skin on
     * the FX thread.
     *
     * @return the last submitted aggregate peak dBFS, or {@link Double#NaN}
     *         if no aggregate level has been submitted
     */
    public double consumeSubmittedPeakDb() {
        return Double.longBitsToDouble(submittedPeakBits[0].get());
    }

    /**
     * Reads the last submitted aggregate RMS dBFS. Called by the skin on
     * the FX thread.
     *
     * @return the last submitted aggregate RMS dBFS, or {@link Double#NaN}
     *         if no aggregate level has been submitted
     */
    public double consumeSubmittedRmsDb() {
        return Double.longBitsToDouble(submittedRmsBits[0].get());
    }

    /**
     * Reads the last submitted per-channel peak dBFS.
     *
     * @param channel zero-based channel index
     * @return the last submitted per-channel peak dBFS, or {@link
     *         Double#NaN} if nothing was submitted for that channel or the
     *         channel is out of range
     */
    public double consumeSubmittedPeakDb(int channel) {
        if (channel < 0 || channel >= MAX_CHANNELS) {
            return Double.NaN;
        }
        return Double.longBitsToDouble(submittedPeakBits[channel + 1].get());
    }

    /**
     * Reads the last submitted per-channel RMS dBFS.
     *
     * @param channel zero-based channel index
     * @return the last submitted per-channel RMS dBFS, or {@link
     *         Double#NaN} if nothing was submitted for that channel or the
     *         channel is out of range
     */
    public double consumeSubmittedRmsDb(int channel) {
        if (channel < 0 || channel >= MAX_CHANNELS) {
            return Double.NaN;
        }
        return Double.longBitsToDouble(submittedRmsBits[channel + 1].get());
    }

    // ── Styleable properties ──────────────────────────────────────────────

    private final StyleableObjectProperty<Color> meterLow =
            new StyleableObjectProperty<>(FALLBACK_LOW) {
                @Override public Object getBean() { return LevelMeter.this; }
                @Override public String getName() { return "meterLow"; }
                @Override public CssMetaData<LevelMeter, Color> getCssMetaData() {
                    return StyleableProperties.METER_LOW;
                }
            };

    private final StyleableObjectProperty<Color> meterMid =
            new StyleableObjectProperty<>(FALLBACK_MID) {
                @Override public Object getBean() { return LevelMeter.this; }
                @Override public String getName() { return "meterMid"; }
                @Override public CssMetaData<LevelMeter, Color> getCssMetaData() {
                    return StyleableProperties.METER_MID;
                }
            };

    private final StyleableObjectProperty<Color> meterHi =
            new StyleableObjectProperty<>(FALLBACK_HI) {
                @Override public Object getBean() { return LevelMeter.this; }
                @Override public String getName() { return "meterHi"; }
                @Override public CssMetaData<LevelMeter, Color> getCssMetaData() {
                    return StyleableProperties.METER_HI;
                }
            };

    private final StyleableObjectProperty<Color> meterClip =
            new StyleableObjectProperty<>(FALLBACK_CLIP) {
                @Override public Object getBean() { return LevelMeter.this; }
                @Override public String getName() { return "meterClip"; }
                @Override public CssMetaData<LevelMeter, Color> getCssMetaData() {
                    return StyleableProperties.METER_CLIP;
                }
            };

    private final StyleableObjectProperty<Color> meterBackground =
            new StyleableObjectProperty<>(FALLBACK_BACKGROUND) {
                @Override public Object getBean() { return LevelMeter.this; }
                @Override public String getName() { return "meterBackground"; }
                @Override public CssMetaData<LevelMeter, Color> getCssMetaData() {
                    return StyleableProperties.METER_BACKGROUND;
                }
            };

    private final StyleableDoubleProperty meterSegmentGap =
            new StyleableDoubleProperty(1.0) {
                @Override public Object getBean() { return LevelMeter.this; }
                @Override public String getName() { return "meterSegmentGap"; }
                @Override public CssMetaData<LevelMeter, Number> getCssMetaData() {
                    return StyleableProperties.METER_SEGMENT_GAP;
                }
            };

    private final StyleableDoubleProperty meterSegmentHeight =
            new StyleableDoubleProperty(2.0) {
                @Override public Object getBean() { return LevelMeter.this; }
                @Override public String getName() { return "meterSegmentHeight"; }
                @Override public CssMetaData<LevelMeter, Number> getCssMetaData() {
                    return StyleableProperties.METER_SEGMENT_HEIGHT;
                }
            };

    private final StyleableBooleanProperty meterTickMarks =
            new StyleableBooleanProperty(false) {
                @Override public Object getBean() { return LevelMeter.this; }
                @Override public String getName() { return "meterTickMarks"; }
                @Override public CssMetaData<LevelMeter, Boolean> getCssMetaData() {
                    return StyleableProperties.METER_TICK_MARKS;
                }
            };

    /** @return the {@code -meter-low} styleable colour property. */
    public final StyleableObjectProperty<Color> meterLowProperty() { return meterLow; }
    /** @return the resolved low-region colour. */
    public final Color getMeterLow() { return meterLow.get(); }
    /** @param c the low-region colour. */
    public final void setMeterLow(Color c) { meterLow.set(c); }

    /** @return the {@code -meter-mid} styleable colour property. */
    public final StyleableObjectProperty<Color> meterMidProperty() { return meterMid; }
    /** @return the resolved mid-region colour. */
    public final Color getMeterMid() { return meterMid.get(); }
    /** @param c the mid-region colour. */
    public final void setMeterMid(Color c) { meterMid.set(c); }

    /** @return the {@code -meter-hi} styleable colour property. */
    public final StyleableObjectProperty<Color> meterHiProperty() { return meterHi; }
    /** @return the resolved high-region colour. */
    public final Color getMeterHi() { return meterHi.get(); }
    /** @param c the high-region colour. */
    public final void setMeterHi(Color c) { meterHi.set(c); }

    /** @return the {@code -meter-clip} styleable colour property. */
    public final StyleableObjectProperty<Color> meterClipProperty() { return meterClip; }
    /** @return the resolved clip-region colour. */
    public final Color getMeterClip() { return meterClip.get(); }
    /** @param c the clip-region colour. */
    public final void setMeterClip(Color c) { meterClip.set(c); }

    /** @return the {@code -meter-background} styleable colour property. */
    public final StyleableObjectProperty<Color> meterBackgroundProperty() { return meterBackground; }
    /** @return the resolved unlit/background segment colour. */
    public final Color getMeterBackground() { return meterBackground.get(); }
    /** @param c the unlit/background segment colour. */
    public final void setMeterBackground(Color c) { meterBackground.set(c); }

    /** @return the {@code -meter-segment-gap} styleable property (px). */
    public final StyleableDoubleProperty meterSegmentGapProperty() { return meterSegmentGap; }
    /** @return the inter-segment gap in pixels. */
    public final double getMeterSegmentGap() { return meterSegmentGap.get(); }
    /** @param px the inter-segment gap in pixels. */
    public final void setMeterSegmentGap(double px) { meterSegmentGap.set(px); }

    /** @return the {@code -meter-segment-height} styleable property (px). */
    public final StyleableDoubleProperty meterSegmentHeightProperty() { return meterSegmentHeight; }
    /** @return the per-segment thickness in pixels. */
    public final double getMeterSegmentHeight() { return meterSegmentHeight.get(); }
    /** @param px the per-segment thickness in pixels. */
    public final void setMeterSegmentHeight(double px) { meterSegmentHeight.set(px); }

    /** @return the {@code -meter-tick-marks} styleable property. */
    public final StyleableBooleanProperty meterTickMarksProperty() { return meterTickMarks; }
    /** @return whether dB tick marks/labels are drawn (performance size). */
    public final boolean isMeterTickMarks() { return meterTickMarks.get(); }
    /** @param on whether to draw dB tick marks/labels. */
    public final void setMeterTickMarks(boolean on) { meterTickMarks.set(on); }

    // ── CssMetaData ───────────────────────────────────────────────────────

    private static final class StyleableProperties {

        static final CssMetaData<LevelMeter, Color> METER_LOW =
                new CssMetaData<>("-lm-low",
                        StyleConverter.getColorConverter(), FALLBACK_LOW) {
                    @Override public boolean isSettable(LevelMeter m) {
                        return !m.meterLow.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(LevelMeter m) {
                        return m.meterLow;
                    }
                };

        static final CssMetaData<LevelMeter, Color> METER_MID =
                new CssMetaData<>("-lm-mid",
                        StyleConverter.getColorConverter(), FALLBACK_MID) {
                    @Override public boolean isSettable(LevelMeter m) {
                        return !m.meterMid.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(LevelMeter m) {
                        return m.meterMid;
                    }
                };

        static final CssMetaData<LevelMeter, Color> METER_HI =
                new CssMetaData<>("-lm-hi",
                        StyleConverter.getColorConverter(), FALLBACK_HI) {
                    @Override public boolean isSettable(LevelMeter m) {
                        return !m.meterHi.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(LevelMeter m) {
                        return m.meterHi;
                    }
                };

        static final CssMetaData<LevelMeter, Color> METER_CLIP =
                new CssMetaData<>("-lm-clip",
                        StyleConverter.getColorConverter(), FALLBACK_CLIP) {
                    @Override public boolean isSettable(LevelMeter m) {
                        return !m.meterClip.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(LevelMeter m) {
                        return m.meterClip;
                    }
                };

        static final CssMetaData<LevelMeter, Color> METER_BACKGROUND =
                new CssMetaData<>("-lm-background",
                        StyleConverter.getColorConverter(), FALLBACK_BACKGROUND) {
                    @Override public boolean isSettable(LevelMeter m) {
                        return !m.meterBackground.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(LevelMeter m) {
                        return m.meterBackground;
                    }
                };

        static final CssMetaData<LevelMeter, Number> METER_SEGMENT_GAP =
                new CssMetaData<>("-lm-segment-gap",
                        StyleConverter.getSizeConverter(), 1.0) {
                    @Override public boolean isSettable(LevelMeter m) {
                        return !m.meterSegmentGap.isBound();
                    }
                    @Override public StyleableProperty<Number> getStyleableProperty(LevelMeter m) {
                        return m.meterSegmentGap;
                    }
                };

        static final CssMetaData<LevelMeter, Number> METER_SEGMENT_HEIGHT =
                new CssMetaData<>("-lm-segment-height",
                        StyleConverter.getSizeConverter(), 2.0) {
                    @Override public boolean isSettable(LevelMeter m) {
                        return !m.meterSegmentHeight.isBound();
                    }
                    @Override public StyleableProperty<Number> getStyleableProperty(LevelMeter m) {
                        return m.meterSegmentHeight;
                    }
                };

        static final CssMetaData<LevelMeter, Boolean> METER_TICK_MARKS =
                new CssMetaData<>("-lm-tick-marks",
                        StyleConverter.getBooleanConverter(), Boolean.FALSE) {
                    @Override public boolean isSettable(LevelMeter m) {
                        return !m.meterTickMarks.isBound();
                    }
                    @Override public StyleableProperty<Boolean> getStyleableProperty(LevelMeter m) {
                        return m.meterTickMarks;
                    }
                };

        static final List<CssMetaData<? extends Styleable, ?>> CSS_META_DATA;

        static {
            List<CssMetaData<? extends Styleable, ?>> list =
                    new ArrayList<>(Control.getClassCssMetaData());
            Collections.addAll(list,
                    METER_LOW, METER_MID, METER_HI, METER_CLIP, METER_BACKGROUND,
                    METER_SEGMENT_GAP, METER_SEGMENT_HEIGHT, METER_TICK_MARKS);
            CSS_META_DATA = Collections.unmodifiableList(list);
        }

        private StyleableProperties() {
        }
    }

    /**
     * @return the {@link CssMetaData} for this control type, combined with
     *         {@link Control}'s.
     */
    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return StyleableProperties.CSS_META_DATA;
    }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getControlCssMetaData() {
        return getClassCssMetaData();
    }

    // ── Builder ───────────────────────────────────────────────────────────

    /**
     * @return a new fluent {@link Builder}. Equivalent to (not a
     *         replacement for) the public no-arg constructor + setters.
     */
    public static Builder create() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link LevelMeter}. One construction path among
     * equals — every method maps onto a public setter / style class.
     */
    public static final class Builder {

        private int channels = 2;
        private Orientation orientation = Orientation.VERTICAL;
        private boolean animated = true;
        private String sizeName;

        private Builder() {
        }

        /**
         * @param channels channel count, clamped to {@code [1, 8]}
         * @return this builder
         */
        public Builder channels(int channels) {
            this.channels = channels;
            return this;
        }

        /**
         * @param orientation the orientation; must not be {@code null}
         * @return this builder
         */
        public Builder orientation(Orientation orientation) {
            this.orientation = Objects.requireNonNull(orientation, "orientation");
            return this;
        }

        /**
         * @param animated whether the meter animates
         * @return this builder
         */
        public Builder animated(boolean animated) {
            this.animated = animated;
            return this;
        }

        /**
         * Adds the style class {@code "size-" + name} (e.g.
         * {@code size-inline}, {@code size-channel}, {@code size-master},
         * {@code size-performance}).
         *
         * @param name the size-variant name; must not be {@code null}
         * @return this builder
         */
        public Builder size(String name) {
            this.sizeName = Objects.requireNonNull(name, "size name");
            return this;
        }

        /**
         * @return a fully configured {@link LevelMeter}.
         */
        public LevelMeter build() {
            LevelMeter m = new LevelMeter();
            m.setChannelCount(channels);
            m.setOrientation(orientation);
            m.setAnimated(animated);
            if (sizeName != null) {
                m.getStyleClass().add("size-" + sizeName);
            }
            return m;
        }
    }
}
