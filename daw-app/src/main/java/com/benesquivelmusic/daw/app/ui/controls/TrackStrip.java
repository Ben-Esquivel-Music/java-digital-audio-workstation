package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.controls.skin.TrackStripSkin;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
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
import com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed;

/**
 * Track strip {@link Control} for the arrangement view's track list (and
 * the inspector's track header).
 *
 * <p>Story: TrackStrip Control + Skin with Outlined-Default / Filled-Active
 * M/S/R. Phase 2 of the UI Design Book §6 migration roadmap — replaces the
 * ad-hoc {@code .track-item} HBox rendering in {@code TrackStripController}.
 * Follows the same {@code Control + SkinBase + StyleableProperty +
 * package-resource user-agent stylesheet} convention as {@link LevelMeter}
 * (story 267), {@link Knob} (story 268) and {@link Fader} (story 269).
 *
 * <h2>Visual contract (UI Design Book §5.3)</h2>
 *
 * <ul>
 *   <li>28&nbsp;px tall row (default density). 24&nbsp;px compact /
 *       32&nbsp;px comfortable variants via {@code .size-compact} /
 *       {@code .size-comfortable} style classes.</li>
 *   <li>Layout left → right: drag handle · index · colour swatch · name ·
 *       M · S · R · level meter · meter readout · ⋯.</li>
 *   <li>{@code :hover}: background swaps to {@code -surface-3}. No shadow,
 *       no border swap (§7.1 / §7.3).</li>
 *   <li>{@code :selected}: background {@code -accent-soft}.</li>
 *   <li>{@code armed}: a 2&nbsp;px {@code -danger} vertical bar on the
 *       left edge (drawn as a {@link javafx.scene.shape.Rectangle} child
 *       of the skin, not a border, so the row height doesn't shift).</li>
 *   <li>{@code muted}: the name fades to {@code -text-mute} and the
 *       embedded meter shows {@code -∞}.</li>
 *   <li><strong>M / S / R buttons outlined by default; filled when active.
 *       </strong> The toggled fill colour encodes the state semantically:
 *       {@code -text} for M, {@code -warn} for S, {@code -danger} for R.
 *       No hover inversion (§7.5 — three states of one thing, not three
 *       semantics).</li>
 * </ul>
 *
 * <h2>Selection events</h2>
 *
 * <p>Click handling inside the skin fires a typed
 * {@link TrackSelectionEvent} via {@link #fireEvent(Event)} so the event
 * bubbles through the scene graph normally and integrates with FXML /
 * the standard event dispatch chain (Skill §12). Prefer
 * {@link #setOnSelectionRequested(EventHandler)} over a
 * {@code Consumer<TrackId>} callback.
 *
 * <h2>Construction paths</h2>
 *
 * <p>The public no-arg constructor with the standard property setters
 * and the fluent {@link #create()} builder are independent, equally
 * supported paths:
 *
 * <pre>{@code
 * TrackStrip a = new TrackStrip();
 * a.setTrackName("Drums");
 * a.setTrackColor(Color.web("#7C8CFF"));
 *
 * TrackStrip b = TrackStrip.create()
 *         .trackId(UUID.randomUUID())
 *         .name("Drums")
 *         .color(Color.web("#7C8CFF"))
 *         .showMeter(true)
 *         .build();
 * }</pre>
 */
@HardcodedColorAllowed("story 277 follow-up: migrate Canvas/inline paints to resolved -token CSS")
public final class TrackStrip extends Control {

    /** Stable style class — selectable as {@code .track-strip} in CSS. */
    public static final String DEFAULT_STYLE_CLASS = "track-strip";

