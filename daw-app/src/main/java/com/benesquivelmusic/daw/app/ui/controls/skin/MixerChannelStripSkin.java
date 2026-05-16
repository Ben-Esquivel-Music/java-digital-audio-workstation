package com.benesquivelmusic.daw.app.ui.controls.skin;

import com.benesquivelmusic.daw.app.ui.controls.Fader;
import com.benesquivelmusic.daw.app.ui.controls.InsertSlotModel;
import com.benesquivelmusic.daw.app.ui.controls.Knob;
import com.benesquivelmusic.daw.app.ui.controls.LevelMeter;
import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip.ChannelType;
import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip.InsertSelectedEvent;
import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip.SendSelectedEvent;
import com.benesquivelmusic.daw.app.ui.controls.SendSlotModel;

import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.SkinBase;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.util.List;
import java.util.Locale;

/**
 * Skin for {@link MixerChannelStrip} — lays out the canonical
 * UI Design Book §5.4 vertical signal chain, top → bottom:
 *
 * <pre>
 *   I In 1+2   O Master      (mono caption)
 *   ───────────────────────
 *   ▸ EQ          ●          (insert rows; status dot)
 *   ▸ Comp        ○
 *   ▸ —
 *   ▸ —
 *   ⋯ +2
 *   ───────────────────────
 *   → Rev   ▭▭▭   P          (send rows; compact fader + pre/post)
 *   → Dly   ▭▭▭   P
 *   ⋯ +1
 *   ───────────────────────
 *        (●)  pan knob (size-28, bipolar)
 *   ───────────────────────
 *      ║ fader column ║ ▌    (Fader.size-mixer + integrated meter)
 *      ║              ║ ▌
 *   ───────────────────────
 *     [M] [S] [R]            (omitted entirely for MASTER)
 *     -6.0 dB                (.numeric-value readout)
 *     Drums                  (channel name; double-click → editable)
 * </pre>
 *
 * <p>Composes the real Phase 2 sub-controls: {@link Knob} (story 268) for
 * pan, {@link Fader} (story 269 — {@code size-mixer}, {@code LOG_DB},
 * {@code showMeter = true}) for level. The integrated {@link LevelMeter}
 * is the fader's own {@code fader.getMeter()} — consumers bind
 * {@code strip.getFader().getMeter().peakDbProperty()}; <strong>no
 * separate meter and no separate audio→FX relay is created</strong> (the
 * embedded LevelMeter already owns its lock-free relay).
 *
 * <p>The M/S/R buttons reuse story 270's exact CSS classes
 * ({@code .track-toggle.size-compact.mute|solo|arm}) for visual
 * consistency. When the channel type is {@link ChannelType#MASTER} they
 * are not added to the scene graph at all (story
 * {@code MixerChannelStripChannelTypeTest} asserts absence, not merely
 * hidden); when {@link ChannelType#BUS} the input caption is omitted.
 * Both are re-evaluated on a held {@code channelTypeProperty} listener
 * that rebuilds the affected regions.
 *
 * <p>All listeners registered in the constructor are unregistered in
 * {@link #dispose()} so swapping the skin via {@code setSkin(null)}
 * cleanly detaches the skin from the control's properties.
 *
 * <h2>Single source of truth (267 expert-review trap #1)</h2>
 *
 * <p>Any draw-model decision a test asserts is routed through one pure
 * helper so the rendered scene graph and the test seam cannot disagree.
 * The insert status-dot active-state is decided exactly once by
 * {@link #insertDotActive(InsertSlotModel)}; both the rendered
 * {@link Circle} fill (via a {@code .active} style class consumed by
 * {@code mixer-channel-strip.css}) and the {@link #insertDotActive(int)}
 * test seam read that single helper — they never re-derive it
 * independently.
 *
 * <h2>Density variants</h2>
 *
 * <p>{@code computeMin/Pref/MaxWidth} return 72&nbsp;px for
 * {@code .density-compact} (also the default) and 88&nbsp;px for
 * {@code .density-comfortable} (story §5.4 / story 278). Width is enforced
 * from Java, not CSS, to avoid a token-validation linter mismatch with the
 * 4&nbsp;px grid (mirrors {@code TrackStripSkin}). Height fills the
 * available vertical space — the mixer panel drives it.
 */
