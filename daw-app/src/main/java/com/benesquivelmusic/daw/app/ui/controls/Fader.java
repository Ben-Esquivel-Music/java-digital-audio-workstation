package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.controls.skin.FaderSkin;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import javafx.css.CssMetaData;
import javafx.css.StyleConverter;
import javafx.css.Styleable;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed;

/**
 * Vertical fader {@link Control} for level / volume parameters.
 *
 * <p>Story 269. Sibling of {@link LevelMeter} (story 267) and {@link Knob}
 * (story 268). Follows the same {@code Control + SkinBase + package-resource
 * user-agent stylesheet} convention so it renders correctly outside the
 * main app stylesheet (plugin-GUI window case).
 *
 * <p>UI Design Book §5.4 (mixer channel strip) and §2.8 (keyboard parity)
 * are the canonical specification. The control implements:
 * <ul>
 *   <li>Wide rectangular cap (~70% of column width × 12 px tall),
 *       {@code -fader-cap-color} fill with a 1 px {@code -fader-cap-line-color}
 *       horizontal centreline (UI Design Book §5.4).</li>
 *   <li>Integrated {@link LevelMeter} laid out to the right of the column
 *       at a fixed 4 px gap. Consumers bind once: {@code
 *       fader.getMeter().peakDbProperty().bind(audioEngine.peakOf(channelId))}.
 *       Set {@link #showMeterProperty()} to {@code false} to omit the meter
 *       (e.g. send-level faders).</li>
 *   <li>Travel-curve mapping ({@link TravelCurve}) — linear for pan, log/dB
 *       for volume.</li>
 *   <li>Size variants via style class ({@code .fader.size-mixer},
 *       {@code .size-inspector}, {@code .size-performance}).</li>
 *   <li>Value clamping at the property level — writes outside
 *       {@code [min, max]} are silently clamped (matches {@link Knob}).</li>
 *   <li>{@link AccessibleRole#SLIDER}, focus-traversable.</li>
 * </ul>
 *
 * <h2>Construction paths</h2>
 *
 * <p>The public no-arg constructor + setters and the fluent
 * {@link #create()} builder are independent, equally-supported paths.
 *
 * <pre>{@code
 * Fader f = new Fader();
 * f.setMin(-96); f.setMax(12); f.setDefaultValue(0); f.setCurve(LOG_DB);
 *
 * Fader g = Fader.create()
 *         .min(-96).max(12).defaultValue(0).curve(LOG_DB)
 *         .showMeter(true).size("mixer").build();
 * }</pre>
 */
@HardcodedColorAllowed("story 277 follow-up: migrate Canvas/inline paints to resolved -token CSS")
public final class Fader extends Control {

    /** Stable style class — selectable as {@code .fader} in CSS. */
    public static final String DEFAULT_STYLE_CLASS = "fader";

    // ── Palette A fallback colours (UI Design Book §5.4) ──────────────────
    private static final Color FALLBACK_TRACK = Color.web("#1D1F26");
    private static final Color FALLBACK_CAP = Color.web("#2E323D");
    private static final Color FALLBACK_CAP_LINE = Color.web("#7C8CFF");
    private static final Color FALLBACK_ZERO_TICK = Color.web("#5C6273");
    private static final Color FALLBACK_FOCUS_RING = Color.web("#5C8CFF");

    /**
     * Travel-curve mapping between user-units value and normalised
     * vertical position ({@code 0.0} = bottom, {@code 1.0} = top).
     *
     * <h3>LOG_DB curve coefficient</h3>
     *
     * <p>The professional-mixer dB curve places {@code 0 dB} at exactly
     * 75% travel — a convention preserved here. The mapping is piecewise
     * linear in dB, anchored to three points:
     * <ul>
     *   <li>{@code min}  → {@code 0.00}</li>
     *   <li>{@code 0 dB} → {@code 0.75}</li>
     *   <li>{@code max}  → {@code 1.00}</li>
     * </ul>
     * For {@code v ≤ 0}: {@code position = 0.75 * (v - min) / (0 - min)}.
     * <br>For {@code v > 0}: {@code position = 0.75 + 0.25 * v / max}.
     * <br>This curve is referenced by surfaces that must align with the
     * fader (automation render, export, etc.) — match it exactly.
     */
    public enum TravelCurve {
        /** Linear value→position (pan faders, normalised sends). */
        LINEAR,
        /** dB fader law: 0 dB at 75% travel. */
        LOG_DB,
        /** Linear in linear gain (10^(dB/20)). */
        LOG_GAIN
    }