    /** Pseudo-class flipped on {@link #selectedProperty()}. */
    static final PseudoClass PSEUDO_SELECTED = PseudoClass.getPseudoClass("selected");
    /** Pseudo-class flipped on {@link #armedProperty()}. */
    static final PseudoClass PSEUDO_ARMED = PseudoClass.getPseudoClass("armed");
    /** Pseudo-class flipped on {@link #mutedProperty()}. */
    static final PseudoClass PSEUDO_MUTED = PseudoClass.getPseudoClass("muted");
    /** Pseudo-class flipped on {@link #soloedProperty()}. */
    static final PseudoClass PSEUDO_SOLOED = PseudoClass.getPseudoClass("soloed");

    // ── Palette A fallback colours (UI Design Book §5.3) ──────────────────
    private static final Color FALLBACK_SURFACE_1 = Color.web("#15161B");
    private static final Color FALLBACK_SURFACE_3 = Color.web("#272A33");
    private static final Color FALLBACK_ACCENT_SOFT = Color.web("rgba(124, 140, 255, 0.14)");
    private static final Color FALLBACK_DANGER = Color.web("#E5484D");
    private static final Color FALLBACK_WARN = Color.web("#E6B450");
    private static final Color FALLBACK_TEXT = Color.web("#B7BCC7");
    private static final Color FALLBACK_TEXT_HI = Color.web("#ECEEF2");
    private static final Color FALLBACK_TEXT_MUTE = Color.web("#7A808C");
    private static final Color FALLBACK_LINE_SOFT = Color.web("#22242C");

    // ── Plain observable properties ───────────────────────────────────────

    private final ObjectProperty<UUID> trackId =
            new SimpleObjectProperty<>(this, "trackId", null);
    private final IntegerProperty trackIndex =
            new SimpleIntegerProperty(this, "trackIndex", 1);
    private final StringProperty trackName =
            new SimpleStringProperty(this, "trackName", "");
    private final ObjectProperty<Color> trackColor =
            new SimpleObjectProperty<>(this, "trackColor", Color.web("#7C8CFF"));

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
    private final BooleanProperty selected =
            new SimpleBooleanProperty(this, "selected", false) {
                @Override
                protected void invalidated() {
                    pseudoClassStateChanged(PSEUDO_SELECTED, get());
                }
            };
    private final BooleanProperty showMeter =
            new SimpleBooleanProperty(this, "showMeter", true);

    // ── Embedded LevelMeter (lazy) ────────────────────────────────────────

    private LevelMeter meter;

    /**
     * Creates a track strip with defaults: index 1, no track id, empty
     * name, accent colour swatch, meter shown, all gating flags
     * ({@link #mutedProperty()}, {@link #soloedProperty()},
     * {@link #armedProperty()}, {@link #selectedProperty()}) false.
     */
    public TrackStrip() {
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        setAccessibleRole(AccessibleRole.LIST_ITEM);
        setAccessibleRoleDescription("Track strip");
        setFocusTraversable(true);
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new TrackStripSkin(this);
    }

    @Override
    public String getUserAgentStylesheet() {
        return Objects.requireNonNull(
                TrackStrip.class.getResource("track-strip.css"),
                "track-strip.css not on classpath").toExternalForm();
    }

    // ── trackId ───────────────────────────────────────────────────────────

    /** @return the track identifier property (the {@link UUID} from the {@code daw-sdk} {@code Track} record). */
    public final ObjectProperty<UUID> trackIdProperty() { return trackId; }
    /** @return the track identifier, or {@code null} if unbound. */
    public final UUID getTrackId() { return trackId.get(); }
    /** @param id the track identifier (may be {@code null} for empty cells). */
    public final void setTrackId(UUID id) { trackId.set(id); }

    // ── trackIndex ────────────────────────────────────────────────────────

    /** @return the 1-based display index property. */
    public final IntegerProperty trackIndexProperty() { return trackIndex; }
    /** @return the 1-based display index. */
    public final int getTrackIndex() { return trackIndex.get(); }
    /** @param index the 1-based display index. */
    public final void setTrackIndex(int index) { trackIndex.set(index); }

    // ── trackName ─────────────────────────────────────────────────────────