public final class MixerChannelStripSkin extends SkinBase<MixerChannelStrip> {

    /** Compact strip width (UI Design Book §5.4, story 278). */
    static final double COMPACT_WIDTH = 72.0;
    /** Comfortable strip width (UI Design Book §5.4, story 278). */
    static final double COMFORTABLE_WIDTH = 88.0;

    /** Visible insert slot rows before the overflow indicator (§5.4). */
    static final int VISIBLE_INSERTS = 4;
    /** Visible send slot rows before the overflow indicator (§5.4). */
    static final int VISIBLE_SENDS = 2;

    // Proportional-layout bands (story §5.4 "no fixed pixel offsets keyed to
    // a single default"). Single-sourced here so insert rows, send rows and
    // the pan knob all reflow from the same math on a density switch.
    /** Lower clamp (px) for a slot row's height. */
    private static final double MIN_ROW_HEIGHT = 14.0;
    /** Upper clamp (px) for a slot row's height. */
    private static final double MAX_ROW_HEIGHT = 22.0;
    /** Fraction of strip height a slot row occupies before clamping. */
    private static final double ROW_HEIGHT_FRACTION = 0.04;
    /** Lower clamp (px) for the pan-knob diameter. */
    private static final double MIN_KNOB_DIAMETER = 24.0;
    /** Upper clamp (px) for the pan-knob diameter. */
    private static final double MAX_KNOB_DIAMETER = 44.0;
    /** Fraction of strip width the pan-knob diameter occupies before clamping. */
    private static final double KNOB_WIDTH_FRACTION = 0.55;

    /** {@code ⋯} U+22EF MIDLINE HORIZONTAL ELLIPSIS overflow glyph. */
    private static final String OVERFLOW_GLYPH = "⋯";

    // ── Scene-graph nodes ─────────────────────────────────────────────────

    private final VBox column;
    private final Label inputLabelNode;
    private final Label outputLabelNode;
    private final VBox insertsBox;
    private final Label insertsOverflow;
    private final VBox sendsBox;
    private final Label sendsOverflow;
    private final Knob panKnob;
    private final Fader fader;
    private final HBox msrRow;
    private final ToggleButton muteBtn;
    private final ToggleButton soloBtn;
    private final ToggleButton armBtn;
    private final Label valueReadout;
    private final StackPane nameHolder;
    private final Label nameLabel;
    private final TextField nameEditor;

    // ── Listeners (held so dispose() can remove exactly what was added) ───

    private final ChangeListener<String> inputLabelListener;
    private final ChangeListener<String> outputLabelListener;
    private final ChangeListener<String> nameListener;
    private final ListChangeListener<InsertSlotModel> insertsListener;
    private final ListChangeListener<SendSlotModel> sendsListener;
    private final ChangeListener<Number> panToWidget;
    private final ChangeListener<Number> panFromWidget;
    private final ChangeListener<Number> faderToWidget;
    private final ChangeListener<Number> faderFromWidget;
    private final ChangeListener<Boolean> mutedSyncListener;
    private final ChangeListener<Boolean> soloedSyncListener;
    private final ChangeListener<Boolean> armedSyncListener;
    private final ChangeListener<Boolean> muteBtnListener;
    private final ChangeListener<Boolean> soloBtnListener;
    private final ChangeListener<Boolean> armBtnListener;
    private final ChangeListener<Object> readoutRefreshListener;
    private final ChangeListener<ChannelType> channelTypeListener;

    private boolean disposed;
    private int registeredListenerCount;

