package com.benesquivelmusic.daw.app.ui.controls.skin;

import com.benesquivelmusic.daw.app.ui.controls.LevelMeter;
import com.benesquivelmusic.daw.app.ui.controls.TrackStrip;
import com.benesquivelmusic.daw.app.ui.controls.TrackStrip.TrackSelectionEvent;
import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.density.DensityMode;
import com.benesquivelmusic.daw.app.ui.icons.DawgIcon;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.Parent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SkinBase;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.util.Locale;

/**
 * Skin for {@link TrackStrip} — lays out the canonical
 * UI Design Book §5.3 row content:
 *
 * <pre>
 *   [⋮⋮] [02] [▌] Drums       [M] [S] [R]  [█▌] -12.4 dB  [⋯]
 * </pre>
 *
 * <p>Left → right: drag handle · index · colour swatch · name · spacer ·
 * M / S / R toggles · level meter · meter readout · overflow menu.
 *
 * <p>The armed "left-edge danger bar" is drawn as a {@link Rectangle}
 * child of the skin (positioned absolutely in
 * {@link #layoutChildren(double, double, double, double)}) — <strong>not</strong>
 * a {@code -fx-border-color} on the row, because per UI Design Book §7.3
 * changing border width causes JavaFX layout reflow. A child rectangle
 * does not perturb the row's intrinsic height.
 *
 * <p>All listeners registered in the constructor are unregistered in
 * {@link #dispose()} so swapping the skin via {@code setSkin(null)}
 * cleanly detaches the skin from the control's properties.
 *
 * <h2>Density variants (story 278)</h2>
 *
 * <p>{@code computeMin/Pref/MaxHeight} honour the three global
 * {@link DensityMode} profiles: {@code COMPACT} → 24&nbsp;px,
 * {@code COMFORTABLE} → 28&nbsp;px (default), {@code TOUCH} → 32&nbsp;px.
 * The effective density is resolved through the single shared
 * {@link DensityMode#resolveFor(javafx.scene.Node)} bridge — it reads the
 * {@code .density-*} class {@code DensityManager} sets on the
 * <em>scene root</em> (JavaFX style classes do not inherit to
 * descendants, so the skin cannot read the root class directly) and falls
 * back to the control's own legacy {@code size-*} class for back-compat.
 * The legacy {@code COMFORTABLE_ROW_HEIGHT} constant is <strong>32</strong>
 * — that is the story's {@code TOUCH}, not its Comfortable; the legacy
 * name is preserved only for the back-compat fallback. The
 * {@link #layoutChildren(double, double, double, double)} routine derives
 * the meter width, swatch height, and toggle button size proportionally
 * from the row height so a density change reflows without geometry
 * surprises.</p>
 *
 * <p>A live density switch must re-measure the already-built skin. A
 * {@link WeakChangeListener} on
 * {@code DensityManager.getDefault().activeDensityProperty()} requests a
 * layout pass on the control <em>and</em> its parent (so a recycling
 * {@code ListView}/{@code TrackStripCell} re-queries the cell's preferred
 * height). The strong listener is held in a field and removed in
 * {@link #dispose()}; the weak wrapper is defence-in-depth because cell
 * skins are not guaranteed to receive {@code dispose()}.</p>
 */
public final class TrackStripSkin extends SkinBase<TrackStrip> {

    /** Default row density (UI Design Book §5.3). */
    static final double DEFAULT_ROW_HEIGHT = 28.0;
    /** Compact row density (story 261). */
    static final double COMPACT_ROW_HEIGHT = 24.0;
    /** Comfortable row density (story 261). */
    static final double COMFORTABLE_ROW_HEIGHT = 32.0;

    /** Width of the armed left-edge danger bar (§5.3). */
    static final double ARM_BAR_WIDTH = 2.0;
    /** Colour-swatch width (§5.3). */
    static final double SWATCH_WIDTH = 4.0;
    /** Inline meter nominal width (story 267 {@code .size-inline}). */
    static final double METER_WIDTH = 4.0;

