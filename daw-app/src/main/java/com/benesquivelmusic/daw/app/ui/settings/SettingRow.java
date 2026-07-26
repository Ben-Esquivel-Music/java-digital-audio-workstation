package com.benesquivelmusic.daw.app.ui.settings;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The generic setting row — the §5.4 "workhorse" of the story-306 Rail &amp;
 * Pane settings shell (Settings View Design Book §5.4). One row type renders
 * <em>every</em> {@link SettingDescriptor} from the story-305
 * {@link SettingsCatalogue} by {@link ControlKind}: label column, kind-specific
 * editor, muted description line, apply-class badge (omitted for
 * {@link ApplyClass#LIVE}, §6.4) and the per-setting reset ↺ (§5.7 tier 1).
 *
 * <p>Follows the {@code Control + SkinBase + package-resource user-agent
 * stylesheet} convention of {@link com.benesquivelmusic.daw.app.ui.hub.ProjectCard}
 * ({@code javafx-application-design} skill §3). All state the shell reacts to
 * — pending {@link #valueProperty() value}, {@link #modifiedProperty()
 * modified}, {@link #highlightQueryProperty() highlight query},
 * {@link #armedProperty() armed} — lives on this control; {@link SettingRowSkin}
 * only renders it.</p>
 *
 * <h2>Pending value, not persisted value</h2>
 *
 * <p>{@link #valueProperty()} holds the row's <em>pending</em> value (§6.2 —
 * dirty/apply generalised). The row never writes any model or manager; the
 * shell diffs pending values against the persisted ones and applies on the
 * footer's Apply/OK. {@link #modifiedProperty()} compares against the
 * descriptor's {@link SettingDescriptor#defaultValue() default} (not the
 * persisted value) — it drives the §5.7 tier-1 reset marker only.</p>
 *
 * <h2>Key-capture arming (§6.5, rejection #7)</h2>
 *
 * <p>For {@link ControlKind#KEY_CAPTURE} rows, {@link #armedProperty()} gates
 * capture: the skin's key filter lives on the capture field only and consumes
 * <em>nothing</em> while disarmed, so ordinary navigation (Up/Down/Tab) is
 * never globally swallowed. For every other kind the property is inert.</p>
 *
 * @see SettingRowSkin
 * @see SettingDescriptor
 */
public final class SettingRow extends Control {

    /** Stable style class — selectable as {@code .setting-row}. */
    public static final String DEFAULT_STYLE_CLASS = "setting-row";

    /**
     * Optional per-kind render hints the descriptor does not carry (§5.4).
     * All components are nullable / best-effort; {@link #none()} is the
     * hint-free default. The badge text is passed in pre-localized because
     * the row deliberately never reaches the {@code Messages} bundle — the
     * shell owns all i18n resolution.
     *
     * @param choiceOptions       {@link ControlKind#CHOICE} options in the
     *                            row's <em>value type</em> (so
     *                            {@code Objects.equals} works against
     *                            persisted values); may be {@code null}
     * @param sliderMin           {@link ControlKind#SLIDER} lower bound (also
     *                            reused as the {@link ControlKind#STEPPER}
     *                            bound when explicitly set)
     * @param sliderMax           {@link ControlKind#SLIDER} upper bound
     * @param converter           display text for CHOICE options; may be
     *                            {@code null} (falls back to
     *                            {@code toString()})
     * @param badgeText           localized apply-class badge text;
     *                            {@code null} for {@link ApplyClass#LIVE}
     *                            (badge omitted entirely, §6.4)
     * @param resetGraphicFactory per-row ↺ graphic factory; {@code null}
     *                            falls back to the text glyph U+21BA
     */
    public record ControlHints(
            List<?> choiceOptions,
            double sliderMin, double sliderMax,
            StringConverter<Object> converter,
            String badgeText,
            Supplier<Node> resetGraphicFactory) {

        private static final ControlHints NONE =
                new ControlHints(null, 0, 1, null, null, null);

        /** @return the hint-free default {@code (null, 0, 1, null, null, null)} */
        public static ControlHints none() {
            return NONE;
        }
    }

    private final SettingDescriptor<?> descriptor;
    private final ControlHints hints;

    /**
     * True ⇔ the pending value differs from the descriptor default (§5.7
     * tier 1), compared through {@link #canonicalComparisonValue} so a
     * TEXT row's numeric String agrees with its boxed default. Declared
     * before {@link #value} so the value property's {@code invalidated()}
     * can update it.
     */
    private final ReadOnlyBooleanWrapper modified =
            new ReadOnlyBooleanWrapper(this, "modified", false);

    private final ObjectProperty<Object> value;

    private final StringProperty highlightQuery =
            new SimpleStringProperty(this, "highlightQuery", "");

    private final BooleanProperty armed =
            new SimpleBooleanProperty(this, "armed", false);

    /**
     * Creates a row for {@code descriptor} seeded with the persisted
     * {@code initialValue}. The editor kind is chosen once, from
     * {@link SettingDescriptor#controlKind()}, when the skin is built.
     *
     * @param descriptor   the story-305 descriptor this row renders; not null
     * @param initialValue the persisted value at open time; may be
     *                     {@code null} (e.g. an unbound key binding)
     * @param hints        render hints; {@code null} is coerced to
     *                     {@link ControlHints#none()}
     */
    public SettingRow(SettingDescriptor<?> descriptor, Object initialValue,
                      ControlHints hints) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.hints = hints == null ? ControlHints.none() : hints;
        this.value = new SimpleObjectProperty<>(this, "value", initialValue) {
            @Override
            protected void invalidated() {
                modified.set(!Objects.equals(
                        canonicalComparisonValue(descriptor, get()),
                        descriptor.defaultValue()));
            }
        };
        // The property ctor does not run invalidated() — seed modified here.
        modified.set(!Objects.equals(
                canonicalComparisonValue(descriptor, initialValue),
                descriptor.defaultValue()));
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        // §6.5: the row itself is not a Tab stop — its inner editor is.
        setFocusTraversable(false);
    }

    /** @return the descriptor this row renders */
    public SettingDescriptor<?> descriptor() {
        return descriptor;
    }

    /** @return the render hints (never {@code null}) — read by the skin */
    ControlHints hints() {
        return hints;
    }

    /**
     * @return the row's current (pending) value — two-way with the inner
     *         control via listener + setter (never {@code bind()}, per the
     *         two-way-control rule)
     */
    public ObjectProperty<Object> valueProperty() {
        return value;
    }

    /** @return the current pending value; may be {@code null} */
    public Object getValue() {
        return value.get();
    }

    /** @param v the new pending value; may be {@code null} */
    public void setValue(Object v) {
        value.set(v);
    }

    /**
     * @return true ⇔ {@code !Objects.equals(value, descriptor.defaultValue())}
     *         after {@link #canonicalComparisonValue} coercion (§5.7 tier 1)
     *         — drives the reset button's visible + managed
     */
    public ReadOnlyBooleanProperty modifiedProperty() {
        return modified.getReadOnlyProperty();
    }

    /** Resets the pending value to {@link SettingDescriptor#defaultValue()}. */
    public void resetToDefault() {
        setValue(descriptor.defaultValue());
    }

    /**
     * Canonicalizes {@code value} for equality comparison against the
     * descriptor's default/persisted values. A {@link ControlKind#TEXT}
     * editor delivers raw {@link String} edits even when the descriptor's
     * canonical value type is a boxed {@link Double} (the catalogue's
     * {@code project.defaultTempo} — the only such descriptor), so a
     * parseable String coerces to Double before {@code Objects.equals};
     * unparseable text stays a String (a real, silently-droppable edit).
     * Every other kind/value passes through untouched — those editors
     * already produce the persisted boxed types. Shared with the shell's
     * pending-edit diff so the ↺ marker and the dirty state always agree.
     */
    static Object canonicalComparisonValue(SettingDescriptor<?> descriptor, Object value) {
        if (value instanceof String text
                && descriptor.controlKind() == ControlKind.TEXT
                && descriptor.defaultValue() instanceof Double) {
            try {
                // Same parse as the apply path (SettingsDialog.applyDefaultTempo).
                return Double.valueOf(text);
            } catch (NumberFormatException unparseable) {
                return value;
            }
        }
        return value;
    }

    /**
     * @return the §6.1 search highlight query ({@code ""} = none); when it
     *         matches the label (case/diacritic-insensitive) the skin renders
     *         the matching span emphasized
     */
    public StringProperty highlightQueryProperty() {
        return highlightQuery;
    }

    /**
     * @return the {@link ControlKind#KEY_CAPTURE} arming gate (§6.5,
     *         rejection #7): capture is active only while armed; inert for
     *         every other kind
     */
    public BooleanProperty armedProperty() {
        return armed;
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new SettingRowSkin(this);
    }

    @Override
    public String getUserAgentStylesheet() {
        return Objects.requireNonNull(
                SettingRow.class.getResource("setting-row.css"),
                "setting-row.css not on classpath").toExternalForm();
    }
}
