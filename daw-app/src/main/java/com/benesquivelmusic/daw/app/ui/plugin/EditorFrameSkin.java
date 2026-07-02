package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.controls.BreadcrumbBar;
import com.benesquivelmusic.daw.app.ui.controls.LevelMeter;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.core.plugin.parameter.ABComparison;
import com.benesquivelmusic.daw.sdk.editor.ChromePolicy;
import com.benesquivelmusic.daw.sdk.editor.ResizePolicy;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SkinBase;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Skin for {@link EditorFrame} — renders the story-301 host chrome
 * (Plugin View Design Book §5.A / §5.D, §6.1–§6.6) and wires the §6.1
 * keyboard / pointer interaction contract.
 *
 * <h2>Structure (top to bottom, a single {@link VBox})</h2>
 *
 * <ol>
 *   <li><strong>Header</strong> {@code .editor-frame-header} (§6.1) — a
 *       {@link BreadcrumbBar} deriving its segments from vendor / plugin
 *       name / insert location (blank segments skipped), a growing spacer,
 *       then the four host actions in order: bypass toggle, A/B toggle,
 *       detach, close. Single line, never wraps; the plugin cannot add to
 *       or remove from this row.</li>
 *   <li><strong>A/B compare bar</strong> {@code .editor-ab-bar} (§6.5) —
 *       revealed by the header {@code [A|B]} toggle; slot indicator labels
 *       (the active one carries {@code .editor-ab-active}) plus
 *       {@code [Copy A▸B]} and {@code [Swap]}.</li>
 *   <li><strong>Fault banner</strong> {@code .editor-fault-banner} (§6.6) —
 *       shown while {@link EditorFrame#faultMessageProperty()} is
 *       non-blank; inline above the body, never modal, and <em>never</em>
 *       auto-hidden by the immersive chrome retraction.</li>
 *   <li><strong>Body host</strong> {@code .editor-frame-body} (§6.3) — a
 *       {@link StackPane} holding {@link EditorFrame#bodyProperty()},
 *       clipped to its own bounds so a misbehaving plugin cannot paint
 *       over the breadcrumb or footer. When the body is a {@link Region}
 *       the host enforces {@link EditorFrame#applyResizePolicy}.</li>
 *   <li><strong>Footer</strong> {@code .editor-frame-footer} (§6.4) —
 *       preset selector + Save / Save As, then IN / OUT
 *       {@link LevelMeter}s fed from {@link EditorFrame#metersProperty()}
 *       ({@link Double#NEGATIVE_INFINITY} renders empty — it normalises
 *       below the meter's −60 dB floor, never as a clip).</li>
 * </ol>
 *
 * <h2>Chrome policy (§5.C / §5.D)</h2>
 *
 * <p>{@code STANDARD}: header + footer visible, A/B bar per
 * {@code abBarVisible}, banner per fault. {@code MINIMAL}: footer and A/B
 * bar hidden — the frame collapses toward a title bar; header and fault
 * banner remain. {@code IMMERSIVE}: all chrome starts visible; after 2 s
 * of pointer inactivity over the frame the header / footer / A/B bar
 * retract (the fault banner never auto-hides — a fault must stay
 * visible), and any {@code MOUSE_MOVED} / mouse-enter over the frame or a
 * focus gain reveals them again. Retraction / reveal fades opacity over
 * 180 ms ({@link Interpolator#EASE_BOTH}) and then flips
 * {@code visible}/{@code managed} so the body reclaims the pixels.</p>
 *
 * <p>The fade is motion-gated per story 279: the skin captures
 * {@link MotionManager#getDefault()} once in a final field and, when
 * Reduce Motion is on, snaps {@code visible}/{@code managed} with no fade
 * — Reduce Motion removes the <em>motion</em>, not the behaviour. The
 * global flag is observed through a strong listener field wrapped in a
 * {@link WeakChangeListener} so the process-lifetime singleton never pins
 * this skin (the {@code KnobSkin}/story-277 sanctioned pattern).</p>
 *
 * <h2>Keyboard map (§6.1)</h2>
 *
 * <p>Registered on the control, ignoring events targeted at text-input
 * controls: {@code B} toggles bypass, {@code Shortcut+\} requests an A/B
 * swap, {@code Shortcut+D} requests detach, {@code Shortcut+W} requests
 * close. Handled events are consumed.</p>
 *
 * <h2>Two-way syncs</h2>
 *
 * <p>The bypass toggle ↔ {@code bypassedProperty}, A/B toggle ↔
 * {@code abBarVisibleProperty} and preset combo ↔
 * {@code selectedPresetProperty} pairs are synced via listener + setter
 * with a reentrancy guard — never {@code bind()}, because a two-way
 * control's own property must stay writable from both sides
 * ({@code feedback_bind_two_way_control_property}).</p>
 */
public final class EditorFrameSkin extends SkinBase<EditorFrame> {

    /** Chrome retract / reveal fade duration (§5.D, 180 ms motion budget). */
    private static final Duration CHROME_FADE = Duration.millis(180);
    /** §5.D pointer-inactivity period before the immersive chrome retracts. */
    private static final Duration IMMERSIVE_INACTIVITY = Duration.seconds(2);
    /** Header / fault-banner icon size (px). */
    private static final int ICON_SIZE = 16;

    /** Chrome strings (Skill §14) — {@code Locale.ROOT}, BrowserPanel idiom. */
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    // ── Nodes ─────────────────────────────────────────────────────────────

    private final VBox root;
    private final HBox header;
    private final BreadcrumbBar breadcrumb;
    private final ToggleButton bypassButton;
    private final ToggleButton abButton;
    private final Button detachButton;
    private final Button closeButton;
    private final HBox abBar;
    private final Label slotALabel;
    private final Label slotBLabel;
    private final HBox faultBanner;
    private final Label faultMessageLabel;
    private final StackPane bodyHost;
    private final Rectangle bodyClip;
    private final HBox footer;
    private final ComboBox<String> presetCombo;
    private final LevelMeter inMeter;
    private final LevelMeter outMeter;

    // ── Motion (story 279) ────────────────────────────────────────────────
    // Captured ONCE at construction so the weak registration and every
    // later isAnimationAllowed() read the SAME MotionManager — a
    // getDefault() swap mid-life (setDefaultForTest) cannot make them
    // diverge (feedback_capture_swappable_singleton_reference).
    private final MotionManager motionManager = MotionManager.getDefault();
    // Strong field — lives exactly as long as this skin; registered via a
    // WeakChangeListener so the singleton never pins the skin.
    private final ChangeListener<Boolean> reduceMotionListener;
    private final WeakChangeListener<Boolean> weakReduceMotionListener;

    private final PauseTransition inactivityTimer;
    /** Per-node running chrome fade — stopped before any new state apply. */
    private final Map<Node, FadeTransition> runningFades = new HashMap<>();

    // ── Listeners on control properties (removed in dispose()) ───────────

    private final ChangeListener<String> breadcrumbListener;
    private final ChangeListener<Boolean> bypassedListener;
    private final ChangeListener<Boolean> abBarVisibleListener;
    private final ChangeListener<ABComparison.Slot> abSlotListener;
    private final ChangeListener<ChromePolicy> chromePolicyListener;
    private final ChangeListener<ResizePolicy> resizePolicyListener;
    private final ChangeListener<Number> bodySizeListener;
    private final ChangeListener<PluginMeterSnapshot> metersListener;
    private final ChangeListener<String> faultListener;
    private final ChangeListener<Node> bodyListener;
    private final ChangeListener<String> selectedPresetListener;
    private final ChangeListener<Boolean> focusListener;
    private final EventHandler<KeyEvent> keyHandler;
    private final EventHandler<MouseEvent> pointerActivityFilter;

    // ── Listeners on skin-owned nodes (detached in dispose()) ────────────

    private final ChangeListener<Boolean> bypassButtonListener;
    private final ChangeListener<Boolean> abButtonListener;
    private final ChangeListener<String> presetComboListener;

    // ── Reentrancy guards (never bind() a two-way control property) ──────

    private boolean syncingBypass;
    private boolean syncingAbBar;
    private boolean syncingPreset;

    /** {@code true} while the §5.D immersive chrome is retracted. */
    private boolean chromeRetracted;
    private boolean disposed;

    /**
     * Builds the full §6 chrome around the given frame and applies the
     * current control state (breadcrumb, toggles, fault banner, body,
     * meters, chrome-policy visibility).
     *
     * @param control the {@link EditorFrame} this skin renders
     */
    public EditorFrameSkin(EditorFrame control) {
        super(control);

        // ── 1. Header (§6.1) ─────────────────────────────────────────────
        breadcrumb = new BreadcrumbBar();
        breadcrumb.getStyleClass().add("editor-frame-breadcrumb");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        bypassButton = new ToggleButton();
        bypassButton.setGraphic(IconNode.of(DawIcon.POWER, ICON_SIZE));
        bypassButton.getStyleClass().addAll("dawg-button", "size-compact", "editor-frame-bypass");
        bypassButton.setTooltip(new Tooltip(msg("editor.frame.bypass.tooltip")));
        bypassButton.setAccessibleText(msg("editor.frame.bypass.accessible"));

        abButton = new ToggleButton();
        abButton.getStyleClass().addAll("dawg-button", "size-compact", "editor-frame-ab");
        abButton.setTooltip(new Tooltip(msg("editor.frame.ab.tooltip")));

        detachButton = new Button();
        detachButton.setGraphic(IconNode.of(DawIcon.EXPAND, ICON_SIZE));
        detachButton.getStyleClass().addAll("dawg-button", "size-compact", "editor-frame-detach");
        detachButton.setTooltip(new Tooltip(msg("editor.frame.detach.tooltip")));
        detachButton.setAccessibleText(msg("editor.frame.detach.accessible"));
        detachButton.setOnAction(e -> {
            runCallback(getSkinnable().getOnDetachRequested());
            e.consume();
        });

        closeButton = new Button();
        closeButton.setGraphic(IconNode.of(DawIcon.CLOSE, ICON_SIZE));
        closeButton.getStyleClass().addAll("dawg-button", "size-compact", "editor-frame-close");
        closeButton.setTooltip(new Tooltip(msg("editor.frame.close.tooltip")));
        closeButton.setAccessibleText(msg("editor.frame.close.accessible"));
        closeButton.setOnAction(e -> {
            runCallback(getSkinnable().getOnCloseRequested());
            e.consume();
        });

        header = new HBox(breadcrumb, headerSpacer,
                bypassButton, abButton, detachButton, closeButton);
        header.getStyleClass().add("editor-frame-header");
        header.setAlignment(Pos.CENTER_LEFT);

        // ── 2. A/B compare bar (§6.5) ────────────────────────────────────
        slotALabel = new Label(ABComparison.Slot.A.name());
        slotALabel.getStyleClass().add("editor-ab-slot");
        slotBLabel = new Label(ABComparison.Slot.B.name());
        slotBLabel.getStyleClass().add("editor-ab-slot");

        Button abCopyButton = new Button(msg("editor.frame.ab.copy"));
        abCopyButton.getStyleClass().addAll("dawg-button", "size-compact", "editor-ab-copy");
        abCopyButton.setTooltip(new Tooltip(msg("editor.frame.ab.copy.tooltip")));
        abCopyButton.setAccessibleText(msg("editor.frame.ab.copy"));
        abCopyButton.setOnAction(e -> {
            runCallback(getSkinnable().getOnAbCopyRequested());
            e.consume();
        });

        Button abSwapButton = new Button(msg("editor.frame.ab.swap"));
        abSwapButton.getStyleClass().addAll("dawg-button", "size-compact", "editor-ab-swap");
        abSwapButton.setTooltip(new Tooltip(msg("editor.frame.ab.swap.tooltip")));
        abSwapButton.setAccessibleText(msg("editor.frame.ab.swap"));
        abSwapButton.setOnAction(e -> {
            runCallback(getSkinnable().getOnAbToggleRequested());
            e.consume();
        });

        Region abSpacer = new Region();
        HBox.setHgrow(abSpacer, Priority.ALWAYS);
        abBar = new HBox(slotALabel, slotBLabel, abSpacer, abCopyButton, abSwapButton);
        abBar.getStyleClass().add("editor-ab-bar");
        abBar.setAlignment(Pos.CENTER_LEFT);

        // ── 3. Fault banner (§6.6) ───────────────────────────────────────
        faultMessageLabel = new Label();
        faultMessageLabel.getStyleClass().add("editor-fault-message");
        faultMessageLabel.setWrapText(true);

        Button reloadButton = new Button(msg("editor.frame.reload.label"));
        reloadButton.getStyleClass().addAll("dawg-button", "size-compact", "editor-fault-reload");
        reloadButton.setTooltip(new Tooltip(msg("editor.frame.reload.tooltip")));
        reloadButton.setAccessibleText(msg("editor.frame.reload.accessible"));
        reloadButton.setOnAction(e -> {
            runCallback(getSkinnable().getOnReloadRequested());
            e.consume();
        });

        Button faultInfoButton = new Button();
        faultInfoButton.setGraphic(IconNode.of(DawIcon.INFO_CIRCLE, ICON_SIZE));
        faultInfoButton.getStyleClass().addAll("dawg-button", "size-compact", "editor-fault-info");
        faultInfoButton.setTooltip(new Tooltip(msg("editor.frame.faultInfo.tooltip")));
        faultInfoButton.setAccessibleText(msg("editor.frame.faultInfo.accessible"));
        faultInfoButton.setOnAction(e -> {
            runCallback(getSkinnable().getOnFaultDetailsRequested());
            e.consume();
        });

        Region faultSpacer = new Region();
        HBox.setHgrow(faultSpacer, Priority.ALWAYS);
        HBox.setHgrow(faultMessageLabel, Priority.SOMETIMES);
        faultBanner = new HBox(IconNode.of(DawIcon.WARNING, ICON_SIZE),
                faultMessageLabel, faultSpacer, reloadButton, faultInfoButton);
        faultBanner.getStyleClass().add("editor-fault-banner");
        faultBanner.setAlignment(Pos.CENTER_LEFT);

        // ── 4. Body host (§6.3) ──────────────────────────────────────────
        bodyHost = new StackPane();
        bodyHost.getStyleClass().add("editor-frame-body");
        // Clip to the host's own bounds — a misbehaving plugin cannot paint
        // over the breadcrumb or footer (§6.3). A Region's layout bounds
        // are (0, 0, width, height), so binding the rectangle to the
        // width/height properties tracks the layout bounds exactly.
        bodyClip = new Rectangle();
        bodyClip.widthProperty().bind(bodyHost.widthProperty());
        bodyClip.heightProperty().bind(bodyHost.heightProperty());
        bodyHost.setClip(bodyClip);
        VBox.setVgrow(bodyHost, Priority.ALWAYS);

        // ── 5. Footer (§6.4) ─────────────────────────────────────────────
        Label presetLabel = new Label(msg("editor.frame.preset.label"));
        presetLabel.getStyleClass().add("editor-frame-preset-label");

        presetCombo = new ComboBox<>();
        presetCombo.getStyleClass().add("editor-frame-preset");
        presetCombo.setItems(control.getPresetItems());
        presetCombo.setAccessibleText(msg("editor.frame.preset.label"));

        Button saveButton = new Button(msg("editor.frame.preset.save"));
        saveButton.getStyleClass().addAll("dawg-button", "size-compact", "editor-frame-preset-save");
        saveButton.setTooltip(new Tooltip(msg("editor.frame.preset.save.tooltip")));
        saveButton.setAccessibleText(msg("editor.frame.preset.save"));
        saveButton.setOnAction(e -> {
            runCallback(getSkinnable().getOnSavePresetRequested());
            e.consume();
        });

        Button saveAsButton = new Button(msg("editor.frame.preset.saveAs"));
        saveAsButton.getStyleClass().addAll("dawg-button", "size-compact", "editor-frame-preset-save-as");
        saveAsButton.setTooltip(new Tooltip(msg("editor.frame.preset.saveAs.tooltip")));
        saveAsButton.setAccessibleText(msg("editor.frame.preset.saveAs"));
        saveAsButton.setOnAction(e -> {
            runCallback(getSkinnable().getOnSaveAsPresetRequested());
            e.consume();
        });

        Label inCaption = new Label(msg("editor.frame.meter.in"));
        inCaption.getStyleClass().add("editor-frame-meter-caption");
        inMeter = LevelMeter.create()
                .channels(1)
                .orientation(Orientation.HORIZONTAL)
                .build();
        inMeter.getStyleClass().add("editor-frame-meter-in");

        Label outCaption = new Label(msg("editor.frame.meter.out"));
        outCaption.getStyleClass().add("editor-frame-meter-caption");
        outMeter = LevelMeter.create()
                .channels(1)
                .orientation(Orientation.HORIZONTAL)
                .build();
        outMeter.getStyleClass().add("editor-frame-meter-out");

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        footer = new HBox(presetLabel, presetCombo, saveButton, saveAsButton,
                footerSpacer, inCaption, inMeter, outCaption, outMeter);
        footer.getStyleClass().add("editor-frame-footer");
        footer.setAlignment(Pos.CENTER_LEFT);

        root = new VBox(header, abBar, faultBanner, bodyHost, footer);
        getChildren().add(root);

        // ── Two-way syncs (listener + setter guard — never bind()) ───────
        bypassButton.setSelected(control.isBypassed());
        bypassedListener = (obs, was, now) -> {
            if (disposed || syncingBypass) {
                return;
            }
            syncingBypass = true;
            try {
                bypassButton.setSelected(now);
            } finally {
                syncingBypass = false;
            }
        };
        bypassButtonListener = (obs, was, now) -> {
            if (disposed || syncingBypass) {
                return;
            }
            syncingBypass = true;
            try {
                getSkinnable().setBypassed(now);
            } finally {
                syncingBypass = false;
            }
        };

        abButton.setSelected(control.isAbBarVisible());
        abBarVisibleListener = (obs, was, now) -> {
            if (disposed) {
                return;
            }
            if (!syncingAbBar) {
                syncingAbBar = true;
                try {
                    abButton.setSelected(now);
                } finally {
                    syncingAbBar = false;
                }
            }
            applyChromeVisibility(false);
        };
        abButtonListener = (obs, was, now) -> {
            if (disposed || syncingAbBar) {
                return;
            }
            syncingAbBar = true;
            try {
                getSkinnable().setAbBarVisible(now);
            } finally {
                syncingAbBar = false;
            }
        };

        presetCombo.setValue(control.getSelectedPreset());
        selectedPresetListener = (obs, was, now) -> {
            if (disposed || syncingPreset) {
                return;
            }
            syncingPreset = true;
            try {
                presetCombo.setValue(now);
            } finally {
                syncingPreset = false;
            }
        };
        presetComboListener = (obs, was, now) -> {
            if (disposed || syncingPreset) {
                return;
            }
            syncingPreset = true;
            try {
                getSkinnable().setSelectedPreset(now);
            } finally {
                syncingPreset = false;
            }
            // A combo value change with the guard off is a user pick —
            // programmatic control-side writes route through the guarded
            // selectedPresetListener above and do NOT re-enter here.
            var callback = getSkinnable().getOnPresetSelected();
            if (callback != null) {
                callback.accept(now);
            }
        };

        // ── One-way state listeners ──────────────────────────────────────
        breadcrumbListener = (obs, was, now) -> {
            if (!disposed) {
                rebuildBreadcrumb();
            }
        };
        abSlotListener = (obs, was, now) -> {
            if (!disposed) {
                updateAbSlot();
            }
        };
        faultListener = (obs, was, now) -> {
            if (!disposed) {
                updateFaultBanner();
            }
        };
        bodyListener = (obs, was, now) -> {
            if (!disposed) {
                applyBody(now);
            }
        };
        metersListener = (obs, was, now) -> {
            if (!disposed) {
                applyMeters(now);
            }
        };
        resizePolicyListener = (obs, was, now) -> {
            if (!disposed) {
                reapplyBodyPolicy();
            }
        };
        bodySizeListener = (obs, was, now) -> {
            if (!disposed) {
                reapplyBodyPolicy();
            }
        };

        // ── Chrome policy + §5.D immersive machinery ─────────────────────
        inactivityTimer = new PauseTransition(IMMERSIVE_INACTIVITY);
        inactivityTimer.setOnFinished(e -> onImmersiveInactivity());

        chromePolicyListener = (obs, was, now) -> {
            if (disposed) {
                return;
            }
            // Leaving IMMERSIVE stops the timer and restores chrome fully;
            // entering it starts the countdown with the chrome visible.
            inactivityTimer.stop();
            chromeRetracted = false;
            if (now == ChromePolicy.IMMERSIVE) {
                inactivityTimer.playFromStart();
            }
            applyChromeVisibility(false);
        };

        pointerActivityFilter = e -> onPointerActivity();
        focusListener = (obs, was, now) -> {
            if (!disposed && now) {
                onPointerActivity();
            }
        };

        // Reduce Motion flipping ON mid-fade: stop the fades and snap the
        // chrome to its target state (story 279 — the hide still happens,
        // only the motion is removed).
        reduceMotionListener = (obs, was, now) -> {
            if (!disposed && now) {
                applyChromeVisibility(false);
            }
        };
        weakReduceMotionListener = new WeakChangeListener<>(reduceMotionListener);
        motionManager.reduceMotionProperty().addListener(weakReduceMotionListener);

        // ── Keyboard map (§6.1) ──────────────────────────────────────────
        keyHandler = this::onKeyPressed;
        control.addEventHandler(KeyEvent.KEY_PRESSED, keyHandler);
        // Filters (not handlers) so a child consuming the event still
        // counts as pointer activity over the frame. MOUSE_ENTERED_TARGET
        // is the bubbling super-type of MOUSE_ENTERED, so one registration
        // sees enters of the frame and of any child.
        control.addEventFilter(MouseEvent.MOUSE_MOVED, pointerActivityFilter);
        control.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, pointerActivityFilter);
        control.focusedProperty().addListener(focusListener);

        // ── Register control-property listeners ──────────────────────────
        control.pluginNameProperty().addListener(breadcrumbListener);
        control.vendorProperty().addListener(breadcrumbListener);
        control.insertLocationProperty().addListener(breadcrumbListener);
        control.bypassedProperty().addListener(bypassedListener);
        control.abBarVisibleProperty().addListener(abBarVisibleListener);
        control.abActiveSlotProperty().addListener(abSlotListener);
        control.chromePolicyProperty().addListener(chromePolicyListener);
        control.resizePolicyProperty().addListener(resizePolicyListener);
        control.preferredBodyWidthProperty().addListener(bodySizeListener);
        control.preferredBodyHeightProperty().addListener(bodySizeListener);
        control.metersProperty().addListener(metersListener);
        control.faultMessageProperty().addListener(faultListener);
        control.bodyProperty().addListener(bodyListener);
        control.selectedPresetProperty().addListener(selectedPresetListener);
        bypassButton.selectedProperty().addListener(bypassButtonListener);
        abButton.selectedProperty().addListener(abButtonListener);
        presetCombo.valueProperty().addListener(presetComboListener);

        // ── Apply the initial control state ──────────────────────────────
        rebuildBreadcrumb();
        updateAbSlot();
        updateFaultBanner();
        applyBody(control.getBody());
        applyMeters(control.getMeters());
        applyChromeVisibility(false);
        if (control.getChromePolicy() == ChromePolicy.IMMERSIVE) {
            inactivityTimer.playFromStart();
        }
    }

    // ── Test seams (package-private) ──────────────────────────────────────

    /** @return whether {@link #dispose()} has run (test seam). */
    boolean isDisposed() {
        return disposed;
    }

    /** @return whether the §5.D immersive chrome is currently retracted (test seam). */
    boolean isChromeRetracted() {
        return chromeRetracted;
    }

    /** @return the §6.1 header row (test seam). */
    HBox header() {
        return header;
    }

    /** @return the §6.5 A/B compare bar (test seam). */
    HBox abBar() {
        return abBar;
    }

    /** @return the §6.6 fault banner (test seam). */
    HBox faultBanner() {
        return faultBanner;
    }

    /** @return the clipped §6.3 body host (test seam). */
    StackPane bodyHost() {
        return bodyHost;
    }

    /** @return the §6.4 footer row (test seam). */
    HBox footer() {
        return footer;
    }

    /** @return the header breadcrumb (test seam). */
    BreadcrumbBar breadcrumb() {
        return breadcrumb;
    }

    /** @return the footer preset selector (test seam). */
    ComboBox<String> presetCombo() {
        return presetCombo;
    }

    /** @return the footer IN meter (test seam). */
    LevelMeter inMeter() {
        return inMeter;
    }

    /** @return the footer OUT meter (test seam). */
    LevelMeter outMeter() {
        return outMeter;
    }

    // ── Breadcrumb (§6.1) ─────────────────────────────────────────────────

    /**
     * Derives the breadcrumb segments from vendor ▸ plugin name ▸ insert
     * location, skipping {@code null}/blank segments (a missing insert
     * location simply omits the crumb).
     */
    private void rebuildBreadcrumb() {
        EditorFrame c = getSkinnable();
        List<String> segments = new ArrayList<>(3);
        addSegmentIfPresent(segments, c.getVendor());
        addSegmentIfPresent(segments, c.getPluginName());
        addSegmentIfPresent(segments, c.getInsertLocation());
        breadcrumb.setSegments(segments);
    }

    private static void addSegmentIfPresent(List<String> segments, String value) {
        if (value != null && !value.isBlank()) {
            segments.add(value);
        }
    }

    // ── A/B (§6.5) ────────────────────────────────────────────────────────

    /**
     * Reflects the display-only active slot: the header toggle's text
     * renders the active slot letter upper-case and the inactive one
     * lower-case (a text-only affordance — the full §6.5 bar carries the
     * richer marking), and the bar's active slot label gains
     * {@code .editor-ab-active}.
     */
    private void updateAbSlot() {
        ABComparison.Slot slot = getSkinnable().getAbActiveSlot();
        boolean aActive = slot == ABComparison.Slot.A;
        abButton.setText(aActive ? "A|b" : "a|B");
        abButton.setAccessibleText(String.format(Locale.ROOT,
                msg("editor.frame.ab.accessibleFormat"), slot.name()));
        markActive(slotALabel, aActive);
        markActive(slotBLabel, !aActive);
    }

    private static void markActive(Label slotLabel, boolean active) {
        slotLabel.getStyleClass().remove("editor-ab-active");
        if (active) {
            slotLabel.getStyleClass().add("editor-ab-active");
        }
    }

    // ── Fault banner (§6.6) ───────────────────────────────────────────────

    /**
     * Shows the banner while the fault message is non-blank and mirrors the
     * message into the banner's accessible text (the test-visible seam).
     * Deliberately independent of the chrome policy — a fault must stay
     * visible even under {@code IMMERSIVE} retraction.
     */
    private void updateFaultBanner() {
        String message = getSkinnable().getFaultMessage();
        boolean show = message != null && !message.isBlank();
        faultMessageLabel.setText(show ? message : "");
        faultBanner.setAccessibleText(show ? message : null);
        faultBanner.setVisible(show);
        faultBanner.setManaged(show);
    }

    // ── Body (§6.3) ───────────────────────────────────────────────────────

    private void applyBody(Node newBody) {
        bodyHost.getChildren().clear();
        if (newBody != null) {
            bodyHost.getChildren().add(newBody);
            reapplyBodyPolicy();
        }
    }

    /**
     * Enforces the host-owned resize policy on a {@link Region} body
     * (§6.3 — the plugin cannot resize itself); re-run whenever the body,
     * the policy, or the preferred size changes. Non-{@code Region} bodies
     * (e.g. a bare canvas) are sized by the {@link StackPane} host alone.
     */
    private void reapplyBodyPolicy() {
        EditorFrame c = getSkinnable();
        if (c.getBody() instanceof Region region) {
            EditorFrame.applyResizePolicy(region, c.getResizePolicy(),
                    c.getPreferredBodyWidth(), c.getPreferredBodyHeight());
        }
    }

    // ── Meters (§6.4) ─────────────────────────────────────────────────────

    /**
     * Feeds the footer meters from the snapshot (peak and RMS share the
     * single dB reading — the snapshot carries one level per direction).
     * {@link Double#NEGATIVE_INFINITY} normalises below the meter's
     * −60 dB floor and renders as an empty meter, never a clip.
     */
    private void applyMeters(PluginMeterSnapshot snapshot) {
        inMeter.submitLevels(snapshot.inputLevelDb(), snapshot.inputLevelDb());
        outMeter.submitLevels(snapshot.outputLevelDb(), snapshot.outputLevelDb());
    }

    // ── Chrome policy + immersive retraction (§5.C / §5.D) ───────────────

    /**
     * Applies the effective chrome visibility for the current policy /
     * A/B-bar flag / retraction state. The fault banner is handled solely
     * by {@link #updateFaultBanner()} — it never retracts.
     *
     * @param animate whether this change may fade (only the §5.D immersive
     *                retract / reveal passes {@code true}); further gated
     *                by the story-279 Reduce Motion flag
     */
    private void applyChromeVisibility(boolean animate) {
        EditorFrame c = getSkinnable();
        ChromePolicy policy = c.getChromePolicy();
        boolean immersiveHidden = policy == ChromePolicy.IMMERSIVE && chromeRetracted;
        boolean headerShown = !immersiveHidden;
        boolean footerShown = policy != ChromePolicy.MINIMAL && !immersiveHidden;
        boolean abShown = c.isAbBarVisible()
                && policy != ChromePolicy.MINIMAL && !immersiveHidden;
        boolean fade = animate && motionManager.isAnimationAllowed();
        applyNodeVisibility(header, headerShown, fade);
        applyNodeVisibility(footer, footerShown, fade);
        applyNodeVisibility(abBar, abShown, fade);
    }

    /**
     * Shows / hides one chrome row. When animating, opacity fades over
     * 180 ms ({@link Interpolator#EASE_BOTH}) and the {@code visible}/
     * {@code managed} flip happens after the fade-out so the body reclaims
     * the pixels only once the row has visually gone; a reveal flips the
     * flags first and fades in. When not animating (Reduce Motion, policy
     * swaps, initial apply) the flags snap with opacity reset to 1.
     */
    private void applyNodeVisibility(Node node, boolean shown, boolean animate) {
        FadeTransition running = runningFades.remove(node);
        if (running != null) {
            running.stop();
        }
        if (!animate) {
            node.setOpacity(1.0);
            node.setVisible(shown);
            node.setManaged(shown);
            return;
        }
        if (shown) {
            if (!node.isVisible()) {
                node.setOpacity(0.0);
                node.setVisible(true);
                node.setManaged(true);
            }
            if (node.getOpacity() >= 1.0) {
                return;
            }
            FadeTransition fade = new FadeTransition(CHROME_FADE, node);
            fade.setToValue(1.0);
            fade.setInterpolator(Interpolator.EASE_BOTH);
            fade.setOnFinished(e -> {
                runningFades.remove(node);
                node.setOpacity(1.0);
            });
            runningFades.put(node, fade);
            fade.play();
        } else {
            if (!node.isVisible()) {
                node.setOpacity(1.0);
                return;
            }
            FadeTransition fade = new FadeTransition(CHROME_FADE, node);
            fade.setToValue(0.0);
            fade.setInterpolator(Interpolator.EASE_BOTH);
            fade.setOnFinished(e -> {
                runningFades.remove(node);
                node.setVisible(false);
                node.setManaged(false);
                // Reset so the next (possibly snap) reveal is fully opaque.
                node.setOpacity(1.0);
            });
            runningFades.put(node, fade);
            fade.play();
        }
    }

    /** §5.D: the 2 s inactivity countdown elapsed — retract the chrome. */
    private void onImmersiveInactivity() {
        if (disposed || getSkinnable().getChromePolicy() != ChromePolicy.IMMERSIVE) {
            return;
        }
        chromeRetracted = true;
        applyChromeVisibility(true);
    }

    /**
     * §5.D: pointer movement / enter over the frame (or a focus gain)
     * reveals retracted chrome and restarts the inactivity countdown.
     * A no-op outside {@code IMMERSIVE}.
     */
    private void onPointerActivity() {
        if (disposed || getSkinnable().getChromePolicy() != ChromePolicy.IMMERSIVE) {
            return;
        }
        if (chromeRetracted) {
            chromeRetracted = false;
            applyChromeVisibility(true);
        }
        inactivityTimer.playFromStart();
    }

    // ── Keyboard (§6.1) ───────────────────────────────────────────────────

    /**
     * §6.1 keyboard map: {@code B} toggles bypass, {@code Shortcut+\}
     * requests an A/B swap, {@code Shortcut+D} detach, {@code Shortcut+W}
     * close. Events targeted at a text-input control are ignored (typing a
     * {@code b} into a plugin's text field must not bypass it); handled
     * events are consumed.
     */
    private void onKeyPressed(KeyEvent e) {
        if (disposed || e.getTarget() instanceof TextInputControl) {
            return;
        }
        EditorFrame c = getSkinnable();
        switch (e.getCode()) {
            case B -> {
                if (hasNoModifiers(e)) {
                    c.setBypassed(!c.isBypassed());
                    e.consume();
                }
            }
            case BACK_SLASH -> {
                if (e.isShortcutDown()) {
                    runCallback(c.getOnAbToggleRequested());
                    e.consume();
                }
            }
            case D -> {
                if (e.isShortcutDown()) {
                    runCallback(c.getOnDetachRequested());
                    e.consume();
                }
            }
            case W -> {
                if (e.isShortcutDown()) {
                    runCallback(c.getOnCloseRequested());
                    e.consume();
                }
            }
            default -> {
                // Not part of the §6.1 map — leave for other handlers.
            }
        }
    }

    private static boolean hasNoModifiers(KeyEvent e) {
        return !e.isShiftDown() && !e.isControlDown() && !e.isAltDown()
                && !e.isMetaDown() && !e.isShortcutDown();
    }

    private static void runCallback(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }

    /**
     * Resolves a chrome string from the shared bundle, falling back to the
     * raw key if absent (mirrors {@code BrowserPanel#msg(String)}).
     */
    private static String msg(String key) {
        try {
            return MESSAGES.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    // ── Dispose ───────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        inactivityTimer.stop();
        for (FadeTransition fade : runningFades.values()) {
            fade.stop();
        }
        runningFades.clear();
        EditorFrame c = getSkinnable();
        if (c != null) {
            c.removeEventHandler(KeyEvent.KEY_PRESSED, keyHandler);
            c.removeEventFilter(MouseEvent.MOUSE_MOVED, pointerActivityFilter);
            c.removeEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, pointerActivityFilter);
            c.focusedProperty().removeListener(focusListener);
            c.pluginNameProperty().removeListener(breadcrumbListener);
            c.vendorProperty().removeListener(breadcrumbListener);
            c.insertLocationProperty().removeListener(breadcrumbListener);
            c.bypassedProperty().removeListener(bypassedListener);
            c.abBarVisibleProperty().removeListener(abBarVisibleListener);
            c.abActiveSlotProperty().removeListener(abSlotListener);
            c.chromePolicyProperty().removeListener(chromePolicyListener);
            c.resizePolicyProperty().removeListener(resizePolicyListener);
            c.preferredBodyWidthProperty().removeListener(bodySizeListener);
            c.preferredBodyHeightProperty().removeListener(bodySizeListener);
            c.metersProperty().removeListener(metersListener);
            c.faultMessageProperty().removeListener(faultListener);
            c.bodyProperty().removeListener(bodyListener);
            c.selectedPresetProperty().removeListener(selectedPresetListener);
        }
        bypassButton.selectedProperty().removeListener(bypassButtonListener);
        abButton.selectedProperty().removeListener(abButtonListener);
        presetCombo.valueProperty().removeListener(presetComboListener);
        motionManager.reduceMotionProperty().removeListener(weakReduceMotionListener);
        bodyClip.widthProperty().unbind();
        bodyClip.heightProperty().unbind();
        super.dispose();
    }
}
