package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;

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

    /** Icon size for transport-bar buttons (play, stop, record). */
    static final double TRANSPORT_ICON_SIZE = 14;
    /** Icon size for toolbar buttons (add track, save, plugins). */
    static final double TOOLBAR_ICON_SIZE = 14;
    /** Icon size for panel-header labels. */
    static final double PANEL_ICON_SIZE = 16;
    /** Show delay for all tooltips (300ms for quick discoverability). */
    static final Duration TOOLTIP_SHOW_DELAY = Duration.millis(300);
    /** Width threshold below which lower-priority toolbar groups are hidden. */
    static final double TOOLBAR_OVERFLOW_THRESHOLD = 1280.0;

    // ── Grouped UI references ────────────────────────────────────────────────

    /** Transport-bar buttons. */
    record TransportButtons(Button skipBack, Button play, Button pause,
                            Button stop, Button record, Button skipForward,
                            Button loop, Button metronome) {}

    /** Main toolbar buttons. */
    record ToolbarButtons(Button addAudioTrack, Button addMidiTrack,
                          Button undo, Button redo, Button snap,
                          Button save, Button plugins) {}

    /** Sidebar navigation and utility buttons. */
    record SidebarButtons(Button home, Button arrangementView, Button mixerView,
                          Button editorView, Button telemetryView,
                          Button masteringView, Button newProject,
                          Button openProject, Button saveProject,
                          Button recentProjects, Button importSession,
                          Button exportSession, Button browser,
                          Button search, Button history,
                          Button pluginsSidebar, Button visualizations,
                          Button settings, Button expandCollapse,
                          Button help) {}

    /** Edit-tool palette buttons. */
    record EditToolButtons(Button pointer, Button pencil, Button eraser,
                           Button scissors, Button glue) {}

    /** Zoom control buttons. */
    record ZoomButtons(Button zoomIn, Button zoomOut, Button zoomToFit) {}

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
    private final SidebarButtons sidebarButtons;
    private final EditToolButtons editToolButtons;
    private final ZoomButtons zoomButtons;
    private final AppearanceLabels labels;
    private final OverflowGroups overflowGroups;
    private final BorderPane rootPane;
    private final KeyBindingManager keyBindingManager;

    ToolbarAppearanceController(TransportButtons transportButtons,
                                ToolbarButtons toolbarButtons,
                                SidebarButtons sidebarButtons,
                                EditToolButtons editToolButtons,
                                ZoomButtons zoomButtons,
                                AppearanceLabels labels,
                                OverflowGroups overflowGroups,
                                BorderPane rootPane,
                                KeyBindingManager keyBindingManager) {
        this.transportButtons = Objects.requireNonNull(transportButtons, "transportButtons must not be null");
        this.toolbarButtons = Objects.requireNonNull(toolbarButtons, "toolbarButtons must not be null");
        this.sidebarButtons = Objects.requireNonNull(sidebarButtons, "sidebarButtons must not be null");
        this.editToolButtons = Objects.requireNonNull(editToolButtons, "editToolButtons must not be null");
        this.zoomButtons = Objects.requireNonNull(zoomButtons, "zoomButtons must not be null");
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
     * Applies SVG icons from the DAW icon pack to all UI controls.
     *
     * <p>Icons are drawn from every category in the pack to provide rich visual
     * feedback: playback controls use the <em>Playback</em> category; track-type
     * indicators pull from <em>Media</em> and <em>Instruments</em>; status labels
     * reference <em>Notifications</em>; I/O routing uses <em>Connectivity</em>;
     * and so on across all 14 categories.</p>
     */
    private void applyIcons() {
        // ── Transport controls (Playback category) ──────────────────────────
        transportButtons.skipBack.setGraphic(IconNode.of(DawIcon.SKIP_BACK, TRANSPORT_ICON_SIZE));
        transportButtons.play.setGraphic(IconNode.of(DawIcon.PLAY, TRANSPORT_ICON_SIZE));
        transportButtons.pause.setGraphic(IconNode.of(DawIcon.PAUSE, TRANSPORT_ICON_SIZE));
        transportButtons.stop.setGraphic(IconNode.of(DawIcon.STOP, TRANSPORT_ICON_SIZE));
        transportButtons.record.setGraphic(IconNode.of(DawIcon.RECORD, TRANSPORT_ICON_SIZE));
        transportButtons.skipForward.setGraphic(IconNode.of(DawIcon.SKIP_FORWARD, TRANSPORT_ICON_SIZE));
        transportButtons.loop.setGraphic(IconNode.of(DawIcon.LOOP, TRANSPORT_ICON_SIZE));
        transportButtons.metronome.setGraphic(IconNode.of(DawIcon.METRONOME, TRANSPORT_ICON_SIZE));

        // ── Toolbar buttons (mixed categories) ─────────────────────────────
        toolbarButtons.addAudioTrack.setGraphic(IconNode.of(DawIcon.MICROPHONE, TOOLBAR_ICON_SIZE));
        toolbarButtons.addMidiTrack.setGraphic(IconNode.of(DawIcon.KEYBOARD, TOOLBAR_ICON_SIZE));
        toolbarButtons.undo.setGraphic(IconNode.of(DawIcon.UNDO, TOOLBAR_ICON_SIZE));
        toolbarButtons.redo.setGraphic(IconNode.of(DawIcon.REDO, TOOLBAR_ICON_SIZE));
        toolbarButtons.snap.setGraphic(IconNode.of(DawIcon.SNAP, TOOLBAR_ICON_SIZE));
        toolbarButtons.save.setGraphic(IconNode.of(DawIcon.DOWNLOAD, TOOLBAR_ICON_SIZE));
        toolbarButtons.plugins.setGraphic(IconNode.of(DawIcon.EQ, TOOLBAR_ICON_SIZE));

        // ── Time display — timer icon prefix (General category) ────────────
        labels.timeDisplay.setGraphic(IconNode.of(DawIcon.TIMER, PANEL_ICON_SIZE));

        // ── Panel headers ───────────────────────────────────────────────────
        labels.tracksPanelHeader.setGraphic(IconNode.of(DawIcon.MIXER, PANEL_ICON_SIZE));
        labels.arrangementPanelHeader.setGraphic(IconNode.of(DawIcon.TIMELINE, PANEL_ICON_SIZE));

        // ── Arrangement placeholder (Media category) ────────────────────────
        labels.arrangementPlaceholder.setGraphic(IconNode.of(DawIcon.MUSIC_NOTE, 24));

        // ── Status bar icons ────────────────────────────────────────────────
        labels.monitoringLabel.setGraphic(IconNode.of(DawIcon.HEADPHONES, 12));
        labels.checkpointLabel.setGraphic(IconNode.of(DawIcon.SYNC, 12));
        labels.statusBarLabel.setGraphic(IconNode.of(DawIcon.STATUS, 12));
        labels.ioRoutingLabel.setGraphic(IconNode.of(DawIcon.USB, 12));
        labels.recIndicator.setGraphic(IconNode.of(DawIcon.RECORD, 14));

        // ── Sidebar toolbar buttons ─────────────────────────────────────────
        sidebarButtons.home.setGraphic(IconNode.of(DawIcon.HOME, TOOLBAR_ICON_SIZE));
        sidebarButtons.arrangementView.setGraphic(IconNode.of(DawIcon.TIMELINE, TOOLBAR_ICON_SIZE));
        sidebarButtons.mixerView.setGraphic(IconNode.of(DawIcon.MIXER, TOOLBAR_ICON_SIZE));
        sidebarButtons.editorView.setGraphic(IconNode.of(DawIcon.WAVEFORM, TOOLBAR_ICON_SIZE));
        sidebarButtons.telemetryView.setGraphic(IconNode.of(DawIcon.SURROUND, TOOLBAR_ICON_SIZE));
        sidebarButtons.masteringView.setGraphic(IconNode.of(DawIcon.LIMITER, TOOLBAR_ICON_SIZE));
        sidebarButtons.newProject.setGraphic(IconNode.of(DawIcon.FOLDER, TOOLBAR_ICON_SIZE));
        sidebarButtons.openProject.setGraphic(IconNode.of(DawIcon.FOLDER, TOOLBAR_ICON_SIZE));
        sidebarButtons.saveProject.setGraphic(IconNode.of(DawIcon.DOWNLOAD, TOOLBAR_ICON_SIZE));
        sidebarButtons.recentProjects.setGraphic(IconNode.of(DawIcon.HISTORY, TOOLBAR_ICON_SIZE));
        sidebarButtons.importSession.setGraphic(IconNode.of(DawIcon.DOWNLOAD, TOOLBAR_ICON_SIZE));
        sidebarButtons.exportSession.setGraphic(IconNode.of(DawIcon.UPLOAD, TOOLBAR_ICON_SIZE));
        sidebarButtons.browser.setGraphic(IconNode.of(DawIcon.LIBRARY, TOOLBAR_ICON_SIZE));
        sidebarButtons.search.setGraphic(IconNode.of(DawIcon.SEARCH, TOOLBAR_ICON_SIZE));
        sidebarButtons.history.setGraphic(IconNode.of(DawIcon.HISTORY, TOOLBAR_ICON_SIZE));
        sidebarButtons.pluginsSidebar.setGraphic(IconNode.of(DawIcon.EQUALIZER, TOOLBAR_ICON_SIZE));
        sidebarButtons.visualizations.setGraphic(IconNode.of(DawIcon.SPECTRUM, TOOLBAR_ICON_SIZE));
        sidebarButtons.settings.setGraphic(IconNode.of(DawIcon.SETTINGS, TOOLBAR_ICON_SIZE));
        sidebarButtons.expandCollapse.setGraphic(IconNode.of(DawIcon.EXPAND, TOOLBAR_ICON_SIZE));
        sidebarButtons.help.setGraphic(IconNode.of(DawIcon.INFO, TOOLBAR_ICON_SIZE));

        // ── Edit tool buttons (Editing category) ───────────────────────────
        editToolButtons.pointer.setGraphic(IconNode.of(DawIcon.MOVE, TOOLBAR_ICON_SIZE));
        editToolButtons.pencil.setGraphic(IconNode.of(DawIcon.MARKER, TOOLBAR_ICON_SIZE));
        editToolButtons.eraser.setGraphic(IconNode.of(DawIcon.DELETE, TOOLBAR_ICON_SIZE));
        editToolButtons.scissors.setGraphic(IconNode.of(DawIcon.SPLIT, TOOLBAR_ICON_SIZE));
        editToolButtons.glue.setGraphic(IconNode.of(DawIcon.CROSSFADE, TOOLBAR_ICON_SIZE));

        // ── Zoom buttons (Editing + Navigation categories) ─────────────────
        zoomButtons.zoomIn.setGraphic(IconNode.of(DawIcon.ZOOM_IN, TOOLBAR_ICON_SIZE));
        zoomButtons.zoomOut.setGraphic(IconNode.of(DawIcon.ZOOM_OUT, TOOLBAR_ICON_SIZE));
        zoomButtons.zoomToFit.setGraphic(IconNode.of(DawIcon.FULLSCREEN, TOOLBAR_ICON_SIZE));

        LOG.fine("Applied SVG icons from DAW icon pack");
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
        transportButtons.play.setTooltip(styledTooltip(tooltipFor("Play", DawAction.PLAY_STOP)));
        transportButtons.pause.setTooltip(styledTooltip("Pause"));
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

        // ── Sidebar view buttons ────────────────────────────────────────────
        sidebarButtons.home.setTooltip(styledTooltip(
                "Home \u2014 Return to the default view"));
        sidebarButtons.arrangementView.setTooltip(styledTooltip(tooltipFor("Arrangement View", DawAction.VIEW_ARRANGEMENT)));
        sidebarButtons.mixerView.setTooltip(styledTooltip(tooltipFor("Mixer View", DawAction.VIEW_MIXER)));
        sidebarButtons.editorView.setTooltip(styledTooltip(tooltipFor("Editor View", DawAction.VIEW_EDITOR)));
        sidebarButtons.telemetryView.setTooltip(styledTooltip(tooltipFor("Sound Wave Telemetry View", DawAction.VIEW_TELEMETRY)));
        sidebarButtons.masteringView.setTooltip(styledTooltip(tooltipFor("Mastering View", DawAction.VIEW_MASTERING)));
        sidebarButtons.newProject.setTooltip(styledTooltip(tooltipFor("New Project", DawAction.NEW_PROJECT)));
        sidebarButtons.openProject.setTooltip(styledTooltip(tooltipFor("Open Project", DawAction.OPEN_PROJECT)));
        sidebarButtons.saveProject.setTooltip(styledTooltip(tooltipFor("Save Project", DawAction.SAVE)));
        sidebarButtons.recentProjects.setTooltip(styledTooltip(
                "Recent Projects \u2014 Open a recently saved project"));
        sidebarButtons.importSession.setTooltip(styledTooltip(
                tooltipFor("Import Session \u2014 Import a DAWproject (.dawproject) file",
                        DawAction.IMPORT_SESSION)));
        sidebarButtons.exportSession.setTooltip(styledTooltip(
                tooltipFor("Export Session \u2014 Export to DAWproject (.dawproject) format",
                        DawAction.EXPORT_SESSION)));
        sidebarButtons.browser.setTooltip(styledTooltip(
                "Browser \u2014 Browse samples, presets, and project files"
                        + shortcutSuffix(DawAction.TOGGLE_BROWSER)));
        sidebarButtons.search.setTooltip(styledTooltip(
                "Search \u2014 Find tracks, clips, and project items"));
        sidebarButtons.history.setTooltip(styledTooltip(
                "Undo History \u2014 Browse and navigate undo history"
                        + shortcutSuffix(DawAction.TOGGLE_HISTORY)));
        sidebarButtons.pluginsSidebar.setTooltip(styledTooltip(
                "Plugins \u2014 Browse and manage audio plugins"));
        sidebarButtons.visualizations.setTooltip(styledTooltip(
                "Visualizations \u2014 Toggle audio visualization panels"
                        + shortcutSuffix(DawAction.TOGGLE_VISUALIZATIONS)));
        sidebarButtons.settings.setTooltip(styledTooltip(tooltipFor("Settings", DawAction.OPEN_SETTINGS)));
        sidebarButtons.expandCollapse.setTooltip(styledTooltip(tooltipFor("Collapse/Expand Toolbar", DawAction.TOGGLE_TOOLBAR)));
        sidebarButtons.help.setTooltip(styledTooltip(
                "Help \u2014 View documentation and keyboard shortcuts"));

        // ── Edit tool buttons ───────────────────────────────────────────────
        editToolButtons.pointer.setTooltip(styledTooltip(tooltipFor("Pointer Tool", DawAction.TOOL_POINTER)));
        editToolButtons.pencil.setTooltip(styledTooltip(tooltipFor("Pencil Tool", DawAction.TOOL_PENCIL)));
        editToolButtons.eraser.setTooltip(styledTooltip(tooltipFor("Eraser Tool", DawAction.TOOL_ERASER)));
        editToolButtons.scissors.setTooltip(styledTooltip(tooltipFor("Scissors Tool", DawAction.TOOL_SCISSORS)));
        editToolButtons.glue.setTooltip(styledTooltip(tooltipFor("Glue Tool", DawAction.TOOL_GLUE)));

        // ── Zoom buttons ────────────────────────────────────────────────────
        zoomButtons.zoomIn.setTooltip(styledTooltip(tooltipFor("Zoom In", DawAction.ZOOM_IN)));
        zoomButtons.zoomOut.setTooltip(styledTooltip(tooltipFor("Zoom Out", DawAction.ZOOM_OUT)));
        zoomButtons.zoomToFit.setTooltip(styledTooltip(tooltipFor("Zoom to Fit", DawAction.ZOOM_TO_FIT)));
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
     * Returns a suffix string like {@code " (shortcut)"} or an empty string
     * if the action has no binding. Useful for appending to longer tooltip text.
     */
    private String shortcutSuffix(DawAction action) {
        String shortcut = keyBindingManager.getDisplayText(action);
        if (shortcut.isEmpty()) {
            return "";
        }
        return " (" + shortcut + ")";
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
                transportButtons.skipBack, transportButtons.play, transportButtons.pause,
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
