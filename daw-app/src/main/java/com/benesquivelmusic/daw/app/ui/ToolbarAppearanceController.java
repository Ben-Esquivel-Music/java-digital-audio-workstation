package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawgIcon;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Applies visual appearance to the main toolbar: SVG icons, descriptive
 * tooltips with keyboard-shortcut hints, minimum button widths, and
 * responsive overflow behavior at narrow window widths.
 *
 * <p>Extracted from {@link MainController} to isolate toolbar look-and-feel
 * concerns into a dedicated, independently testable class.  All
 * dependencies are received via constructor injection.</p>
 */
final class ToolbarAppearanceController {

    private static final Logger LOG = Logger.getLogger(ToolbarAppearanceController.class.getName());

    /** Show delay for all tooltips (300ms for quick discoverability). */
    static final Duration TOOLTIP_SHOW_DELAY = Duration.millis(300);
    /** Width threshold below which lower-priority toolbar groups are hidden. */
    static final double TOOLBAR_OVERFLOW_THRESHOLD = 1280.0;

    // ── Grouped UI references ────────────────────────────────────────────────

    /** Transport-bar buttons. */
    record TransportButtons(Button skipBack, Button play,
                            Button stop, Button record, Button skipForward,
                            Button loop, Button metronome) {}

    /** Main toolbar buttons. */
    record ToolbarButtons(Button addAudioTrack, Button addMidiTrack,
                          Button undo, Button redo, Button snap,
                          Button save, Button plugins) {}

    /** Labels whose icons and tooltips are managed by this controller. */
    record AppearanceLabels(Label status, Label timeDisplay,
                            Label tracksPanelHeader, Label arrangementPanelHeader,
                            Label arrangementPlaceholder,
                            Label monitoringLabel, Label checkpointLabel,
                            Label statusBarLabel, Label ioRoutingLabel,
                            Label recIndicator) {}

    /** Overflow groups that are hidden/shown when the window is narrow. */
    record OverflowGroups(HBox utilityGroup, HBox undoRedoGroup) {}

    // ── Instance state ───────────────────────────────────────────────────────

    private final TransportButtons transportButtons;
    private final ToolbarButtons toolbarButtons;
    private final AppearanceLabels labels;
    private final OverflowGroups overflowGroups;
    private final BorderPane rootPane;
    private final KeyBindingManager keyBindingManager;

    ToolbarAppearanceController(TransportButtons transportButtons,
                                ToolbarButtons toolbarButtons,
                                AppearanceLabels labels,
                                OverflowGroups overflowGroups,
                                BorderPane rootPane,
                                KeyBindingManager keyBindingManager) {
        this.transportButtons = Objects.requireNonNull(transportButtons, "transportButtons must not be null");
        this.toolbarButtons = Objects.requireNonNull(toolbarButtons, "toolbarButtons must not be null");
        this.labels = Objects.requireNonNull(labels, "labels must not be null");
        this.overflowGroups = Objects.requireNonNull(overflowGroups, "overflowGroups must not be null");
        this.rootPane = Objects.requireNonNull(rootPane, "rootPane must not be null");
        this.keyBindingManager = Objects.requireNonNull(keyBindingManager, "keyBindingManager must not be null");
    }

    // ── Public entry point ───────────────────────────────────────────────────

    /**
     * Applies icons, tooltips, and overflow behavior to the toolbar in one call.
     */
    void apply() {
        applyIcons();
        applyTooltips();
        preventButtonTruncation();
    }

    // ── Icon application ─────────────────────────────────────────────────────

