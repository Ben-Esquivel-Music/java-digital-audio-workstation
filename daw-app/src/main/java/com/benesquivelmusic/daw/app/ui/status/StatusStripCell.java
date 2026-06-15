package com.benesquivelmusic.daw.app.ui.status;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;

import java.util.Objects;

/**
 * Session Status Strip cell {@link Control} — one text fact in the §4.4
 * status strip (e.g. {@code SR 48k}, {@code BUF 256}, {@code DISK OK}).
 *
 * <p>Story 295. Project Manager Design Book §4.4: the strip is a row of
 * discrete facts and "each cell is itself a small {@code Control}
 * ({@code StatusStripCell}) so themes can restyle it without touching the
 * strip." Following the same {@code Control + SkinBase + package-resource
 * user-agent stylesheet} convention as the Phase 2 controls
 * ({@code MixerChannelStrip}, {@code Knob}, {@code Fader}, …) and the
 * {@code javafx-application-design} skill §3 (Control/Skin split) and §8
 * (themeable via a user-agent stylesheet plus stable style classes).
 *
 * <h2>Binding-only contract</h2>
 *
 * <p>The cell is a <strong>binding-only</strong> surface. The strip binds
 * {@link #textProperty()} to its model and nothing ever calls
 * {@code Label.setText} — the skin ({@link StatusStripCellSkin}) binds the
 * inner label's text via {@code bind(...)}. The {@link #setText(String)}
 * declaration below mutates the cell's own {@link StringProperty} (so the
 * convenience constructor and direct callers still work) and is <em>not</em>
 * a label mutation. This keeps the source free of any {@code Label.setText}
 * call, which the story's conformance test source-scans for.
 *
 * <h2>Severity</h2>
 *
 * <p>{@link #severityProperty()} drives the §6.2 disk amber/red treatment by
 * flipping <em>style classes on the control itself</em> (not pseudo-classes,
 * and not via the skin) so the visual state is correct even in a headless,
 * unrealized-skin test. {@link Severity#NORMAL} carries no extra class;
 * {@link Severity#WARN} adds {@link #SEVERITY_WARN_CLASS}; and
 * {@link Severity#CRITICAL} adds {@link #SEVERITY_CRITICAL_CLASS}. The
 * matching {@code -fx-text-fill} rules live in {@code status-strip-cell.css}.
 *
 * @see StatusStripCellSkin
 */
public final class StatusStripCell extends Control {

    /** Stable style class — selectable as {@code .status-strip-cell} in CSS. */
    public static final String DEFAULT_STYLE_CLASS = "status-strip-cell";

    /**
     * Style class added when {@link #severityProperty()} is
     * {@link Severity#WARN} (the §6.2 disk amber treatment). Public so the
     * strip and the conformance tests can reference the exact name.
     */
    public static final String SEVERITY_WARN_CLASS = "status-cell-warn";

    /**
     * Style class added when {@link #severityProperty()} is
     * {@link Severity#CRITICAL} (the §6.2 disk red treatment). Public so the
     * strip and the conformance tests can reference the exact name.
     */
    public static final String SEVERITY_CRITICAL_CLASS = "status-cell-critical";

    /**
     * Cell severity. {@link #NORMAL} adds no extra class; {@link #WARN} adds
     * {@link #SEVERITY_WARN_CLASS}; {@link #CRITICAL} adds
     * {@link #SEVERITY_CRITICAL_CLASS}.
     */
    public enum Severity {
        /** Default — no extra style class, text fill is {@code -ssc-text}. */
        NORMAL,
        /** Warning — adds {@link #SEVERITY_WARN_CLASS} ({@code -ssc-warn}). */
        WARN,
        /** Critical — adds {@link #SEVERITY_CRITICAL_CLASS} ({@code -ssc-critical}). */
        CRITICAL
    }

    private final StringProperty text =
            new SimpleStringProperty(this, "text", "");

    /**
     * Severity property whose {@code invalidated()} reconciles the
     * severity style classes directly on this control (headless-safe — it
     * does not rely on the skin) and whose {@code set(...)} coerces a
     * {@code null} to {@link Severity#NORMAL}.
     */
    private final ObjectProperty<Severity> severity =
            new SimpleObjectProperty<>(this, "severity", Severity.NORMAL) {
                @Override
                public void set(Severity newValue) {
                    super.set(newValue == null ? Severity.NORMAL : newValue);
                }

                @Override
                protected void invalidated() {
                    // Remove both first (guards against duplicate adds), then
                    // add at most one — mirrors MixerChannelStrip's invalidated()
                    // pseudo-class flip, but with getStyleClass() add/remove so
                    // the state is correct without a realized skin.
                    getStyleClass().removeAll(SEVERITY_WARN_CLASS, SEVERITY_CRITICAL_CLASS);
                    Severity s = get();
                    if (s == Severity.WARN) {
                        getStyleClass().add(SEVERITY_WARN_CLASS);
                    } else if (s == Severity.CRITICAL) {
                        getStyleClass().add(SEVERITY_CRITICAL_CLASS);
                    }
                }
            };

    /** Creates an empty cell ({@link Severity#NORMAL}, blank text). */
    public StatusStripCell() {
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        setAccessibleRole(AccessibleRole.TEXT);
        setFocusTraversable(false);
    }

    /**
     * Creates a cell with initial text ({@link Severity#NORMAL}).
     *
     * @param text the initial fact text (a {@code null} becomes {@code ""}
     *             via the underlying property's behaviour)
     */
    public StatusStripCell(String text) {
        this();
        this.text.set(text);
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new StatusStripCellSkin(this);
    }

    @Override
    public String getUserAgentStylesheet() {
        return Objects.requireNonNull(
                StatusStripCell.class.getResource("status-strip-cell.css"),
                "status-strip-cell.css not on classpath").toExternalForm();
    }

    // ── text ──────────────────────────────────────────────────────────────

    /** @return the fact-text property; bound by the skin's label via {@code bind(...)}. */
    public final StringProperty textProperty() {
        return text;
    }

    /** @return the fact text. */
    public final String getText() {
        return text.get();
    }

    /**
     * Sets the cell's fact text. Mutates this control's own
     * {@link StringProperty} (not a {@code Label}); the skin's label observes
     * it via a one-way bind, so the binding-only contract holds.
     *
     * @param text the fact text
     */
    public final void setText(String text) {
        this.text.set(text);
    }

    // ── severity ──────────────────────────────────────────────────────────

    /**
     * @return the severity property. Setting it (or invalidating it)
     *         reconciles the {@link #SEVERITY_WARN_CLASS} /
     *         {@link #SEVERITY_CRITICAL_CLASS} style classes on this control.
     */
    public final ObjectProperty<Severity> severityProperty() {
        return severity;
    }

    /** @return the current severity (never {@code null}). */
    public final Severity getSeverity() {
        return severity.get();
    }

    /**
     * @param severity the severity; a {@code null} is coerced to
     *                 {@link Severity#NORMAL}
     */
    public final void setSeverity(Severity severity) {
        this.severity.set(severity);
    }
}
