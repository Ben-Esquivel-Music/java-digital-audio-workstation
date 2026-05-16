package com.benesquivelmusic.daw.app.ui.inspector;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.controls.TrackStrip;
import com.benesquivelmusic.daw.app.ui.inspector.sections.InsertsSection;
import com.benesquivelmusic.daw.app.ui.inspector.sections.NotesSection;
import com.benesquivelmusic.daw.app.ui.inspector.sections.RoutingSection;
import com.benesquivelmusic.daw.app.ui.inspector.sections.SendsSection;
import com.benesquivelmusic.daw.app.ui.inspector.sections.TrackSection;
import com.benesquivelmusic.daw.app.ui.inspector.skin.InspectorDrawerSkin;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
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
import java.util.Objects;

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
 *       Notes), and a 16 px right-edge gutter.</li>
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

    /** Reduce-motion opt-out (story 279). */
    private final BooleanProperty animated =
            new SimpleBooleanProperty(this, "animated", true);

    /** Header bar text — typically "Track 01 — Drums" once a track is selected. */
    private final StringProperty headerText =
            new SimpleStringProperty(this, "headerText", "");

    private final ObjectProperty<InspectorSelectionModel> selectionModel =
            new SimpleObjectProperty<>(this, "selectionModel",
                    new InspectorSelectionModel());

    // ── Sections (created once, surfaced as accessors for tests) ──────────

    private final TrackSection trackSection;
    private final InsertsSection insertsSection;
    private final SendsSection sendsSection;
    private final RoutingSection routingSection;
    private final NotesSection notesSection;

    // ── Constructors ──────────────────────────────────────────────────────

    /** Creates a drawer with empty selection and the five default sections. */
    public InspectorDrawer() {
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        setAccessibleRole(AccessibleRole.NODE);
        setAccessibleRoleDescription("Inspector");
        setFocusTraversable(true);

        this.trackSection = new TrackSection(null);
        this.insertsSection = new InsertsSection(null, "+ Add");
        this.sendsSection = new SendsSection(null);
        this.routingSection = new RoutingSection(null);
        this.notesSection = new NotesSection(null);

        // Wire selection-model changes → typed inspector event so the
        // dispatch chain delivers it to any ancestor / sibling listener.
        selectionModel.get().selectionProperty().addListener((o, oldS, newS) -> {
            if (newS != null) {
                applySelectionToSections(newS);
                fireEvent(new InspectorSelectionEvent(newS));
            }
        });

        // Keyboard parity — Esc collapses; Ctrl+I toggles.
        addEventHandler(KeyEvent.KEY_PRESSED, this::handleKey);
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
        source.addEventFilter(MixerChannelStrip.InsertSelectedEvent.INSERT_SELECTED, ev ->
                getSelectionModel().setSelection(
                        new InspectorSelection.InsertSelection(null, ev.getInsertIndex())));
        source.addEventFilter(MixerChannelStrip.SendSelectedEvent.SEND_SELECTED, ev ->
                getSelectionModel().setSelection(
                        new InspectorSelection.SendSelection(null, ev.getSendIndex())));
    }

    /** Manually publishes a selection — primarily for tests and external controllers. */
    public void selectFromExternal(InspectorSelection selection) {
        getSelectionModel().setSelection(selection);
    }

    // ── Public properties ─────────────────────────────────────────────────

    public final BooleanProperty expandedProperty()      { return expanded; }
    public final boolean isExpanded()                    { return expanded.get(); }
    public final void setExpanded(boolean e)             { expanded.set(e); }

    public final BooleanProperty animatedProperty()      { return animated; }
    public final boolean isAnimated()                    { return animated.get(); }
    public final void setAnimated(boolean a)             { animated.set(a); }

    public final StringProperty headerTextProperty()     { return headerText; }
    public final String getHeaderText()                  { return headerText.get(); }
    public final void setHeaderText(String t)            { headerText.set(t == null ? "" : t); }

    public final ObjectProperty<InspectorSelectionModel> selectionModelProperty() {
        return selectionModel;
    }
    public final InspectorSelectionModel getSelectionModel() {
        return selectionModel.get();
    }

    // ── Section accessors ─────────────────────────────────────────────────

    public TrackSection   getTrackSection()   { return trackSection; }
    public InsertsSection getInsertsSection() { return insertsSection; }
    public SendsSection   getSendsSection()   { return sendsSection; }
    public RoutingSection getRoutingSection() { return routingSection; }
    public NotesSection   getNotesSection()   { return notesSection; }

    /** @return the five default sections in §5.6 order. */
    public List<InspectorSection> getSections() {
        return List.of(trackSection, insertsSection, sendsSection,
                routingSection, notesSection);
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
}
