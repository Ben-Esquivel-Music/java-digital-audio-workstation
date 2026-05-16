package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.controls.skin.KnobSkin;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.css.CssMetaData;
import javafx.css.StyleConverter;
import javafx.css.Styleable;
import javafx.css.StyleableDoubleProperty;
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

/**
 * Rotary knob {@link Control} for bipolar / unipolar parameters
 * (pan, sends, plugin parameters).
 *
 * <p>Story 268. Sibling of {@link LevelMeter} (story 267) — the
 * {@code controls} package convention is one public {@link Control} subclass
 * + one {@code SkinBase} renderer + a self-contained package-resource
 * user-agent stylesheet so the control renders correctly outside the main
 * app stylesheet (e.g. inside a plugin GUI).
 *
 * <p>UI Design Book §5.8 (visual contract) and §2.8 (keyboard parity) are
 * the canonical specification. The control implements:
 * <ul>
 *   <li>Three size variants via style class — {@code .size-28},
 *       {@code .size-36}, {@code .size-48}.</li>
 *   <li>Bipolar mode (centre detent at 12 o'clock) for pan-style
 *       parameters via {@link #bipolarProperty()}.</li>
 *   <li>Value clamping at the property level — writes outside
 *       {@code [min, max]} are silently clamped to the range.</li>
 *   <li>Construction-time validation that {@code defaultValue} sits
 *       inside {@code [min, max]} (throws {@link IllegalArgumentException}).</li>
 *   <li>{@link AccessibleRole#SLIDER} — the closest standard role to a
 *       rotary parameter; screen readers narrate value changes via the
 *       skin updating the accessible text.</li>
 *   <li>Focus traversability enabled by default — required by §2.8
 *       keyboard parity (otherwise the keyboard contract is
 *       unobservable).</li>
 * </ul>
 *
 * <h2>Construction paths</h2>
 *
 * <p>The public no-arg constructor plus the standard property setters is
 * one fully-supported path. The fluent {@link #create()} builder is an
 * equivalent convenience path — neither is privileged:
 * <pre>{@code
 * Knob a = new Knob();
 * a.setMin(-1); a.setMax(1); a.setDefaultValue(0); a.setBipolar(true);
 *
 * Knob b = Knob.create()
 *         .min(-1).max(1).defaultValue(0).bipolar(true)
 *         .unit("L/R").size(28).build();
 * }</pre>
 *
 * <p>The {@link #valueFormatterProperty()} is intentionally a plain
 * {@link ObjectProperty}, not a {@link StyleableProperty} —
 * {@link CssMetaData} cannot express {@link Function} types, so this is
 * configured from Java only.
 */
public final class Knob extends Control {

    /** Stable style class — selectable as {@code .knob} in CSS. */
    public static final String DEFAULT_STYLE_CLASS = "knob";

    // ── Hard-coded Palette A fallbacks (UI Design Book) ───────────────────
    // Match knob.css and styles.css so the control renders correctly with
    // no application stylesheet loaded (plugin-GUI window case).
    private static final Color FALLBACK_DIAL = Color.web("#1D1F26");
    private static final Color FALLBACK_BORDER = Color.web("#2E323D");
    private static final Color FALLBACK_ARC = Color.web("#7C8CFF");
    private static final Color FALLBACK_TRACK = Color.web("#7A808C");
    private static final Color FALLBACK_INDICATOR = Color.web("#B7BCC7");
    private static final Color FALLBACK_FOCUS_RING = Color.web("#5C8CFF");

    // ── Plain observable properties ───────────────────────────────────────

