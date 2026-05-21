package com.benesquivelmusic.daw.app.ui.inspector;

import com.benesquivelmusic.daw.app.ui.NotificationHistoryService;
import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.controls.TrackStrip;
import com.benesquivelmusic.daw.app.ui.inspector.sections.InsertsSection;
import com.benesquivelmusic.daw.app.ui.inspector.sections.NotesSection;
import com.benesquivelmusic.daw.app.ui.inspector.sections.NotificationsSection;
import com.benesquivelmusic.daw.app.ui.inspector.sections.RoutingSection;
import com.benesquivelmusic.daw.app.ui.inspector.sections.SendsSection;
import com.benesquivelmusic.daw.app.ui.inspector.sections.TrackSection;
import com.benesquivelmusic.daw.app.ui.inspector.skin.InspectorDrawerSkin;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import javafx.css.CssMetaData;
import javafx.css.StyleConverter;
import javafx.css.Styleable;
import javafx.css.StyleableDoubleProperty;
import javafx.css.StyleableProperty;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Unified Inspector drawer (UI Design Book §5.6, story 272).
 *
 * <h2>Visual contract</h2>
 *
 * <ul>
 *   <li><strong>Collapsed</strong> ({@link #expandedProperty()} = {@code false}):
 *       a 24 px rail showing only a vertical "INSPECTOR" label (rotated text
 *       per the §5.6 mockup's {@code ⏵} collapsed glyph).</li>
 *   <li><strong>Expanded</strong>: a 240 px panel with a header bar
 *       ({@code "{Track 01 — Drums}"}-style), a vertical stack of
 *       {@link InspectorSection} cards (Track / Inserts / Sends / Routing /
 *       Notes / Notifications), and a 16 px right-edge gutter.</li>
 *   <li>Open / close transition: 220 ms {@code EASE_OUT} per §3.5. Halves
 *       to 0 ms when {@link #animatedProperty()} is {@code false}
 *       (reduce-motion accessibility opt-out — story 279).</li>
 * </ul>
 *
 * <h2>Styling</h2>
 *
 * <p>The expanded and collapsed widths are exposed as
 * {@link StyleableProperty} numerics. Defaults match §5.6 (240 / 24)
 * and can be overridden from CSS without changing this class —
 * Performance Stage (story 280) or future layout themes use
 * {@code .inspector-drawer { -inspector-expanded-width: 320; }} to
 * adapt the drawer.
 *
 * <h2>Selection &amp; events</h2>
 *
 * <p>The drawer owns an {@link InspectorSelectionModel} (the single
 * source of truth for "what is selected?") and fires an
 * {@link InspectorSelectionEvent} every time the model transitions.
 * Source-side controls — {@link TrackStrip},
 * {@link MixerChannelStrip} — already publish their own typed events
 * ({@link TrackStrip.TrackSelectionEvent},
 * {@link MixerChannelStrip.InsertSelectedEvent},
 * {@link MixerChannelStrip.SendSelectedEvent}). When this drawer is
 * installed inside a parent that catches those events, hook them in
 * with {@link #installSourceEventForwarding(javafx.scene.Node)}.
 *
 * <h2>Keyboard parity</h2>
 *
 * <ul>
 *   <li>{@code Esc} collapses the drawer to the rail.</li>
 *   <li>{@code Ctrl+I} toggles the drawer (matches typical DAW
 *       convention; coordinate with {@code KeyBindingManager}).</li>
 *   <li>{@code Tab} traverses through every focusable element in the
 *       section bodies (standard JavaFX focus traversal).</li>
 * </ul>
 */
public final class InspectorDrawer extends Control {

    /** Stable style class — selectable as {@code .inspector-drawer}. */
    public static final String DEFAULT_STYLE_CLASS = "inspector-drawer";

    /** §5.6 default — fallback used when the stylesheet is absent. */
    public static final double DEFAULT_EXPANDED_WIDTH = 240.0;
    /** §5.6 default — the collapsed rail width. */
    public static final double DEFAULT_COLLAPSED_WIDTH = 24.0;
    /** §3.5 default open / close transition duration in milliseconds. */
    public static final double DEFAULT_TRANSITION_MS = 220.0;

    // ── State ─────────────────────────────────────────────────────────────

    private final BooleanProperty expanded =
            new SimpleBooleanProperty(this, "expanded", true);

    // ── Animated flag — two-mechanism design (story 279) ──────────────────
    // localAnimated is the per-control opt-out (set via setAnimated);
    // `animated` is the read-only COMBINED value (localAnimated AND NOT
    // global Reduce Motion). The skin reads isAnimated() and so collapses
    // the 220 ms open/close transition to 0 ms under Reduce Motion.
    private final BooleanProperty localAnimated =
            new SimpleBooleanProperty(this, "localAnimated", true);
    private final ReadOnlyBooleanWrapper animated =
            new ReadOnlyBooleanWrapper(this, "animated", true);
    // Strong field — lives exactly as long as this control; registered on
    // the MotionManager singleton via a WeakChangeListener so the
    // singleton never pins the control (story 277/278 pattern).
    private final ChangeListener<Boolean> reduceMotionListener =
            (obs, was, now) -> recomputeAnimated();

    /** Header bar text — typically "Track 01 — Drums" once a track is selected. */
    private final StringProperty headerText =
            new SimpleStringProperty(this, "headerText", "");

    /**
     * The selection model is immutable — callers interact with the single
     * instance returned by {@link #getSelectionModel()} and must not
     * swap it out. This avoids the listener-leak problem where a
     * selection-changed listener bound to the initial model is silently
     * orphaned when the model reference is replaced.
     */
    private final InspectorSelectionModel selectionModel =
            new InspectorSelectionModel();

    /** Resource bundle for user-facing inspector strings (Skill §14). */
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    // ── Sections (created once, surfaced as accessors for tests) ──────────

    private final TrackSection trackSection;
    private final InsertsSection insertsSection;
    private final SendsSection sendsSection;
    private final RoutingSection routingSection;
    private final NotesSection notesSection;
    private final NotificationsSection notificationsSection;

    /**
     * Default notification log so a no-arg / FXML-loaded drawer renders
     * standalone. {@link #setNotificationHistoryService} re-points the
     * section at the application's shared log (story 273 — exactly one
     * notification stream feeding both surfaces).
     */
    private final NotificationHistoryService defaultHistoryService =
            new NotificationHistoryService();

    // ── Constructors ──────────────────────────────────────────────────────

    /** Creates a drawer with empty selection and the five default sections. */
    public InspectorDrawer() {
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        setAccessibleRole(AccessibleRole.NODE);
        setAccessibleRoleDescription("Inspector");
        setFocusTraversable(true);

        // Section titles from the i18n bundle (Skill §14 — no hard-coded
        // user-facing strings in code or FXML).
        this.trackSection = new TrackSection(msg("inspector.section.track"));
        this.insertsSection = new InsertsSection(
                msg("inspector.section.inserts"), msg("inspector.inserts.add"));
        this.sendsSection = new SendsSection(msg("inspector.section.sends"));
        this.routingSection = new RoutingSection(msg("inspector.section.routing"));
        this.notesSection = new NotesSection(msg("inspector.section.notes"));
        this.notificationsSection =
                new NotificationsSection(msg("inspector.section.notifications"));
        this.notificationsSection.setHistoryService(defaultHistoryService);

        // Wire selection-model changes → typed inspector event so the
        // dispatch chain delivers it to any ancestor / sibling listener.
        selectionModel.selectionProperty().addListener((o, oldS, newS) -> {
            if (newS != null) {
                applySelectionToSections(newS);
                fireEvent(new InspectorSelectionEvent(newS));
            }
        });

        // Keyboard parity — Esc collapses; Ctrl+I toggles.
        addEventHandler(KeyEvent.KEY_PRESSED, this::handleKey);

        // Combined animated = localAnimated AND NOT global Reduce Motion
        // (story 279). The global listener is weak so the MotionManager
        // singleton cannot pin this drawer.
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

    private void handleKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ESCAPE) {
            if (isExpanded()) {
                setExpanded(false);
                e.consume();
            }
        } else if (e.getCode() == KeyCode.I && e.isControlDown()) {
            setExpanded(!isExpanded());
            e.consume();
        }
    }

    /**
     * Default selection-to-section dispatch. Subclasses or external
     * controllers can override by binding to the
     * {@link InspectorSelectionModel} directly.
     */
    private void applySelectionToSections(InspectorSelection s) {
        switch (s) {
            case InspectorSelection.TrackSelection t -> {
                trackSection.trackIdProperty().set(t.trackId());
                if (getHeaderText() == null || getHeaderText().isEmpty()) {
                    setHeaderText("Track " + abbreviate(t.trackId()));
                }
            }
            case InspectorSelection.InsertSelection i ->
                insertsSection.selectedIndexProperty().set(i.insertIndex());
            case InspectorSelection.SendSelection ignored -> { /* handled by SendsSection rows */ }
            case InspectorSelection.ClipSelection ignored -> { /* clip surfaces in dedicated section in follow-on */ }
            case InspectorSelection.BusSelection ignored -> { /* bus selection routes into Routing section */ }
            case InspectorSelection.Empty ignored -> { /* placeholder */ }
        }
    }

    private static String abbreviate(java.util.UUID id) {
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new InspectorDrawerSkin(this);
    }

    @Override
    public String getUserAgentStylesheet() {
        return Objects.requireNonNull(
                InspectorDrawer.class.getResource("inspector.css"),
                "inspector.css not on classpath").toExternalForm();
    }

    /**
     * Installs event filters on {@code source} that fold
     * {@link TrackStrip.TrackSelectionEvent} /
     * {@link MixerChannelStrip.InsertSelectedEvent} /
     * {@link MixerChannelStrip.SendSelectedEvent} into this drawer's
     * {@link InspectorSelectionModel}. Call once during scene
     * construction with the common ancestor of the source controls
     * (typically the application root pane).
     *
     * @param source ancestor node whose subtree publishes the
     *               source-side selection events
     */
    public void installSourceEventForwarding(javafx.scene.Node source) {
        if (source == null) return;
        source.addEventFilter(TrackStrip.TrackSelectionEvent.SELECTION_REQUESTED, ev ->
                getSelectionModel().setSelection(
                        new InspectorSelection.TrackSelection(ev.getTrackId())));
        source.addEventFilter(MixerChannelStrip.InsertSelectedEvent.INSERT_SELECTED, ev -> {
            // Extract the channel's UUID from the originating MixerChannelStrip
            // so the InsertSelection disambiguates inserts across channels.
            java.util.UUID channelId = null;
            if (ev.getSource() instanceof MixerChannelStrip strip) {
                channelId = strip.getChannelId();
            }
            getSelectionModel().setSelection(
                    new InspectorSelection.InsertSelection(channelId, ev.getInsertIndex()));
        });
        source.addEventFilter(MixerChannelStrip.SendSelectedEvent.SEND_SELECTED, ev -> {
            java.util.UUID channelId = null;
            if (ev.getSource() instanceof MixerChannelStrip strip) {
                channelId = strip.getChannelId();
            }
            getSelectionModel().setSelection(
                    new InspectorSelection.SendSelection(channelId, ev.getSendIndex()));
        });
    }

    /** Manually publishes a selection — primarily for tests and external controllers. */
    public void selectFromExternal(InspectorSelection selection) {
        getSelectionModel().setSelection(selection);
    }

    // ── Public properties ─────────────────────────────────────────────────

    public final BooleanProperty expandedProperty()      { return expanded; }
    public final boolean isExpanded()                    { return expanded.get(); }
    public final void setExpanded(boolean e)             { expanded.set(e); }

    /**
     * @return the combined {@code animated} property (default
     *         {@code true}): {@code true} only when this drawer's
     *         per-control flag is set <em>and</em> global Reduce Motion is
     *         off (story 279). When {@code false} the skin's 220 ms
     *         open/close transition collapses to {@code 0 ms}. Read-only —
     *         write the per-control flag via {@link #setAnimated(boolean)}.
     */
    public final ReadOnlyBooleanProperty animatedProperty() {
        return animated.getReadOnlyProperty();
    }
    /** @return the combined animated value (per-control flag AND NOT Reduce Motion). */
    public final boolean isAnimated()                    { return animated.get(); }
    /**
     * Sets this drawer's per-control animation opt-out flag. The effective
     * {@link #isAnimated()} value also depends on the global Reduce Motion
     * setting (story 279).
     *
     * @param a whether the drawer should animate (per-control flag)
     */
    public final void setAnimated(boolean a)             { localAnimated.set(a); }

    public final StringProperty headerTextProperty()     { return headerText; }
    public final String getHeaderText()                  { return headerText.get(); }
    public final void setHeaderText(String t)            { headerText.set(t == null ? "" : t); }

    /**
     * @return the immutable selection model — always the same instance.
     *         Callers interact with the model directly; swapping the
     *         model itself is not supported (avoids listener leaks).
     */
    public final InspectorSelectionModel getSelectionModel() {
        return selectionModel;
    }

    // ── Section accessors ─────────────────────────────────────────────────

    public TrackSection         getTrackSection()         { return trackSection; }
    public InsertsSection       getInsertsSection()       { return insertsSection; }
    public SendsSection         getSendsSection()         { return sendsSection; }
    public RoutingSection       getRoutingSection()       { return routingSection; }
    public NotesSection         getNotesSection()         { return notesSection; }
    public NotificationsSection getNotificationsSection() { return notificationsSection; }

    /**
     * Re-points the Notifications section at the application's shared
     * notification log (story 273). Called by {@code MainController} so
     * the transient toast and the inspector history share one stream,
     * without breaking FXML's no-arg construction.
     *
     * @param svc the shared notification log
     */
    public void setNotificationHistoryService(NotificationHistoryService svc) {
        notificationsSection.setHistoryService(svc);
    }

    /** @return the six default sections in §5.6 order. */
    public List<InspectorSection> getSections() {
        return List.of(trackSection, insertsSection, sendsSection,
                routingSection, notesSection, notificationsSection);
    }

    // ── Styleable numeric width tokens ────────────────────────────────────

    private final StyleableDoubleProperty inspectorExpandedWidth =
            new StyleableDoubleProperty(DEFAULT_EXPANDED_WIDTH) {
                @Override public Object getBean() { return InspectorDrawer.this; }
                @Override public String getName() { return "inspectorExpandedWidth"; }
                @Override public CssMetaData<InspectorDrawer, Number> getCssMetaData() {
                    return StyleableProperties.EXPANDED_WIDTH;
                }
            };

    private final StyleableDoubleProperty inspectorCollapsedWidth =
            new StyleableDoubleProperty(DEFAULT_COLLAPSED_WIDTH) {
                @Override public Object getBean() { return InspectorDrawer.this; }
                @Override public String getName() { return "inspectorCollapsedWidth"; }
                @Override public CssMetaData<InspectorDrawer, Number> getCssMetaData() {
                    return StyleableProperties.COLLAPSED_WIDTH;
                }
            };

    /** @return styleable expanded width (CSS: {@code -inspector-expanded-width}). */
    public final StyleableDoubleProperty inspectorExpandedWidthProperty() {
        return inspectorExpandedWidth;
    }
    public final double getInspectorExpandedWidth() { return inspectorExpandedWidth.get(); }
    public final void setInspectorExpandedWidth(double v) { inspectorExpandedWidth.set(v); }

    /** @return styleable collapsed width (CSS: {@code -inspector-collapsed-width}). */
    public final StyleableDoubleProperty inspectorCollapsedWidthProperty() {
        return inspectorCollapsedWidth;
    }
    public final double getInspectorCollapsedWidth() { return inspectorCollapsedWidth.get(); }
    public final void setInspectorCollapsedWidth(double v) { inspectorCollapsedWidth.set(v); }

    // ── CssMetaData ───────────────────────────────────────────────────────

    private static final class StyleableProperties {

        static final CssMetaData<InspectorDrawer, Number> EXPANDED_WIDTH =
                new CssMetaData<>("-inspector-expanded-width",
                        StyleConverter.getSizeConverter(), DEFAULT_EXPANDED_WIDTH) {
                    @Override public boolean isSettable(InspectorDrawer d) {
                        return !d.inspectorExpandedWidth.isBound();
                    }
                    @Override public StyleableProperty<Number> getStyleableProperty(InspectorDrawer d) {
                        return d.inspectorExpandedWidth;
                    }
                };

        static final CssMetaData<InspectorDrawer, Number> COLLAPSED_WIDTH =
                new CssMetaData<>("-inspector-collapsed-width",
                        StyleConverter.getSizeConverter(), DEFAULT_COLLAPSED_WIDTH) {
                    @Override public boolean isSettable(InspectorDrawer d) {
                        return !d.inspectorCollapsedWidth.isBound();
                    }
                    @Override public StyleableProperty<Number> getStyleableProperty(InspectorDrawer d) {
                        return d.inspectorCollapsedWidth;
                    }
                };

        static final List<CssMetaData<? extends Styleable, ?>> CSS_META_DATA;

        static {
            List<CssMetaData<? extends Styleable, ?>> list =
                    new ArrayList<>(Control.getClassCssMetaData());
            Collections.addAll(list, EXPANDED_WIDTH, COLLAPSED_WIDTH);
            CSS_META_DATA = Collections.unmodifiableList(list);
        }

        private StyleableProperties() { }
    }

    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return StyleableProperties.CSS_META_DATA;
    }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getControlCssMetaData() {
        return getClassCssMetaData();
    }

    /** Convenience event-type re-export. */
    public static final javafx.event.EventType<InspectorSelectionEvent> SELECTION_CHANGED =
            InspectorSelectionEvent.SELECTION_CHANGED;

    /**
     * Convenience setter for an inspector-selection-changed handler.
     *
     * @param handler the handler, or {@code null} to clear
     */
    public final void setOnSelectionChanged(javafx.event.EventHandler<InspectorSelectionEvent> handler) {
        setEventHandler(SELECTION_CHANGED, handler);
    }

    /**
     * Resolves an i18n key from the inspector's
     * {@link ResourceBundle}. Falls back to the raw key if the bundle
     * does not contain a mapping (defensive against missing keys).
     */
    public static String msg(String key) {
        try {
            return MESSAGES.getString(key);
        } catch (java.util.MissingResourceException e) {
            return key;
        }
    }
}