    /**
     * Applies the toolbar's iconography in line with UI Design Book §2.4
     * and §3.6.
     *
     * <p><strong>The "no icon next to label" rule.</strong> Per §2.4, an
     * icon is a <em>replacement</em> for a label, not a decoration on it.
     * Transport buttons (Skip / Play / Stop / Record / Skip / Loop) and
     * toolbar action buttons (Audio Track / MIDI Track / Undo / Redo /
     * Snap / Save / Plugins) keep their text label only — no graphic.
     * Panel-header labels, the time display, the tempo label, and the
     * status-bar labels keep their text only for the same reason.</p>
     *
     * <p><strong>Where icons remain.</strong> Inline status glyphs that
     * <em>are</em> the indicator — the record dot and the arrangement
     * placeholder's music note — render as pure {@link DawgIcon} regions
     * sourced from Lucide ({@link DawgIcon}, §3.6).</p>
     */
    private void applyIcons() {
        // ── Transport, toolbar, panel-header & status-bar labels ──
        //
        // Per UI Design Book §2.4 these controls all carry text labels;
        // icon-next-to-label is the rejected pattern from §1.4 ("Icon-in-
        // button overload") and the user veto in §7.9. Strip any graphic
        // that a previous build / story may have left in place so the
        // controls become pure-text consistently.
        clearGraphic(transportButtons.skipBack);
        clearGraphic(transportButtons.play);
        clearGraphic(transportButtons.stop);
        clearGraphic(transportButtons.record);
        clearGraphic(transportButtons.skipForward);
        clearGraphic(transportButtons.loop);
        clearGraphic(transportButtons.metronome);

        clearGraphic(toolbarButtons.addAudioTrack);
        clearGraphic(toolbarButtons.addMidiTrack);
        clearGraphic(toolbarButtons.undo);
        clearGraphic(toolbarButtons.redo);
        clearGraphic(toolbarButtons.snap);
        clearGraphic(toolbarButtons.save);
        clearGraphic(toolbarButtons.plugins);

        clearGraphic(labels.timeDisplay);
        clearGraphic(labels.tracksPanelHeader);
        clearGraphic(labels.arrangementPanelHeader);
        clearGraphic(labels.monitoringLabel);
        clearGraphic(labels.checkpointLabel);
        clearGraphic(labels.statusBarLabel);
        clearGraphic(labels.ioRoutingLabel);

        // ── Inline status glyphs — icon-only, no text ──
        // The arrangement placeholder is a hint, not a button; pairing
        // the icon with text reads as a tile heading rather than a call
        // to action (UI Design Book §2.4).
        labels.arrangementPlaceholder.setGraphic(
                DawgIcon.of("music", DawgIcon.Size.SIZE_24));
        // The REC indicator is icon-only per §2.4 — the "● REC" text in
        // FXML is cleared and replaced with a tooltip.
        labels.recIndicator.setGraphic(
                DawgIcon.of("circle-dot", DawgIcon.Size.SIZE_16));
        labels.recIndicator.setText("");
        labels.recIndicator.setTooltip(styledTooltip("Recording"));

        LOG.fine("Applied iconography per UI Design Book §2.4 / §3.6");
    }

    /**
     * Clears any previously-applied graphic from a labelled control.
     * {@link javafx.scene.control.Labeled Labeled} is the common
     * supertype shared by {@link javafx.scene.control.Button Button}
     * and {@link javafx.scene.control.Label Label}, which are the two
     * control types the toolbar manages.
     */
    private static void clearGraphic(javafx.scene.control.Labeled control) {
        if (control == null) return;
        control.setGraphic(null);
    }


    // ── Tooltip application ──────────────────────────────────────────────────

    /**
     * Applies descriptive tooltips with keyboard shortcut hints to all UI controls.
     *
     * <p>Tooltips follow the format {@code "Action Name (Shortcut)"} and use a
     * 300&nbsp;ms show delay for quick discoverability. Shortcut hints are
     * resolved from the {@link KeyBindingManager} so they reflect any custom
     * bindings. Ambiguous buttons include a brief description separated by an
     * em-dash.</p>
     */
    private void applyTooltips() {
        // ── Transport controls ──────────────────────────────────────────────
        transportButtons.skipBack.setTooltip(styledTooltip(tooltipFor("Skip to Beginning", DawAction.SKIP_TO_START)));
        transportButtons.play.setTooltip(styledTooltip(tooltipFor("Play / Pause", DawAction.PLAY_STOP)));
        transportButtons.stop.setTooltip(styledTooltip(tooltipFor("Stop", DawAction.STOP)));
        transportButtons.record.setTooltip(styledTooltip(tooltipFor("Record", DawAction.RECORD)));
        transportButtons.skipForward.setTooltip(styledTooltip(tooltipFor("Skip Forward", DawAction.SKIP_TO_END)));
        transportButtons.loop.setTooltip(styledTooltip(tooltipFor("Toggle Loop", DawAction.TOGGLE_LOOP)));
        transportButtons.metronome.setTooltip(styledTooltip(
                tooltipFor("Toggle Metronome", DawAction.TOGGLE_METRONOME) + " \u00b7 Right-click for options"));

        // ── Toolbar buttons ─────────────────────────────────────────────────
        toolbarButtons.addAudioTrack.setTooltip(styledTooltip(tooltipFor("Add Audio Track", DawAction.ADD_AUDIO_TRACK)));
        toolbarButtons.addMidiTrack.setTooltip(styledTooltip(tooltipFor("Add MIDI Track", DawAction.ADD_MIDI_TRACK)));
        toolbarButtons.undo.setTooltip(styledTooltip(tooltipFor("Undo", DawAction.UNDO)));
        toolbarButtons.redo.setTooltip(styledTooltip(tooltipFor("Redo", DawAction.REDO)));
        toolbarButtons.snap.setTooltip(styledTooltip(
                tooltipFor("Toggle Snap", DawAction.TOGGLE_SNAP) + " \u00b7 Right-click for grid resolution"));
        toolbarButtons.save.setTooltip(styledTooltip(tooltipFor("Save Project", DawAction.SAVE)));
        toolbarButtons.plugins.setTooltip(styledTooltip(
                "Manage Plugins \u2014 Add, remove, and configure audio plugins"));
    }