    private final DoubleProperty min =
            new SimpleDoubleProperty(this, "min", 0.0) {
                @Override
                protected void invalidated() {
                    // Re-clamp current value when the range narrows.
                    double v = value.get();
                    double mn = get();
                    if (v < mn) {
                        value.set(mn);
                    }
                }
            };
    private final DoubleProperty max =
            new SimpleDoubleProperty(this, "max", 1.0) {
                @Override
                protected void invalidated() {
                    double v = value.get();
                    double mx = get();
                    if (v > mx) {
                        value.set(mx);
                    }
                }
            };
    private final DoubleProperty value =
            new SimpleDoubleProperty(this, "value", 0.0) {
                @Override
                public void set(double newValue) {
                    // Clamp at the property level so direct writes, binds,
                    // and skin-side mutations all observe the [min, max]
                    // invariant. Both endpoints are read through the
                    // property accessors so a clamp during a range change
                    // sees the new range.
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
    private final BooleanProperty bipolar =
            new SimpleBooleanProperty(this, "bipolar", false);
    private final StringProperty unit =
            new SimpleStringProperty(this, "unit", "");
    private final BooleanProperty animated =
            new SimpleBooleanProperty(this, "animated", true);

    /** Default formatter: 1-decimal numeric (UI Design Book §5.8). */
    public static final Function<Double, String> DEFAULT_FORMATTER =
            v -> String.format(Locale.ROOT, "%.1f", v);

    private final ObjectProperty<Function<Double, String>> valueFormatter =
            new SimpleObjectProperty<>(this, "valueFormatter", DEFAULT_FORMATTER) {
                @Override
                public void set(Function<Double, String> newValue) {
                    super.set(Objects.requireNonNull(newValue, "valueFormatter"));
                }
            };

    /**
     * Creates a knob with defaults: {@code min = 0}, {@code max = 1},
     * {@code value = 0}, {@code defaultValue = 0}, unipolar, no unit,
     * animated, 1-decimal formatter.
     */
    public Knob() {
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        setAccessibleRole(AccessibleRole.SLIDER);
        setAccessibleRoleDescription("Knob");
        setAccessibleText("Knob: 0.0");
        setFocusTraversable(true);
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new KnobSkin(this);
    }

    @Override
    public String getUserAgentStylesheet() {
        return Knob.class.getResource("knob.css").toExternalForm();
    }

    private double clampToRange(double v) {
        double mn = min.get();
        double mx = max.get();
        if (mn > mx) {
            // Degenerate range — leave the value alone; the test for
            // defaultValue in range will catch most user errors at
            // construction. A reversed range is not a supported
            // configuration but should not throw on every write.
            return v;
        }
        return Math.max(mn, Math.min(mx, v));
    }

    // ── value ─────────────────────────────────────────────────────────────

    public final DoubleProperty valueProperty() { return value; }
    public final double getValue() { return value.get(); }
    public final void setValue(double v) { value.set(v); }

    // ── min ───────────────────────────────────────────────────────────────

    public final DoubleProperty minProperty() { return min; }
    public final double getMin() { return min.get(); }
    public final void setMin(double v) { min.set(v); }

    // ── max ───────────────────────────────────────────────────────────────

    public final DoubleProperty maxProperty() { return max; }
    public final double getMax() { return max.get(); }
    public final void setMax(double v) { max.set(v); }

    // ── defaultValue ──────────────────────────────────────────────────────

    public final DoubleProperty defaultValueProperty() { return defaultValue; }
    public final double getDefaultValue() { return defaultValue.get(); }
    public final void setDefaultValue(double v) { defaultValue.set(v); }

    // ── bipolar ───────────────────────────────────────────────────────────

    public final BooleanProperty bipolarProperty() { return bipolar; }
    public final boolean isBipolar() { return bipolar.get(); }
    public final void setBipolar(boolean v) { bipolar.set(v); }

    // ── unit ──────────────────────────────────────────────────────────────

    public final StringProperty unitProperty() { return unit; }
    public final String getUnit() { return unit.get(); }
    public final void setUnit(String v) { unit.set(v == null ? "" : v); }

    // ── animated (Reduce Motion) ──────────────────────────────────────────

    /**
     * @return the {@code animated} property (default {@code true}). When
     *         {@code false} the skin's centre-detent spring is suppressed
     *         (UI Design Book §2.5, story 279 Reduce Motion).
     */
    public final BooleanProperty animatedProperty() { return animated; }
    public final boolean isAnimated() { return animated.get(); }
    public final void setAnimated(boolean v) { animated.set(v); }

    // ── valueFormatter (plain — not styleable) ────────────────────────────

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

    // ── Styleable colour & sensitivity properties ─────────────────────────

    private final StyleableObjectProperty<Color> knobDialColor =
            new StyleableObjectProperty<>(FALLBACK_DIAL) {
                @Override public Object getBean() { return Knob.this; }
                @Override public String getName() { return "knobDialColor"; }
                @Override public CssMetaData<Knob, Color> getCssMetaData() {
                    return StyleableProperties.DIAL_COLOR;
                }
            };
    private final StyleableObjectProperty<Color> knobBorderColor =
            new StyleableObjectProperty<>(FALLBACK_BORDER) {
                @Override public Object getBean() { return Knob.this; }
                @Override public String getName() { return "knobBorderColor"; }
                @Override public CssMetaData<Knob, Color> getCssMetaData() {
                    return StyleableProperties.BORDER_COLOR;
                }
            };
    private final StyleableObjectProperty<Color> knobArcColor =
            new StyleableObjectProperty<>(FALLBACK_ARC) {
                @Override public Object getBean() { return Knob.this; }
                @Override public String getName() { return "knobArcColor"; }
                @Override public CssMetaData<Knob, Color> getCssMetaData() {
                    return StyleableProperties.ARC_COLOR;
                }
            };
    private final StyleableObjectProperty<Color> knobTrackColor =
            new StyleableObjectProperty<>(FALLBACK_TRACK) {
                @Override public Object getBean() { return Knob.this; }
                @Override public String getName() { return "knobTrackColor"; }
                @Override public CssMetaData<Knob, Color> getCssMetaData() {
                    return StyleableProperties.TRACK_COLOR;
                }
            };
    private final StyleableObjectProperty<Color> knobIndicatorColor =
            new StyleableObjectProperty<>(FALLBACK_INDICATOR) {
                @Override public Object getBean() { return Knob.this; }
                @Override public String getName() { return "knobIndicatorColor"; }
                @Override public CssMetaData<Knob, Color> getCssMetaData() {
                    return StyleableProperties.INDICATOR_COLOR;
                }
            };
    private final StyleableObjectProperty<Color> knobFocusRingColor =
            new StyleableObjectProperty<>(FALLBACK_FOCUS_RING) {
                @Override public Object getBean() { return Knob.this; }
                @Override public String getName() { return "knobFocusRingColor"; }
                @Override public CssMetaData<Knob, Color> getCssMetaData() {
                    return StyleableProperties.FOCUS_RING_COLOR;
                }
            };
    /**
     * Vertical pixels of drag travel that cover the full value range.
     * Defaults to 200 px (UI Design Book §5.8). Exposed as a styleable
     * property so a noisy plugin (e.g. phase rotator) can override per
     * knob via {@code -knob-drag-sensitivity: 400;}.
     */
    private final StyleableDoubleProperty knobDragSensitivity =
            new StyleableDoubleProperty(200.0) {
                @Override public Object getBean() { return Knob.this; }
                @Override public String getName() { return "knobDragSensitivity"; }
                @Override public CssMetaData<Knob, Number> getCssMetaData() {
                    return StyleableProperties.DRAG_SENSITIVITY;
                }
            };

    public final ObjectProperty<Color> knobDialColorProperty() { return knobDialColor; }
    public final Color getKnobDialColor() { return knobDialColor.get(); }
    public final void setKnobDialColor(Color v) { knobDialColor.set(v); }

    public final ObjectProperty<Color> knobBorderColorProperty() { return knobBorderColor; }
    public final Color getKnobBorderColor() { return knobBorderColor.get(); }
    public final void setKnobBorderColor(Color v) { knobBorderColor.set(v); }

    public final ObjectProperty<Color> knobArcColorProperty() { return knobArcColor; }
    public final Color getKnobArcColor() { return knobArcColor.get(); }
    public final void setKnobArcColor(Color v) { knobArcColor.set(v); }

    public final ObjectProperty<Color> knobTrackColorProperty() { return knobTrackColor; }
    public final Color getKnobTrackColor() { return knobTrackColor.get(); }
    public final void setKnobTrackColor(Color v) { knobTrackColor.set(v); }

    public final ObjectProperty<Color> knobIndicatorColorProperty() { return knobIndicatorColor; }
    public final Color getKnobIndicatorColor() { return knobIndicatorColor.get(); }
    public final void setKnobIndicatorColor(Color v) { knobIndicatorColor.set(v); }

    public final ObjectProperty<Color> knobFocusRingColorProperty() { return knobFocusRingColor; }
    public final Color getKnobFocusRingColor() { return knobFocusRingColor.get(); }
    public final void setKnobFocusRingColor(Color v) { knobFocusRingColor.set(v); }

    public final DoubleProperty knobDragSensitivityProperty() { return knobDragSensitivity; }
    public final double getKnobDragSensitivity() { return knobDragSensitivity.get(); }
    public final void setKnobDragSensitivity(double v) { knobDragSensitivity.set(v); }

    // ── CssMetaData ───────────────────────────────────────────────────────

    private static final class StyleableProperties {

        static final CssMetaData<Knob, Color> DIAL_COLOR =
                new CssMetaData<>("-knob-dial-color",
                        StyleConverter.getColorConverter(), FALLBACK_DIAL) {
                    @Override public boolean isSettable(Knob k) {
                        return !k.knobDialColor.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(Knob k) {
                        return k.knobDialColor;
                    }
                };
        static final CssMetaData<Knob, Color> BORDER_COLOR =
                new CssMetaData<>("-knob-border-color",
                        StyleConverter.getColorConverter(), FALLBACK_BORDER) {
                    @Override public boolean isSettable(Knob k) {
                        return !k.knobBorderColor.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(Knob k) {
                        return k.knobBorderColor;
                    }
                };
        static final CssMetaData<Knob, Color> ARC_COLOR =
                new CssMetaData<>("-knob-arc-color",
                        StyleConverter.getColorConverter(), FALLBACK_ARC) {
                    @Override public boolean isSettable(Knob k) {
                        return !k.knobArcColor.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(Knob k) {
                        return k.knobArcColor;
                    }
                };
        static final CssMetaData<Knob, Color> TRACK_COLOR =
                new CssMetaData<>("-knob-track-color",
                        StyleConverter.getColorConverter(), FALLBACK_TRACK) {
                    @Override public boolean isSettable(Knob k) {
                        return !k.knobTrackColor.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(Knob k) {
                        return k.knobTrackColor;
                    }
                };
        static final CssMetaData<Knob, Color> INDICATOR_COLOR =
                new CssMetaData<>("-knob-indicator-color",
                        StyleConverter.getColorConverter(), FALLBACK_INDICATOR) {
                    @Override public boolean isSettable(Knob k) {
                        return !k.knobIndicatorColor.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(Knob k) {
                        return k.knobIndicatorColor;
                    }
                };
        static final CssMetaData<Knob, Color> FOCUS_RING_COLOR =
                new CssMetaData<>("-knob-focus-ring-color",
                        StyleConverter.getColorConverter(), FALLBACK_FOCUS_RING) {
                    @Override public boolean isSettable(Knob k) {
                        return !k.knobFocusRingColor.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(Knob k) {
                        return k.knobFocusRingColor;
                    }
                };
        static final CssMetaData<Knob, Number> DRAG_SENSITIVITY =
                new CssMetaData<>("-knob-drag-sensitivity",
                        StyleConverter.getSizeConverter(), 200.0) {
                    @Override public boolean isSettable(Knob k) {
                        return !k.knobDragSensitivity.isBound();
                    }
                    @Override public StyleableProperty<Number> getStyleableProperty(Knob k) {
                        return k.knobDragSensitivity;
                    }
                };

        static final List<CssMetaData<? extends Styleable, ?>> CSS_META_DATA;

        static {
            List<CssMetaData<? extends Styleable, ?>> list =
                    new ArrayList<>(Control.getClassCssMetaData());
            Collections.addAll(list,
                    DIAL_COLOR, BORDER_COLOR, ARC_COLOR, TRACK_COLOR,
                    INDICATOR_COLOR, FOCUS_RING_COLOR, DRAG_SENSITIVITY);
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

    /**
     * Fluent builder for {@link Knob}. {@code build()} validates that
     * {@code defaultValue} lies in {@code [min, max]} —
     * {@link IllegalArgumentException} on violation.
     */
    public static final class Builder {

        private double min = 0.0;
        private double max = 1.0;
        private double value = Double.NaN;
        private double defaultValue = 0.0;
        private boolean bipolar = false;
        private String unit = "";
        private boolean animated = true;
        private int sizePx = -1;
        private Function<Double, String> formatter;

        private Builder() {
        }

        public Builder min(double v) { this.min = v; return this; }
        public Builder max(double v) { this.max = v; return this; }
        public Builder value(double v) { this.value = v; return this; }
        public Builder defaultValue(double v) { this.defaultValue = v; return this; }
        public Builder bipolar(boolean v) { this.bipolar = v; return this; }
        public Builder unit(String v) {
            this.unit = v == null ? "" : v;
            return this;
        }
        public Builder animated(boolean v) { this.animated = v; return this; }

        /**
         * @param px the size variant in pixels: 28, 36, or 48
         * @return this builder
         * @throws IllegalArgumentException if {@code px} is not 28, 36 or 48
         */
        public Builder size(int px) {
            if (px != 28 && px != 36 && px != 48) {
                throw new IllegalArgumentException(
                        "size must be 28, 36, or 48 (got " + px + ")");
            }
            this.sizePx = px;
            return this;
        }
        public Builder valueFormatter(Function<Double, String> f) {
            this.formatter = Objects.requireNonNull(f, "valueFormatter");
            return this;
        }

        /**
         * @return a fully configured {@link Knob}.
         * @throws IllegalArgumentException if {@code defaultValue} is
         *         outside {@code [min, max]} or {@code min > max}.
         */
        public Knob build() {
            if (min > max) {
                throw new IllegalArgumentException(
                        "min (" + min + ") must be <= max (" + max + ")");
            }
            if (defaultValue < min || defaultValue > max) {
                throw new IllegalArgumentException(
                        "defaultValue (" + defaultValue
                                + ") must lie in [" + min + ", " + max + "]");
            }
            Knob k = new Knob();
            // Order matters: set min/max first so the value clamp works.
            k.setMin(min);
            k.setMax(max);
            k.setDefaultValue(defaultValue);
            double v = Double.isNaN(value) ? defaultValue : value;
            k.setValue(v);
            k.setBipolar(bipolar);
            k.setUnit(unit);
            k.setAnimated(animated);
            if (formatter != null) {
                k.setValueFormatter(formatter);
            }
            if (sizePx > 0) {
                k.getStyleClass().add("size-" + sizePx);
            }
            return k;
        }
    }
}