    // ── Plain observable properties ───────────────────────────────────────

    private final DoubleProperty min =
            new SimpleDoubleProperty(this, "min", -96.0) {
                @Override
                protected void invalidated() {
                    double mn = get();
                    if (value.get() < mn) {
                        value.set(mn);
                    }
                    if (defaultValue.get() < mn) {
                        defaultValue.set(mn);
                    }
                }
            };
    private final DoubleProperty max =
            new SimpleDoubleProperty(this, "max", 12.0) {
                @Override
                protected void invalidated() {
                    double mx = get();
                    if (value.get() > mx) {
                        value.set(mx);
                    }
                    if (defaultValue.get() > mx) {
                        defaultValue.set(mx);
                    }
                }
            };
    private final DoubleProperty value =
            new SimpleDoubleProperty(this, "value", 0.0) {
                @Override
                public void set(double newValue) {
                    super.set(clampToRange(newValue));
                }
            };
    private final DoubleProperty defaultValue =
            new SimpleDoubleProperty(this, "defaultValue", 0.0) {
                @Override
                public void set(double newValue) {
                    double mn = min.get();
                    double mx = max.get();
                    if (mn <= mx && (newValue < mn || newValue > mx)) {
                        throw new IllegalArgumentException(
                                "defaultValue (" + newValue
                                        + ") must lie in [" + mn + ", " + mx + "]");
                    }
                    super.set(newValue);
                }
            };
    private final ObjectProperty<TravelCurve> curve =
            new SimpleObjectProperty<>(this, "curve", TravelCurve.LOG_DB) {
                @Override
                public void set(TravelCurve newValue) {
                    super.set(Objects.requireNonNull(newValue, "curve"));
                }
            };
    private final BooleanProperty showMeter =
            new SimpleBooleanProperty(this, "showMeter", true);

    // ── Animated flag — two-mechanism design (story 279) ──────────────────
    // localAnimated is the per-control opt-out (set via setAnimated /
    // builder.animated); `animated` is the read-only COMBINED value
    // (localAnimated AND NOT global Reduce Motion).
    private final BooleanProperty localAnimated =
            new SimpleBooleanProperty(this, "localAnimated", true);
    private final ReadOnlyBooleanWrapper animated =
            new ReadOnlyBooleanWrapper(this, "animated", true);
    // Strong field — lives exactly as long as this control; registered on
    // the MotionManager singleton via a WeakChangeListener so the
    // singleton never pins the control (story 277/278 pattern).
    private final ChangeListener<Boolean> reduceMotionListener =
            (obs, was, now) -> recomputeAnimated();

    private final StringProperty unit =
            new SimpleStringProperty(this, "unit", "dB");

    /** Default formatter: 1-decimal numeric (UI Design Book §5.4). */
    public static final Function<Double, String> DEFAULT_FORMATTER =
            v -> {
                if (Double.isInfinite(v) && v < 0) return "-\u221E";
                return String.format(Locale.ROOT, "%.1f", v);
            };

    private final ObjectProperty<Function<Double, String>> valueFormatter =
            new SimpleObjectProperty<>(this, "valueFormatter", DEFAULT_FORMATTER) {
                @Override
                public void set(Function<Double, String> newValue) {
                    super.set(Objects.requireNonNull(newValue, "valueFormatter"));
                }
            };

    // ── Embedded LevelMeter (lazy) ────────────────────────────────────────

    private LevelMeter meter;

    /**
     * Creates a fader with defaults: {@code min = -96 dB}, {@code max = +12 dB},
     * {@code value = 0 dB}, {@code defaultValue = 0 dB},
     * {@link TravelCurve#LOG_DB}, integrated meter shown, animated.
     */
    public Fader() {
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        setAccessibleRole(AccessibleRole.SLIDER);
        setAccessibleRoleDescription("Fader");
        setAccessibleText("Fader: " + formatValue());
        setFocusTraversable(true);

        // Combined animated = localAnimated AND NOT global Reduce Motion
        // (story 279). The global listener is weak so the MotionManager
        // singleton cannot pin this control.
        localAnimated.addListener((obs, was, now) -> recomputeAnimated());
        MotionManager.getDefault().reduceMotionProperty()
                .addListener(new WeakChangeListener<>(reduceMotionListener));
        recomputeAnimated();
    }