    // ── User-facing menu strings (future i18n: move to Messages.properties) ─
    static final String MENU_RENAME = "Rename track";
    static final String MENU_CHANGE_COLOUR = "Change colour\u2026";
    static final String MENU_DUPLICATE = "Duplicate";
    static final String MENU_DELETE = "Delete";

    // ── Scene-graph nodes ─────────────────────────────────────────────────

    private final HBox row;
    private final Rectangle armBar;
    private final Region dragHandle;
    private final Label indexLabel;
    private final Rectangle swatch;
    private final Label nameLabel;
    private final ToggleButton muteBtn;
    private final ToggleButton soloBtn;
    private final ToggleButton armBtn;
    private final StackPane meterHolder;
    private final Label meterReadout;
    private final Label overflowBtn;
    private final javafx.event.EventHandler<KeyEvent> keyHandler;

    // ── Listeners (held so dispose() can remove exactly what was added) ───

    private final ChangeListener<Object> readoutRefreshListener;
    private final ChangeListener<Boolean> mutedSyncListener;
    private final ChangeListener<Boolean> soloedSyncListener;
    private final ChangeListener<Boolean> armedSyncListener;
    private final ChangeListener<Boolean> showMeterListener;
    private final ChangeListener<javafx.scene.paint.Color> swatchListener;
    private final ChangeListener<Number> indexListener;
    private final ChangeListener<String> nameListener;

    /**
     * Strong listener on the global density property — held so
     * {@link #dispose()} can remove exactly what was added. Wrapped in a
     * {@link WeakChangeListener} when registered (defence-in-depth: a
     * recycled {@code TrackStripCell} skin is not guaranteed to receive
     * {@code dispose()}, so the weak wrapper lets the listener be GC'd
     * with the skin even if {@code dispose()} never runs).
     */
    private final DensityManager densityManager;
    private final ChangeListener<DensityMode> densityListener;
    private final WeakChangeListener<DensityMode> weakDensityListener;

    private boolean disposed;
    private int registeredListenerCount;