    /** @return the track display name property. */
    public final StringProperty trackNameProperty() { return trackName; }
    /** @return the track display name. */
    public final String getTrackName() { return trackName.get(); }
    /** @param name the track display name. */
    public final void setTrackName(String name) { trackName.set(name); }

    // ── trackColor ────────────────────────────────────────────────────────

    /** @return the colour-swatch colour property. */
    public final ObjectProperty<Color> trackColorProperty() { return trackColor; }
    /** @return the colour-swatch colour. */
    public final Color getTrackColor() { return trackColor.get(); }
    /** @param c the colour-swatch colour. */
    public final void setTrackColor(Color c) { trackColor.set(c); }

    // ── muted / soloed / armed / selected / showMeter ─────────────────────

    /** @return the muted gating property; flips the {@code :muted} pseudo-class. */
    public final BooleanProperty mutedProperty() { return muted; }
    /** @return whether the track is muted. */
    public final boolean isMuted() { return muted.get(); }
    /** @param value whether the track is muted. */
    public final void setMuted(boolean value) { muted.set(value); }

    /** @return the soloed gating property; flips the {@code :soloed} pseudo-class. */
    public final BooleanProperty soloedProperty() { return soloed; }
    /** @return whether the track is soloed. */
    public final boolean isSoloed() { return soloed.get(); }
    /** @param value whether the track is soloed. */
    public final void setSoloed(boolean value) { soloed.set(value); }

    /** @return the armed gating property; flips the {@code :armed} pseudo-class. */
    public final BooleanProperty armedProperty() { return armed; }
    /** @return whether the track is record-armed. */
    public final boolean isArmed() { return armed.get(); }
    /** @param value whether the track is record-armed. */
    public final void setArmed(boolean value) { armed.set(value); }

    /** @return the selected property; flips the {@code :selected} pseudo-class. */
    public final BooleanProperty selectedProperty() { return selected; }
    /** @return whether the track is the active selection. */
    public final boolean isSelected() { return selected.get(); }
    /** @param value whether the track is the active selection. */
    public final void setSelected(boolean value) { selected.set(value); }

    /** @return the show-meter property (default {@code true}). */
    public final BooleanProperty showMeterProperty() { return showMeter; }
    /** @return whether the embedded meter is shown. */
    public final boolean isShowMeter() { return showMeter.get(); }
    /** @param value whether to show the embedded meter. */
    public final void setShowMeter(boolean value) { showMeter.set(value); }

    // ── Embedded meter ────────────────────────────────────────────────────

    /**
     * @return the embedded {@link LevelMeter}, created on first access and
     *         styled with the {@code .size-inline} variant by default (a
     *         4&nbsp;px&nbsp;×&nbsp;16&nbsp;px inline strip per story 267).
     *         Consumers bind once:
     *         {@code strip.getMeter().peakDbProperty().bind(...)}.
     *         A non-null meter always exists logically; visibility is
     *         controlled by {@link #showMeterProperty()}.
     */
    public final LevelMeter getMeter() {
        if (meter == null) {
            meter = new LevelMeter();
            meter.getStyleClass().add("size-inline");
        }
        return meter;
    }

    /** @return whether the meter has been instantiated (test seam). */
    public final boolean isMeterCreated() {
        return meter != null;
    }

    // ── Styleable colour properties (consume root-pane tokens) ────────────
    //
    // The CssMetaData uses internal -ts-* names so styles.css can forward
    // role tokens without circular looked-up-colour drops, matching the
    // LevelMeter pattern.