    /**
     * Recomputes the combined {@link #animatedProperty()} value:
     * {@code localAnimated AND NOT reduceMotion} (story 279).
     */
    private void recomputeAnimated() {
        animated.set(localAnimated.get()
                && !MotionManager.getDefault().isReduceMotion());
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new FaderSkin(this);
    }

    @Override
    public String getUserAgentStylesheet() {
        return Objects.requireNonNull(
                Fader.class.getResource("fader.css"),
                "fader.css not on classpath").toExternalForm();
    }

    private double clampToRange(double v) {
        double mn = min.get();
        double mx = max.get();
        if (mn > mx) {
            return v;
        }
        if (Double.isNaN(v)) return mn;
        return Math.max(mn, Math.min(mx, v));
    }

    /**
     * Maps {@code v} (user units) to a normalised travel position in
     * {@code [0, 1]} via the active {@link #curveProperty()}. {@code 0.0}
     * is the bottom of the column, {@code 1.0} is the top.
     *
     * @param v value in user units
     * @return normalised travel position, clamped to {@code [0, 1]}
     */
    public double positionForValue(double v) {
        double mn = getMin();
        double mx = getMax();
        if (mn >= mx) return 0.0;
        double clamped = Math.max(mn, Math.min(mx, v));
        double p = switch (getCurve()) {
            case LINEAR -> (clamped - mn) / (mx - mn);
            case LOG_DB -> dbToPosition(clamped, mn, mx);
            case LOG_GAIN -> {
                // Linear in linear gain: gain = 10^(dB/20)
                double gMin = Math.pow(10.0, mn / 20.0);
                double gMax = Math.pow(10.0, mx / 20.0);
                double g = Math.pow(10.0, clamped / 20.0);
                yield (gMax > gMin) ? (g - gMin) / (gMax - gMin) : 0.0;
            }
        };
        return Math.max(0.0, Math.min(1.0, p));
    }

    /**
     * Inverse of {@link #positionForValue(double)}: maps a normalised
     * travel position to a user-unit value via the active curve.
     *
     * @param p normalised position in {@code [0, 1]}
     * @return value in user units, clamped to {@code [min, max]}
     */
    public double valueForPosition(double p) {
        double mn = getMin();
        double mx = getMax();
        if (mn >= mx) return mn;
        double clamped = Math.max(0.0, Math.min(1.0, p));
        return switch (getCurve()) {
            case LINEAR -> mn + clamped * (mx - mn);
            case LOG_DB -> positionToDb(clamped, mn, mx);
            case LOG_GAIN -> {
                double gMin = Math.pow(10.0, mn / 20.0);
                double gMax = Math.pow(10.0, mx / 20.0);
                double g = gMin + clamped * (gMax - gMin);
                yield (g <= 0) ? mn : 20.0 * Math.log10(g);
            }
        };
    }

    // Two-segment linear-in-dB law anchored at 0 dB = 75% travel.
    // This is the canonical professional-mixer fader curve (see TravelCurve doc).
    private static double dbToPosition(double db, double mn, double mx) {
        // If max <= 0 dB there is no upper segment; collapse to a single
        // linear-in-dB segment scaled so max maps to 1.0.
        if (mx <= 0.0) {
            return (db - mn) / (mx - mn);
        }
        // If min >= 0 dB the lower segment vanishes; linear from min→max.
        if (mn >= 0.0) {
            return (db - mn) / (mx - mn);
        }
        if (db <= 0.0) {
            return 0.75 * (db - mn) / (0.0 - mn);
        }
        return 0.75 + 0.25 * (db / mx);
    }

    private static double positionToDb(double p, double mn, double mx) {
        if (mx <= 0.0 || mn >= 0.0) {
            return mn + p * (mx - mn);
        }
        if (p <= 0.75) {
            return mn + (p / 0.75) * (0.0 - mn);
        }
        return ((p - 0.75) / 0.25) * mx;
    }

    // ── value ─────────────────────────────────────────────────────────────

    public final DoubleProperty valueProperty() { return value; }
    public final double getValue() { return value.get(); }
    public final void setValue(double v) { value.set(v); }

