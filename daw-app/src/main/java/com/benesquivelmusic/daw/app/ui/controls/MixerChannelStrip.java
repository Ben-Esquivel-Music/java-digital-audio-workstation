package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.CssMetaData;
import javafx.css.PseudoClass;
import javafx.css.StyleConverter;
import javafx.css.Styleable;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.event.EventTarget;
import javafx.event.EventType;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Mixer channel-strip {@link Control} — the complete §5.4 signal-chain
 * surface for one mixer channel: I/O labels, inserts list, sends list,
 * pan knob, fader (with integrated meter), M/S/R buttons and name.
 *
 * <p>Story 271. Phase 2 of the UI Design Book §6 migration roadmap.
 * Replaces the hand-rolled {@code .mixer-channel} {@code VBox} rendering
 * scattered across {@code MixerView} and friends. Follows the same
 * {@code Control + SkinBase + StyleableProperty + package-resource
 * user-agent stylesheet} convention as {@link LevelMeter} (story 267),
 * {@link Knob} (story 268), {@link Fader} (story 269) and
 * {@link TrackStrip} (story 270), composing those Phase 2 sub-controls
 * into the §5.4 layout.
 *
 * <h2>Visual contract (UI Design Book §5.4)</h2>
 *
 * <ul>
 *   <li>Vertical strip laid out top → bottom: I/O caption · inserts
 *       (4 + overflow) · sends (2 + overflow) · pan knob · fader + meter ·
 *       M/S/R · value readout · name.</li>
 *   <li><strong>No card border, no shadow, no rounded corners</strong> on
 *       the strip itself — visual separation between adjacent strips is a
 *       4&nbsp;px gutter at {@code -mcs-surface-bg} (the same colour as the
 *       panel background). The mixer panel sits at elevation 0
 *       (story 263).</li>
 *   <li>72&nbsp;px wide Compact / 88&nbsp;px wide Comfortable (selected by
 *       the density mode in story 278 via {@code .density-compact} /
 *       {@code .density-comfortable}). Width is enforced from the Skin
 *       (Java), not CSS, to avoid a token-validation linter mismatch with
 *       the 4&nbsp;px grid.</li>
 *   <li>The M/S/R buttons reuse story 270's exact CSS classes
 *       ({@code .track-toggle.mute|solo|arm}) for visual consistency.</li>
 *   <li>When {@link #channelTypeProperty()} is
 *       {@link ChannelType#MASTER} the M/S/R buttons are not added to the
 *       scene graph at all; when {@link ChannelType#BUS} the input
 *       selector/label is omitted (UI Design Book §5.4).</li>
 * </ul>
 *
 * <h2>{@code UUID} vs {@code ChannelId} (deviation from story text)</h2>
 *
 * <p>The story's Goals literally specify
 * {@code ObjectProperty<ChannelId>}. This control uses
 * {@link java.util.UUID} instead — <strong>a deliberate, documented
 * deviation</strong>, mirroring exactly the decision {@link TrackStrip}
 * made for its analogous {@code trackId}: the existing mixer code keys
 * channels by {@code UUID} ({@code MixerView} maintains
 * {@code Map<UUID, MixerChannel>} and parses channel ids to {@code UUID}),
 * and introducing a parallel {@code ChannelId} wrapper would force every
 * consumer to translate at the boundary for no behavioural gain. The
 * channel id is the {@code UUID} the mixer model already uses.
 *
 * <h2>Slot events</h2>
 *
 * <p>Clicking an insert or send slot row inside the skin fires a typed
 * {@link InsertSelectedEvent} / {@link SendSelectedEvent} via
 * {@link #fireEvent(Event)} so the event bubbles through the scene graph
 * normally and integrates with the standard event dispatch chain
 * (Skill §12). Story 272's Inspector consumes these via the standard
 * dispatch chain; prefer {@link #setOnInsertSelected(EventHandler)} /
 * {@link #setOnSendSelected(EventHandler)} over ad-hoc callbacks.
 *
 * <h2>Construction paths</h2>
 *
 * <p>The public no-arg constructor with the standard property setters and
 * the fluent {@link #create()} builder are independent, equally supported
 * paths:
 *
 * <pre>{@code
 * MixerChannelStrip a = new MixerChannelStrip();
 * a.setChannelName("Drums");
 * a.setChannelType(MixerChannelStrip.ChannelType.AUDIO);
 *
 * MixerChannelStrip b = MixerChannelStrip.create()
 *         .channelId(UUID.randomUUID())
 *         .name("Drums")
 *         .channelType(MixerChannelStrip.ChannelType.AUDIO)
 *         .size("compact")
 *         .build();
 * }</pre>
 */
public final class MixerChannelStrip extends Control {

    /**
     * Stable style class — selectable as {@code .mixer-channel-strip} in
     * CSS. (The story refers to the alias target as
     * {@code .dawg-mixer-channel-strip}; the actual stable class is the
     * unprefixed {@code mixer-channel-strip}, matching the
     * {@code .track-strip} precedent from story 270.)
     */
    public static final String DEFAULT_STYLE_CLASS = "mixer-channel-strip";

    /** Pseudo-class flipped on {@link #mutedProperty()}. */
    static final PseudoClass PSEUDO_MUTED = PseudoClass.getPseudoClass("muted");
    /** Pseudo-class flipped on {@link #soloedProperty()}. */
    static final PseudoClass PSEUDO_SOLOED = PseudoClass.getPseudoClass("soloed");
    /** Pseudo-class flipped on {@link #armedProperty()}. */
    static final PseudoClass PSEUDO_ARMED = PseudoClass.getPseudoClass("armed");

    /**
     * Channel category. JavaFX has no {@code EnumProperty}, so
     * {@link #channelTypeProperty()} is a
     * {@link SimpleObjectProperty}{@code <ChannelType>}. Co-located in this
     * control (like {@link Fader.TravelCurve}) — {@code daw-app} is
     * non-modular so package placement is free and keeping the strip's
     * vocabulary self-contained matches the controls-package convention.
     */
    public enum ChannelType {
        /** A regular audio track channel — full M/S/R + I/O. */
        AUDIO,
        /** A MIDI/instrument channel — full M/S/R + I/O. */
        MIDI,
        /** A return/group bus — no input selector (§5.4). */
        BUS,
        /** The master bus — no M/S/R buttons (§5.4 / §7.5). */
        MASTER
    }

    // ── Palette A fallback colours (UI Design Book §5.4) ──────────────────
    private static final Color FALLBACK_SURFACE_1 = Color.web("#15161B");
    private static final Color FALLBACK_SURFACE_3 = Color.web("#272A33");
    private static final Color FALLBACK_SURFACE_BG = Color.web("#0B0B0E");
    private static final Color FALLBACK_ACCENT = Color.web("#7C8CFF");
    private static final Color FALLBACK_ACCENT_SOFT = Color.web("rgba(124, 140, 255, 0.14)");
    private static final Color FALLBACK_DANGER = Color.web("#E5484D");
    private static final Color FALLBACK_WARN = Color.web("#E6B450");
    private static final Color FALLBACK_TEXT = Color.web("#B7BCC7");
    private static final Color FALLBACK_TEXT_HI = Color.web("#ECEEF2");
    private static final Color FALLBACK_TEXT_MUTE = Color.web("#7A808C");
    private static final Color FALLBACK_LINE_SOFT = Color.web("#22242C");

    // ── Plain observable properties ───────────────────────────────────────

    private final ObjectProperty<UUID> channelId =
            new SimpleObjectProperty<>(this, "channelId", null);
    private final StringProperty channelName =
            new SimpleStringProperty(this, "channelName", "");
    private final StringProperty inputLabel =
            new SimpleStringProperty(this, "inputLabel", "");
    private final StringProperty outputLabel =
            new SimpleStringProperty(this, "outputLabel", "");

    private final ObservableList<InsertSlotModel> inserts =
            FXCollections.observableArrayList();
    private final ObservableList<SendSlotModel> sends =
            FXCollections.observableArrayList();

    private final DoubleProperty pan =
            new SimpleDoubleProperty(this, "pan", 0.0);
    private final DoubleProperty faderDb =
            new SimpleDoubleProperty(this, "faderDb", 0.0);

    private final BooleanProperty muted =
            new SimpleBooleanProperty(this, "muted", false) {
                @Override
                protected void invalidated() {
                    pseudoClassStateChanged(PSEUDO_MUTED, get());
                }
            };
    private final BooleanProperty soloed =
            new SimpleBooleanProperty(this, "soloed", false) {
                @Override
                protected void invalidated() {
                    pseudoClassStateChanged(PSEUDO_SOLOED, get());
                }
            };
    private final BooleanProperty armed =
            new SimpleBooleanProperty(this, "armed", false) {
                @Override
                protected void invalidated() {
                    pseudoClassStateChanged(PSEUDO_ARMED, get());
                }
            };

    private final ObjectProperty<ChannelType> channelType =
            new SimpleObjectProperty<>(this, "channelType", ChannelType.AUDIO) {
                @Override
                public void set(ChannelType newValue) {
                    super.set(Objects.requireNonNull(newValue, "channelType"));
                }
            };

    /**
     * Creates a channel strip with defaults: no channel id, empty name /
     * I/O labels, empty inserts / sends lists, pan {@code 0.0} (centre),
     * fader {@code 0.0 dB}, M/S/R all false, {@link ChannelType#AUDIO}.
     */
    public MixerChannelStrip() {
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        setAccessibleRole(AccessibleRole.PARENT);
        setAccessibleRoleDescription("Mixer channel strip");
        setFocusTraversable(true);
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new MixerChannelStripSkin(this);
    }

    @Override
    public String getUserAgentStylesheet() {
        return Objects.requireNonNull(
                MixerChannelStrip.class.getResource("mixer-channel-strip.css"),
                "mixer-channel-strip.css not on classpath").toExternalForm();
    }

    // ── channelId ─────────────────────────────────────────────────────────

    /** @return the channel identifier property (the mixer model's {@link UUID}). */
    public final ObjectProperty<UUID> channelIdProperty() { return channelId; }
    /** @return the channel identifier, or {@code null} if unbound. */
    public final UUID getChannelId() { return channelId.get(); }
    /** @param id the channel identifier (may be {@code null}). */
    public final void setChannelId(UUID id) { channelId.set(id); }

    // ── channelName ───────────────────────────────────────────────────────

    /** @return the channel display-name property. */
    public final StringProperty channelNameProperty() { return channelName; }
    /** @return the channel display name. */
    public final String getChannelName() { return channelName.get(); }
    /** @param name the channel display name. */
    public final void setChannelName(String name) { channelName.set(name); }

    // ── inputLabel / outputLabel ──────────────────────────────────────────

    /** @return the input-routing caption property (small mono, §3.2). */
    public final StringProperty inputLabelProperty() { return inputLabel; }
    /** @return the input-routing caption. */
    public final String getInputLabel() { return inputLabel.get(); }
    /** @param label the input-routing caption. */
    public final void setInputLabel(String label) { inputLabel.set(label); }

    /** @return the output-routing caption property (small mono, §3.2). */
    public final StringProperty outputLabelProperty() { return outputLabel; }
    /** @return the output-routing caption. */
    public final String getOutputLabel() { return outputLabel.get(); }
    /** @param label the output-routing caption. */
    public final void setOutputLabel(String label) { outputLabel.set(label); }

    // ── inserts / sends ───────────────────────────────────────────────────

    /**
     * @return the live, mutable inserts list. The skin renders the first
     *         4 entries plus a {@code ⋯} overflow indicator and reflows on
     *         list mutation.
     */
    public final ObservableList<InsertSlotModel> insertsProperty() { return inserts; }

    /**
     * @return the live, mutable sends list. The skin renders the first
     *         2 entries plus a {@code ⋯} overflow indicator and reflows on
     *         list mutation.
     */
    public final ObservableList<SendSlotModel> sendsProperty() { return sends; }

    // ── pan / faderDb ─────────────────────────────────────────────────────

    /**
     * @return the bipolar pan property in {@code [-1, 1]}. Two-way synced
     *         to the skin's {@link Knob} (centre detent at 0).
     */
    public final DoubleProperty panProperty() { return pan; }
    /** @return the pan position in {@code [-1, 1]}. */
    public final double getPan() { return pan.get(); }
    /** @param value the pan position in {@code [-1, 1]}. */
    public final void setPan(double value) { pan.set(value); }

    /**
     * @return the fader value property, in dB. Two-way synced to the
     *         skin's {@link Fader} ({@link Fader.TravelCurve#LOG_DB}).
     */
    public final DoubleProperty faderDbProperty() { return faderDb; }
    /** @return the fader value in dB. */
    public final double getFaderDb() { return faderDb.get(); }
    /** @param db the fader value in dB. */
    public final void setFaderDb(double db) { faderDb.set(db); }

    // ── muted / soloed / armed ────────────────────────────────────────────

    /** @return the muted gating property; flips the {@code :muted} pseudo-class. */
    public final BooleanProperty mutedProperty() { return muted; }
    /** @return whether the channel is muted. */
    public final boolean isMuted() { return muted.get(); }
    /** @param value whether the channel is muted. */
    public final void setMuted(boolean value) { muted.set(value); }

    /** @return the soloed gating property; flips the {@code :soloed} pseudo-class. */
    public final BooleanProperty soloedProperty() { return soloed; }
    /** @return whether the channel is soloed. */
    public final boolean isSoloed() { return soloed.get(); }
    /** @param value whether the channel is soloed. */
    public final void setSoloed(boolean value) { soloed.set(value); }

    /** @return the armed gating property; flips the {@code :armed} pseudo-class. */
    public final BooleanProperty armedProperty() { return armed; }
    /** @return whether the channel is record-armed. */
    public final boolean isArmed() { return armed.get(); }
    /** @param value whether the channel is record-armed. */
    public final void setArmed(boolean value) { armed.set(value); }

    // ── channelType ───────────────────────────────────────────────────────

    /**
     * @return the channel-type property. JavaFX has no {@code EnumProperty};
     *         this is a {@link SimpleObjectProperty}. Controls visibility
     *         of the M/S/R buttons (absent for {@link ChannelType#MASTER})
     *         and the input selector (absent for {@link ChannelType#BUS}).
     */
    public final ObjectProperty<ChannelType> channelTypeProperty() { return channelType; }
    /** @return the channel type (never {@code null}). */
    public final ChannelType getChannelType() { return channelType.get(); }
    /** @param type the channel type; must not be {@code null}. */
    public final void setChannelType(ChannelType type) { channelType.set(type); }

    // ── Styleable colour properties (consume root-pane tokens) ────────────
    //
    // The CssMetaData uses internal -mcs-* names so styles.css can forward
    // role tokens without circular looked-up-colour drops, matching the
    // TrackStrip / LevelMeter pattern. A same-name self-referential forward
    // (.mixer-channel-strip { -surface-1: -surface-1 }) is a circular
    // looked-up colour the engine silently drops — the internal name lets
    // styles.css declare `-mcs-surface-1: -surface-1;` (distinct names).

    private final StyleableObjectProperty<Color> surface1 =
            new StyleableObjectProperty<>(FALLBACK_SURFACE_1) {
                @Override public Object getBean() { return MixerChannelStrip.this; }
                @Override public String getName() { return "surface1"; }
                @Override public CssMetaData<MixerChannelStrip, Color> getCssMetaData() {
                    return StyleableProperties.SURFACE_1;
                }
            };
    private final StyleableObjectProperty<Color> surface3 =
            new StyleableObjectProperty<>(FALLBACK_SURFACE_3) {
                @Override public Object getBean() { return MixerChannelStrip.this; }
                @Override public String getName() { return "surface3"; }
                @Override public CssMetaData<MixerChannelStrip, Color> getCssMetaData() {
                    return StyleableProperties.SURFACE_3;
                }
            };
    private final StyleableObjectProperty<Color> surfaceBg =
            new StyleableObjectProperty<>(FALLBACK_SURFACE_BG) {
                @Override public Object getBean() { return MixerChannelStrip.this; }
                @Override public String getName() { return "surfaceBg"; }
                @Override public CssMetaData<MixerChannelStrip, Color> getCssMetaData() {
                    return StyleableProperties.SURFACE_BG;
                }
            };
    private final StyleableObjectProperty<Color> accent =
            new StyleableObjectProperty<>(FALLBACK_ACCENT) {
                @Override public Object getBean() { return MixerChannelStrip.this; }
                @Override public String getName() { return "accent"; }
                @Override public CssMetaData<MixerChannelStrip, Color> getCssMetaData() {
                    return StyleableProperties.ACCENT;
                }
            };
    private final StyleableObjectProperty<Color> accentSoft =
            new StyleableObjectProperty<>(FALLBACK_ACCENT_SOFT) {
                @Override public Object getBean() { return MixerChannelStrip.this; }
                @Override public String getName() { return "accentSoft"; }
                @Override public CssMetaData<MixerChannelStrip, Color> getCssMetaData() {
                    return StyleableProperties.ACCENT_SOFT;
                }
            };
    private final StyleableObjectProperty<Color> danger =
            new StyleableObjectProperty<>(FALLBACK_DANGER) {
                @Override public Object getBean() { return MixerChannelStrip.this; }
                @Override public String getName() { return "danger"; }
                @Override public CssMetaData<MixerChannelStrip, Color> getCssMetaData() {
                    return StyleableProperties.DANGER;
                }
            };
    private final StyleableObjectProperty<Color> warn =
            new StyleableObjectProperty<>(FALLBACK_WARN) {
                @Override public Object getBean() { return MixerChannelStrip.this; }
                @Override public String getName() { return "warn"; }
                @Override public CssMetaData<MixerChannelStrip, Color> getCssMetaData() {
                    return StyleableProperties.WARN;
                }
            };
    private final StyleableObjectProperty<Color> text =
            new StyleableObjectProperty<>(FALLBACK_TEXT) {
                @Override public Object getBean() { return MixerChannelStrip.this; }
                @Override public String getName() { return "text"; }
                @Override public CssMetaData<MixerChannelStrip, Color> getCssMetaData() {
                    return StyleableProperties.TEXT;
                }
            };
    private final StyleableObjectProperty<Color> textHi =
            new StyleableObjectProperty<>(FALLBACK_TEXT_HI) {
                @Override public Object getBean() { return MixerChannelStrip.this; }
                @Override public String getName() { return "textHi"; }
                @Override public CssMetaData<MixerChannelStrip, Color> getCssMetaData() {
                    return StyleableProperties.TEXT_HI;
                }
            };
    private final StyleableObjectProperty<Color> textMute =
            new StyleableObjectProperty<>(FALLBACK_TEXT_MUTE) {
                @Override public Object getBean() { return MixerChannelStrip.this; }
                @Override public String getName() { return "textMute"; }
                @Override public CssMetaData<MixerChannelStrip, Color> getCssMetaData() {
                    return StyleableProperties.TEXT_MUTE;
                }
            };
    private final StyleableObjectProperty<Color> lineSoft =
            new StyleableObjectProperty<>(FALLBACK_LINE_SOFT) {
                @Override public Object getBean() { return MixerChannelStrip.this; }
                @Override public String getName() { return "lineSoft"; }
                @Override public CssMetaData<MixerChannelStrip, Color> getCssMetaData() {
                    return StyleableProperties.LINE_SOFT;
                }
            };

    /** @return the surface-1 styleable colour (default strip background). */
    public final StyleableObjectProperty<Color> surface1Property() { return surface1; }
    /** @return the resolved surface-1 colour. */
    public final Color getSurface1() { return surface1.get(); }
    /** @param c the surface-1 colour. */
    public final void setSurface1(Color c) { surface1.set(c); }

    /** @return the surface-3 styleable colour (hover background). */
    public final StyleableObjectProperty<Color> surface3Property() { return surface3; }
    /** @return the resolved surface-3 colour. */
    public final Color getSurface3() { return surface3.get(); }
    /** @param c the surface-3 colour. */
    public final void setSurface3(Color c) { surface3.set(c); }

    /** @return the surface-bg styleable colour (inter-strip gutter, §5.4). */
    public final StyleableObjectProperty<Color> surfaceBgProperty() { return surfaceBg; }
    /** @return the resolved surface-bg colour. */
    public final Color getSurfaceBg() { return surfaceBg.get(); }
    /** @param c the surface-bg colour. */
    public final void setSurfaceBg(Color c) { surfaceBg.set(c); }

    /** @return the accent styleable colour (active insert status dot). */
    public final StyleableObjectProperty<Color> accentProperty() { return accent; }
    /** @return the resolved accent colour. */
    public final Color getAccent() { return accent.get(); }
    /** @param c the accent colour. */
    public final void setAccent(Color c) { accent.set(c); }

    /** @return the accent-soft styleable colour (selected background). */
    public final StyleableObjectProperty<Color> accentSoftProperty() { return accentSoft; }
    /** @return the resolved accent-soft colour. */
    public final Color getAccentSoft() { return accentSoft.get(); }
    /** @param c the accent-soft colour. */
    public final void setAccentSoft(Color c) { accentSoft.set(c); }

    /** @return the danger styleable colour (record toggle fill). */
    public final StyleableObjectProperty<Color> dangerProperty() { return danger; }
    /** @return the resolved danger colour. */
    public final Color getDanger() { return danger.get(); }
    /** @param c the danger colour. */
    public final void setDanger(Color c) { danger.set(c); }

    /** @return the warn styleable colour (solo toggle fill). */
    public final StyleableObjectProperty<Color> warnProperty() { return warn; }
    /** @return the resolved warn colour. */
    public final Color getWarn() { return warn.get(); }
    /** @param c the warn colour. */
    public final void setWarn(Color c) { warn.set(c); }

    /** @return the text styleable colour (mute toggle fill, captions). */
    public final StyleableObjectProperty<Color> textProperty() { return text; }
    /** @return the resolved text colour. */
    public final Color getText() { return text.get(); }
    /** @param c the text colour. */
    public final void setText(Color c) { text.set(c); }

    /** @return the text-hi styleable colour (channel name). */
    public final StyleableObjectProperty<Color> textHiProperty() { return textHi; }
    /** @return the resolved text-hi colour. */
    public final Color getTextHi() { return textHi.get(); }
    /** @param c the text-hi colour. */
    public final void setTextHi(Color c) { textHi.set(c); }

    /** @return the text-mute styleable colour (bypassed insert dot, I/O caption). */
    public final StyleableObjectProperty<Color> textMuteProperty() { return textMute; }
    /** @return the resolved text-mute colour. */
    public final Color getTextMute() { return textMute.get(); }
    /** @param c the text-mute colour. */
    public final void setTextMute(Color c) { textMute.set(c); }

    /** @return the line-soft styleable colour (section separators). */
    public final StyleableObjectProperty<Color> lineSoftProperty() { return lineSoft; }
    /** @return the resolved line-soft colour. */
    public final Color getLineSoft() { return lineSoft.get(); }
    /** @param c the line-soft colour. */
    public final void setLineSoft(Color c) { lineSoft.set(c); }

    // ── CssMetaData ───────────────────────────────────────────────────────

    private static final class StyleableProperties {

        private static CssMetaData<MixerChannelStrip, Color> color(
                String name, Color fallback,
                java.util.function.Function<MixerChannelStrip,
                        StyleableObjectProperty<Color>> accessor) {
            return new CssMetaData<>(name,
                    StyleConverter.getColorConverter(), fallback) {
                @Override
                public boolean isSettable(MixerChannelStrip s) {
                    return !accessor.apply(s).isBound();
                }
                @Override
                public StyleableProperty<Color> getStyleableProperty(MixerChannelStrip s) {
                    return accessor.apply(s);
                }
            };
        }

        static final CssMetaData<MixerChannelStrip, Color> SURFACE_1 =
                color("-mcs-surface-1", FALLBACK_SURFACE_1, s -> s.surface1);
        static final CssMetaData<MixerChannelStrip, Color> SURFACE_3 =
                color("-mcs-surface-3", FALLBACK_SURFACE_3, s -> s.surface3);
        static final CssMetaData<MixerChannelStrip, Color> SURFACE_BG =
                color("-mcs-surface-bg", FALLBACK_SURFACE_BG, s -> s.surfaceBg);
        static final CssMetaData<MixerChannelStrip, Color> ACCENT =
                color("-mcs-accent", FALLBACK_ACCENT, s -> s.accent);
        static final CssMetaData<MixerChannelStrip, Color> ACCENT_SOFT =
                color("-mcs-accent-soft", FALLBACK_ACCENT_SOFT, s -> s.accentSoft);
        static final CssMetaData<MixerChannelStrip, Color> DANGER =
                color("-mcs-danger", FALLBACK_DANGER, s -> s.danger);
        static final CssMetaData<MixerChannelStrip, Color> WARN =
                color("-mcs-warn", FALLBACK_WARN, s -> s.warn);
        static final CssMetaData<MixerChannelStrip, Color> TEXT =
                color("-mcs-text", FALLBACK_TEXT, s -> s.text);
        static final CssMetaData<MixerChannelStrip, Color> TEXT_HI =
                color("-mcs-text-hi", FALLBACK_TEXT_HI, s -> s.textHi);
        static final CssMetaData<MixerChannelStrip, Color> TEXT_MUTE =
                color("-mcs-text-mute", FALLBACK_TEXT_MUTE, s -> s.textMute);
        static final CssMetaData<MixerChannelStrip, Color> LINE_SOFT =
                color("-mcs-line-soft", FALLBACK_LINE_SOFT, s -> s.lineSoft);

        static final List<CssMetaData<? extends Styleable, ?>> CSS_META_DATA;

        static {
            List<CssMetaData<? extends Styleable, ?>> list =
                    new ArrayList<>(Control.getClassCssMetaData());
            Collections.addAll(list,
                    SURFACE_1, SURFACE_3, SURFACE_BG, ACCENT, ACCENT_SOFT,
                    DANGER, WARN, TEXT, TEXT_HI, TEXT_MUTE, LINE_SOFT);
            CSS_META_DATA = Collections.unmodifiableList(list);
        }

        private StyleableProperties() {
        }
    }

    /** @return the {@link CssMetaData} for this control type, combined with {@link Control}'s. */
    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return StyleableProperties.CSS_META_DATA;
    }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getControlCssMetaData() {
        return getClassCssMetaData();
    }

    // ── Slot selection events ─────────────────────────────────────────────

    /**
     * Typed event fired when the user clicks an insert slot row. Bubbles
     * through the scene graph; story 272's Inspector consumes it via the
     * standard event dispatch chain (Skill §12). Mirrors
     * {@link TrackStrip.TrackSelectionEvent}.
     */
    public static final class InsertSelectedEvent extends Event {

        private static final long serialVersionUID = 20260516L;

        /** Single typed event-type for insert-slot selection. */
        public static final EventType<InsertSelectedEvent> INSERT_SELECTED =
                new EventType<>(Event.ANY, "MCS_INSERT_SELECTED");

        private final int insertIndex;

        /**
         * Creates an insert-selected event with no explicit source/target
         * (the dispatch chain fills them in when fired via
         * {@link javafx.scene.Node#fireEvent(Event)}).
         *
         * @param insertIndex the clicked slot's index in
         *                    {@link #insertsProperty()}
         */
        public InsertSelectedEvent(int insertIndex) {
            super(INSERT_SELECTED);
            this.insertIndex = insertIndex;
        }

        /**
         * @param source      the event source (typically the strip)
         * @param target      the event target
         * @param insertIndex the clicked slot's index in
         *                    {@link #insertsProperty()}
         */
        public InsertSelectedEvent(Object source, EventTarget target, int insertIndex) {
            super(source, target, INSERT_SELECTED);
            this.insertIndex = insertIndex;
        }

        /** @return the clicked insert-slot index. */
        public int getInsertIndex() {
            return insertIndex;
        }
    }

    /**
     * Typed event fired when the user clicks a send slot row. Bubbles
     * through the scene graph. Mirrors {@link InsertSelectedEvent}.
     */
    public static final class SendSelectedEvent extends Event {

        private static final long serialVersionUID = 20260516L;

        /** Single typed event-type for send-slot selection. */
        public static final EventType<SendSelectedEvent> SEND_SELECTED =
                new EventType<>(Event.ANY, "MCS_SEND_SELECTED");

        private final int sendIndex;

        /**
         * Creates a send-selected event with no explicit source/target.
         *
         * @param sendIndex the clicked slot's index in
         *                  {@link #sendsProperty()}
         */
        public SendSelectedEvent(int sendIndex) {
            super(SEND_SELECTED);
            this.sendIndex = sendIndex;
        }

        /**
         * @param source    the event source (typically the strip)
         * @param target    the event target
         * @param sendIndex the clicked slot's index in
         *                  {@link #sendsProperty()}
         */
        public SendSelectedEvent(Object source, EventTarget target, int sendIndex) {
            super(source, target, SEND_SELECTED);
            this.sendIndex = sendIndex;
        }

        /** @return the clicked send-slot index. */
        public int getSendIndex() {
            return sendIndex;
        }
    }

    /** Public re-export of the insert-selection event type (Skill §12 convention). */
    public static final EventType<InsertSelectedEvent> INSERT_SELECTED =
            InsertSelectedEvent.INSERT_SELECTED;
    /** Public re-export of the send-selection event type (Skill §12 convention). */
    public static final EventType<SendSelectedEvent> SEND_SELECTED =
            SendSelectedEvent.SEND_SELECTED;

    /**
     * Convenience setter for a single
     * {@link InsertSelectedEvent#INSERT_SELECTED} handler. Replaces any
     * previously installed handler.
     *
     * @param handler the handler, or {@code null} to clear
     */
    public final void setOnInsertSelected(EventHandler<InsertSelectedEvent> handler) {
        setEventHandler(INSERT_SELECTED, handler);
    }

    /**
     * Convenience setter for a single
     * {@link SendSelectedEvent#SEND_SELECTED} handler. Replaces any
     * previously installed handler.
     *
     * @param handler the handler, or {@code null} to clear
     */
    public final void setOnSendSelected(EventHandler<SendSelectedEvent> handler) {
        setEventHandler(SEND_SELECTED, handler);
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
     * Fluent builder for {@link MixerChannelStrip}. One construction path
     * among equals — every method maps onto a public setter / style class.
     */
    public static final class Builder {

        private UUID channelId;
        private String name = "";
        private String inputLabel = "";
        private String outputLabel = "";
        private double pan;
        private double faderDb;
        private boolean muted;
        private boolean soloed;
        private boolean armed;
        private ChannelType channelType = ChannelType.AUDIO;
        private final List<InsertSlotModel> inserts = new ArrayList<>();
        private final List<SendSlotModel> sends = new ArrayList<>();
        private String sizeName;

        private Builder() {
        }

        /** @param id the channel identifier; may be {@code null}. @return this builder */
        public Builder channelId(UUID id) {
            this.channelId = id;
            return this;
        }

        /** @param name the channel display name; must not be {@code null}. @return this builder */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** @param label the input-routing caption; must not be {@code null}. @return this builder */
        public Builder inputLabel(String label) {
            this.inputLabel = Objects.requireNonNull(label, "inputLabel");
            return this;
        }

        /** @param label the output-routing caption; must not be {@code null}. @return this builder */
        public Builder outputLabel(String label) {
            this.outputLabel = Objects.requireNonNull(label, "outputLabel");
            return this;
        }

        /** @param value the pan position in {@code [-1, 1]}. @return this builder */
        public Builder pan(double value) {
            this.pan = value;
            return this;
        }

        /** @param db the fader value in dB. @return this builder */
        public Builder faderDb(double db) {
            this.faderDb = db;
            return this;
        }

        /** @param value muted state. @return this builder */
        public Builder muted(boolean value) {
            this.muted = value;
            return this;
        }

        /** @param value soloed state. @return this builder */
        public Builder soloed(boolean value) {
            this.soloed = value;
            return this;
        }

        /** @param value armed state. @return this builder */
        public Builder armed(boolean value) {
            this.armed = value;
            return this;
        }

        /** @param type the channel type; must not be {@code null}. @return this builder */
        public Builder channelType(ChannelType type) {
            this.channelType = Objects.requireNonNull(type, "channelType");
            return this;
        }

        /** @param slots the initial insert slots; must not be {@code null}. @return this builder */
        public Builder inserts(List<InsertSlotModel> slots) {
            Objects.requireNonNull(slots, "inserts");
            this.inserts.clear();
            this.inserts.addAll(slots);
            return this;
        }

        /** @param slots the initial send slots; must not be {@code null}. @return this builder */
        public Builder sends(List<SendSlotModel> slots) {
            Objects.requireNonNull(slots, "sends");
            this.sends.clear();
            this.sends.addAll(slots);
            return this;
        }

        /**
         * Adds the style class {@code "density-" + name} (e.g.
         * {@code density-compact}, {@code density-comfortable}). The
         * default 72&nbsp;px Compact width needs no class.
         *
         * @param name the density-variant name; must not be {@code null}
         * @return this builder
         */
        public Builder size(String name) {
            this.sizeName = Objects.requireNonNull(name, "size name");
            return this;
        }

        /** @return a fully configured {@link MixerChannelStrip}. */
        public MixerChannelStrip build() {
            MixerChannelStrip s = new MixerChannelStrip();
            s.setChannelId(channelId);
            s.setChannelName(name);
            s.setInputLabel(inputLabel);
            s.setOutputLabel(outputLabel);
            s.setPan(pan);
            s.setFaderDb(faderDb);
            s.setMuted(muted);
            s.setSoloed(soloed);
            s.setArmed(armed);
            s.setChannelType(channelType);
            s.insertsProperty().setAll(inserts);
            s.sendsProperty().setAll(sends);
            if (sizeName != null) {
                s.getStyleClass().add("density-" + sizeName);
            }
            return s;
        }
    }
}