    private final StyleableObjectProperty<Color> surface1 =
            new StyleableObjectProperty<>(FALLBACK_SURFACE_1) {
                @Override public Object getBean() { return TrackStrip.this; }
                @Override public String getName() { return "surface1"; }
                @Override public CssMetaData<TrackStrip, Color> getCssMetaData() {
                    return StyleableProperties.SURFACE_1;
                }
            };
    private final StyleableObjectProperty<Color> surface3 =
            new StyleableObjectProperty<>(FALLBACK_SURFACE_3) {
                @Override public Object getBean() { return TrackStrip.this; }
                @Override public String getName() { return "surface3"; }
                @Override public CssMetaData<TrackStrip, Color> getCssMetaData() {
                    return StyleableProperties.SURFACE_3;
                }
            };
    private final StyleableObjectProperty<Color> accentSoft =
            new StyleableObjectProperty<>(FALLBACK_ACCENT_SOFT) {
                @Override public Object getBean() { return TrackStrip.this; }
                @Override public String getName() { return "accentSoft"; }
                @Override public CssMetaData<TrackStrip, Color> getCssMetaData() {
                    return StyleableProperties.ACCENT_SOFT;
                }
            };
    private final StyleableObjectProperty<Color> danger =
            new StyleableObjectProperty<>(FALLBACK_DANGER) {
                @Override public Object getBean() { return TrackStrip.this; }
                @Override public String getName() { return "danger"; }
                @Override public CssMetaData<TrackStrip, Color> getCssMetaData() {
                    return StyleableProperties.DANGER;
                }
            };
    private final StyleableObjectProperty<Color> warn =
            new StyleableObjectProperty<>(FALLBACK_WARN) {
                @Override public Object getBean() { return TrackStrip.this; }
                @Override public String getName() { return "warn"; }
                @Override public CssMetaData<TrackStrip, Color> getCssMetaData() {
                    return StyleableProperties.WARN;
                }
            };
    private final StyleableObjectProperty<Color> text =
            new StyleableObjectProperty<>(FALLBACK_TEXT) {
                @Override public Object getBean() { return TrackStrip.this; }
                @Override public String getName() { return "text"; }
                @Override public CssMetaData<TrackStrip, Color> getCssMetaData() {
                    return StyleableProperties.TEXT;
                }
            };
    private final StyleableObjectProperty<Color> textHi =
            new StyleableObjectProperty<>(FALLBACK_TEXT_HI) {
                @Override public Object getBean() { return TrackStrip.this; }
                @Override public String getName() { return "textHi"; }
                @Override public CssMetaData<TrackStrip, Color> getCssMetaData() {
                    return StyleableProperties.TEXT_HI;
                }
            };
    private final StyleableObjectProperty<Color> textMute =
            new StyleableObjectProperty<>(FALLBACK_TEXT_MUTE) {
                @Override public Object getBean() { return TrackStrip.this; }
                @Override public String getName() { return "textMute"; }
                @Override public CssMetaData<TrackStrip, Color> getCssMetaData() {
                    return StyleableProperties.TEXT_MUTE;
                }
            };
    private final StyleableObjectProperty<Color> lineSoft =
            new StyleableObjectProperty<>(FALLBACK_LINE_SOFT) {
                @Override public Object getBean() { return TrackStrip.this; }
                @Override public String getName() { return "lineSoft"; }
                @Override public CssMetaData<TrackStrip, Color> getCssMetaData() {
                    return StyleableProperties.LINE_SOFT;
                }
            };

    /** @return the surface-1 styleable colour (default row background). */
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

    /** @return the accent-soft styleable colour (selected background). */
    public final StyleableObjectProperty<Color> accentSoftProperty() { return accentSoft; }
    /** @return the resolved accent-soft colour. */
    public final Color getAccentSoft() { return accentSoft.get(); }
    /** @param c the accent-soft colour. */
    public final void setAccentSoft(Color c) { accentSoft.set(c); }

    /** @return the danger styleable colour (armed edge bar + record toggle fill). */
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

    /** @return the text styleable colour (mute toggle fill). */
    public final StyleableObjectProperty<Color> textProperty() { return text; }
    /** @return the resolved text colour. */
    public final Color getText() { return text.get(); }
    /** @param c the text colour. */
    public final void setText(Color c) { text.set(c); }

