package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.core.plugin.parameter.ABComparison;
import com.benesquivelmusic.daw.sdk.editor.ChromePolicy;
import com.benesquivelmusic.daw.sdk.editor.ResizePolicy;
import com.benesquivelmusic.daw.sdk.editor.Theme;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.CssMetaData;
import javafx.css.StyleConverter;
import javafx.css.Styleable;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Host-side plugin-editor frame {@link Control} — the standard chrome that
 * wraps every contract-driven plugin editor (story 301, Plugin View Design
 * Book §5.A "Workbench" / §5.D "Immersive canvas" / §6.1–§6.6).
 *
 * <p>Phase 2 of the §8 migration path: story 300 gave the SDK the
 * {@code PluginEditorFactory} contract; this control is how the host
 * honours it. The frame renders, for <em>every</em> editor mode
 * (declarative / panel / canvas):</p>
 *
 * <ul>
 *   <li>the <strong>breadcrumb header</strong> (§6.1) — vendor mark ▸ plugin
 *       name ▸ insert location, plus the four host actions
 *       {@code [Bypass] [A|B] [↗] [×]} on a single line that never wraps;</li>
 *   <li>the <strong>A/B compare bar</strong> (§6.5), revealed by the header
 *       {@code [A|B]} toggle and backed by the existing
 *       {@link ABComparison} machinery (this control carries display state
 *       only — see {@link #abActiveSlotProperty()});</li>
 *   <li>the <strong>fault banner</strong> (§6.6) — inline above the body,
 *       never modal, shown whenever {@link #faultMessageProperty()} is
 *       non-blank;</li>
 *   <li>the clipped <strong>body host</strong> (§6.3) — the mode-specific
 *       editor node installed via {@link #bodyProperty()}; the host enforces
 *       {@link ResizePolicy} (see {@link #applyResizePolicy}), the plugin
 *       cannot resize itself or paint outside its bounds;</li>
 *   <li>the <strong>footer</strong> (§6.4) — preset selector + Save /
 *       Save As, and always-present IN/OUT meters fed from
 *       {@link #metersProperty()}.</li>
 * </ul>
 *
 * <p>Design principle §2.2 — <em>"the plugin is a guest, not a
 * tenant"</em> — is structural: all chrome is host-rendered, the plugin can
 * neither add nor remove header actions, and {@link ChromePolicy} only
 * selects <em>how much</em> of the host chrome is shown
 * ({@code STANDARD} full, {@code MINIMAL} title-bar-ish per §5.C,
 * {@code IMMERSIVE} auto-retracting per §5.D).</p>
 *
 * <h2>Role-token forwarding (§2.5)</h2>
 *
 * <p>The six {@code -editor-frame-*} styleable colour properties mirror the
 * component structure of the SDK {@link Theme} record. The application
 * stylesheet forwards the resolved role tokens
 * ({@code -surface-1}/{@code -text}/{@code -accent}/{@code -meter-*}) into
 * them, and the host builds the {@link Theme} handed to canvas plugins from
 * the <em>resolved</em> values ({@link #getEditorBackground()} et al.) —
 * per the story technical note, colours are derived from resolved CSS,
 * never a mirrored hex literal. The Java-side defaults are the component
 * values of {@link Theme#neutral()}: the SDK's deliberately-neutral
 * fallback palette, replaced by resolved CSS the moment the frame is
 * styled — <strong>not</strong> a mirror of any shipped theme.</p>
 *
 * <p>Following the {@code controls}-package convention ({@code LevelMeter}
 * story 267, {@code Knob} story 268, {@code StatusStripCell} story 295)
 * this is the observable-state half of a Control/Skin split; the visuals
 * and interaction live in {@link EditorFrameSkin}.</p>
 *
 * @see EditorFrameSkin
 */
public final class EditorFrame extends Control {

    /** Stable style class — selectable as {@code .editor-frame} in CSS. */
    public static final String STYLE_CLASS = "editor-frame";

    // ── SDK neutral fallbacks (Theme.neutral()) ───────────────────────────
    // Defaults for the styleable -editor-frame-* colour properties. These
    // are the SDK's neutral greyscale fallback palette — NOT a mirror of
    // any shipped DAW theme — and are replaced by resolved CSS the moment
    // the frame is styled (story 301 technical note: "derive colours from
    // resolved CSS, never mirror a hex literal").
    private static final Theme NEUTRAL_THEME = Theme.neutral();
    private static final Color FALLBACK_BACKGROUND = NEUTRAL_THEME.background();
    private static final Color FALLBACK_FOREGROUND = NEUTRAL_THEME.foreground();
    private static final Color FALLBACK_ACCENT = NEUTRAL_THEME.accent();
    private static final Color FALLBACK_METER_SAFE = NEUTRAL_THEME.meterSafe();
    private static final Color FALLBACK_METER_WARN = NEUTRAL_THEME.meterWarn();
    private static final Color FALLBACK_METER_CLIP = NEUTRAL_THEME.meterClip();

    // ── Identity (breadcrumb §6.1) ────────────────────────────────────────

    private final StringProperty pluginName =
            new SimpleStringProperty(this, "pluginName", "");
    private final StringProperty vendor =
            new SimpleStringProperty(this, "vendor", "");
    private final StringProperty insertLocation =
            new SimpleStringProperty(this, "insertLocation", "");

    // ── Policy (host enforces; §6.3) ──────────────────────────────────────

    private final ObjectProperty<ChromePolicy> chromePolicy =
            new SimpleObjectProperty<>(this, "chromePolicy", ChromePolicy.STANDARD) {
                @Override
                public void set(ChromePolicy newValue) {
                    super.set(Objects.requireNonNull(newValue, "chromePolicy"));
                }
            };
    private final ObjectProperty<ResizePolicy> resizePolicy =
            new SimpleObjectProperty<>(this, "resizePolicy", ResizePolicy.BOTH) {
                @Override
                public void set(ResizePolicy newValue) {
                    super.set(Objects.requireNonNull(newValue, "resizePolicy"));
                }
            };
    private final DoubleProperty preferredBodyWidth =
            new SimpleDoubleProperty(this, "preferredBodyWidth", 360.0);
    private final DoubleProperty preferredBodyHeight =
            new SimpleDoubleProperty(this, "preferredBodyHeight", 240.0);

    // ── State ─────────────────────────────────────────────────────────────

    private final BooleanProperty bypassed =
            new SimpleBooleanProperty(this, "bypassed", false);
    private final BooleanProperty abBarVisible =
            new SimpleBooleanProperty(this, "abBarVisible", false);
    private final ObjectProperty<ABComparison.Slot> abActiveSlot =
            new SimpleObjectProperty<>(this, "abActiveSlot", ABComparison.Slot.A) {
                @Override
                public void set(ABComparison.Slot newValue) {
                    // Display-only slot indicator — a null is coerced to the
                    // A default (StatusStripCell severity precedent) so the
                    // skin never has to branch on a missing slot.
                    super.set(newValue == null ? ABComparison.Slot.A : newValue);
                }
            };
    private final ObjectProperty<PluginMeterSnapshot> meters =
            new SimpleObjectProperty<>(this, "meters", PluginMeterSnapshot.SILENT) {
                @Override
                public void set(PluginMeterSnapshot newValue) {
                    // Never null: a null write is coerced to SILENT so the
                    // footer meters always have a coherent snapshot (§6.4
                    // "I/O meters always present").
                    super.set(newValue == null ? PluginMeterSnapshot.SILENT : newValue);
                }
            };
    private final StringProperty faultMessage =
            new SimpleStringProperty(this, "faultMessage", "");
    private final ObjectProperty<Node> body =
            new SimpleObjectProperty<>(this, "body", null);
    private final ObservableList<String> presetItems =
            FXCollections.observableArrayList();
    private final StringProperty selectedPreset =
            new SimpleStringProperty(this, "selectedPreset", null);

    // ── Momentary action callbacks (all nullable) ─────────────────────────
    // Plain fields per the codebase precedent (AudioEditorView,
    // SessionStatusStrip, TaskProgressIndicator) — invoked by the skin's
    // buttons / key handlers; a null callback is simply a no-op.

    private Runnable onCloseRequested;
    private Runnable onDetachRequested;
    private Runnable onReloadRequested;
    private Runnable onFaultDetailsRequested;
    private Runnable onAbToggleRequested;
    private Runnable onAbCopyRequested;
    private Runnable onSavePresetRequested;
    private Runnable onSaveAsPresetRequested;
    private Consumer<String> onPresetSelected;

    /**
     * Creates an editor frame with defaults: {@link ChromePolicy#STANDARD},
     * {@link ResizePolicy#BOTH}, preferred body 360 × 240, not bypassed,
     * A/B bar hidden, slot {@code A} active, {@link PluginMeterSnapshot#SILENT}
     * meters, no fault, no body.
     */
    public EditorFrame() {
        getStyleClass().add(STYLE_CLASS);
        setAccessibleRole(AccessibleRole.NODE);
        setAccessibleRoleDescription("Plugin editor");
        // Focus traversability makes the §6.1 keyboard contract (B bypass,
        // Cmd+\ A/B, Cmd+D detach, Cmd+W close) reachable when the frame
        // itself is focused, not only while a child control has focus
        // (mirrors the Knob rationale — otherwise the keyboard contract
        // is unobservable).
        setFocusTraversable(true);
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new EditorFrameSkin(this);
    }

    // ── pluginName ────────────────────────────────────────────────────────

    /** @return the plugin display-name property (breadcrumb §6.1, from {@code PluginDescriptor#name}). */
    public final StringProperty pluginNameProperty() { return pluginName; }
    /** @return the plugin display name. */
    public final String getPluginName() { return pluginName.get(); }
    /** @param v the plugin display name; a blank/null value omits the crumb. */
    public final void setPluginName(String v) { pluginName.set(v); }

    // ── vendor ────────────────────────────────────────────────────────────

    /** @return the vendor-mark property (breadcrumb §6.1, from {@code PluginDescriptor#vendor}). */
    public final StringProperty vendorProperty() { return vendor; }
    /** @return the vendor mark. */
    public final String getVendor() { return vendor.get(); }
    /** @param v the vendor mark; a blank/null value omits the crumb. */
    public final void setVendor(String v) { vendor.set(v); }

    // ── insertLocation ────────────────────────────────────────────────────

    /**
     * @return the insert-location property (breadcrumb §6.1 — e.g.
     *         {@code "Drum Bus / Insert 2"}). A {@code null} or blank value
     *         omits the crumb entirely.
     */
    public final StringProperty insertLocationProperty() { return insertLocation; }
    /** @return the insert location, possibly {@code null}/blank (crumb omitted). */
    public final String getInsertLocation() { return insertLocation.get(); }
    /** @param v the insert location; {@code null}/blank omits the crumb. */
    public final void setInsertLocation(String v) { insertLocation.set(v); }

    // ── chromePolicy ──────────────────────────────────────────────────────

    /**
     * @return the chrome-policy property (default
     *         {@link ChromePolicy#STANDARD}). The host owns the chrome in
     *         every mode (§2.2); the policy only selects how much is shown:
     *         {@code STANDARD} = full chrome, {@code MINIMAL} = header +
     *         fault banner only (§5.C), {@code IMMERSIVE} = chrome retracts
     *         after 2 s of pointer inactivity (§5.D). Writes of {@code null}
     *         are rejected with a {@link NullPointerException}.
     */
    public final ObjectProperty<ChromePolicy> chromePolicyProperty() { return chromePolicy; }
    /** @return the current chrome policy (never {@code null}). */
    public final ChromePolicy getChromePolicy() { return chromePolicy.get(); }
    /** @param v the chrome policy; must not be {@code null}. */
    public final void setChromePolicy(ChromePolicy v) {
        chromePolicy.set(Objects.requireNonNull(v, "chromePolicy"));
    }

    // ── resizePolicy ──────────────────────────────────────────────────────

    /**
     * @return the resize-policy property (default {@link ResizePolicy#BOTH}).
     *         Enforced by the host via {@link #applyResizePolicy} — the
     *         plugin cannot resize itself (§6.3). Writes of {@code null} are
     *         rejected with a {@link NullPointerException}.
     */
    public final ObjectProperty<ResizePolicy> resizePolicyProperty() { return resizePolicy; }
    /** @return the current resize policy (never {@code null}). */
    public final ResizePolicy getResizePolicy() { return resizePolicy.get(); }
    /** @param v the resize policy; must not be {@code null}. */
    public final void setResizePolicy(ResizePolicy v) {
        resizePolicy.set(Objects.requireNonNull(v, "resizePolicy"));
    }

    // ── preferredBodyWidth / preferredBodyHeight ──────────────────────────

    /** @return the preferred body width property in px (default 360) — the plugin's declared editor width. */
    public final DoubleProperty preferredBodyWidthProperty() { return preferredBodyWidth; }
    /** @return the preferred body width (px). */
    public final double getPreferredBodyWidth() { return preferredBodyWidth.get(); }
    /** @param v the preferred body width (px). */
    public final void setPreferredBodyWidth(double v) { preferredBodyWidth.set(v); }

    /** @return the preferred body height property in px (default 240) — the plugin's declared editor height. */
    public final DoubleProperty preferredBodyHeightProperty() { return preferredBodyHeight; }
    /** @return the preferred body height (px). */
    public final double getPreferredBodyHeight() { return preferredBodyHeight.get(); }
    /** @param v the preferred body height (px). */
    public final void setPreferredBodyHeight(double v) { preferredBodyHeight.set(v); }

    // ── bypassed ──────────────────────────────────────────────────────────

    /**
     * @return the bypass-state property (§6.1 — a state toggle, also wired
     *         to the mixer slot by the hosting controller). The skin's
     *         {@code [Bypass]} header toggle and the {@code B} key both
     *         write it.
     */
    public final BooleanProperty bypassedProperty() { return bypassed; }
    /** @return whether the plugin is bypassed. */
    public final boolean isBypassed() { return bypassed.get(); }
    /** @param v whether the plugin is bypassed. */
    public final void setBypassed(boolean v) { bypassed.set(v); }

    // ── abBarVisible ──────────────────────────────────────────────────────

    /**
     * @return whether the §6.5 A/B compare bar is revealed (default
     *         {@code false}). Toggled by the header {@code [A|B]} button.
     *         Under {@link ChromePolicy#MINIMAL} the bar stays hidden
     *         regardless of this flag; under {@link ChromePolicy#IMMERSIVE}
     *         it retracts with the rest of the chrome.
     */
    public final BooleanProperty abBarVisibleProperty() { return abBarVisible; }
    /** @return whether the A/B compare bar is revealed. */
    public final boolean isAbBarVisible() { return abBarVisible.get(); }
    /** @param v whether the A/B compare bar is revealed. */
    public final void setAbBarVisible(boolean v) { abBarVisible.set(v); }

    // ── abActiveSlot ──────────────────────────────────────────────────────

    /**
     * @return the display-only active A/B slot property (default
     *         {@link ABComparison.Slot#A}). The hosting controller mirrors
     *         the real {@link ABComparison#getActiveSlot()} into this;
     *         swapping slots is requested via
     *         {@link #setOnAbToggleRequested(Runnable)}, never by writing
     *         this property from the skin. A {@code null} write is coerced
     *         to {@code A}.
     */
    public final ObjectProperty<ABComparison.Slot> abActiveSlotProperty() { return abActiveSlot; }
    /** @return the active A/B slot (never {@code null}). */
    public final ABComparison.Slot getAbActiveSlot() { return abActiveSlot.get(); }
    /** @param v the active A/B slot; {@code null} is coerced to {@link ABComparison.Slot#A}. */
    public final void setAbActiveSlot(ABComparison.Slot v) { abActiveSlot.set(v); }

    // ── meters ────────────────────────────────────────────────────────────

    /**
     * @return the footer I/O meter snapshot property (§6.4 — "I/O meters
     *         always present"), default {@link PluginMeterSnapshot#SILENT},
     *         never {@code null} (a {@code null} write is coerced to
     *         {@code SILENT}). The hosting controller taps the parameter
     *         store's audio-side snapshot and mirrors it here on the FX
     *         thread; {@link Double#NEGATIVE_INFINITY} levels render as an
     *         empty meter, not a clip.
     */
    public final ObjectProperty<PluginMeterSnapshot> metersProperty() { return meters; }
    /** @return the current meter snapshot (never {@code null}). */
    public final PluginMeterSnapshot getMeters() { return meters.get(); }
    /** @param v the meter snapshot; {@code null} is coerced to {@link PluginMeterSnapshot#SILENT}. */
    public final void setMeters(PluginMeterSnapshot v) { meters.set(v); }

    // ── faultMessage ──────────────────────────────────────────────────────

    /**
     * @return the fault-banner message property (§6.6). A {@code null} or
     *         blank value hides the banner; any other text shows the inline
     *         (never modal) banner above the body with its {@code [Reload]}
     *         and {@code [ⓘ]} actions.
     */
    public final StringProperty faultMessageProperty() { return faultMessage; }
    /** @return the fault message, possibly {@code null}/blank (banner hidden). */
    public final String getFaultMessage() { return faultMessage.get(); }
    /** @param v the fault message; {@code null}/blank hides the banner. */
    public final void setFaultMessage(String v) { faultMessage.set(v); }

    // ── body ──────────────────────────────────────────────────────────────

    /**
     * @return the mode-specific editor body the session installs — the
     *         host-generated parameter grid (declarative), the plugin's
     *         {@link Region} (panel), or the host-owned canvas region
     *         (canvas). May be {@code null} (empty body host). The skin
     *         clips the body to its bounds (§6.3 — a misbehaving plugin
     *         cannot paint over the breadcrumb or footer) and, when the
     *         node is a {@link Region}, applies {@link #applyResizePolicy}.
     */
    public final ObjectProperty<Node> bodyProperty() { return body; }
    /** @return the current editor body node, or {@code null}. */
    public final Node getBody() { return body.get(); }
    /** @param v the editor body node, or {@code null} to empty the body host. */
    public final void setBody(Node v) { body.set(v); }

    // ── presets ───────────────────────────────────────────────────────────

    /**
     * @return the live observable preset-name list backing the footer's
     *         preset selector (§6.4). The hosting controller populates it
     *         from the {@code PluginParameterStore} snapshots; mutations
     *         are reflected by the skin's {@code ComboBox} directly.
     */
    public final ObservableList<String> getPresetItems() { return presetItems; }

    /**
     * @return the selected preset-name property, kept in two-way sync with
     *         the footer selector by the skin (listener + setter guard —
     *         never {@code bind()}). May be {@code null} (no selection).
     */
    public final StringProperty selectedPresetProperty() { return selectedPreset; }
    /** @return the selected preset name, or {@code null}. */
    public final String getSelectedPreset() { return selectedPreset.get(); }
    /** @param v the selected preset name, or {@code null} to clear. */
    public final void setSelectedPreset(String v) { selectedPreset.set(v); }

    // ── Momentary action callbacks ────────────────────────────────────────

    /** @param v callback for the {@code [×]} close action / {@code Cmd+W} (§6.1 — focus only, does not unload); nullable. */
    public final void setOnCloseRequested(Runnable v) { onCloseRequested = v; }
    /** @return the close-requested callback, or {@code null}. */
    public final Runnable getOnCloseRequested() { return onCloseRequested; }

    /** @param v callback for the {@code [↗]} detach action / {@code Cmd+D} (§6.1 — fires the plugin-detach intent); nullable. */
    public final void setOnDetachRequested(Runnable v) { onDetachRequested = v; }
    /** @return the detach-requested callback, or {@code null}. */
    public final Runnable getOnDetachRequested() { return onDetachRequested; }

    /** @param v callback for the fault banner's {@code [Reload]} action (§6.6 — re-invokes {@code editorFactory()}); nullable. */
    public final void setOnReloadRequested(Runnable v) { onReloadRequested = v; }
    /** @return the reload-requested callback, or {@code null}. */
    public final Runnable getOnReloadRequested() { return onReloadRequested; }

    /** @param v callback for the fault banner's {@code [ⓘ]} action (§6.6 — opens {@code PluginFaultLogDialog}); nullable. */
    public final void setOnFaultDetailsRequested(Runnable v) { onFaultDetailsRequested = v; }
    /** @return the fault-details callback, or {@code null}. */
    public final Runnable getOnFaultDetailsRequested() { return onFaultDetailsRequested; }

    /** @param v callback for an A/B slot swap request (§6.5 {@code [Swap]} / {@code Cmd+\}); nullable. */
    public final void setOnAbToggleRequested(Runnable v) { onAbToggleRequested = v; }
    /** @return the A/B-toggle callback, or {@code null}. */
    public final Runnable getOnAbToggleRequested() { return onAbToggleRequested; }

    /** @param v callback for the §6.5 {@code [Copy A▸B]} action (copy active slot to inactive); nullable. */
    public final void setOnAbCopyRequested(Runnable v) { onAbCopyRequested = v; }
    /** @return the A/B-copy callback, or {@code null}. */
    public final Runnable getOnAbCopyRequested() { return onAbCopyRequested; }

    /** @param v callback for the footer {@code [Save…]} preset action (§6.4); nullable. */
    public final void setOnSavePresetRequested(Runnable v) { onSavePresetRequested = v; }
    /** @return the save-preset callback, or {@code null}. */
    public final Runnable getOnSavePresetRequested() { return onSavePresetRequested; }

    /** @param v callback for the footer {@code [Save As…]} preset action (§6.4); nullable. */
    public final void setOnSaveAsPresetRequested(Runnable v) { onSaveAsPresetRequested = v; }
    /** @return the save-as-preset callback, or {@code null}. */
    public final Runnable getOnSaveAsPresetRequested() { return onSaveAsPresetRequested; }

    /**
     * @param v callback invoked with the preset name when the user picks
     *          one in the footer selector (§6.4). Not invoked for
     *          programmatic {@link #setSelectedPreset(String)} writes;
     *          nullable.
     */
    public final void setOnPresetSelected(Consumer<String> v) { onPresetSelected = v; }
    /** @return the preset-selected callback, or {@code null}. */
    public final Consumer<String> getOnPresetSelected() { return onPresetSelected; }

    // ── Resize-policy enforcement (§6.3) ──────────────────────────────────

    /**
     * Applies a {@link ResizePolicy} to a plugin body {@link Region} by
     * pinning / freeing its min / pref / max constraints. This is the pure,
     * statically-testable seam behind §6.3 — <em>"the plugin region honours
     * {@code EditorHints.resize()}; host enforces, plugin cannot resize
     * itself"</em> ({@code EditorHintsResizePolicyTest} asserts on it).
     *
     * <p>Per-policy mapping:</p>
     * <ul>
     *   <li>{@link ResizePolicy#FIXED} — both axes pinned:
     *       {@code min = pref = max = (prefW, prefH)}. The host shows no
     *       resize grip.</li>
     *   <li>{@link ResizePolicy#HORIZONTAL} — height pinned
     *       ({@code min = pref = max height = prefH}), width free
     *       ({@code minWidth = USE_COMPUTED_SIZE}, {@code prefWidth = prefW},
     *       {@code maxWidth = Double.MAX_VALUE}).</li>
     *   <li>{@link ResizePolicy#VERTICAL} — mirror image: width pinned
     *       ({@code min = pref = max width = prefW}), height free
     *       ({@code minHeight = USE_COMPUTED_SIZE}, {@code prefHeight = prefH},
     *       {@code maxHeight = Double.MAX_VALUE}).</li>
     *   <li>{@link ResizePolicy#BOTH} — freely resizable:
     *       {@code pref = (prefW, prefH)}, {@code max = Double.MAX_VALUE} on
     *       both axes, min left computed ({@code USE_COMPUTED_SIZE}).</li>
     * </ul>
     *
     * @param body   the plugin body region to constrain; must not be {@code null}
     * @param policy the resize policy to enforce; must not be {@code null}
     * @param prefW  the plugin's preferred body width (px)
     * @param prefH  the plugin's preferred body height (px)
     */
    public static void applyResizePolicy(Region body, ResizePolicy policy,
            double prefW, double prefH) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(policy, "policy");
        switch (policy) {
            case FIXED -> {
                body.setMinSize(prefW, prefH);
                body.setPrefSize(prefW, prefH);
                body.setMaxSize(prefW, prefH);
            }
            case HORIZONTAL -> {
                body.setMinWidth(Region.USE_COMPUTED_SIZE);
                body.setPrefWidth(prefW);
                body.setMaxWidth(Double.MAX_VALUE);
                body.setMinHeight(prefH);
                body.setPrefHeight(prefH);
                body.setMaxHeight(prefH);
            }
            case VERTICAL -> {
                body.setMinWidth(prefW);
                body.setPrefWidth(prefW);
                body.setMaxWidth(prefW);
                body.setMinHeight(Region.USE_COMPUTED_SIZE);
                body.setPrefHeight(prefH);
                body.setMaxHeight(Double.MAX_VALUE);
            }
            case BOTH -> {
                body.setMinWidth(Region.USE_COMPUTED_SIZE);
                body.setMinHeight(Region.USE_COMPUTED_SIZE);
                body.setPrefSize(prefW, prefH);
                body.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            }
        }
    }

    // ── Styleable role-token colour properties (§2.5) ─────────────────────
    // Six StyleableObjectProperty<Color> mirroring the SDK Theme record's
    // components. styles.css forwards -surface-1/-text/-accent/-meter-* into
    // these; the host reads the RESOLVED values to build the Theme handed to
    // canvas plugins (never a hex mirror). Pattern copied from Knob.

    private final StyleableObjectProperty<Color> editorBackground =
            new StyleableObjectProperty<>(FALLBACK_BACKGROUND) {
                @Override public Object getBean() { return EditorFrame.this; }
                @Override public String getName() { return "editorBackground"; }
                @Override public CssMetaData<EditorFrame, Color> getCssMetaData() {
                    return StyleableProperties.BACKGROUND;
                }
            };
    private final StyleableObjectProperty<Color> editorForeground =
            new StyleableObjectProperty<>(FALLBACK_FOREGROUND) {
                @Override public Object getBean() { return EditorFrame.this; }
                @Override public String getName() { return "editorForeground"; }
                @Override public CssMetaData<EditorFrame, Color> getCssMetaData() {
                    return StyleableProperties.FOREGROUND;
                }
            };
    private final StyleableObjectProperty<Color> editorAccent =
            new StyleableObjectProperty<>(FALLBACK_ACCENT) {
                @Override public Object getBean() { return EditorFrame.this; }
                @Override public String getName() { return "editorAccent"; }
                @Override public CssMetaData<EditorFrame, Color> getCssMetaData() {
                    return StyleableProperties.ACCENT;
                }
            };
    private final StyleableObjectProperty<Color> editorMeterSafe =
            new StyleableObjectProperty<>(FALLBACK_METER_SAFE) {
                @Override public Object getBean() { return EditorFrame.this; }
                @Override public String getName() { return "editorMeterSafe"; }
                @Override public CssMetaData<EditorFrame, Color> getCssMetaData() {
                    return StyleableProperties.METER_SAFE;
                }
            };
    private final StyleableObjectProperty<Color> editorMeterWarn =
            new StyleableObjectProperty<>(FALLBACK_METER_WARN) {
                @Override public Object getBean() { return EditorFrame.this; }
                @Override public String getName() { return "editorMeterWarn"; }
                @Override public CssMetaData<EditorFrame, Color> getCssMetaData() {
                    return StyleableProperties.METER_WARN;
                }
            };
    private final StyleableObjectProperty<Color> editorMeterClip =
            new StyleableObjectProperty<>(FALLBACK_METER_CLIP) {
                @Override public Object getBean() { return EditorFrame.this; }
                @Override public String getName() { return "editorMeterClip"; }
                @Override public CssMetaData<EditorFrame, Color> getCssMetaData() {
                    return StyleableProperties.METER_CLIP;
                }
            };

    /** @return the {@code -editor-frame-background} styleable colour property. */
    public final ObjectProperty<Color> editorBackgroundProperty() { return editorBackground; }
    /** @return the resolved editor background colour ({@link Theme#background()} role). */
    public final Color getEditorBackground() { return editorBackground.get(); }
    /** @param v the editor background colour. */
    public final void setEditorBackground(Color v) { editorBackground.set(v); }

    /** @return the {@code -editor-frame-foreground} styleable colour property. */
    public final ObjectProperty<Color> editorForegroundProperty() { return editorForeground; }
    /** @return the resolved editor foreground colour ({@link Theme#foreground()} role). */
    public final Color getEditorForeground() { return editorForeground.get(); }
    /** @param v the editor foreground colour. */
    public final void setEditorForeground(Color v) { editorForeground.set(v); }

    /** @return the {@code -editor-frame-accent} styleable colour property. */
    public final ObjectProperty<Color> editorAccentProperty() { return editorAccent; }
    /** @return the resolved editor accent colour ({@link Theme#accent()} role). */
    public final Color getEditorAccent() { return editorAccent.get(); }
    /** @param v the editor accent colour. */
    public final void setEditorAccent(Color v) { editorAccent.set(v); }

    /** @return the {@code -editor-frame-meter-safe} styleable colour property. */
    public final ObjectProperty<Color> editorMeterSafeProperty() { return editorMeterSafe; }
    /** @return the resolved nominal-band meter colour ({@link Theme#meterSafe()} role). */
    public final Color getEditorMeterSafe() { return editorMeterSafe.get(); }
    /** @param v the nominal-band meter colour. */
    public final void setEditorMeterSafe(Color v) { editorMeterSafe.set(v); }

    /** @return the {@code -editor-frame-meter-warn} styleable colour property. */
    public final ObjectProperty<Color> editorMeterWarnProperty() { return editorMeterWarn; }
    /** @return the resolved warning-band meter colour ({@link Theme#meterWarn()} role). */
    public final Color getEditorMeterWarn() { return editorMeterWarn.get(); }
    /** @param v the warning-band meter colour. */
    public final void setEditorMeterWarn(Color v) { editorMeterWarn.set(v); }

    /** @return the {@code -editor-frame-meter-clip} styleable colour property. */
    public final ObjectProperty<Color> editorMeterClipProperty() { return editorMeterClip; }
    /** @return the resolved clip-band meter colour ({@link Theme#meterClip()} role). */
    public final Color getEditorMeterClip() { return editorMeterClip.get(); }
    /** @param v the clip-band meter colour. */
    public final void setEditorMeterClip(Color v) { editorMeterClip.set(v); }

    // ── CssMetaData ───────────────────────────────────────────────────────

    private static final class StyleableProperties {

        static final CssMetaData<EditorFrame, Color> BACKGROUND =
                new CssMetaData<>("-editor-frame-background",
                        StyleConverter.getColorConverter(), FALLBACK_BACKGROUND) {
                    @Override public boolean isSettable(EditorFrame f) {
                        return !f.editorBackground.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(EditorFrame f) {
                        return f.editorBackground;
                    }
                };
        static final CssMetaData<EditorFrame, Color> FOREGROUND =
                new CssMetaData<>("-editor-frame-foreground",
                        StyleConverter.getColorConverter(), FALLBACK_FOREGROUND) {
                    @Override public boolean isSettable(EditorFrame f) {
                        return !f.editorForeground.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(EditorFrame f) {
                        return f.editorForeground;
                    }
                };
        static final CssMetaData<EditorFrame, Color> ACCENT =
                new CssMetaData<>("-editor-frame-accent",
                        StyleConverter.getColorConverter(), FALLBACK_ACCENT) {
                    @Override public boolean isSettable(EditorFrame f) {
                        return !f.editorAccent.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(EditorFrame f) {
                        return f.editorAccent;
                    }
                };
        static final CssMetaData<EditorFrame, Color> METER_SAFE =
                new CssMetaData<>("-editor-frame-meter-safe",
                        StyleConverter.getColorConverter(), FALLBACK_METER_SAFE) {
                    @Override public boolean isSettable(EditorFrame f) {
                        return !f.editorMeterSafe.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(EditorFrame f) {
                        return f.editorMeterSafe;
                    }
                };
        static final CssMetaData<EditorFrame, Color> METER_WARN =
                new CssMetaData<>("-editor-frame-meter-warn",
                        StyleConverter.getColorConverter(), FALLBACK_METER_WARN) {
                    @Override public boolean isSettable(EditorFrame f) {
                        return !f.editorMeterWarn.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(EditorFrame f) {
                        return f.editorMeterWarn;
                    }
                };
        static final CssMetaData<EditorFrame, Color> METER_CLIP =
                new CssMetaData<>("-editor-frame-meter-clip",
                        StyleConverter.getColorConverter(), FALLBACK_METER_CLIP) {
                    @Override public boolean isSettable(EditorFrame f) {
                        return !f.editorMeterClip.isBound();
                    }
                    @Override public StyleableProperty<Color> getStyleableProperty(EditorFrame f) {
                        return f.editorMeterClip;
                    }
                };

        static final List<CssMetaData<? extends Styleable, ?>> CSS_META_DATA;

        static {
            List<CssMetaData<? extends Styleable, ?>> list =
                    new ArrayList<>(Control.getClassCssMetaData());
            Collections.addAll(list,
                    BACKGROUND, FOREGROUND, ACCENT,
                    METER_SAFE, METER_WARN, METER_CLIP);
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
}