    /**
     * @param control the {@link TrackStrip} this skin renders
     */
    public TrackStripSkin(TrackStrip control) {
        super(control);

        // ── Build scene graph ─────────────────────────────────────────
        dragHandle = DawgIcon.of("grip-vertical", DawgIcon.Size.SIZE_16);
        dragHandle.getStyleClass().add("track-strip-drag-handle");

        indexLabel = new Label();
        indexLabel.getStyleClass().addAll("track-strip-index", "numeric-caption");
        indexLabel.setText(formatIndex(control.getTrackIndex()));

        swatch = new Rectangle(SWATCH_WIDTH, 16.0);
        swatch.getStyleClass().add("track-strip-swatch");
        swatch.setFill(control.getTrackColor());

        nameLabel = new Label(control.getTrackName());
        nameLabel.getStyleClass().add("track-strip-name");
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        Region nameSpacer = new Region();
        HBox.setHgrow(nameSpacer, Priority.ALWAYS);

        muteBtn = makeToggle("mute", "M");
        soloBtn = makeToggle("solo", "S");
        armBtn = makeToggle("arm", "R");

        // Two-way sync between the toggles and the control's properties.
        // Bidirectional binding avoids losing user toggle clicks while
        // still letting programmatic property writes propagate to the UI.
        // We listen instead of bind so dispose() can detach cleanly.
        mutedSyncListener = (obs, was, now) -> muteBtn.setSelected(now);
        soloedSyncListener = (obs, was, now) -> soloBtn.setSelected(now);
        armedSyncListener = (obs, was, now) -> armBtn.setSelected(now);
        control.mutedProperty().addListener(mutedSyncListener);
        control.soloedProperty().addListener(soloedSyncListener);
        control.armedProperty().addListener(armedSyncListener);
        registeredListenerCount += 3;
        muteBtn.setSelected(control.isMuted());
        soloBtn.setSelected(control.isSoloed());
        armBtn.setSelected(control.isArmed());
        muteBtn.selectedProperty().addListener((obs, was, now) -> control.setMuted(now));
        soloBtn.selectedProperty().addListener((obs, was, now) -> control.setSoloed(now));
        armBtn.selectedProperty().addListener((obs, was, now) -> control.setArmed(now));

        meterHolder = new StackPane();
        meterHolder.getStyleClass().add("track-strip-meter-holder");
        // Attach/detach the LevelMeter from the scene graph based on
        // showMeter — same pattern as FaderSkin#syncMeterAttachment().
        // LevelMeterSkin's AnimationTimer keys off sceneProperty (not
        // visible), so merely hiding the meter with visible=false still
        // burns per-frame CPU. Removing it from the scene graph ensures
        // the timer stops when the meter is disabled.
        syncMeterAttachment(control);

        meterReadout = new Label("-\u221E dB");
        meterReadout.getStyleClass().addAll("track-strip-readout", "numeric-caption");

        overflowBtn = new Label("\u22EF"); // ⋯ U+22EF MIDLINE HORIZONTAL ELLIPSIS
        overflowBtn.getStyleClass().add("track-strip-overflow");
        ContextMenu overflowMenu = new ContextMenu();
        // TODO: Migrate to resource bundle (Messages.properties) when the
        // i18n infrastructure is established — matches the existing codebase
        // pattern of hard-coded MenuItem labels (see MixerView, etc.).
        overflowMenu.getItems().addAll(
                new MenuItem(MENU_RENAME),
                new MenuItem(MENU_CHANGE_COLOUR),
                new MenuItem(MENU_DUPLICATE),
                new MenuItem(MENU_DELETE));
        overflowBtn.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                overflowMenu.show(overflowBtn,
                        e.getScreenX(), e.getScreenY());
            }
        });

        row = new HBox(
                dragHandle, indexLabel, swatch, nameLabel, nameSpacer,
                muteBtn, soloBtn, armBtn,
                meterHolder, meterReadout, overflowBtn);
        row.getStyleClass().add("track-strip-row");
        row.setAlignment(Pos.CENTER_LEFT);

        // Armed left-edge bar — positioned manually in layoutChildren so
        // its presence doesn't affect the row's intrinsic geometry.
        // Fill is driven exclusively by CSS (.track-strip-arm-bar { -fx-fill: -ts-danger }).
        // Do NOT call setFill() or bind fillProperty() — either would set
        // USER origin and prevent theme token updates from taking effect.
        armBar = new Rectangle(ARM_BAR_WIDTH, 0);
        armBar.getStyleClass().add("track-strip-arm-bar");
        armBar.visibleProperty().bind(control.armedProperty());
        armBar.managedProperty().bind(control.armedProperty());

        getChildren().setAll(row, armBar);

        // ── Property→view listeners ──────────────────────────────────
        indexListener = (obs, was, now) ->
                indexLabel.setText(formatIndex(now.intValue()));
        control.trackIndexProperty().addListener(indexListener);
        registeredListenerCount++;

        nameListener = (obs, was, now) -> nameLabel.setText(now == null ? "" : now);
        control.trackNameProperty().addListener(nameListener);
        registeredListenerCount++;

        swatchListener = (obs, was, now) -> {
            if (now != null) swatch.setFill(now);
        };
        control.trackColorProperty().addListener(swatchListener);
        registeredListenerCount++;

        readoutRefreshListener = (obs, was, now) -> refreshReadout();
        control.getMeter().peakDbProperty().addListener(readoutRefreshListener);
        control.mutedProperty().addListener(readoutRefreshListener);
        registeredListenerCount += 2;

        // showMeter: detach/attach the meter node from the scene graph
        // (stops the AnimationTimer) and show/hide the readout.
        showMeterListener = (obs, was, now) -> {
            syncMeterAttachment(control);
            meterReadout.setVisible(now);
            meterReadout.setManaged(now);
        };
        control.showMeterProperty().addListener(showMeterListener);
        registeredListenerCount++;
        meterReadout.setVisible(control.isShowMeter());
        meterReadout.setManaged(control.isShowMeter());

        // Clicking the row fires the typed selection event.
        row.addEventHandler(MouseEvent.MOUSE_PRESSED, this::onRowPressed);

        // Keyboard shortcuts: M / S / R toggle mute / solo / arm. Matches
        // existing arrangement-view shortcut conventions.
        keyHandler = this::onKeyPressed;
        control.addEventHandler(KeyEvent.KEY_PRESSED, keyHandler);

        // Story 278 — live density switch must re-measure the already
        // built skin. The density class lives on the SCENE ROOT (not the
        // control — JavaFX classes don't inherit), so a style-class
        // listener on the control would never fire. Instead observe the
        // global density property; on change request a layout pass on the
        // control AND its parent so a recycling ListView / TrackStripCell
        // re-queries the cell's preferred height (verified empirically by
        // DensityRowHeightTest's live-switch assertion).
        densityListener = (obs, was, now) -> requestRemeasure();
        weakDensityListener = new WeakChangeListener<>(densityListener);
        densityManager = DensityManager.getDefault();
        densityManager.activeDensityProperty()
                .addListener(weakDensityListener);

        // Initial render.
        refreshReadout();
    }

    /**
     * Forces this skin (and any recycling parent cell) to re-query the
     * density-derived preferred height after a live density switch.
     */
    private void requestRemeasure() {
        TrackStrip c = getSkinnable();
        if (c == null) {
            return;
        }
        c.requestLayout();
        Parent parent = c.getParent();
        if (parent != null) {
            parent.requestLayout();
        }
    }

    private static ToggleButton makeToggle(String role, String glyph) {
        ToggleButton t = new ToggleButton(glyph);
        // Deliberately NOT a `.dawg-button` — the app stylesheet's
        // `.dawg-button { -fx-background-color: -surface-2 }` rule has
        // author-origin priority and would override the user-agent
        // `.track-toggle:selected` fill. UI Design Book §5.3 / §7.5
        // wants a fully self-contained M/S/R visual language.
        t.getStyleClass().addAll("track-toggle", "size-compact", role);
        t.setFocusTraversable(false);
        t.setMaxHeight(Double.MAX_VALUE);
        return t;
    }

    private static String formatIndex(int idx) {
        int safe = Math.max(0, idx);
        return String.format(Locale.ROOT, "%02d", safe);
    }

    /**
     * Attaches or detaches the embedded {@link LevelMeter} from the scene
     * graph based on {@code control.isShowMeter()}. Same pattern as
     * {@code FaderSkin#syncMeterAttachment()} — the LevelMeterSkin's
     * {@code AnimationTimer} keys off {@code sceneProperty}, not
     * {@code visible}, so merely hiding the meter with
     * {@code setVisible(false)} still burns per-frame CPU. Removing it
     * from the scene graph ensures the timer stops.
     */
    private void syncMeterAttachment(TrackStrip control) {
        LevelMeter meter = control.getMeter();
        if (control.isShowMeter()) {
            if (!meterHolder.getChildren().contains(meter)) {
                meterHolder.getChildren().setAll(meter);
            }
            meterHolder.setVisible(true);
            meterHolder.setManaged(true);
        } else {
            meterHolder.getChildren().remove(meter);
            meterHolder.setVisible(false);
            meterHolder.setManaged(false);
        }
    }

    private void refreshReadout() {
        TrackStrip c = getSkinnable();
        if (c == null) return;
        double db = c.getMeter().getPeakDb();
        String text;
        if (c.isMuted() || Double.isInfinite(db) || db <= -120.0) {
            text = "-\u221E dB";
        } else {
            text = String.format(Locale.ROOT, "%.1f dB", db);
        }
        meterReadout.setText(text);
    }

    private void onRowPressed(MouseEvent e) {
        if (disposed) return;
        if (e.getButton() != MouseButton.PRIMARY) return;
        TrackStrip c = getSkinnable();
        if (c == null) return;
        // Don't fire when the click landed on (or inside) an interactive
        // child — those have their own handlers. In JavaFX the event
        // target is often a child node inside the button (e.g., the Text
        // node), so we walk up the parent chain from the target to detect
        // whether the press occurred inside a toggle or the overflow label.
        if (isInsideInteractiveChild(e.getTarget())) return;
        c.fireEvent(new TrackSelectionEvent(c, c, c.getTrackId()));
    }

    /**
     * Returns {@code true} if the given event target is, or is a
     * descendant of, one of this skin's interactive controls (M/S/R
     * toggle buttons or overflow menu trigger).
     */
    private boolean isInsideInteractiveChild(javafx.event.EventTarget target) {
        if (!(target instanceof Node node)) return false;
        while (node != null) {
            if (node == muteBtn || node == soloBtn || node == armBtn || node == overflowBtn) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private void onKeyPressed(KeyEvent e) {
        if (disposed) return;
        TrackStrip c = getSkinnable();
        if (c == null) return;
        if (e.isShortcutDown() || e.isAltDown() || e.isShiftDown()) return;
        KeyCode code = e.getCode();
        if (code == KeyCode.M) {
            c.setMuted(!c.isMuted());
            e.consume();
        } else if (code == KeyCode.S) {
            c.setSoloed(!c.isSoloed());
            e.consume();
        } else if (code == KeyCode.R) {
            c.setArmed(!c.isArmed());
            e.consume();
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────

    @Override
    protected void layoutChildren(double x, double y, double w, double h) {
        // Row fills the content area (respecting CSS padding/border insets).
        row.resizeRelocate(x, y, w, h);

        // Arm bar pinned to the CONTROL's left edge (local x=0), full
        // control height — NOT the content-area inset. This makes the
        // bar a visual indicator on the absolute edge of the strip,
        // independent of padding changes from density variants.
        TrackStrip c = getSkinnable();
        double rowH = c.getHeight() > 0 ? c.getHeight() : h;
        armBar.setX(0);
        armBar.setY(0);
        armBar.setWidth(ARM_BAR_WIDTH);
        armBar.setHeight(rowH);

        // Derive proportional sizes from the available row height so a
        // density change reflows without geometry surprises.
        double swatchH = Math.max(8.0, Math.min(h - 8.0, h * 0.6));
        swatch.setHeight(swatchH);

        // Embedded meter sized to METER_WIDTH (fixed) × the row's
        // available height minus 8 px of padding so it never overflows.
        // Only configure meter geometry when it's attached to the scene
        // graph (showMeter=true); when detached, meterHolder is
        // invisible/unmanaged and occupies no layout space.
        double meterH = Math.max(8.0, h - 8.0);
        LevelMeter meter = getSkinnable().getMeter();
        meterHolder.setMinWidth(METER_WIDTH);
        meterHolder.setPrefWidth(METER_WIDTH);
        meterHolder.setMaxWidth(METER_WIDTH);
        if (meterHolder.getChildren().contains(meter)) {
            meter.setMinHeight(meterH);
            meter.setPrefHeight(meterH);
            meter.setMaxHeight(meterH);
        }

        // Toggle buttons are square per the row height (minus 4 px inset).
        double btnSize = Math.max(16.0, h - 4.0);
        for (ToggleButton t : new ToggleButton[] { muteBtn, soloBtn, armBtn }) {
            t.setMinHeight(btnSize);
            t.setPrefHeight(btnSize);
            t.setMaxHeight(btnSize);
            t.setMinWidth(btnSize);
            t.setPrefWidth(btnSize);
        }
    }

    /**
     * The density-derived row height (story 278). Routes through the
     * single shared {@link DensityMode#resolveFor(javafx.scene.Node)}
     * bridge: it reads the {@code .density-*} class on the scene root
     * (the authoritative source set by {@code DensityManager}) and falls
     * back to the control's own legacy {@code size-*} class for
     * back-compat. {@code COMPACT} → 24&nbsp;px, {@code COMFORTABLE} →
     * 28&nbsp;px (default), {@code TOUCH} → 32&nbsp;px — exactly the
     * three legacy constants, but mapped from {@link DensityMode} (the
     * source of truth) rather than re-deriving from style classes here.
     */
    private double rowHeightForVariant() {
        return switch (DensityMode.resolveFor(getSkinnable())) {
            case COMPACT -> COMPACT_ROW_HEIGHT;        // 24
            case COMFORTABLE -> DEFAULT_ROW_HEIGHT;    // 28
            case TOUCH -> COMFORTABLE_ROW_HEIGHT;      // 32 (legacy name)
        };
    }

    @Override
    protected double computeMinHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return rowHeightForVariant();
    }

    @Override
    protected double computePrefHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return rowHeightForVariant();
    }

    @Override
    protected double computeMaxHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return rowHeightForVariant();
    }

    @Override
    protected double computeMinWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        // A reasonable minimum so the row remains usable; the container
        // (ListView / VBox) drives the actual width.
        return 160.0;
    }

    @Override
    protected double computePrefWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return 320.0;
    }

    @Override
    protected double computeMaxWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return Double.MAX_VALUE;
    }

    // ── Test seams ────────────────────────────────────────────────────────

    /** @return the M toggle button (test seam). */
    public ToggleButton muteButton() { return muteBtn; }
    /** @return the S toggle button (test seam). */
    public ToggleButton soloButton() { return soloBtn; }
    /** @return the R toggle button (test seam). */
    public ToggleButton armButton() { return armBtn; }
    /** @return the track-name label (test seam). */
    public Label nameLabel() { return nameLabel; }
    /** @return the armed left-edge bar (test seam). */
    public Rectangle armBar() { return armBar; }
    /** @return the meter readout label (test seam). */
    public Label meterReadout() { return meterReadout; }
    /** @return whether {@link #dispose()} has run (test seam). */
    public boolean isDisposed() { return disposed; }
    /** @return cumulative count of listeners registered on the control's properties (test seam). */
    public int registeredListenerCount() { return registeredListenerCount; }

    // ── Dispose ───────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        TrackStrip c = getSkinnable();
        if (c != null) {
            c.trackIndexProperty().removeListener(indexListener);
            registeredListenerCount--;
            c.trackNameProperty().removeListener(nameListener);
            registeredListenerCount--;
            c.trackColorProperty().removeListener(swatchListener);
            registeredListenerCount--;
            c.mutedProperty().removeListener(mutedSyncListener);
            registeredListenerCount--;
            c.soloedProperty().removeListener(soloedSyncListener);
            registeredListenerCount--;
            c.armedProperty().removeListener(armedSyncListener);
            registeredListenerCount--;
            c.getMeter().peakDbProperty().removeListener(readoutRefreshListener);
            registeredListenerCount--;
            c.mutedProperty().removeListener(readoutRefreshListener);
            registeredListenerCount--;
            c.showMeterProperty().removeListener(showMeterListener);
            registeredListenerCount--;
            // Detach meter from meterHolder so the AnimationTimer
            // stops (sceneProperty → null).
            meterHolder.getChildren().clear();
            armBar.visibleProperty().unbind();
            armBar.managedProperty().unbind();
            c.removeEventHandler(KeyEvent.KEY_PRESSED, keyHandler);
        }
        // Story 278 — detach the global-density listener. Removing the
        // weak wrapper that was registered is sufficient; the weak
        // wrapper is belt-and-braces for cells that never get dispose().
        densityManager.activeDensityProperty()
                .removeListener(weakDensityListener);
        super.dispose();
    }
}