    // ── min / max / defaultValue ──────────────────────────────────────────

    public final DoubleProperty minProperty() { return min; }
    public final double getMin() { return min.get(); }
    public final void setMin(double v) { min.set(v); }

    public final DoubleProperty maxProperty() { return max; }
    public final double getMax() { return max.get(); }
    public final void setMax(double v) { max.set(v); }

    public final DoubleProperty defaultValueProperty() { return defaultValue; }
    public final double getDefaultValue() { return defaultValue.get(); }
    public final void setDefaultValue(double v) { defaultValue.set(v); }

    // ── curve ─────────────────────────────────────────────────────────────

    /** @return the travel-curve property. JavaFX has no {@code EnumProperty};
     *          this is a {@link SimpleObjectProperty}. */
    public final ObjectProperty<TravelCurve> curveProperty() { return curve; }
    public final TravelCurve getCurve() { return curve.get(); }
    public final void setCurve(TravelCurve v) { curve.set(v); }

    // ── showMeter ─────────────────────────────────────────────────────────

    public final BooleanProperty showMeterProperty() { return showMeter; }
    public final boolean isShowMeter() { return showMeter.get(); }
    public final void setShowMeter(boolean v) { showMeter.set(v); }

    // ── animated (Reduce Motion) ──────────────────────────────────────────

    /**
     * @return the combined {@code animated} property (default
     *         {@code true}): {@code true} only when this fader's
     *         per-control flag is set <em>and</em> global Reduce Motion is
     *         off (story 279). Read-only — write the per-control flag via
     *         {@link #setAnimated(boolean)}.
     */
    public final ReadOnlyBooleanProperty animatedProperty() {
        return animated.getReadOnlyProperty();
    }
    /** @return the combined animated value (per-control flag AND NOT Reduce Motion). */
    public final boolean isAnimated() { return animated.get(); }
    /**
     * Sets this fader's per-control animation opt-out flag. The effective
     * {@link #isAnimated()} value also depends on the global Reduce Motion
     * setting (story 279).
     *
     * @param v whether the fader should animate (per-control flag)
     */
    public final void setAnimated(boolean v) { localAnimated.set(v); }

    // ── unit / formatter ──────────────────────────────────────────────────

    public final StringProperty unitProperty() { return unit; }
    public final String getUnit() { return unit.get(); }
    public final void setUnit(String v) { unit.set(v == null ? "" : v); }

    public final ObjectProperty<Function<Double, String>> valueFormatterProperty() {
        return valueFormatter;
    }
    public final Function<Double, String> getValueFormatter() {
        return valueFormatter.get();
    }
    public final void setValueFormatter(Function<Double, String> v) {
        valueFormatter.set(v);
    }

    /** @return formatted current value with optional unit suffix. */
    public final String formatValue() {
        String s = getValueFormatter().apply(getValue());
        String u = getUnit();
        return (u == null || u.isEmpty()) ? s : s + " " + u;
    }

    // ── Integrated meter accessor (lazy) ──────────────────────────────────

    /**
     * @return the embedded {@link LevelMeter}, created on first access.
     *         Consumers bind once:
     *         {@code fader.getMeter().peakDbProperty().bind(...)}. The
     *         skin lays out the meter to the right of the fader column
     *         when {@link #isShowMeter()} is {@code true}. Mirroring
     *         {@link Optional} semantics is intentionally avoided: a
     *         non-null meter always exists logically; visibility is
     *         controlled by {@code showMeter}.
     */
    public final LevelMeter getMeter() {
        if (meter == null) {
            meter = new LevelMeter();
            // The meter inside a fader takes its size cue from the
            // fader's size variant — set a sensible default.
            meter.getStyleClass().add("size-channel");
        }
        return meter;
    }

    /** @return whether the meter has been created (test seam). */
    public final boolean isMeterCreated() {
        return meter != null;
    }

    // ── Styleable colour properties ───────────────────────────────────────