    /** @return the text-hi styleable colour (track name). */
    public final StyleableObjectProperty<Color> textHiProperty() { return textHi; }
    /** @return the resolved text-hi colour. */
    public final Color getTextHi() { return textHi.get(); }
    /** @param c the text-hi colour. */
    public final void setTextHi(Color c) { textHi.set(c); }

    /** @return the text-mute styleable colour (muted track name fade). */
    public final StyleableObjectProperty<Color> textMuteProperty() { return textMute; }
    /** @return the resolved text-mute colour. */
    public final Color getTextMute() { return textMute.get(); }
    /** @param c the text-mute colour. */
    public final void setTextMute(Color c) { textMute.set(c); }

    /** @return the line-soft styleable colour (row separator). */
    public final StyleableObjectProperty<Color> lineSoftProperty() { return lineSoft; }
    /** @return the resolved line-soft colour. */
    public final Color getLineSoft() { return lineSoft.get(); }
    /** @param c the line-soft colour. */
    public final void setLineSoft(Color c) { lineSoft.set(c); }

    // ── CssMetaData ───────────────────────────────────────────────────────

    private static final class StyleableProperties {

        private static CssMetaData<TrackStrip, Color> color(
                String name, Color fallback,
                java.util.function.Function<TrackStrip, StyleableObjectProperty<Color>> accessor) {
            return new CssMetaData<>(name,
                    StyleConverter.getColorConverter(), fallback) {
                @Override
                public boolean isSettable(TrackStrip s) {
                    return !accessor.apply(s).isBound();
                }
                @Override
                public StyleableProperty<Color> getStyleableProperty(TrackStrip s) {
                    return accessor.apply(s);
                }
            };
        }

        static final CssMetaData<TrackStrip, Color> SURFACE_1 =
                color("-ts-surface-1", FALLBACK_SURFACE_1, s -> s.surface1);
        static final CssMetaData<TrackStrip, Color> SURFACE_3 =
                color("-ts-surface-3", FALLBACK_SURFACE_3, s -> s.surface3);
        static final CssMetaData<TrackStrip, Color> ACCENT_SOFT =
                color("-ts-accent-soft", FALLBACK_ACCENT_SOFT, s -> s.accentSoft);
        static final CssMetaData<TrackStrip, Color> DANGER =
                color("-ts-danger", FALLBACK_DANGER, s -> s.danger);
        static final CssMetaData<TrackStrip, Color> WARN =
                color("-ts-warn", FALLBACK_WARN, s -> s.warn);
        static final CssMetaData<TrackStrip, Color> TEXT =
                color("-ts-text", FALLBACK_TEXT, s -> s.text);
        static final CssMetaData<TrackStrip, Color> TEXT_HI =
                color("-ts-text-hi", FALLBACK_TEXT_HI, s -> s.textHi);
        static final CssMetaData<TrackStrip, Color> TEXT_MUTE =
                color("-ts-text-mute", FALLBACK_TEXT_MUTE, s -> s.textMute);
        static final CssMetaData<TrackStrip, Color> LINE_SOFT =
                color("-ts-line-soft", FALLBACK_LINE_SOFT, s -> s.lineSoft);

        static final List<CssMetaData<? extends Styleable, ?>> CSS_META_DATA;