    /**
     * @param control the {@link MixerChannelStrip} this skin renders
     */
    public MixerChannelStripSkin(MixerChannelStrip control) {
        super(control);

        // ── I/O captions (small mono per story 266) ───────────────────
        inputLabelNode = new Label(control.getInputLabel());
        inputLabelNode.getStyleClass().addAll("mixer-channel-strip-io", "numeric-caption");
        inputLabelNode.setMaxWidth(Double.MAX_VALUE);
        outputLabelNode = new Label(control.getOutputLabel());
        outputLabelNode.getStyleClass().addAll("mixer-channel-strip-io", "numeric-caption");
        outputLabelNode.setMaxWidth(Double.MAX_VALUE);

        // ── Insert list ───────────────────────────────────────────────
        insertsBox = new VBox();
        insertsBox.getStyleClass().add("mixer-channel-strip-inserts");
        insertsOverflow = new Label();
        insertsOverflow.getStyleClass().addAll(
                "mixer-channel-strip-overflow", "numeric-caption");

        // ── Send list ─────────────────────────────────────────────────
        sendsBox = new VBox();
        sendsBox.getStyleClass().add("mixer-channel-strip-sends");
        sendsOverflow = new Label();
        sendsOverflow.getStyleClass().addAll(
                "mixer-channel-strip-overflow", "numeric-caption");

        // ── Pan knob (story 268, size-28, bipolar) ────────────────────
        panKnob = Knob.create()
                .bipolar(true).min(-1).max(1).defaultValue(0)
                .unit("L / R").size(28).build();
        panKnob.setValue(control.getPan());

        // ── Fader (story 269, size-mixer, LOG_DB, integrated meter) ────
        fader = Fader.create()
                .curve(Fader.TravelCurve.LOG_DB)
                .showMeter(true)
                .size("mixer")
                .build();
        fader.setValue(control.getFaderDb());

        // ── M / S / R toggles (story 270 CSS classes verbatim) ────────
        muteBtn = makeToggle("mute", "M");
        soloBtn = makeToggle("solo", "S");
        armBtn = makeToggle("arm", "R");
        msrRow = new HBox(muteBtn, soloBtn, armBtn);
        msrRow.getStyleClass().add("mixer-channel-strip-msr");
        msrRow.setAlignment(Pos.CENTER);

        // ── Fader value readout + channel name ────────────────────────
        valueReadout = new Label();
        valueReadout.getStyleClass().addAll(
                "mixer-channel-strip-readout", "numeric-value");
        valueReadout.setMaxWidth(Double.MAX_VALUE);
        valueReadout.setAlignment(Pos.CENTER);

        nameLabel = new Label(control.getChannelName());
        nameLabel.getStyleClass().add("mixer-channel-strip-name");
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        nameLabel.setAlignment(Pos.CENTER);
        nameEditor = new TextField();
        nameEditor.getStyleClass().add("mixer-channel-strip-name-editor");
        nameEditor.setVisible(false);
        nameEditor.setManaged(false);
        nameHolder = new StackPane(nameLabel, nameEditor);
        nameHolder.getStyleClass().add("mixer-channel-strip-name-holder");
        // Double-click swaps to an editable TextField (minimal inline
        // rename — the full rename-commit polish is out of scope per the
        // story, but a double-click must not throw).
        nameLabel.setOnMouseClicked(this::onNameClicked);
        nameEditor.setOnAction(e -> commitNameEdit());
        nameEditor.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                commitNameEdit();
            }
        });

        Region insertSep = separator();
        Region sendSep = separator();
        Region panSep = separator();
        Region faderSep = separator();

        column = new VBox(
                inputLabelNode, outputLabelNode,
                insertSep,
                insertsBox, insertsOverflow,
                sendSep,
                sendsBox, sendsOverflow,
                panSep,
                panKnob,
                faderSep,
                fader,
                msrRow,
                valueReadout,
                nameHolder);
        column.getStyleClass().add("mixer-channel-strip-column");
        column.setFillWidth(true);
        // The fader is the elastic region — it absorbs the vertical slack
        // so the strip fills the panel height (the mixer panel drives the
        // overall strip height; everything else is intrinsic).
        VBox.setVgrow(fader, Priority.ALWAYS);

        getChildren().setAll(column);

        // ── Property → view listeners ─────────────────────────────────
        inputLabelListener = (obs, was, now) ->
                inputLabelNode.setText(now == null ? "" : now);
        control.inputLabelProperty().addListener(inputLabelListener);
        registeredListenerCount++;

        outputLabelListener = (obs, was, now) ->
                outputLabelNode.setText(now == null ? "" : now);
        control.outputLabelProperty().addListener(outputLabelListener);
        registeredListenerCount++;

        nameListener = (obs, was, now) ->
                nameLabel.setText(now == null ? "" : now);
        control.channelNameProperty().addListener(nameListener);
        registeredListenerCount++;

        insertsListener = c -> rebuildInserts();
        control.insertsProperty().addListener(insertsListener);
        registeredListenerCount++;

        sendsListener = c -> rebuildSends();
        control.sendsProperty().addListener(sendsListener);
        registeredListenerCount++;

        // Pan: two-way sync control.panProperty() ↔ knob.valueProperty().
        panToWidget = (obs, was, now) -> {
            if (now != null && panKnob.getValue() != now.doubleValue()) {
                panKnob.setValue(now.doubleValue());
            }
        };
        control.panProperty().addListener(panToWidget);
        registeredListenerCount++;
        panFromWidget = (obs, was, now) -> {
            if (now != null) {
                control.setPan(now.doubleValue());
            }
        };
        panKnob.valueProperty().addListener(panFromWidget);
        registeredListenerCount++;

        // Fader: two-way sync control.faderDbProperty() ↔ fader.valueProperty().
        faderToWidget = (obs, was, now) -> {
            if (now != null && fader.getValue() != now.doubleValue()) {
                fader.setValue(now.doubleValue());
            }
        };
        control.faderDbProperty().addListener(faderToWidget);
        registeredListenerCount++;
        faderFromWidget = (obs, was, now) -> {
            if (now != null) {
                control.setFaderDb(now.doubleValue());
            }
        };
        fader.valueProperty().addListener(faderFromWidget);
        registeredListenerCount++;

        // M/S/R two-way sync (mirrors TrackStripSkin exactly).
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
        muteBtnListener = (obs, was, now) -> control.setMuted(now);
        soloBtnListener = (obs, was, now) -> control.setSoloed(now);
        armBtnListener = (obs, was, now) -> control.setArmed(now);
        muteBtn.selectedProperty().addListener(muteBtnListener);
        soloBtn.selectedProperty().addListener(soloBtnListener);
        armBtn.selectedProperty().addListener(armBtnListener);
        registeredListenerCount += 3;

        // Value readout follows the fader value (and the embedded meter is
        // the fader's own — no separate relay).
        readoutRefreshListener = (obs, was, now) -> refreshReadout();
        control.faderDbProperty().addListener(readoutRefreshListener);
        registeredListenerCount++;

        // Channel type drives M/S/R + input-caption presence; re-evaluate
        // on change and rebuild the affected regions.
        channelTypeListener = (obs, was, now) -> applyChannelType();
        control.channelTypeProperty().addListener(channelTypeListener);
        registeredListenerCount++;

        // Initial render.
        rebuildInserts();
        rebuildSends();
        applyChannelType();
        refreshReadout();
    }

    private static ToggleButton makeToggle(String role, String glyph) {
        ToggleButton t = new ToggleButton(glyph);
        // Deliberately NOT a `.dawg-button` — same rationale as
        // TrackStripSkin: the app stylesheet's `.dawg-button` rule has
        // author-origin priority and would override the user-agent
        // `.track-toggle:selected` fill. Reuse story 270's exact classes
        // for visual consistency (story §5.4).
        t.getStyleClass().addAll("track-toggle", "size-compact", role);
        t.setFocusTraversable(false);
        t.setMaxWidth(Double.MAX_VALUE);
        return t;
    }

    private Region separator() {
        Region r = new Region();
        r.getStyleClass().add("mixer-channel-strip-separator");
        r.setMinHeight(1);
        r.setPrefHeight(1);
        r.setMaxHeight(1);
        return r;
    }

    // ── Insert / send list rendering ──────────────────────────────────────

    /**
     * Single source of truth for the insert status-dot active state
     * (267 expert-review trap #1). The rendered {@link Circle} and the
     * {@link #insertDotActive(int)} test seam both route through this — no
     * independent re-derivation. A slot's dot is "active" (renders
     * {@code -mcs-accent}) when the slot is engaged and not bypassed;
     * otherwise it is "bypassed" ({@code -mcs-text-mute}). Story §5.4:
     * "{@code -accent} if active, {@code -text-mute} if bypassed".
     *
     * @param slot the slot model
     * @return whether the slot's status dot is the active (accent) colour
     */
    private static boolean insertDotActive(InsertSlotModel slot) {
        return slot.active() && !slot.bypassed();
    }

    private void rebuildInserts() {
        MixerChannelStrip c = getSkinnable();
        List<InsertSlotModel> all = c.insertsProperty();
        insertsBox.getChildren().clear();
        int shown = Math.min(VISIBLE_INSERTS, all.size());
        for (int i = 0; i < shown; i++) {
            insertsBox.getChildren().add(insertRow(i, all.get(i)));
        }
        // Always pad to VISIBLE_INSERTS with empty placeholder rows so the
        // strip's intrinsic height is stable regardless of insert count.
        for (int i = shown; i < VISIBLE_INSERTS; i++) {
            insertsBox.getChildren().add(emptySlotRow());
        }
        int overflow = all.size() - VISIBLE_INSERTS;
        if (overflow > 0) {
            insertsOverflow.setText(OVERFLOW_GLYPH + " +" + overflow);
            insertsOverflow.setVisible(true);
            insertsOverflow.setManaged(true);
        } else {
            insertsOverflow.setVisible(false);
            insertsOverflow.setManaged(false);
        }
    }

    private Node insertRow(int index, InsertSlotModel slot) {
        Label name = new Label(slot.name());
        name.getStyleClass().add("mixer-channel-strip-slot-name");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        Circle dot = new Circle(3.0);
        dot.getStyleClass().add("mixer-channel-strip-status-dot");
        // The active/bypassed colour is decided once, here, by the shared
        // helper; the .active style class is consumed by
        // mixer-channel-strip.css so the rendered fill and the
        // insertDotActive(int) test seam cannot disagree.
        if (insertDotActive(slot)) {
            dot.getStyleClass().add("active");
        }

        HBox row = new HBox(name, dot);
        row.getStyleClass().add("mixer-channel-strip-slot-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> {
            if (disposed) return;
            if (e.getButton() != MouseButton.PRIMARY) return;
            MixerChannelStrip ctl = getSkinnable();
            if (ctl == null) return;
            // Fire through the scene graph so it bubbles (story 272's
            // Inspector consumes it via the standard dispatch chain).
            ctl.fireEvent(new InsertSelectedEvent(ctl, ctl, index));
        });
        return row;
    }

    private Node emptySlotRow() {
        Label name = new Label("—"); // em dash — empty slot
        name.getStyleClass().addAll(
                "mixer-channel-strip-slot-name", "mixer-channel-strip-slot-empty");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(name);
        row.getStyleClass().add("mixer-channel-strip-slot-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void rebuildSends() {
        MixerChannelStrip c = getSkinnable();
        List<SendSlotModel> all = c.sendsProperty();
        sendsBox.getChildren().clear();
        int shown = Math.min(VISIBLE_SENDS, all.size());
        for (int i = 0; i < shown; i++) {
            sendsBox.getChildren().add(sendRow(i, all.get(i)));
        }
        int overflow = all.size() - VISIBLE_SENDS;
        if (overflow > 0) {
            sendsOverflow.setText(OVERFLOW_GLYPH + " +" + overflow);
            sendsOverflow.setVisible(true);
            sendsOverflow.setManaged(true);
        } else {
            sendsOverflow.setVisible(false);
            sendsOverflow.setManaged(false);
        }
    }

    private Node sendRow(int index, SendSlotModel slot) {
        Label name = new Label(slot.name());
        name.getStyleClass().add("mixer-channel-strip-slot-name");
        name.setMaxWidth(Double.MAX_VALUE);

        // Compact send-level fader: no meter (story §5.4 / Fader story 269).
        Fader sendFader = Fader.create()
                .min(0).max(1).defaultValue(0)
                .value(slot.level())
                .curve(Fader.TravelCurve.LINEAR)
                .showMeter(false)
                .unit("")
                .build();
        sendFader.getStyleClass().add("mixer-channel-strip-send-fader");
        sendFader.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(sendFader, Priority.ALWAYS);

        // Pre/post toggle — plain dawg-button is acceptable per Non-Goals
        // (the per-send tap-point visual polish is story 154).
        ToggleButton prePost = new ToggleButton(slot.preFader() ? "F" : "P");
        prePost.getStyleClass().addAll("dawg-button", "size-compact",
                "mixer-channel-strip-send-tap");
        prePost.setSelected(slot.preFader());
        prePost.setFocusTraversable(false);
        prePost.selectedProperty().addListener(
                (obs, was, now) -> prePost.setText(now ? "F" : "P"));

        VBox nameRow = new VBox(name, new HBox(4, sendFader, prePost));
        nameRow.getStyleClass().add("mixer-channel-strip-send-row");
        nameRow.setOnMouseClicked(e -> {
            if (disposed) return;
            if (e.getButton() != MouseButton.PRIMARY) return;
            // Don't fire when the click landed on an interactive child
            // (the send fader / pre-post toggle have their own handlers).
            if (isInside(e.getTarget(), sendFader, prePost)) return;
            MixerChannelStrip ctl = getSkinnable();
            if (ctl == null) return;
            ctl.fireEvent(new SendSelectedEvent(ctl, ctl, index));
        });
        return nameRow;
    }

    private static boolean isInside(javafx.event.EventTarget target, Node... guards) {
        if (!(target instanceof Node node)) return false;
        while (node != null) {
            for (Node g : guards) {
                if (node == g) return true;
            }
            node = node.getParent();
        }
        return false;
    }

    // ── Channel-type-driven region presence ───────────────────────────────

    /**
     * Re-evaluates which regions are present for the current
     * {@link ChannelType}. The M/S/R row is <strong>removed from the
     * scene graph entirely</strong> for {@link ChannelType#MASTER} (not
     * merely hidden — {@code MixerChannelStripChannelTypeTest} asserts
     * absence). The input caption is removed for {@link ChannelType#BUS}.
     */
    private void applyChannelType() {
        MixerChannelStrip c = getSkinnable();
        if (c == null) return;
        ChannelType type = c.getChannelType();

        boolean showMsr = type != ChannelType.MASTER;
        if (showMsr) {
            if (!column.getChildren().contains(msrRow)) {
                // Re-insert just before the value readout to preserve
                // top→bottom order.
                int idx = column.getChildren().indexOf(valueReadout);
                column.getChildren().add(idx, msrRow);
            }
        } else {
            column.getChildren().remove(msrRow);
        }

        boolean showInput = type != ChannelType.BUS;
        inputLabelNode.setVisible(showInput);
        inputLabelNode.setManaged(showInput);
    }

    // ── Name inline-edit (minimal — must not throw) ───────────────────────

    private void onNameClicked(MouseEvent e) {
        if (disposed) return;
        if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() < 2) return;
        nameEditor.setText(nameLabel.getText());
        nameEditor.setVisible(true);
        nameEditor.setManaged(true);
        nameLabel.setVisible(false);
        nameEditor.requestFocus();
        nameEditor.selectAll();
    }

    private void commitNameEdit() {
        if (!nameEditor.isVisible()) return;
        MixerChannelStrip c = getSkinnable();
        if (c != null) {
            c.setChannelName(nameEditor.getText());
        }
        nameEditor.setVisible(false);
        nameEditor.setManaged(false);
        nameLabel.setVisible(true);
    }

    private void refreshReadout() {
        MixerChannelStrip c = getSkinnable();
        if (c == null) return;
        double db = c.getFaderDb();
        String text;
        if (Double.isInfinite(db) || db <= -120.0) {
            text = "-∞ dB";
        } else {
            text = String.format(Locale.ROOT, "%.1f dB", db);
        }
        valueReadout.setText(text);
    }

    // ── Layout ────────────────────────────────────────────────────────────

    @Override
    protected void layoutChildren(double x, double y, double w, double h) {
        // The column fills the content area (CSS padding/insets honoured).
        // VBox lays its children top→bottom; the fader has Vgrow=ALWAYS so
        // it absorbs vertical slack. Every internal dimension derives from
        // the assigned w/h — no fixed pixel offset keyed to a single
        // default (skill §7 / story "no fixed pixel offsets").
        column.resizeRelocate(x, y, w, h);

        // Pan knob diameter scales with the assigned width (clamped to a
        // sensible band so a very narrow strip doesn't produce a 0 px
        // knob and a very wide one doesn't waste vertical budget).
        double knobD = Math.max(MIN_KNOB_DIAMETER,
                Math.min(w * KNOB_WIDTH_FRACTION, MAX_KNOB_DIAMETER));
        panKnob.setMinHeight(knobD);
        panKnob.setPrefHeight(knobD);
        panKnob.setMaxHeight(knobD);
        panKnob.setMinWidth(knobD);
        panKnob.setPrefWidth(knobD);
        panKnob.setMaxWidth(knobD);

        // Insert AND send rows derive their height from the assigned height
        // so the whole strip reflows proportionally (e.g. on a Compact↔
        // Comfortable density switch) rather than via a fixed offset. Both
        // slot lists use the same single-sourced math so they stay in lockstep.
        double rowH = Math.max(MIN_ROW_HEIGHT,
                Math.min(h * ROW_HEIGHT_FRACTION, MAX_ROW_HEIGHT));
        applyRowHeight(insertsBox, rowH);
        applyRowHeight(sendsBox, rowH);
    }

    /** Applies a uniform proportional row height to every {@link Region}
     *  child of a slot-list box. Shared by the insert and send lists so the
     *  reflow math is defined exactly once. */
    private static void applyRowHeight(VBox rows, double rowH) {
        for (Node n : rows.getChildren()) {
            if (n instanceof Region r) {
                r.setMinHeight(rowH);
                r.setPrefHeight(rowH);
            }
        }
    }

    private double widthForDensity() {
        MixerChannelStrip c = getSkinnable();
        if (c == null) return COMPACT_WIDTH;
        var classes = c.getStyleClass();
        if (classes.contains("density-comfortable")) {
            return COMFORTABLE_WIDTH;
        }
        // Default density == compact width (story §5.4 "keep the width").
        return COMPACT_WIDTH;
    }

    @Override
    protected double computeMinWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return widthForDensity();
    }

    @Override
    protected double computePrefWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return widthForDensity();
    }

    @Override
    protected double computeMaxWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return widthForDensity();
    }

    @Override
    protected double computeMinHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        // A modest minimum so the strip remains usable in a tiny window;
        // the mixer panel drives the actual height.
        return 240.0;
    }

    @Override
    protected double computePrefHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return 480.0;
    }

    @Override
    protected double computeMaxHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return Double.MAX_VALUE;
    }

    // ── Test seams ────────────────────────────────────────────────────────

    /** @return the embedded pan {@link Knob} (test seam). */
    public Knob knob() { return panKnob; }
    /** @return the embedded {@link Fader} (test seam). */
    public Fader fader() { return fader; }
    /** @return the M toggle button (test seam). */
    public ToggleButton muteButton() { return muteBtn; }
    /** @return the S toggle button (test seam). */
    public ToggleButton soloButton() { return soloBtn; }
    /** @return the R toggle button (test seam). */
    public ToggleButton armButton() { return armBtn; }
    /** @return the M/S/R row (test seam — absent from scene graph for MASTER). */
    public HBox msrRow() { return msrRow; }
    /** @return the channel-name label (test seam). */
    public Label nameLabel() { return nameLabel; }
    /** @return the fader value readout label (test seam). */
    public Label valueReadout() { return valueReadout; }
    /** @return the input caption label (test seam). */
    public Label inputLabelNode() { return inputLabelNode; }

    /**
     * Test seam: the rendered insert slot-row node at {@code index} (the
     * same node whose {@code onMouseClicked} fires
     * {@link InsertSelectedEvent}). Padding placeholder rows beyond the
     * model size are also returned but carry no click handler.
     *
     * @param index the visible insert-row index
     * @return the row {@link Node}, or {@code null} if out of range
     */
    public Node insertRowNode(int index) {
        if (index < 0 || index >= insertsBox.getChildren().size()) return null;
        return insertsBox.getChildren().get(index);
    }

    /**
     * Test seam: the rendered send slot-row node at {@code index}.
     *
     * @param index the visible send-row index
     * @return the row {@link Node}, or {@code null} if out of range
     */
    public Node sendRowNode(int index) {
        if (index < 0 || index >= sendsBox.getChildren().size()) return null;
        return sendsBox.getChildren().get(index);
    }

    /**
     * Test seam: whether the insert slot at {@code index} renders its
     * status dot in the active (accent) colour. Routes through the same
     * {@link #insertDotActive(InsertSlotModel)} helper the rendered
     * {@link Circle} uses — they cannot disagree (267 trap #1).
     *
     * @param index the insert-slot index
     * @return {@code true} if that slot's dot is the active colour;
     *         {@code false} if out of range or bypassed/inactive
     */
    public boolean insertDotActive(int index) {
        MixerChannelStrip c = getSkinnable();
        if (c == null) return false;
        List<InsertSlotModel> all = c.insertsProperty();
        if (index < 0 || index >= all.size()) return false;
        return insertDotActive(all.get(index));
    }

    /** @return whether {@link #dispose()} has run (test seam). */
    public boolean isDisposed() { return disposed; }
    /** @return cumulative count of listeners registered on the control's properties (test seam). */
    public int registeredListenerCount() { return registeredListenerCount; }

    // ── Dispose ───────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        MixerChannelStrip c = getSkinnable();
        if (c != null) {
            c.inputLabelProperty().removeListener(inputLabelListener);
            registeredListenerCount--;
            c.outputLabelProperty().removeListener(outputLabelListener);
            registeredListenerCount--;
            c.channelNameProperty().removeListener(nameListener);
            registeredListenerCount--;
            c.insertsProperty().removeListener(insertsListener);
            registeredListenerCount--;
            c.sendsProperty().removeListener(sendsListener);
            registeredListenerCount--;
            c.panProperty().removeListener(panToWidget);
            registeredListenerCount--;
            c.faderDbProperty().removeListener(faderToWidget);
            registeredListenerCount--;
            c.mutedProperty().removeListener(mutedSyncListener);
            registeredListenerCount--;
            c.soloedProperty().removeListener(soloedSyncListener);
            registeredListenerCount--;
            c.armedProperty().removeListener(armedSyncListener);
            registeredListenerCount--;
            c.faderDbProperty().removeListener(readoutRefreshListener);
            registeredListenerCount--;
            c.channelTypeProperty().removeListener(channelTypeListener);
            registeredListenerCount--;
        }
        // Detach button → control listeners too, so swapping the skin fully
        // severs the link in both directions (otherwise external mutations
        // of the cached button references would still mutate the control
        // through the disposed skin's lambdas).
        muteBtn.selectedProperty().removeListener(muteBtnListener);
        registeredListenerCount--;
        soloBtn.selectedProperty().removeListener(soloBtnListener);
        registeredListenerCount--;
        armBtn.selectedProperty().removeListener(armBtnListener);
        registeredListenerCount--;
        panKnob.valueProperty().removeListener(panFromWidget);
        registeredListenerCount--;
        fader.valueProperty().removeListener(faderFromWidget);
        registeredListenerCount--;
        // Detach the embedded fader's meter so its AnimationTimer stops
        // (sceneProperty → null) when the skin is swapped. Done outside the
        // null guard so the timer is guaranteed to stop even if the
        // skinnable is already null at dispose — otherwise the meter's
        // pulse leaks for the lifetime of the JVM.
        getChildren().clear();
        super.dispose();
    }
}