    private final StyleableObjectProperty<Color> faderTrackColor =
            new StyleableObjectProperty<>(FALLBACK_TRACK) {
                @Override public Object getBean() { return Fader.this; }
                @Override public String getName() { return "faderTrackColor"; }
                @Override public CssMetaData<Fader, Color> getCssMetaData() {
                    return StyleableProperties.TRACK_COLOR;
                }
            };
    private final StyleableObjectProperty<Color> faderCapColor =
            new StyleableObjectProperty<>(FALLBACK_CAP) {
                @Override public Object getBean() { return Fader.this; }
                @Override public String getName() { return "faderCapColor"; }
                @Override public CssMetaData<Fader, Color> getCssMetaData() {
                    return StyleableProperties.CAP_COLOR;
                }
            };
    private final StyleableObjectProperty<Color> faderCapLineColor =
            new StyleableObjectProperty<>(FALLBACK_CAP_LINE) {
                @Override public Object getBean() { return Fader.this; }
                @Override public String getName() { return "faderCapLineColor"; }
                @Override public CssMetaData<Fader, Color> getCssMetaData() {
                    return StyleableProperties.CAP_LINE_COLOR;
                }
            };
    private final StyleableObjectProperty<Color> faderZeroTickColor =
            new StyleableObjectProperty<>(FALLBACK_ZERO_TICK) {
                @Override public Object getBean() { return Fader.this; }
                @Override public String getName() { return "faderZeroTickColor"; }
                @Override public CssMetaData<Fader, Color> getCssMetaData() {
                    return StyleableProperties.ZERO_TICK_COLOR;
                }
            };
    private final StyleableObjectProperty<Color> faderFocusRingColor =
            new StyleableObjectProperty<>(FALLBACK_FOCUS_RING) {
                @Override public Object getBean() { return Fader.this; }
                @Override public String getName() { return "faderFocusRingColor"; }
                @Override public CssMetaData<Fader, Color> getCssMetaData() {
                    return StyleableProperties.FOCUS_RING_COLOR;
                }
            };

    public final ObjectProperty<Color> faderTrackColorProperty() { return faderTrackColor; }
    public final Color getFaderTrackColor() { return faderTrackColor.get(); }
    public final void setFaderTrackColor(Color v) { faderTrackColor.set(v); }

    public final ObjectProperty<Color> faderCapColorProperty() { return faderCapColor; }
    public final Color getFaderCapColor() { return faderCapColor.get(); }
    public final void setFaderCapColor(Color v) { faderCapColor.set(v); }

    public final ObjectProperty<Color> faderCapLineColorProperty() { return faderCapLineColor; }
    public final Color getFaderCapLineColor() { return faderCapLineColor.get(); }
    public final void setFaderCapLineColor(Color v) { faderCapLineColor.set(v); }

    public final ObjectProperty<Color> faderZeroTickColorProperty() { return faderZeroTickColor; }
    public final Color getFaderZeroTickColor() { return faderZeroTickColor.get(); }
    public final void setFaderZeroTickColor(Color v) { faderZeroTickColor.set(v); }

    public final ObjectProperty<Color> faderFocusRingColorProperty() { return faderFocusRingColor; }
    public final Color getFaderFocusRingColor() { return faderFocusRingColor.get(); }
    public final void setFaderFocusRingColor(Color v) { faderFocusRingColor.set(v); }

    // ── CssMetaData ───────────────────────────────────────────────────────

    private static final class StyleableProperties {