    /**
     * Returns a tooltip string in the form {@code "label (shortcut)"}.
     * If the action has no binding, returns just the label.
     */
    private String tooltipFor(String label, DawAction action) {
        String shortcut = keyBindingManager.getDisplayText(action);
        if (shortcut.isEmpty()) {
            return label;
        }
        return label + " (" + shortcut + ")";
    }

    /**
     * Creates a {@link Tooltip} with the given text and a fast show delay
     * matching the application's dark theme.
     */
    static Tooltip styledTooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(TOOLTIP_SHOW_DELAY);
        return tooltip;
    }

    // ── Button truncation and overflow ───────────────────────────────────────

    /**
     * Prevents transport-bar buttons and the status label from truncating their
     * text by setting each control's minimum width to its preferred width.
     * Also installs a responsive overflow listener that hides lower-priority
     * button groups (utility and undo/redo) at narrow window widths.
     */
    private void preventButtonTruncation() {
        for (Button btn : new Button[]{
                transportButtons.skipBack, transportButtons.play,
                transportButtons.stop, transportButtons.record,
                transportButtons.skipForward, transportButtons.loop,
                toolbarButtons.addAudioTrack, toolbarButtons.addMidiTrack,
                toolbarButtons.undo, toolbarButtons.redo, toolbarButtons.snap,
                toolbarButtons.save, toolbarButtons.plugins}) {
            btn.setMinWidth(Region.USE_PREF_SIZE);
        }
        labels.status.setMinWidth(Region.USE_PREF_SIZE);
        installToolbarOverflowListener();
    }

    /**
     * Installs a listener that hides non-essential toolbar button groups when
     * the window width drops at or below {@link #TOOLBAR_OVERFLOW_THRESHOLD}.
     * The transport controls and time display always remain visible.
     */
    private void installToolbarOverflowListener() {
        rootPane.sceneProperty().addListener((_, _, scene) -> {
            if (scene != null) {
                scene.widthProperty().addListener((_, _, newWidth) ->
                        applyToolbarOverflow(newWidth.doubleValue()));
                applyToolbarOverflow(scene.getWidth());
            }
        });
    }

    /**
     * Shows or hides lower-priority toolbar groups based on the current
     * scene width.  Both the utility and undo/redo groups are hidden
     * simultaneously when the width is at or below the threshold.
     */
    private void applyToolbarOverflow(double width) {
        boolean narrow = width <= TOOLBAR_OVERFLOW_THRESHOLD;
        setGroupVisible(overflowGroups.utilityGroup, !narrow);
        setGroupVisible(overflowGroups.undoRedoGroup, !narrow);
    }

    private void setGroupVisible(HBox group, boolean visible) {
        if (group != null) {
            group.setVisible(visible);
            group.setManaged(visible);
            // Also hide the separator immediately before the group
            int idx = group.getParent() instanceof HBox parent
                    ? parent.getChildren().indexOf(group)
                    : -1;
            if (idx > 0) {
                Node prev = ((HBox) group.getParent()).getChildren().get(idx - 1);
                if (prev instanceof Separator) {
                    prev.setVisible(visible);
                    prev.setManaged(visible);
                }
            }
        }
    }
}