        static {
            List<CssMetaData<? extends Styleable, ?>> list =
                    new ArrayList<>(Control.getClassCssMetaData());
            Collections.addAll(list,
                    SURFACE_1, SURFACE_3, ACCENT_SOFT, DANGER, WARN,
                    TEXT, TEXT_HI, TEXT_MUTE, LINE_SOFT);
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

    // ── Selection event ───────────────────────────────────────────────────

    /**
     * Typed event fired when the user clicks a track strip to request
     * selection. Bubbles through the scene graph; integrates with FXML's
     * {@code onSelectionRequested} attribute and the standard event
     * dispatch chain (Skill §12).
     */
    public static final class TrackSelectionEvent extends Event {

        private static final long serialVersionUID = 20260516L;

        /** Single typed event-type for selection requests. */
        public static final EventType<TrackSelectionEvent> SELECTION_REQUESTED =
                new EventType<>(Event.ANY, "TRACK_SELECTION_REQUESTED");

        private final UUID trackId;

        /**
         * Creates a selection-request event with no explicit source/target
         * (the dispatch chain fills them in when the event is fired via
         * {@link javafx.scene.Node#fireEvent(Event)}).
         *
         * @param trackId the requested track's id; may be {@code null}
         *                when the strip is currently unbound
         */
        public TrackSelectionEvent(UUID trackId) {
            super(SELECTION_REQUESTED);
            this.trackId = trackId;
        }

        /**
         * @param source  the event source (typically the {@link TrackStrip})
         * @param target  the event target
         * @param trackId the requested track's id; may be {@code null}
         */
        public TrackSelectionEvent(Object source, EventTarget target, UUID trackId) {
            super(source, target, SELECTION_REQUESTED);
            this.trackId = trackId;
        }

        /** @return the requested track's id (may be {@code null}). */
        public UUID getTrackId() {
            return trackId;
        }
    }

    /** Public re-export of the selection event type (Skill §12 convention). */
    public static final EventType<TrackSelectionEvent> SELECTION_REQUESTED =
            TrackSelectionEvent.SELECTION_REQUESTED;

    /**
     * Convenience setter for a single
     * {@link TrackSelectionEvent#SELECTION_REQUESTED} handler. Replaces any
     * previously installed handler.
     *
     * @param handler the handler, or {@code null} to clear
     */
    public final void setOnSelectionRequested(EventHandler<TrackSelectionEvent> handler) {
        setEventHandler(SELECTION_REQUESTED, handler);
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
     * Fluent builder for {@link TrackStrip}. One construction path among
     * equals — every method maps onto a public setter / style class.
     */
    public static final class Builder {

        private UUID trackId;
        private int trackIndex = 1;
        private String name = "";
        private Color color = Color.web("#7C8CFF");
        private boolean muted;
        private boolean soloed;
        private boolean armed;
        private boolean selected;
        private boolean showMeter = true;
        private String sizeName;

        private Builder() {
        }

        /** @param id the track identifier; may be {@code null}. @return this builder */
        public Builder trackId(UUID id) {
            this.trackId = id;
            return this;
        }

        /** @param index the 1-based display index. @return this builder */
        public Builder trackIndex(int index) {
            this.trackIndex = index;
            return this;
        }

        /** @param name the track display name; must not be {@code null}. @return this builder */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** @param color the swatch colour; must not be {@code null}. @return this builder */
        public Builder color(Color color) {
            this.color = Objects.requireNonNull(color, "color");
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

        /** @param value selected state. @return this builder */
        public Builder selected(boolean value) {
            this.selected = value;
            return this;
        }

        /** @param value whether to show the embedded meter. @return this builder */
        public Builder showMeter(boolean value) {
            this.showMeter = value;
            return this;
        }

        /**
         * Adds the style class {@code "size-" + name} (e.g.
         * {@code size-compact}, {@code size-comfortable},
         * {@code size-performance}). The default 28&nbsp;px density needs
         * no class.
         *
         * @param name the size-variant name; must not be {@code null}
         * @return this builder
         */
        public Builder size(String name) {
            this.sizeName = Objects.requireNonNull(name, "size name");
            return this;
        }

        /** @return a fully configured {@link TrackStrip}. */
        public TrackStrip build() {
            TrackStrip s = new TrackStrip();
            s.setTrackId(trackId);
            s.setTrackIndex(trackIndex);
            s.setTrackName(name);
            s.setTrackColor(color);
            s.setMuted(muted);
            s.setSoloed(soloed);
            s.setArmed(armed);
            s.setSelected(selected);
            s.setShowMeter(showMeter);
            if (sizeName != null) {
                s.getStyleClass().add("size-" + sizeName);
            }
            return s;
        }
    }
}