        static final CssMetaData<Fader, Color> TRACK_COLOR =
                new CssMetaData<>("-fader-track-color",
                        StyleConverter.getColorConverter(), FALLBACK_TRACK) {
                    @Override public boolean isSettable(Fader f) {
                        return !f.faderTrackColor.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(Fader f) {
                        return f.faderTrackColor;
                    }
                };
        static final CssMetaData<Fader, Color> CAP_COLOR =
                new CssMetaData<>("-fader-cap-color",
                        StyleConverter.getColorConverter(), FALLBACK_CAP) {
                    @Override public boolean isSettable(Fader f) {
                        return !f.faderCapColor.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(Fader f) {
                        return f.faderCapColor;
                    }
                };
        static final CssMetaData<Fader, Color> CAP_LINE_COLOR =
                new CssMetaData<>("-fader-cap-line-color",
                        StyleConverter.getColorConverter(), FALLBACK_CAP_LINE) {
                    @Override public boolean isSettable(Fader f) {
                        return !f.faderCapLineColor.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(Fader f) {
                        return f.faderCapLineColor;
                    }
                };
        static final CssMetaData<Fader, Color> ZERO_TICK_COLOR =
                new CssMetaData<>("-fader-zero-tick-color",
                        StyleConverter.getColorConverter(), FALLBACK_ZERO_TICK) {
                    @Override public boolean isSettable(Fader f) {
                        return !f.faderZeroTickColor.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(Fader f) {
                        return f.faderZeroTickColor;
                    }
                };
        static final CssMetaData<Fader, Color> FOCUS_RING_COLOR =
                new CssMetaData<>("-fader-focus-ring-color",
                        StyleConverter.getColorConverter(), FALLBACK_FOCUS_RING) {
                    @Override public boolean isSettable(Fader f) {
                        return !f.faderFocusRingColor.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(Fader f) {
                        return f.faderFocusRingColor;
                    }
                };

        static final List<CssMetaData<? extends Styleable, ?>> CSS_META_DATA;

        static {
            List<CssMetaData<? extends Styleable, ?>> list =
                    new ArrayList<>(Control.getClassCssMetaData());
            Collections.addAll(list,
                    TRACK_COLOR, CAP_COLOR, CAP_LINE_COLOR,
                    ZERO_TICK_COLOR, FOCUS_RING_COLOR);
            CSS_META_DATA = Collections.unmodifiableList(list);
        }

        private StyleableProperties() {
        }
    }

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

    /** Permitted size-variant names. */
    private static final List<String> SIZE_VARIANTS =
            List.of("mixer", "inspector", "performance");

    /**
     * Fluent builder for {@link Fader}. {@code build()} validates that
     * {@code defaultValue} lies in {@code [min, max]}.
     */
    public static final class Builder {

        private double min = -96.0;
        private double max = 12.0;
        private double value = Double.NaN;
        private double defaultValue = 0.0;
        private TravelCurve curve = TravelCurve.LOG_DB;
        private boolean showMeter = true;
        private boolean animated = true;
        private String unit = "dB";
        private String size;
        private Function<Double, String> formatter;

        private Builder() {
        }

        public Builder min(double v) { this.min = v; return this; }
        public Builder max(double v) { this.max = v; return this; }
        public Builder value(double v) { this.value = v; return this; }
        public Builder defaultValue(double v) { this.defaultValue = v; return this; }
        public Builder curve(TravelCurve v) {
            this.curve = Objects.requireNonNull(v, "curve");
            return this;
        }
        public Builder showMeter(boolean v) { this.showMeter = v; return this; }
        public Builder animated(boolean v) { this.animated = v; return this; }
        public Builder unit(String v) {
            this.unit = v == null ? "" : v;
            return this;
        }

        /**
         * @param name the size variant: {@code "mixer"}, {@code "inspector"}
         *             or {@code "performance"}
         * @return this builder
         * @throws IllegalArgumentException if {@code name} is not a known
         *         size variant
         */
        public Builder size(String name) {
            Objects.requireNonNull(name, "size");
            if (!SIZE_VARIANTS.contains(name)) {
                throw new IllegalArgumentException(
                        "size must be one of " + SIZE_VARIANTS + " (got " + name + ")");
            }
            this.size = name;
            return this;
        }

        public Builder valueFormatter(Function<Double, String> f) {
            this.formatter = Objects.requireNonNull(f, "valueFormatter");
            return this;
        }

        /**
         * @return a fully configured {@link Fader}.
         * @throws IllegalArgumentException if {@code defaultValue} is
         *         outside {@code [min, max]} or {@code min > max}.
         */
        public Fader build() {
            if (min > max) {
                throw new IllegalArgumentException(
                        "min (" + min + ") must be <= max (" + max + ")");
            }
            if (defaultValue < min || defaultValue > max) {
                throw new IllegalArgumentException(
                        "defaultValue (" + defaultValue
                                + ") must lie in [" + min + ", " + max + "]");
            }
            Fader f = new Fader();
            // Order matters: set min/max first so value-clamp sees the
            // new range, defaultValue before value so a NaN-default value
            // can fall through to defaultValue.
            f.setMin(min);
            f.setMax(max);
            f.setDefaultValue(defaultValue);
            double v = Double.isNaN(value) ? defaultValue : value;
            f.setValue(v);
            f.setCurve(curve);
            f.setShowMeter(showMeter);
            f.setAnimated(animated);
            f.setUnit(unit);
            if (formatter != null) {
                f.setValueFormatter(formatter);
            }
            if (size != null) {
                f.getStyleClass().add("size-" + size);
            }
            return f;
        }
    }
}
