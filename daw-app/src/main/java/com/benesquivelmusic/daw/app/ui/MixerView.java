package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.InputMeterStrip;
import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitor;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.mixer.*;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshotManager;
import com.benesquivelmusic.daw.core.mixer.snapshot.RecallSnapshotAction;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo;
import com.benesquivelmusic.daw.sdk.audio.ChannelGrouping;
import com.benesquivelmusic.daw.sdk.audio.ChannelKind;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A mixer view that displays all project tracks as vertical channel strips.
 *
 * <p>Each channel strip contains (from top to bottom):
 * <ul>
 *   <li>Channel name label</li>
 *   <li>Insert effects rack ({@link InsertEffectRack})</li>
 *   <li>Level meter (vertical bar via {@link LevelMeterDisplay})</li>
 *   <li>Volume fader (vertical {@link Slider})</li>
 *   <li>Pan control (horizontal {@link Slider})</li>
 *   <li>Mute / Solo / Arm buttons</li>
 *   <li>Send level controls (one per return bus)</li>
 * </ul>
 *
 * <p>Return buses are displayed as distinct channel strips between the track
 * channels and the master channel, separated by vertical separators.</p>
 *
 * <p>The master channel strip is always displayed on the far right,
 * separated from the return bus channels by a vertical separator.</p>
 *
 * <p>Uses existing CSS classes: {@code .mixer-panel}, {@code .mixer-channel},
 * {@code .mixer-channel-name}, {@code .mixer-fader}.</p>
 */
public final class MixerView extends VBox {

    private static final double FADER_HEIGHT = 150;
    private static final double CHANNEL_WIDTH = 80;
    private static final double METER_WIDTH = 12;
    private static final double METER_HEIGHT = 120;
    private static final double INPUT_METER_WIDTH = 10;
    private static final double CONTROL_ICON_SIZE = 14;
    private static final double SEND_SLIDER_WIDTH = 60;

    private final DawProject project;
    private final UndoManager undoManager;
    private final HBox channelStrips;
    private final HBox returnBusStrips;
    private final HBox vcaStrips;
    private final VBox masterStrip;
    private final List<InsertEffectRack> activeInsertRacks = new ArrayList<>();
    private final List<InputMeterStrip> activeInputMeterStrips = new ArrayList<>();
    /**
     * Channel UUIDs (track ids) currently selected via Ctrl/Shift-click on a
     * channel strip. Used to seed the "Create VCA from selection" right-click
     * menu so the engineer can create a VCA over several drum channels in one
     * gesture, matching the issue's "select several channels → right-click →
     * Create VCA" UX.
     */
    private final Set<UUID> selectedChannelIds = new HashSet<>();
    /**
     * Per-channel-id slider/button references the {@link ChannelLinkManager}
     * propagation path uses to mirror UI state across a stereo pair without
     * a full strip rebuild. The {@code LinkedHashMap} preserves insertion
     * order to make iteration in tests deterministic. Cleared and
     * repopulated by {@link #refresh()} on every rebuild — the lookup
     * tables are owned by this {@code MixerView} and are not exposed
     * outside the package.
     */
    private final Map<UUID, Slider> volumeFaderByChannelId = new LinkedHashMap<>();
    private final Map<UUID, Slider> panSliderByChannelId   = new LinkedHashMap<>();
    private final Map<UUID, Button> muteBtnByChannelId     = new LinkedHashMap<>();
    private final Map<UUID, Button> soloBtnByChannelId     = new LinkedHashMap<>();
    private final Map<UUID, MixerChannel> channelByChannelId = new LinkedHashMap<>();
    private final Map<UUID, Track>        trackByChannelId   = new LinkedHashMap<>();
    /**
     * Channel ids currently receiving a propagated edit from a partner.
     * Used as a re-entry guard so mirroring a fader/pan/mute/solo change
     * onto the partner does not in turn re-fire the propagation path back
     * onto the source — guarantees a single round-trip per user gesture.
     */
    private final Set<UUID> propagationSuppressed = new HashSet<>();
    /**
     * Listener registered on the project's {@link ChannelLinkManager} that
     * triggers a {@link #refresh()} whenever a link is added, removed, or
     * replaced — keeps the chain glyphs, connector lines, and L/R badges
     * in sync with the model. Held as a field so we can deregister if the
     * view is ever disposed (defensive — there is no current dispose path).
     */
    private final Runnable channelLinkListener;
    /**
     * Side panel listing saved mixer-scene snapshots (Story 103). Mounted
     * to the right of the channel strips; toggled via the "Snapshots"
     * toolbar button and the corresponding mixer-menu item.
     */
    private final MixerSnapshotsPanel snapshotsPanel;
    /** Top-row button that toggles {@link #snapshotsPanel} visibility. */
    private final ToggleButton snapshotsToggleButton;
    /** Top-row "A" slot button — recalls or saves to {@link MixerSnapshotManager.Slot#A}. */
    private final Button slotAButton;
    /** Top-row "B" slot button — recalls or saves to {@link MixerSnapshotManager.Slot#B}. */
    private final Button slotBButton;
    /** Container that holds {@link #snapshotsPanel} when visible. */
    private HBox mainArea;
    /**
     * Callbacks that re-sync solo-button visuals and the "Solo safe"
     * {@code CheckMenuItem} from the model. Invoked after undo/redo so
     * solo-safe changes round-trip through the UI even when the user did
     * not initiate them via the context menu.
     */
    private final List<Runnable> soloSafeSyncCallbacks = new ArrayList<>();
    /**
     * History listener registered on the {@link UndoManager}. Runs every
     * {@link #soloSafeSyncCallbacks} entry on the JavaFX thread so the solo
     * ring and "Solo safe" checkmark always reflect the model after
     * undo/redo (or any other history mutation).
     */
    private final com.benesquivelmusic.daw.core.undo.UndoHistoryListener undoHistoryListener;
    private PluginRegistry pluginRegistry;
    private InputLevelMonitorRegistry inputLevelMonitorRegistry;
    private java.util.function.Supplier<List<AudioChannelInfo>> inputChannelInfoSupplier =
            () -> List.of();
    private java.util.function.Supplier<List<AudioChannelInfo>> outputChannelInfoSupplier =
            () -> List.of();
    /**
     * Story 100 — track templates and channel-strip presets. When set, the
     * per-channel right-click menu grows "Save channel strip\u2026" and
     * "Apply channel strip\u2026" entries that delegate to the controller.
     * {@code null} hides the entries.
     */
    private TrackTemplateController trackTemplateController;

    /**
     * Creates a new mixer view bound to the given project.
     *
     * @param project the DAW project to visualize
     */
    public MixerView(DawProject project) {
        this(project, null);
    }

    /**
     * Creates a new mixer view bound to the given project with undo support.
     *
     * @param project     the DAW project to visualize
     * @param undoManager the undo manager for insert effect operations (may be {@code null})
     */
    public MixerView(DawProject project, UndoManager undoManager) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.undoManager = undoManager;
        getStyleClass().add("mixer-panel");

        // Refresh solo-safe button rings and "Solo safe" checkmarks after
        // any undo/redo so the UI never shows a stale value when
        // SetSoloSafeAction (or a snapshot recall, etc.) is undone or
        // redone. The listener runs on whatever thread fired the event;
        // hop to the FX application thread to mutate widgets safely.
        this.undoHistoryListener = _ -> {
            if (javafx.application.Platform.isFxApplicationThread()) {
                runSoloSafeSyncCallbacks();
            } else {
                javafx.application.Platform.runLater(this::runSoloSafeSyncCallbacks);
            }
        };
        if (this.undoManager != null) {
            this.undoManager.addHistoryListener(this.undoHistoryListener);
        }

        // ── Mixer channel-link manager hook (Story 159) ─────────────────────
        // Re-render whenever a stereo pair is created, removed, or re-edited
        // via the popover so the chain glyphs, connector lines, and L/R
        // badges always reflect the model. Hop to the FX thread because
        // ChannelLinkManager fires synchronously on the mutating thread.
        this.channelLinkListener = () -> {
            if (javafx.application.Platform.isFxApplicationThread()) {
                refresh();
            } else {
                javafx.application.Platform.runLater(this::refresh);
            }
        };
        project.getChannelLinkManager().addListener(this.channelLinkListener);

        // Auto-unregister listeners when this view is removed from a scene
        // so a replaced MixerView (e.g. on project reload via
        // ViewNavigationController.setMixerView) does not stay strongly
        // referenced by ChannelLinkManager or UndoManager (memory-leak fix).
        sceneProperty().addListener((_, _, newScene) -> {
            if (newScene == null) {
                project.getChannelLinkManager().removeListener(channelLinkListener);
                if (undoManager != null) {
                    undoManager.removeHistoryListener(undoHistoryListener);
                }
            }
        });

        Label header = new Label("MIXER");
        header.getStyleClass().add("panel-header");
        header.setGraphic(IconNode.of(DawIcon.MIXER, 16));
        header.setPadding(new Insets(0, 0, 6, 0));

        // ── Snapshots panel & A/B controls (Story 103) ──────────────────────
        // The panel is constructed against the project's persistent
        // MixerSnapshotManager so saved scenes round-trip through
        // ProjectSerializer. The MixerView never replaces the manager —
        // it always reads project.getMixerSnapshotManager() so a freshly
        // loaded project's snapshots appear automatically.
        this.snapshotsPanel = new MixerSnapshotsPanel(
                project.getMixerSnapshotManager(), project.getMixer(), undoManager);
        this.snapshotsPanel.setOnChange(this::syncSlotButtons);

        this.snapshotsToggleButton = new ToggleButton("Snapshots");
        this.snapshotsToggleButton.setTooltip(new Tooltip(
                "Show or hide the mixer scene snapshots panel."));
        this.snapshotsToggleButton.selectedProperty().addListener((_, _, selected) -> {
            if (selected) {
                if (!mainAreaContains(snapshotsPanel)) {
                    mainArea.getChildren().add(snapshotsPanel);
                }
            } else {
                mainArea.getChildren().remove(snapshotsPanel);
            }
        });

        this.slotAButton = new Button("A");
        this.slotAButton.setTooltip(new Tooltip(
                "Recall snapshot slot A. Right-click to save the current state to A."));
        this.slotAButton.setOnAction(_ -> recallSlot(MixerSnapshotManager.Slot.A));
        this.slotAButton.setContextMenu(buildSlotContextMenu(MixerSnapshotManager.Slot.A));

        this.slotBButton = new Button("B");
        this.slotBButton.setTooltip(new Tooltip(
                "Recall snapshot slot B. Right-click to save the current state to B."));
        this.slotBButton.setOnAction(_ -> recallSlot(MixerSnapshotManager.Slot.B));
        this.slotBButton.setContextMenu(buildSlotContextMenu(MixerSnapshotManager.Slot.B));

        // Mixer maintenance menu — exposes "Reset solo safe to defaults"
        // (legacy) plus the Story 103 Snapshots submenu.
        MenuButton mixerMenu = new MenuButton("⋮");
        mixerMenu.setTooltip(new Tooltip("Mixer options"));
        MenuItem resetSoloSafeItem = new MenuItem("Reset solo safe to defaults");
        resetSoloSafeItem.setOnAction(_ -> {
            project.getMixer().resetSoloSafeToDefaults();
            refresh();
        });

        // Snapshots submenu — Save / Manage / Recall A / Recall B
        Menu snapshotsMenu = new Menu("Snapshots");
        MenuItem saveSnapshotItem = new MenuItem("Save current state…");
        saveSnapshotItem.setOnAction(_ -> snapshotsPanel.getSaveButton().fire());
        MenuItem manageSnapshotsItem = new MenuItem("Manage…");
        manageSnapshotsItem.setOnAction(_ -> snapshotsToggleButton.setSelected(true));
        MenuItem recallAItem = new MenuItem("Recall A");
        recallAItem.setOnAction(_ -> recallSlot(MixerSnapshotManager.Slot.A));
        MenuItem recallBItem = new MenuItem("Recall B");
        recallBItem.setOnAction(_ -> recallSlot(MixerSnapshotManager.Slot.B));
        snapshotsMenu.getItems().addAll(
                saveSnapshotItem, manageSnapshotsItem, recallAItem, recallBItem);

        mixerMenu.getItems().addAll(resetSoloSafeItem, snapshotsMenu);

        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(8,
                header, toolbarSpacer,
                slotAButton, slotBButton,
                snapshotsToggleButton, mixerMenu);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setPadding(new Insets(0, 0, 6, 0));

        channelStrips = new HBox(6);
        channelStrips.setAlignment(Pos.TOP_LEFT);

        returnBusStrips = new HBox(6);
        returnBusStrips.setAlignment(Pos.TOP_LEFT);

        vcaStrips = new HBox(6);
        vcaStrips.setAlignment(Pos.TOP_LEFT);

        masterStrip = buildMasterStrip();

        HBox allStrips = new HBox(6);
        allStrips.setAlignment(Pos.TOP_LEFT);
        allStrips.getChildren().addAll(
                channelStrips,
                new Separator(Orientation.VERTICAL),
                returnBusStrips,
                new Separator(Orientation.VERTICAL),
                masterStrip,
                new Separator(Orientation.VERTICAL),
                vcaStrips);
        HBox.setHgrow(channelStrips, Priority.ALWAYS);

        ScrollPane scrollPane = new ScrollPane(allStrips);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        // mainArea wraps the strip area and the (optional) snapshots side panel
        // so toggling the panel is an O(1) add/remove of a child node.
        mainArea = new HBox(0, scrollPane);
        HBox.setHgrow(scrollPane, Priority.ALWAYS);
        VBox.setVgrow(mainArea, Priority.ALWAYS);

        getChildren().addAll(headerRow, mainArea);
        setPadding(new Insets(8));

        syncSlotButtons();

        // Keep slot button highlights in sync after undo/redo of saves/
        // recalls so the "active" slot indicator never goes stale.
        if (this.undoManager != null) {
            this.undoManager.addHistoryListener(_ -> {
                if (javafx.application.Platform.isFxApplicationThread()) {
                    snapshotsPanel.refresh();
                    syncSlotButtons();
                } else {
                    javafx.application.Platform.runLater(() -> {
                        snapshotsPanel.refresh();
                        syncSlotButtons();
                    });
                }
            });
        }

        refresh();
    }

    private boolean mainAreaContains(javafx.scene.Node node) {
        return mainArea != null && mainArea.getChildren().contains(node);
    }

    private ContextMenu buildSlotContextMenu(MixerSnapshotManager.Slot slot) {
        ContextMenu menu = new ContextMenu();
        MenuItem saveItem = new MenuItem("Save current state to " + slot.name());
        saveItem.setOnAction(_ -> saveCurrentStateToSlot(slot));
        MenuItem clearItem = new MenuItem("Clear " + slot.name());
        clearItem.setOnAction(_ -> {
            project.getMixerSnapshotManager().setSlot(slot, null);
            syncSlotButtons();
        });
        menu.getItems().addAll(saveItem, clearItem);
        return menu;
    }

    private void saveCurrentStateToSlot(MixerSnapshotManager.Slot slot) {
        MixerSnapshot snap = MixerSnapshot.capture(
                project.getMixer(), "Slot " + slot.name());
        project.getMixerSnapshotManager().setSlot(slot, snap);
        syncSlotButtons();
    }

    private void recallSlot(MixerSnapshotManager.Slot slot) {
        MixerSnapshotManager manager = project.getMixerSnapshotManager();
        MixerSnapshot snap = manager.getSlot(slot);
        if (snap == null) {
            return;
        }
        MixerSnapshotManager.Slot previousSlot = manager.getActiveSlot();
        RecallSnapshotAction recallAction = new RecallSnapshotAction(project.getMixer(), snap);
        UndoableAction compound = new UndoableAction() {
            @Override public String description() { return "Recall Mixer Snapshot (" + slot.name() + ")"; }
            @Override public void execute() {
                manager.setActiveSlot(slot);
                recallAction.execute();
            }
            @Override public void undo() {
                recallAction.undo();
                manager.setActiveSlot(previousSlot);
            }
        };
        if (undoManager != null) {
            undoManager.execute(compound);
        } else {
            compound.execute();
        }
        syncSlotButtons();
    }

    /**
     * Toggles between the A and B snapshot slots, applying the newly active
     * slot's state to the mixer as a single compound undoable action. Bound
     * to {@link DawAction#MIXER_TOGGLE_AB} (default {@code Shift+A}).
     */
    public void toggleAB() {
        MixerSnapshotManager manager = project.getMixerSnapshotManager();
        MixerSnapshotManager.Slot previous = manager.getActiveSlot();
        MixerSnapshotManager.Slot next =
                (previous == MixerSnapshotManager.Slot.A)
                        ? MixerSnapshotManager.Slot.B
                        : MixerSnapshotManager.Slot.A;
        MixerSnapshot snap = manager.getSlot(next);
        if (snap != null) {
            RecallSnapshotAction recallAction = new RecallSnapshotAction(project.getMixer(), snap);
            UndoableAction compound = new UndoableAction() {
                @Override public String description() { return "Toggle Mixer A/B"; }
                @Override public void execute() {
                    manager.setActiveSlot(next);
                    recallAction.execute();
                }
                @Override public void undo() {
                    recallAction.undo();
                    manager.setActiveSlot(previous);
                }
            };
            if (undoManager != null) {
                undoManager.execute(compound);
            } else {
                compound.execute();
            }
        } else {
            manager.setActiveSlot(next);
        }
        syncSlotButtons();
    }

    private void syncSlotButtons() {
        MixerSnapshotManager manager = project.getMixerSnapshotManager();
        boolean activeA = manager.getActiveSlot() == MixerSnapshotManager.Slot.A;
        applySlotButtonStyle(slotAButton, activeA, manager.getSlot(MixerSnapshotManager.Slot.A) != null);
        applySlotButtonStyle(slotBButton, !activeA, manager.getSlot(MixerSnapshotManager.Slot.B) != null);
    }

    private static void applySlotButtonStyle(Button btn, boolean active, boolean filled) {
        btn.getStyleClass().removeAll("mixer-slot-active", "mixer-slot-filled");
        if (active) {
            btn.getStyleClass().add("mixer-slot-active");
        } else if (filled) {
            btn.getStyleClass().add("mixer-slot-filled");
        }
    }

    /** Returns the snapshots side panel. Visible for testing. */
    public MixerSnapshotsPanel getSnapshotsPanel() {
        return snapshotsPanel;
    }

    /** Returns the snapshots toggle button. Visible for testing. */
    ToggleButton getSnapshotsToggleButton() {
        return snapshotsToggleButton;
    }

    /** Returns the A-slot toolbar button. Visible for testing. */
    Button getSlotAButton() {
        return slotAButton;
    }

    /** Returns the B-slot toolbar button. Visible for testing. */
    Button getSlotBButton() {
        return slotBButton;
    }

    /**
     * Sets the plugin registry for this mixer view. When set, all insert
     * effect racks will offer registered external plugins as additional
     * insert options.
     *
     * @param registry the plugin registry, or {@code null} to disable
     */
    public void setPluginRegistry(PluginRegistry registry) {
        this.pluginRegistry = registry;
        for (InsertEffectRack rack : activeInsertRacks) {
            rack.setPluginRegistry(registry);
        }
    }

    /**
     * Wires the {@link TrackTemplateController} that powers the per-channel
     * "Save channel strip\u2026" and "Apply channel strip\u2026" right-click
     * actions (Story 100). Pass {@code null} to suppress those entries.
     *
     * @param controller the controller, or {@code null} to disable
     */
    public void setTrackTemplateController(TrackTemplateController controller) {
        this.trackTemplateController = controller;
    }

    /**
     * Binds an {@link InputLevelMonitorRegistry} so that armed tracks show
     * an input-signal meter column with a latching clip LED (user story 137).
     *
     * <p>When set, every channel strip whose backing track is armed gets a
     * second vertical meter column sourced from the track's
     * {@link InputLevelMonitor}. Clicking the clip LED on any strip resets
     * that track's latch; {@code Alt+click} resets every track's latch via
     * {@link InputLevelMonitorRegistry#resetAll()}.</p>
     *
     * <p>Call {@link #refresh()} after binding (or rebinding) so the strips
     * rebuild with the new registry.</p>
     *
     * @param registry the registry to bind, or {@code null} to disable the
     *                 input-meter column
     */
    public void setInputLevelMonitorRegistry(InputLevelMonitorRegistry registry) {
        this.inputLevelMonitorRegistry = registry;
    }

    /**
     * Returns the currently bound input-level monitor registry, or
     * {@code null} if none has been set.
     */
    public InputLevelMonitorRegistry getInputLevelMonitorRegistry() {
        return inputLevelMonitorRegistry;
    }

    /**
     * Configures the source of driver-reported input-channel metadata used
     * to populate the per-track input-routing dropdown — story 199. When
     * the supplier returns a non-empty list, the dropdown renders the
     * driver's display names (e.g. {@code "Mic/Line 1"},
     * {@code "S/PDIF L"}), shows a {@link ChannelKind} icon, auto-groups
     * consecutive {@code L}/{@code R} pairs into a single
     * {@code "<stem> (Stereo)"} entry, and greys out channels reported
     * inactive by the driver. The default supplier returns an empty list,
     * which preserves the legacy "Input N" / "Output N" dropdowns.
     *
     * @param supplier supplies the live input-channel metadata; must not be null
     */
    public void setInputChannelInfoSupplier(
            java.util.function.Supplier<List<AudioChannelInfo>> supplier) {
        this.inputChannelInfoSupplier = Objects.requireNonNull(
                supplier, "supplier must not be null");
    }

    /**
     * Output-side counterpart of {@link #setInputChannelInfoSupplier}. See
     * that method for the semantics.
     *
     * @param supplier supplies the live output-channel metadata; must not be null
     */
    public void setOutputChannelInfoSupplier(
            java.util.function.Supplier<List<AudioChannelInfo>> supplier) {
        this.outputChannelInfoSupplier = Objects.requireNonNull(
                supplier, "supplier must not be null");
    }

    /**
     * Rebuilds the channel strips from the current project tracks and return
     * buses.
     *
     * <p>Call this method after adding or removing tracks or return buses to
     * keep the mixer view synchronized with the project model.</p>
     */
    public void refresh() {
        // Drop any stale selections referencing tracks that no longer exist
        // so right-click "Create VCA from selection" can't pick up phantoms
        // after a track removal.
        Set<UUID> liveIds = new HashSet<>();
        for (Track t : project.getTracks()) {
            try {
                liveIds.add(UUID.fromString(t.getId()));
            } catch (IllegalArgumentException ignored) {
                // non-UUID id — skip
            }
        }
        selectedChannelIds.retainAll(liveIds);

        // Drop any stale solo-safe sync callbacks left over from the
        // previous build of strips so we don't poke discarded widgets.
        soloSafeSyncCallbacks.clear();
        // Dispose existing InsertEffectRack instances to prevent listener leaks
        for (InsertEffectRack rack : activeInsertRacks) {
            rack.dispose();
        }
        activeInsertRacks.clear();
        // Stop redraw timers on previously-constructed input-meter strips
        // so they don't keep firing (and holding references to discarded
        // JavaFX nodes) after a refresh.
        for (InputMeterStrip strip : activeInputMeterStrips) {
            strip.stop();
        }
        activeInputMeterStrips.clear();

        // Channel-link lookup tables are rebuilt with the strips below so
        // the propagation paths reference the live JavaFX widgets.
        volumeFaderByChannelId.clear();
        panSliderByChannelId.clear();
        muteBtnByChannelId.clear();
        soloBtnByChannelId.clear();
        channelByChannelId.clear();
        trackByChannelId.clear();
        propagationSuppressed.clear();

        channelStrips.getChildren().clear();
        // First pass: build each track's channel strip and remember its
        // backing channel id (when the track id is a UUID — non-UUID test
        // fixtures get a strip with no link affordance, matching the
        // existing VCA-wiring fallback).
        List<UUID> stripIdsInOrder = new ArrayList<>();
        for (Track track : project.getTracks()) {
            MixerChannel mixerChannel = project.getMixerChannelForTrack(track);
            if (mixerChannel != null) {
                channelStrips.getChildren().add(buildChannelStrip(track, mixerChannel));
                UUID id = parseChannelId(track);
                stripIdsInOrder.add(id);
                if (id != null) {
                    channelByChannelId.put(id, mixerChannel);
                    trackByChannelId.put(id, track);
                }
            }
        }
        // Second pass: insert a small chain-glyph link toggle between every
        // adjacent pair of channel strips. The toggle pairs / unpairs the
        // two adjacent channels and (right-click) opens the link-detail
        // popover. Done after strips are built so lookup tables (volume
        // fader / pan slider / mute / solo) are populated and the
        // propagation path can mirror immediately on first use.
        installLinkToggles(stripIdsInOrder);

        returnBusStrips.getChildren().clear();
        for (MixerChannel returnBus : project.getMixer().getReturnBuses()) {
            returnBusStrips.getChildren().add(buildReturnBusStrip(returnBus));
        }
        // "Add Return Bus" button at the end
        Button addReturnBusBtn = new Button("+");
        addReturnBusBtn.getStyleClass().add("track-arm-button");
        addReturnBusBtn.setTooltip(new Tooltip("Add Return Bus"));
        boolean atLimit = project.getMixer().getReturnBusCount() >= Mixer.MAX_RETURN_BUSES;
        addReturnBusBtn.setDisable(atLimit);
        if (atLimit) {
            addReturnBusBtn.setTooltip(new Tooltip(
                    "Maximum of " + Mixer.MAX_RETURN_BUSES + " return buses reached"));
        }
        addReturnBusBtn.setOnAction(_ -> {
            int busCount = project.getMixer().getReturnBusCount();
            String busName = "Return " + (busCount + 1);
            if (undoManager != null) {
                AddReturnBusAction action = new AddReturnBusAction(project.getMixer(), busName);
                undoManager.execute(action);
            } else {
                project.getMixer().addReturnBus(busName);
            }
            refresh();
        });
        returnBusStrips.getChildren().add(addReturnBusBtn);

        // ── VCA strips (right of master, story 153) ──────────────────────
        vcaStrips.getChildren().clear();
        VcaGroupManager vcaManager = project.getVcaGroupManager();
        // Precompute a UUID→MixerChannel map with safe parsing so VCA strips
        // can resolve member channels without crashing on non-UUID track ids.
        java.util.Map<UUID, MixerChannel> channelMap = new java.util.HashMap<>();
        for (Track t : project.getTracks()) {
            try {
                channelMap.put(UUID.fromString(t.getId()),
                        project.getMixerChannelForTrack(t));
            } catch (IllegalArgumentException ignored) {
                // non-UUID track id — skip
            }
        }
        java.util.function.Function<UUID, MixerChannel> channelLookup = channelMap::get;
        for (VcaGroup vca : vcaManager.getVcaGroups()) {
            vcaStrips.getChildren().add(new VcaStrip(
                    vca, vcaManager, undoManager, channelLookup, this::refresh));
        }
    }

    /**
     * Returns the container holding the VCA group strips. Visible for testing.
     */
    HBox getVcaStrips() {
        return vcaStrips;
    }

    /**
     * Returns the container holding the track channel strips (excluding master).
     * Visible for testing.
     */
    HBox getChannelStrips() {
        return channelStrips;
    }

    /**
     * Returns the container holding the return bus channel strips.
     * Visible for testing.
     */
    HBox getReturnBusStrips() {
        return returnBusStrips;
    }

    /**
     * Returns the master channel strip. Visible for testing.
     */
    VBox getMasterStrip() {
        return masterStrip;
    }

    private VBox buildChannelStrip(Track track, MixerChannel mixerChannel) {
        VBox strip = new VBox(4);
        strip.getStyleClass().add("mixer-channel");
        strip.setAlignment(Pos.TOP_CENTER);
        strip.setPrefWidth(CHANNEL_WIDTH);
        strip.setMinWidth(CHANNEL_WIDTH);

        // The channel-id used by VCA membership and drag/drop is the track's
        // own UUID (its id is a UUID-formatted string per Track.java). Parse
        // once up front; if the id is not a UUID (forged test fixture) the
        // VCA wiring is skipped but the rest of the strip still renders.
        UUID parsedId = null;
        try {
            parsedId = UUID.fromString(track.getId());
        } catch (IllegalArgumentException ignored) {
            // skip VCA wiring for non-UUID ids
        }
        final UUID channelId = parsedId;

        if (channelId != null) {
            applyChannelStripSelectionStyle(strip, channelId);
        }

        // ── Member-VCA badge(s) at the top of the strip ───────────────────
        // Story 153: a small "VCA: <name>" label per group the channel is
        // currently a member of. Background uses the VCA's color so the user
        // can see at a glance which VCA(s) are riding this channel.
        VcaGroupManager vcaMgr = project.getVcaGroupManager();
        if (channelId != null) {
            List<VcaGroup> memberOf = vcaMgr.getGroupsForChannel(channelId);
            if (!memberOf.isEmpty()) {
                VBox badges = new VBox(1);
                badges.setAlignment(Pos.CENTER);
                for (VcaGroup g : memberOf) {
                    Label badge = new Label("VCA: " + g.label());
                    badge.getStyleClass().add("mixer-channel-name");
                    String hex = g.color() != null ? g.color().getHexColor() : "#9c27b0";
                    badge.setStyle("-fx-background-color: " + hex + ";"
                            + " -fx-text-fill: #ffffff; -fx-padding: 1 4 1 4;"
                            + " -fx-font-size: 9px; -fx-background-radius: 3;");
                    badge.setMaxWidth(CHANNEL_WIDTH - 12);
                    badges.getChildren().add(badge);
                }
                strip.getChildren().add(badges);
            }
        }

        // ── Click selection (Ctrl/Shift toggles, plain click selects only) ─
        if (channelId != null) {
            strip.setOnMouseClicked(e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
                if (e.isShiftDown() || e.isControlDown() || e.isMetaDown()) {
                    if (!selectedChannelIds.add(channelId)) {
                        selectedChannelIds.remove(channelId);
                    }
                } else {
                    selectedChannelIds.clear();
                    selectedChannelIds.add(channelId);
                }
                // Cheap restyle of every visible channel strip — no full refresh.
                for (Node n : channelStrips.getChildren()) {
                    if (n instanceof VBox box && box.getUserData() instanceof UUID id) {
                        applyChannelStripSelectionStyle(box, id);
                    }
                }
            });
            strip.setUserData(channelId);

            // ── Drag source: publishes the channel id so VCA strips can assign ─
            strip.setOnDragDetected(event -> {
                javafx.scene.input.Dragboard db =
                        strip.startDragAndDrop(javafx.scene.input.TransferMode.LINK);
                javafx.scene.input.ClipboardContent content =
                        new javafx.scene.input.ClipboardContent();
                content.put(VcaStrip.CHANNEL_ID_FORMAT, channelId.toString());
                content.putString(track.getName());
                db.setContent(content);
                event.consume();
            });

            // ── Right-click "Create VCA from selection" + assign submenu ───
            ContextMenu stripMenu = new ContextMenu();
            MenuItem createVcaItem = new MenuItem("Create VCA from selection");
            createVcaItem.setOnAction(_ -> createVcaFromSelection(channelId));
            stripMenu.getItems().add(createVcaItem);
            if (!vcaMgr.getVcaGroups().isEmpty()) {
                javafx.scene.control.Menu assignMenu =
                        new javafx.scene.control.Menu("Assign to VCA");
                for (VcaGroup g : vcaMgr.getVcaGroups()) {
                    boolean already = g.hasMember(channelId);
                    MenuItem item = new MenuItem((already ? "✓ " : "") + g.label());
                    item.setOnAction(_ -> {
                        AssignVcaMemberAction action = new AssignVcaMemberAction(
                                vcaMgr, g.id(), channelId, !already);
                        if (undoManager != null) {
                            undoManager.execute(action);
                        } else {
                            action.execute();
                        }
                        refresh();
                    });
                    assignMenu.getItems().add(item);
                }
                stripMenu.getItems().add(assignMenu);
            }
            // ── Story 100: channel-strip presets ────────────────────────────
            // "Save channel strip\u2026" captures the current insert chain +
            // sends + level/pan via TrackTemplateService.captureChannelStrip
            // and persists it as a ChannelStripPreset.  "Apply channel
            // strip\u2026" opens the preset browser and runs an
            // ApplyChannelStripPresetAction through the undo manager so the
            // change is reversible (the previous strip is restored on undo).
            if (trackTemplateController != null) {
                stripMenu.getItems().add(new SeparatorMenuItem());
                MenuItem saveStripItem = new MenuItem("Save channel strip\u2026");
                saveStripItem.setOnAction(_ -> trackTemplateController
                        .saveChannelStripAsPreset(mixerChannel));
                MenuItem applyStripItem = new MenuItem("Apply channel strip\u2026");
                applyStripItem.setOnAction(_ -> trackTemplateController
                        .applyChannelStripPreset(mixerChannel));
                stripMenu.getItems().addAll(saveStripItem, applyStripItem);
            }
            strip.setOnContextMenuRequested(e ->
                    stripMenu.show(strip, e.getScreenX(), e.getScreenY()));
        }

        // Channel name
        Label nameLabel = new Label(track.getName());
        nameLabel.getStyleClass().add("mixer-channel-name");
        nameLabel.setMaxWidth(CHANNEL_WIDTH - 12);

        // ── L / R badge for a member of a stereo pair (Story 159) ─────────
        // The chain-link manager owns the (left, right) ordering — render
        // a single-letter badge under the strip name so the engineer can
        // see at a glance which strip is the source / mirrored side.
        Label lrBadge = null;
        if (channelId != null) {
            ChannelLink existingLink = project.getChannelLinkManager().getLink(channelId);
            if (existingLink != null) {
                boolean isLeft = existingLink.leftChannelId().equals(channelId);
                lrBadge = new Label(isLeft ? "L" : "R");
                lrBadge.getStyleClass().add("mixer-channel-link-badge");
                lrBadge.setStyle("-fx-background-color: #00bcd4;"
                        + " -fx-text-fill: #0d0d0d;"
                        + " -fx-font-weight: bold;"
                        + " -fx-padding: 0 4 0 4;"
                        + " -fx-font-size: 9px;"
                        + " -fx-background-radius: 3;");
            }
        }

        // Level meter
        LevelMeterDisplay levelMeter = new LevelMeterDisplay(true);
        levelMeter.setPrefWidth(METER_WIDTH);
        levelMeter.setMinWidth(METER_WIDTH);
        levelMeter.setMaxWidth(METER_WIDTH);
        levelMeter.setPrefHeight(METER_HEIGHT);
        levelMeter.setMinHeight(METER_HEIGHT);

        // ── Input meter (second column, armed tracks only) ──────────────
        // Story 137: when a track is armed, show a dedicated input-signal
        // meter column ahead of the output meter with a latching clip LED.
        // Clicking the clip LED resets that track; Alt+click resets all.
        HBox meterRow = new HBox(2);
        meterRow.setAlignment(Pos.CENTER);
        if (track.isArmed() && inputLevelMonitorRegistry != null) {
            InputLevelMonitor monitor = inputLevelMonitorRegistry.getOrCreate(track);
            InputMeterStrip inputStrip = new InputMeterStrip(monitor, inputLevelMonitorRegistry);
            inputStrip.setPrefWidth(INPUT_METER_WIDTH);
            inputStrip.setMinWidth(INPUT_METER_WIDTH);
            inputStrip.setMaxWidth(INPUT_METER_WIDTH);
            inputStrip.setPrefHeight(METER_HEIGHT);
            inputStrip.setMinHeight(METER_HEIGHT);
            Tooltip.install(inputStrip,
                    new Tooltip("Input meter (pre-processing). "
                            + "Click clip LED to reset; Alt+click resets all."));
            activeInputMeterStrips.add(inputStrip);
            meterRow.getChildren().add(inputStrip);
        }
        meterRow.getChildren().add(levelMeter);

        // Volume fader (vertical slider)
        Slider volumeFader = new Slider(0.0, 1.0, mixerChannel.getVolume());
        volumeFader.setOrientation(Orientation.VERTICAL);
        volumeFader.setPrefHeight(FADER_HEIGHT);
        volumeFader.getStyleClass().add("mixer-fader");
        volumeFader.setTooltip(new Tooltip("Volume"));
        volumeFader.valueProperty().addListener((_, oldVal, newVal) -> {
            double value = newVal.doubleValue();
            mixerChannel.setVolume(value);
            track.setVolume(value);
            if (channelId != null) {
                propagateVolumeChange(channelId, oldVal.doubleValue(), value);
            }
        });
        if (channelId != null) {
            volumeFaderByChannelId.put(channelId, volumeFader);
        }

        // Pan control (horizontal slider)
        Slider panSlider = new Slider(-1.0, 1.0, mixerChannel.getPan());
        panSlider.setPrefWidth(CHANNEL_WIDTH - 12);
        panSlider.getStyleClass().add("mixer-fader");
        panSlider.setTooltip(new Tooltip("Pan (L/R)"));
        panSlider.valueProperty().addListener((_, _, newVal) -> {
            double value = newVal.doubleValue();
            mixerChannel.setPan(value);
            track.setPan(value);
            if (channelId != null) {
                propagatePanChange(channelId, value);
            }
        });
        if (channelId != null) {
            panSliderByChannelId.put(channelId, panSlider);
        }
        Label panLabel = new Label("PAN");
        panLabel.getStyleClass().add("mixer-channel-name");

        // Mute button
        Button muteBtn = new Button("M");
        muteBtn.getStyleClass().add("track-mute-button");
        muteBtn.setTooltip(new Tooltip("Mute"));
        muteBtn.setGraphic(IconNode.of(DawIcon.MUTE, CONTROL_ICON_SIZE));
        muteBtn.setOnAction(_ -> {
            boolean muted = !mixerChannel.isMuted();
            mixerChannel.setMuted(muted);
            track.setMuted(muted);
            muteBtn.setStyle(muted
                    ? "-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;" : "");
            if (channelId != null) {
                propagateMuteChange(channelId, muted);
            }
        });
        if (channelId != null) {
            muteBtnByChannelId.put(channelId, muteBtn);
        }

        // Solo button — right-click to toggle "solo safe" (solo-in-place
        // defeat). When solo-safe is on, a yellow ring highlights the button
        // so the engineer can see at a glance which channels stay audible
        // during a solo (typically reverb/group returns).
        Button soloBtn = new Button("S");
        soloBtn.getStyleClass().add("track-solo-button");
        soloBtn.setTooltip(new Tooltip("Solo (right-click for Solo Safe)"));
        soloBtn.setGraphic(IconNode.of(DawIcon.SOLO, CONTROL_ICON_SIZE));
        applySoloButtonStyle(soloBtn, mixerChannel);
        soloBtn.setOnAction(_ -> {
            boolean solo = !mixerChannel.isSolo();
            mixerChannel.setSolo(solo);
            track.setSolo(solo);
            applySoloButtonStyle(soloBtn, mixerChannel);
            if (channelId != null) {
                propagateSoloChange(channelId, solo);
            }
        });
        installSoloSafeContextMenu(soloBtn, mixerChannel);
        if (channelId != null) {
            soloBtnByChannelId.put(channelId, soloBtn);
        }

        // Arm button
        Button armBtn = new Button("R");
        armBtn.getStyleClass().add("track-arm-button");
        armBtn.setTooltip(new Tooltip("Arm for Recording"));
        armBtn.setGraphic(IconNode.of(DawIcon.ARM_TRACK, CONTROL_ICON_SIZE));
        armBtn.setOnAction(_ -> {
            boolean armed = !track.isArmed();
            track.setArmed(armed);
            armBtn.setStyle(armed
                    ? "-fx-background-color: #ff1744; -fx-text-fill: #ffffff;" : "");
            // Story 137: refresh so the input-meter column appears (on arm)
            // or disappears (on disarm) immediately.
            if (inputLevelMonitorRegistry != null) {
                refresh();
            }
        });

        HBox buttonRow = new HBox(2, muteBtn, soloBtn, armBtn);
        buttonRow.setAlignment(Pos.CENTER);

        // 3D Panner button
        Button pannerBtn = new Button("3D");
        pannerBtn.getStyleClass().add("track-arm-button");
        pannerBtn.setTooltip(new Tooltip("Open 3D Spatial Panner"));
        pannerBtn.setGraphic(IconNode.of(DawIcon.SURROUND, CONTROL_ICON_SIZE));
        pannerBtn.setOnAction(actionEvent -> {
            SpatialPannerController controller = new SpatialPannerController(
                    SpatialPannerController.createDefaultPanner(SpeakerLayout.LAYOUT_7_1_4),
                    track.getName());
            controller.openWindow();
        });

        // Send controls — one slider per return bus with active-routing indicator
        VBox sendBox = new VBox(2);
        for (MixerChannel returnBus : project.getMixer().getReturnBuses()) {
            Circle sendIndicator = new Circle(4);
            Send existingSend = mixerChannel.getSendForTarget(returnBus);
            double initialLevel = existingSend != null ? existingSend.getLevel() : 0.0;
            sendIndicator.setFill(initialLevel > 0.0 ? Color.web("#00e676") : Color.web("#555555"));

            Label sendLabel = new Label("→ " + returnBus.getName());
            sendLabel.getStyleClass().add("mixer-channel-name");
            sendLabel.setMaxWidth(CHANNEL_WIDTH - 12);
            sendLabel.setGraphic(sendIndicator);

            Slider sendSlider = new Slider(0.0, 1.0, initialLevel);
            sendSlider.setPrefWidth(SEND_SLIDER_WIDTH);
            sendSlider.getStyleClass().add("mixer-fader");
            sendSlider.setTooltip(new Tooltip("Send to " + returnBus.getName()));

            // Compact tap-point cycler ("I" pre-inserts / "F" pre-fader /
            // "P" post-fader) — a single letter the user can click to cycle
            // through the three tap positions. Tooltip explains all three
            // states. The button reflects the current tap of the send (or
            // the default POST_FADER when no send exists yet).
            Button tapButton = new Button();
            tapButton.getStyleClass().add("mixer-send-tap");
            tapButton.setStyle("-fx-padding: 0 4 0 4; -fx-font-size: 10px;");
            tapButton.setTooltip(new Tooltip(
                    "Send tap point — click to cycle:\n"
                            + "  I = pre-Inserts (before any insert effect)\n"
                            + "  F = pre-Fader (after inserts, before fader)\n"
                            + "  P = Post-fader (default)"));

            MixerChannel targetBus = returnBus;
            // Capture the send state before a drag starts so that undo restores
            // to the pre-drag state (the slider listener modifies the model live)
            double[] dragStartLevel = {initialLevel};
            boolean[] hadSendAtDragStart = {existingSend != null};

            sendSlider.setOnMousePressed(_ -> {
                Send send = mixerChannel.getSendForTarget(targetBus);
                hadSendAtDragStart[0] = send != null;
                dragStartLevel[0] = send != null ? send.getLevel() : 0.0;
            });

            sendSlider.valueProperty().addListener((_, _, newVal) -> {
                double value = newVal.doubleValue();
                Send send = mixerChannel.getSendForTarget(targetBus);
                if (send != null) {
                    send.setLevel(value);
                } else if (value > 0.0) {
                    mixerChannel.addSend(new Send(targetBus, value, SendTap.POST_FADER));
                }
                sendIndicator.setFill(value > 0.0 ? Color.web("#00e676") : Color.web("#555555"));
            });

            // Commit undoable action when the user finishes dragging the slider.
            // Restore the pre-drag model state so that execute() captures the
            // correct previousLevel/hadSendBefore for undo, then re-applies
            // the final value.
            sendSlider.setOnMouseReleased(_ -> {
                if (undoManager != null) {
                    double finalValue = sendSlider.getValue();
                    Send send = mixerChannel.getSendForTarget(targetBus);
                    SendMode mode = send != null ? send.getMode() : SendMode.POST_FADER;

                    // Restore pre-drag state so execute() records the right previous
                    if (!hadSendAtDragStart[0]) {
                        // No send existed before drag — remove the one created
                        // by the value listener so execute() records hadSendBefore=false
                        if (send != null) {
                            mixerChannel.removeSend(send);
                        }
                    } else if (send != null) {
                        send.setLevel(dragStartLevel[0]);
                    }

                    SetSendRoutingAction action = new SetSendRoutingAction(
                            mixerChannel, targetBus, finalValue, mode);
                    undoManager.execute(action);
                }
            });

            // Right-click context menu and tap-cycler button to choose the
            // send tap point. Both delegate to SetSendTapAction so changes
            // are undoable and consistent with the rest of the mixer.
            Runnable refreshTapButton = () -> {
                Send s = mixerChannel.getSendForTarget(targetBus);
                SendTap currentTap = s != null ? s.getTap() : SendTap.POST_FADER;
                tapButton.setText(switch (currentTap) {
                    case PRE_INSERTS -> "I";
                    case PRE_FADER   -> "F";
                    case POST_FADER  -> "P";
                });
            };
            refreshTapButton.run();

            java.util.function.Consumer<SendTap> applyTap = newTap -> {
                Send s = mixerChannel.getSendForTarget(targetBus);
                if (s == null) {
                    return; // nothing to update until the send exists
                }
                if (undoManager != null) {
                    undoManager.execute(new SetSendTapAction(mixerChannel, targetBus, newTap));
                } else {
                    s.setTap(newTap);
                }
                refreshTapButton.run();
            };

            tapButton.setOnAction(_ -> {
                Send s = mixerChannel.getSendForTarget(targetBus);
                if (s == null) {
                    // No send exists yet: don't auto-create one (that path is
                    // not undoable) — the user must raise the slider first to
                    // create a send, then cycle the tap point.
                    return;
                }
                SendTap next = switch (s.getTap()) {
                    case POST_FADER  -> SendTap.PRE_FADER;
                    case PRE_FADER   -> SendTap.PRE_INSERTS;
                    case PRE_INSERTS -> SendTap.POST_FADER;
                };
                applyTap.accept(next);
            });

            ContextMenu sendMenu = new ContextMenu();
            MenuItem preInsertsItem = new MenuItem("Pre-Inserts");
            preInsertsItem.setOnAction(_ -> applyTap.accept(SendTap.PRE_INSERTS));
            MenuItem preFaderItem = new MenuItem("Pre-Fader");
            preFaderItem.setOnAction(_ -> applyTap.accept(SendTap.PRE_FADER));
            MenuItem postFaderItem = new MenuItem("Post-Fader");
            postFaderItem.setOnAction(_ -> applyTap.accept(SendTap.POST_FADER));
            sendMenu.getItems().addAll(preInsertsItem, preFaderItem, postFaderItem);
            sendLabel.setContextMenu(sendMenu);

            HBox sliderRow = new HBox(2, sendSlider, tapButton);
            sliderRow.setAlignment(Pos.CENTER_LEFT);
            sendBox.getChildren().addAll(sendLabel, sliderRow);
        }

        // Legacy send level control (for backward compatibility)
        Label sendLabel = new Label("SEND");
        sendLabel.getStyleClass().add("mixer-channel-name");
        Slider sendSlider = new Slider(0.0, 1.0, mixerChannel.getSendLevel());
        sendSlider.setPrefWidth(SEND_SLIDER_WIDTH);
        sendSlider.getStyleClass().add("mixer-fader");
        sendSlider.setTooltip(new Tooltip("Send Level"));
        sendSlider.valueProperty().addListener((_, _, newVal) ->
                mixerChannel.setSendLevel(newVal.doubleValue()));

        // Track type icon
        Node typeIcon = trackTypeIcon(track.getType());

        // Input routing selector
        ComboBox<IoOption> inputRoutingCombo = buildInputRoutingSelector(track);

        // Output routing selector
        ComboBox<IoOption> outputRoutingCombo = buildOutputRoutingSelector(mixerChannel);

        // Insert effects rack
        int channels = project.getFormat().channels();
        double sr = project.getFormat().sampleRate();
        int bs = project.getFormat().bufferSize();
        InsertEffectRack insertRack = new InsertEffectRack(mixerChannel, channels, sr, bs, undoManager);
        insertRack.setPluginRegistry(pluginRegistry);
        insertRack.setMixer(project.getMixer());
        activeInsertRacks.add(insertRack);

        // Per-channel latency label for plugin delay compensation (PDC)
        Label latencyLabel = new Label();
        latencyLabel.getStyleClass().add("mixer-channel-name");
        latencyLabel.setMaxWidth(CHANNEL_WIDTH - 12);
        latencyLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #888888;");
        updateLatencyLabel(latencyLabel, mixerChannel, sr);

        // Update the latency label whenever inserts are added/removed/reordered/bypassed
        insertRack.setOnSlotsChanged(() -> updateLatencyLabel(latencyLabel, mixerChannel, sr));

        strip.getChildren().addAll(
                nameLabel, typeIcon,
                inputRoutingCombo, outputRoutingCombo,
                insertRack, latencyLabel, meterRow, volumeFader,
                panLabel, panSlider, buttonRow, pannerBtn,
                sendBox, sendLabel, sendSlider);
        // L/R badge is inserted after nameLabel so it renders *under* the
        // channel name rather than above it (Story 159).
        if (lrBadge != null) {
            strip.getChildren().add(
                    strip.getChildren().indexOf(nameLabel) + 1, lrBadge);
        }

        return strip;
    }

    private VBox buildReturnBusStrip(MixerChannel returnBus) {
        VBox strip = new VBox(4);
        strip.getStyleClass().add("mixer-channel");
        strip.setAlignment(Pos.TOP_CENTER);
        strip.setPrefWidth(CHANNEL_WIDTH);
        strip.setMinWidth(CHANNEL_WIDTH);
        strip.setStyle("-fx-border-color: #00bcd4;");

        Label nameLabel = new Label(returnBus.getName());
        nameLabel.getStyleClass().add("mixer-channel-name");
        nameLabel.setMaxWidth(CHANNEL_WIDTH - 12);
        nameLabel.setStyle("-fx-text-fill: #00e5ff; -fx-font-weight: bold;");

        LevelMeterDisplay levelMeter = new LevelMeterDisplay(true);
        levelMeter.setPrefWidth(METER_WIDTH);
        levelMeter.setMinWidth(METER_WIDTH);
        levelMeter.setMaxWidth(METER_WIDTH);
        levelMeter.setPrefHeight(METER_HEIGHT);
        levelMeter.setMinHeight(METER_HEIGHT);

        Slider volumeFader = new Slider(0.0, 1.0, returnBus.getVolume());
        volumeFader.setOrientation(Orientation.VERTICAL);
        volumeFader.setPrefHeight(FADER_HEIGHT);
        volumeFader.getStyleClass().add("mixer-fader");
        volumeFader.setTooltip(new Tooltip("Return Bus Volume"));
        volumeFader.valueProperty().addListener((_, _, newVal) ->
                returnBus.setVolume(newVal.doubleValue()));

        Slider panSlider = new Slider(-1.0, 1.0, returnBus.getPan());
        panSlider.setPrefWidth(CHANNEL_WIDTH - 12);
        panSlider.getStyleClass().add("mixer-fader");
        panSlider.setTooltip(new Tooltip("Return Bus Pan"));
        panSlider.valueProperty().addListener((_, _, newVal) ->
                returnBus.setPan(newVal.doubleValue()));
        Label panLabel = new Label("PAN");
        panLabel.getStyleClass().add("mixer-channel-name");

        Button muteBtn = new Button("M");
        muteBtn.getStyleClass().add("track-mute-button");
        muteBtn.setTooltip(new Tooltip("Mute Return Bus"));
        muteBtn.setGraphic(IconNode.of(DawIcon.MUTE, CONTROL_ICON_SIZE));
        muteBtn.setOnAction(_ -> {
            boolean muted = !returnBus.isMuted();
            returnBus.setMuted(muted);
            muteBtn.setStyle(muted
                    ? "-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;" : "");
        });

        // Remove return bus button
        Button removeBtn = new Button("✕");
        removeBtn.getStyleClass().add("track-arm-button");
        removeBtn.setTooltip(new Tooltip("Remove Return Bus"));
        removeBtn.setOnAction(_ -> {
            // Check if any channels have active sends targeting this bus
            boolean hasActiveSends = project.getMixer().getChannels().stream()
                    .map(ch -> ch.getSendForTarget(returnBus))
                    .anyMatch(send -> send != null && send.getLevel() > 0.0);

            if (hasActiveSends) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("Remove Return Bus");
                confirm.setHeaderText("Active sends exist");
                confirm.setContentText(
                        "One or more channels have active sends targeting \""
                                + returnBus.getName()
                                + "\". Removing it will also remove those sends. Continue?");
                Optional<ButtonType> result = confirm.showAndWait();
                if (result.isEmpty() || result.get() != ButtonType.OK) {
                    return;
                }
            }

            if (undoManager != null) {
                RemoveReturnBusAction action = new RemoveReturnBusAction(
                        project.getMixer(), returnBus);
                undoManager.execute(action);
            } else {
                project.getMixer().removeReturnBus(returnBus);
            }
            refresh();
        });

        // Solo button — return buses don't usually solo, but they expose the
        // same right-click "Solo Safe" toggle as track strips so users can
        // turn off solo-safe on a return bus that they want to silence under
        // solo (e.g. a parallel-compression bus they want to A/B).
        Button soloBtn = new Button("S");
        soloBtn.getStyleClass().add("track-solo-button");
        soloBtn.setTooltip(new Tooltip("Solo (right-click for Solo Safe)"));
        soloBtn.setGraphic(IconNode.of(DawIcon.SOLO, CONTROL_ICON_SIZE));
        applySoloButtonStyle(soloBtn, returnBus);
        soloBtn.setOnAction(_ -> {
            returnBus.setSolo(!returnBus.isSolo());
            applySoloButtonStyle(soloBtn, returnBus);
        });
        installSoloSafeContextMenu(soloBtn, returnBus);

        HBox buttonRow = new HBox(2, muteBtn, soloBtn, removeBtn);
        buttonRow.setAlignment(Pos.CENTER);
        int channels = project.getFormat().channels();
        double sr = project.getFormat().sampleRate();
        int bs = project.getFormat().bufferSize();
        InsertEffectRack insertRack = new InsertEffectRack(returnBus, channels, sr, bs, undoManager);
        insertRack.setPluginRegistry(pluginRegistry);
        insertRack.setMixer(project.getMixer());
        activeInsertRacks.add(insertRack);

        // Per-bus latency label for plugin delay compensation (PDC)
        Label latencyLabel = new Label();
        latencyLabel.getStyleClass().add("mixer-channel-name");
        latencyLabel.setMaxWidth(CHANNEL_WIDTH - 12);
        latencyLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #888888;");
        updateLatencyLabel(latencyLabel, returnBus, sr);

        // Update the latency label whenever inserts are added/removed/reordered/bypassed
        insertRack.setOnSlotsChanged(() -> updateLatencyLabel(latencyLabel, returnBus, sr));

        Node busIcon = IconNode.of(DawIcon.MIXER, CONTROL_ICON_SIZE);

        strip.getChildren().addAll(
                nameLabel, busIcon, insertRack, latencyLabel, levelMeter, volumeFader,
                panLabel, panSlider, buttonRow);

        return strip;
    }

    private VBox buildMasterStrip() {
        MixerChannel master = project.getMixer().getMasterChannel();

        VBox strip = new VBox(4);
        strip.getStyleClass().add("mixer-channel");
        strip.setAlignment(Pos.TOP_CENTER);
        strip.setPrefWidth(CHANNEL_WIDTH);
        strip.setMinWidth(CHANNEL_WIDTH);
        strip.setStyle("-fx-border-color: #7c4dff;");

        Label nameLabel = new Label("Master");
        nameLabel.getStyleClass().add("mixer-channel-name");
        nameLabel.setStyle("-fx-text-fill: #e040fb; -fx-font-weight: bold;");

        LevelMeterDisplay levelMeter = new LevelMeterDisplay(true);
        levelMeter.setPrefWidth(METER_WIDTH);
        levelMeter.setMinWidth(METER_WIDTH);
        levelMeter.setMaxWidth(METER_WIDTH);
        levelMeter.setPrefHeight(METER_HEIGHT);
        levelMeter.setMinHeight(METER_HEIGHT);

        Slider volumeFader = new Slider(0.0, 1.0, master.getVolume());
        volumeFader.setOrientation(Orientation.VERTICAL);
        volumeFader.setPrefHeight(FADER_HEIGHT);
        volumeFader.getStyleClass().add("mixer-fader");
        volumeFader.setTooltip(new Tooltip("Master Volume"));
        volumeFader.valueProperty().addListener((_, _, newVal) ->
                master.setVolume(newVal.doubleValue()));

        Slider panSlider = new Slider(-1.0, 1.0, master.getPan());
        panSlider.setPrefWidth(CHANNEL_WIDTH - 12);
        panSlider.getStyleClass().add("mixer-fader");
        panSlider.setTooltip(new Tooltip("Master Pan"));
        panSlider.valueProperty().addListener((_, _, newVal) ->
                master.setPan(newVal.doubleValue()));
        Label panLabel = new Label("PAN");
        panLabel.getStyleClass().add("mixer-channel-name");

        Button muteBtn = new Button("M");
        muteBtn.getStyleClass().add("track-mute-button");
        muteBtn.setTooltip(new Tooltip("Mute Master"));
        muteBtn.setGraphic(IconNode.of(DawIcon.MUTE, CONTROL_ICON_SIZE));
        muteBtn.setOnAction(_ -> {
            boolean muted = !master.isMuted();
            master.setMuted(muted);
            muteBtn.setStyle(muted
                    ? "-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;" : "");
        });

        HBox buttonRow = new HBox(2, muteBtn);
        buttonRow.setAlignment(Pos.CENTER);

        // Spacer to align vertically with track strips
        Region spacer = new Region();
        spacer.setPrefHeight(20);

        Node masterIcon = IconNode.of(DawIcon.SPEAKER, CONTROL_ICON_SIZE);

        strip.getChildren().addAll(
                nameLabel, masterIcon, levelMeter, volumeFader,
                panLabel, panSlider, buttonRow, spacer);

        return strip;
    }

    private static Node trackTypeIcon(TrackType type) {
        return switch (type) {
            case AUDIO        -> IconNode.of(DawIcon.MICROPHONE, CONTROL_ICON_SIZE);
            case MIDI         -> IconNode.of(DawIcon.PIANO, CONTROL_ICON_SIZE);
            case AUX          -> IconNode.of(DawIcon.MIXER, CONTROL_ICON_SIZE);
            case MASTER       -> IconNode.of(DawIcon.SPEAKER, CONTROL_ICON_SIZE);
            case FOLDER       -> IconNode.of(DawIcon.FOLDER, CONTROL_ICON_SIZE);
            case BED_CHANNEL  -> IconNode.of(DawIcon.SURROUND, CONTROL_ICON_SIZE);
            case AUDIO_OBJECT -> IconNode.of(DawIcon.PAN, CONTROL_ICON_SIZE);
            case REFERENCE    -> IconNode.of(DawIcon.HEADPHONES, CONTROL_ICON_SIZE);
        };
    }

    // ── I/O routing selectors ──────────────────────────────────────────────

    private static final int MAX_IO_CHANNELS = 16;

    /**
     * One row in the I/O routing dropdown, carrying both the underlying
     * routing identity and the rendering metadata (driver-reported
     * display name, kind icon, active flag). Stored as the ComboBox's
     * item type so the list cell factory can render it directly.
     */
    private record IoOption(int firstChannel, int channelCount,
                            String displayName, ChannelKind kind,
                            boolean active, boolean isNoneOrMaster) {
        @Override public String toString() { return displayName; }
    }

    private ComboBox<IoOption> buildInputRoutingSelector(Track track) {
        ComboBox<IoOption> combo = new ComboBox<>();
        combo.setMaxWidth(CHANNEL_WIDTH - 8);
        combo.setMaxHeight(18);
        combo.setStyle("-fx-font-size: 8px;");
        combo.setTooltip(new Tooltip("Input routing"));

        List<IoOption> options = new ArrayList<>();
        // Always offer "None" as the first entry.
        options.add(new IoOption(InputRouting.NONE.firstChannel(),
                InputRouting.NONE.channelCount(),
                InputRouting.NONE.displayName(),
                ChannelKind.Generic.INSTANCE, true, true));

        List<AudioChannelInfo> live = inputChannelInfoSupplier.get();
        if (live != null && !live.isEmpty()) {
            // Driver-reported channels: build options via the L/R-grouping
            // helper so consecutive "Mic 1 L" + "Mic 1 R" auto-collapse to
            // "Mic 1 (Stereo)".
            for (ChannelGrouping.Option opt : ChannelGrouping.buildOptions(live)) {
                options.add(new IoOption(
                        opt.firstChannel(), opt.channelCount(),
                        opt.displayName(), opt.kind(), opt.active(), false));
            }
        } else {
            // Legacy fallback when no live channel info is available.
            for (int ch = 0; ch < MAX_IO_CHANNELS; ch++) {
                options.add(new IoOption(ch, 1, "Input " + (ch + 1),
                        ChannelKind.Generic.INSTANCE, true, false));
            }
            for (int ch = 0; ch < MAX_IO_CHANNELS; ch += 2) {
                options.add(new IoOption(ch, 2,
                        "Input " + (ch + 1) + "-" + (ch + 2),
                        ChannelKind.Generic.INSTANCE, true, false));
            }
        }

        combo.getItems().addAll(options);
        combo.setCellFactory(_ -> ioCell());
        combo.setButtonCell(ioCell());

        // Select current routing (matched on first channel + count).
        InputRouting current = track.getInputRouting();
        IoOption selected = options.stream()
                .filter(o -> o.firstChannel() == current.firstChannel()
                        && o.channelCount() == current.channelCount())
                .findFirst()
                .orElse(options.getFirst());
        combo.getSelectionModel().select(selected);

        combo.setOnAction(_ -> {
            IoOption opt = combo.getSelectionModel().getSelectedItem();
            if (opt == null) {
                return;
            }
            track.setInputRouting(new InputRouting(opt.firstChannel(), opt.channelCount()));
            track.setInputRoutingDisplayName(opt.isNoneOrMaster() ? "" : opt.displayName());
        });

        return combo;
    }

    private ComboBox<IoOption> buildOutputRoutingSelector(MixerChannel channel) {
        ComboBox<IoOption> combo = new ComboBox<>();
        combo.setMaxWidth(CHANNEL_WIDTH - 8);
        combo.setMaxHeight(18);
        combo.setStyle("-fx-font-size: 8px;");
        combo.setTooltip(new Tooltip("Output routing"));

        List<IoOption> options = new ArrayList<>();
        options.add(new IoOption(OutputRouting.MASTER.firstChannel(),
                OutputRouting.MASTER.channelCount(),
                OutputRouting.MASTER.displayName(),
                ChannelKind.Generic.INSTANCE, true, true));

        List<AudioChannelInfo> live = outputChannelInfoSupplier.get();
        if (live != null && !live.isEmpty()) {
            for (ChannelGrouping.Option opt : ChannelGrouping.buildOptions(live)) {
                options.add(new IoOption(
                        opt.firstChannel(), opt.channelCount(),
                        opt.displayName(), opt.kind(), opt.active(), false));
            }
        } else {
            // Legacy fallback: stereo pairs only, matching the historical UI.
            for (int ch = 0; ch < MAX_IO_CHANNELS; ch += 2) {
                options.add(new IoOption(ch, 2,
                        "Output " + (ch + 1) + "-" + (ch + 2),
                        ChannelKind.Generic.INSTANCE, true, false));
            }
        }

        combo.getItems().addAll(options);
        combo.setCellFactory(_ -> ioCell());
        combo.setButtonCell(ioCell());

        OutputRouting current = channel.getOutputRouting();
        IoOption selected = options.stream()
                .filter(o -> o.firstChannel() == current.firstChannel()
                        && o.channelCount() == current.channelCount())
                .findFirst()
                .orElse(options.getFirst());
        combo.getSelectionModel().select(selected);

        combo.setOnAction(_ -> {
            IoOption opt = combo.getSelectionModel().getSelectedItem();
            if (opt == null) {
                return;
            }
            channel.setOutputRouting(new OutputRouting(opt.firstChannel(), opt.channelCount()));
            channel.setOutputRoutingDisplayName(opt.isNoneOrMaster() ? "" : opt.displayName());
        });

        return combo;
    }

    /**
     * Builds a {@link ListCell} that renders an {@link IoOption} with a
     * small {@link ChannelKind} glyph and dims/disables inactive entries
     * with the "Disabled in driver" tooltip required by story 199.
     */
    private static ListCell<IoOption> ioCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(IoOption item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    setDisable(false);
                    setStyle("");
                    return;
                }
                setText(item.displayName());
                setGraphic(IconNode.of(iconForKind(item.kind()), 10));
                if (!item.active()) {
                    setDisable(true);
                    setStyle("-fx-text-fill: #888888;");
                    setTooltip(new Tooltip("Disabled in driver"));
                } else {
                    setDisable(false);
                    setStyle("");
                    setTooltip(null);
                }
            }
        };
    }

    private static DawIcon iconForKind(ChannelKind kind) {
        return switch (kind) {
            case ChannelKind.Mic m         -> DawIcon.MICROPHONE;
            case ChannelKind.Line l        -> DawIcon.XLR;
            case ChannelKind.Instrument i  -> DawIcon.GUITAR;
            case ChannelKind.Digital d     -> DawIcon.SPDIF;
            case ChannelKind.Monitor mo    -> DawIcon.MONITOR;
            case ChannelKind.Headphone h   -> DawIcon.HEADPHONES;
            case ChannelKind.Generic g     -> DawIcon.LINK;
        };
    }

    /**
     * Updates a latency label to reflect the current insert-chain latency of
     * the given mixer channel. Called once during strip construction and again
     * each time the {@link InsertEffectRack} rebuilds its slots.
     */
    private static void updateLatencyLabel(Label label, MixerChannel channel, double sampleRate) {
        int latencySamples = channel.getEffectsChain().getTotalLatencySamples();
        if (latencySamples > 0) {
            double latencyMs = latencySamples / sampleRate * 1000.0;
            label.setText(String.format("%.1f ms", latencyMs));
            label.setTooltip(new Tooltip(latencySamples + " samples latency"));
        } else {
            label.setText("");
            label.setTooltip(null);
        }
    }

    /**
     * Applies the visual style to a solo button so that its colour conveys
     * both the solo and the solo-safe state. A soloed channel paints the
     * button green; the solo-safe (solo-in-place defeat) flag adds a yellow
     * ring so the engineer can see at a glance which channels stay audible
     * during a solo.
     */
    private static void applySoloButtonStyle(Button soloBtn, MixerChannel channel) {
        StringBuilder style = new StringBuilder();
        if (channel.isSolo()) {
            style.append("-fx-background-color: #00e676; -fx-text-fill: #0d0d0d;");
        }
        if (channel.isSoloSafe()) {
            // Yellow ring marks "safe" channels (returns and groups) — they
            // remain audible regardless of any other channel's solo state.
            style.append("-fx-border-color: #ffeb3b; -fx-border-width: 2;"
                    + " -fx-border-radius: 3; -fx-background-radius: 3;");
        }
        soloBtn.setStyle(style.toString());
        Tooltip tip = new Tooltip(channel.isSoloSafe()
                ? "Solo (Solo Safe enabled — right-click to disable)"
                : "Solo (right-click for Solo Safe)");
        soloBtn.setTooltip(tip);
    }

    /**
     * Installs a right-click context menu on the supplied solo button that
     * toggles the channel's solo-safe flag through {@link SetSoloSafeAction}
     * (so the change participates in undo/redo). Invoked on track and
     * return-bus channel strips.
     *
     * <p>Also registers a sync callback so the button ring and the
     * {@code CheckMenuItem} selection reflect the model after any
     * {@link UndoManager} history change (undo, redo, or recall).</p>
     */
    private void installSoloSafeContextMenu(Button soloBtn, MixerChannel channel) {
        ContextMenu menu = new ContextMenu();
        CheckMenuItem soloSafeItem = new CheckMenuItem("Solo safe");
        soloSafeItem.setSelected(channel.isSoloSafe());
        soloSafeItem.setOnAction(_ -> {
            boolean target = soloSafeItem.isSelected();
            SetSoloSafeAction action = new SetSoloSafeAction(channel, target);
            if (undoManager != null) {
                undoManager.execute(action);
            } else {
                action.execute();
            }
            applySoloButtonStyle(soloBtn, channel);
        });
        menu.getItems().add(soloSafeItem);
        soloBtn.setContextMenu(menu);

        // Keep the button ring + checkmark in sync with the model after
        // undo/redo, snapshot recalls, or "Reset solo safe to defaults".
        soloSafeSyncCallbacks.add(() -> {
            soloSafeItem.setSelected(channel.isSoloSafe());
            applySoloButtonStyle(soloBtn, channel);
        });
    }

    private void runSoloSafeSyncCallbacks() {
        for (Runnable r : soloSafeSyncCallbacks) {
            r.run();
        }
    }

    // ── VCA helpers (story 153) ────────────────────────────────────────────

    /**
     * Highlights a channel strip when it is part of the current
     * {@link #selectedChannelIds} multi-selection. Used as a cheap restyle
     * after Ctrl/Shift-click instead of a full {@link #refresh()}.
     */
    private void applyChannelStripSelectionStyle(VBox strip, UUID channelId) {
        if (selectedChannelIds.contains(channelId)) {
            strip.setStyle("-fx-background-color: rgba(124, 77, 255, 0.18);"
                    + " -fx-border-color: #7c4dff; -fx-border-width: 2;");
        } else {
            strip.setStyle("");
        }
    }

    /**
     * Implements the issue's "select several channels → right-click → Create
     * VCA" flow. Prompts for a name, then auto-assigns a palette color and
     * dispatches a {@link CreateVcaGroupAction} (with the seed members)
     * through the {@link UndoManager}. Falls back to the right-clicked
     * channel when nothing is multi-selected.
     */
    private void createVcaFromSelection(UUID rightClickedChannelId) {
        Set<UUID> seeds = new java.util.LinkedHashSet<>(selectedChannelIds);
        if (seeds.isEmpty()) {
            seeds.add(rightClickedChannelId);
        } else if (!seeds.contains(rightClickedChannelId)) {
            // The user right-clicked a strip that isn't in the selection —
            // honor the right-click target as the primary intent and add it
            // to the seeds so it doesn't get silently excluded.
            seeds.add(rightClickedChannelId);
        }

        TextInputDialog nameDialog = new TextInputDialog("VCA " +
                (project.getVcaGroupManager().getVcaGroups().size() + 1));
        nameDialog.setTitle("Create VCA");
        nameDialog.setHeaderText("Name the new VCA group");
        nameDialog.setContentText("Name:");
        Optional<String> result = nameDialog.showAndWait();
        if (result.isEmpty() || result.get().isBlank()) {
            return;
        }
        String name = result.get().trim();

        // Auto-pick a palette color so the user gets a visible swatch
        // immediately; they can always change it via the strip's color
        // picker later. Cycling through the 16-color palette mirrors the
        // automatic track-color rotation used by DawProject.
        TrackColor color = TrackColor.fromPaletteIndex(
                project.getVcaGroupManager().getVcaGroups().size());

        CreateVcaGroupAction action = new CreateVcaGroupAction(
                project.getVcaGroupManager(), name, color, new ArrayList<>(seeds));
        if (undoManager != null) {
            undoManager.execute(action);
        } else {
            action.execute();
        }
        selectedChannelIds.clear();
        refresh();
    }

    // ── Channel-link UI (Story 159) ─────────────────────────────────────────

    /**
     * Parses {@code track.getId()} into a {@link UUID}, or returns
     * {@code null} for legacy / hand-crafted test fixtures whose ids are
     * not UUID-formatted (mirrors the same defensive pattern used by the
     * VCA-strip wiring elsewhere in this view).
     */
    private static UUID parseChannelId(Track track) {
        try {
            return UUID.fromString(track.getId());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Inserts a small "link toggle" node between every adjacent pair of
     * channel strips in {@link #channelStrips}. The toggle pairs the two
     * adjacent channels via {@link LinkChannelsAction} (or unpairs via
     * {@link UnlinkChannelsAction}) on left-click and opens a
     * {@link ChannelLinkPopover} on right-click. The toggle itself is a
     * tiny {@link Button} rendered with the {@link DawIcon#LINK} chain
     * glyph; it turns the active accent colour when the pair is linked,
     * matching the behaviour described in Story 159.
     *
     * @param idsInOrder channel ids of the strips in left-to-right order;
     *                   {@code null} entries (non-UUID track ids) get a
     *                   non-clickable placeholder so column spacing is
     *                   preserved
     */
    private void installLinkToggles(List<UUID> idsInOrder) {
        if (idsInOrder.size() < 2) {
            return;
        }
        ChannelLinkManager linkManager = project.getChannelLinkManager();
        // Iterate in reverse so we can splice toggles into channelStrips
        // without invalidating the indices of the strips we still have to
        // process. The toggle for the (i, i+1) pair is inserted at child
        // position i+1.
        for (int i = idsInOrder.size() - 2; i >= 0; i--) {
            UUID leftId  = idsInOrder.get(i);
            UUID rightId = idsInOrder.get(i + 1);
            channelStrips.getChildren().add(i + 1,
                    buildLinkToggle(leftId, rightId, linkManager));
        }
    }

    /**
     * Builds the chain-glyph link toggle node spliced between two adjacent
     * channel strips.
     */
    private Node buildLinkToggle(UUID leftId, UUID rightId, ChannelLinkManager linkManager) {
        ChannelLink existing = (leftId != null && rightId != null)
                ? linkManager.getLink(leftId)
                : null;
        boolean linkedTogether = existing != null
                && existing.involves(leftId)
                && existing.involves(rightId);

        Button btn = new Button();
        btn.getStyleClass().add("mixer-channel-link-toggle");
        btn.setGraphic(IconNode.of(DawIcon.LINK, CONTROL_ICON_SIZE));
        btn.setTooltip(new Tooltip(linkedTogether
                ? "Stereo-link active. Click to unlink. Right-click for link options."
                : "Click to link these two channels into a stereo pair."));
        // Highlight when active so the engineer can see the pair at a glance.
        btn.setStyle(linkedTogether
                ? "-fx-background-color: #00bcd4; -fx-text-fill: #0d0d0d;"
                + " -fx-padding: 2 4 2 4;"
                : "-fx-padding: 2 4 2 4;");
        btn.setUserData(new UUID[]{leftId, rightId});

        // Both endpoints must be UUID-typed and not currently linked to a
        // *third* channel for "Link" to be valid. Disable the button in
        // edge cases so the user gets a clear non-action.
        boolean canLink = leftId != null && rightId != null;
        if (!canLink) {
            btn.setDisable(true);
            return wrapLinkToggle(btn, false, leftId, rightId, null);
        }
        boolean leftLinked  = linkManager.isLinked(leftId);
        boolean rightLinked = linkManager.isLinked(rightId);
        if (!linkedTogether && (leftLinked || rightLinked)) {
            btn.setDisable(true);
            btn.setTooltip(new Tooltip(
                    "One of these channels is already linked to a different channel. "
                            + "Unlink it first."));
        }

        btn.setOnAction(_ -> {
            ChannelLink current = linkManager.getLink(leftId);
            boolean isLinkedTogetherNow = current != null
                    && current.involves(leftId) && current.involves(rightId);
            if (isLinkedTogetherNow) {
                UnlinkChannelsAction unlink = new UnlinkChannelsAction(linkManager, leftId);
                if (undoManager != null) {
                    undoManager.execute(unlink);
                } else {
                    unlink.execute();
                }
            } else if (!linkManager.isLinked(leftId) && !linkManager.isLinked(rightId)) {
                // Default per Story 159: faders + pans + mute/solo on,
                // inserts + sends off, RELATIVE mode.
                ChannelLink link = new ChannelLink(leftId, rightId,
                        LinkMode.RELATIVE, true, true, true, false, false);
                LinkChannelsAction linkAction = new LinkChannelsAction(linkManager, link);
                if (undoManager != null) {
                    undoManager.execute(linkAction);
                } else {
                    linkAction.execute();
                }
            }
        });

        btn.setOnContextMenuRequested(e -> {
            ChannelLink current = linkManager.getLink(leftId);
            if (current != null && current.involves(leftId) && current.involves(rightId)) {
                ChannelLinkPopover popover =
                        new ChannelLinkPopover(linkManager, undoManager, current);
                popover.show(btn, e.getScreenX(), e.getScreenY());
            }
        });

        return wrapLinkToggle(btn, linkedTogether, leftId, rightId, existing);
    }

    /**
     * Wraps the link toggle button in a thin VBox with an optional
     * connector line that spans the two strips at the fader-gap level when
     * the pair is linked. Visible (active accent) only when {@code linked}
     * is true so unlinked pairs render only the unobtrusive toggle.
     */
    private VBox wrapLinkToggle(Button btn, boolean linked,
                                UUID leftId, UUID rightId, ChannelLink link) {
        VBox box = new VBox(2);
        box.setAlignment(Pos.CENTER);
        box.setPrefWidth(18);
        box.setMinWidth(18);
        box.setMaxWidth(24);
        box.getChildren().add(btn);
        if (linked) {
            // Thin horizontal connector line "between" the two faders.
            Region connector = new Region();
            connector.setPrefSize(20, 2);
            connector.setMinHeight(2);
            connector.setStyle("-fx-background-color: #00bcd4;");
            box.getChildren().add(connector);
        }
        // Tag the wrapper too so tests can locate the node by user data.
        box.setUserData(new LinkTogglePair(leftId, rightId, link));
        return box;
    }

    /**
     * Lookup tag attached to a link-toggle wrapper {@link VBox} so tests
     * (and any future feature using the toggle) can correlate a wrapper
     * node back to the pair of channel ids it spans without walking the
     * scene graph.
     */
    public record LinkTogglePair(UUID leftChannelId, UUID rightChannelId, ChannelLink link) { }

    private void propagateVolumeChange(UUID sourceId, double oldValue, double newValue) {
        if (propagationSuppressed.contains(sourceId)) {
            return;
        }
        ChannelLinkManager mgr = project.getChannelLinkManager();
        ChannelLink link = mgr.getLink(sourceId);
        if (link == null || !link.linkFaders()) {
            return;
        }
        UUID partnerId = link.partnerOf(sourceId);
        MixerChannel source  = channelByChannelId.get(sourceId);
        MixerChannel partner = channelByChannelId.get(partnerId);
        Slider partnerSlider = volumeFaderByChannelId.get(partnerId);
        if (source == null || partner == null || partnerSlider == null) {
            return;
        }
        propagationSuppressed.add(partnerId);
        try {
            // Delegate the per-mode arithmetic to the manager so UI and
            // model behaviour stay in lock-step. The manager updates the
            // model, then we copy the new model value onto the partner
            // slider's value (which would otherwise still hold the old
            // pre-mirror display value).
            mgr.applyVolumeChange(link, source, partner, oldValue, newValue);
            partnerSlider.setValue(partner.getVolume());
            Track partnerTrack = trackByChannelId.get(partnerId);
            if (partnerTrack != null) {
                partnerTrack.setVolume(partner.getVolume());
            }
        } finally {
            propagationSuppressed.remove(partnerId);
        }
    }

    private void propagatePanChange(UUID sourceId, double newPan) {
        if (propagationSuppressed.contains(sourceId)) {
            return;
        }
        ChannelLinkManager mgr = project.getChannelLinkManager();
        ChannelLink link = mgr.getLink(sourceId);
        if (link == null || !link.linkPans()) {
            return;
        }
        UUID partnerId = link.partnerOf(sourceId);
        MixerChannel partner = channelByChannelId.get(partnerId);
        Slider partnerSlider = panSliderByChannelId.get(partnerId);
        if (partner == null || partnerSlider == null) {
            return;
        }
        propagationSuppressed.add(partnerId);
        try {
            mgr.applyPanChange(link, partner, newPan);
            partnerSlider.setValue(partner.getPan());
            Track partnerTrack = trackByChannelId.get(partnerId);
            if (partnerTrack != null) {
                partnerTrack.setPan(partner.getPan());
            }
        } finally {
            propagationSuppressed.remove(partnerId);
        }
    }

    private void propagateMuteChange(UUID sourceId, boolean muted) {
        if (propagationSuppressed.contains(sourceId)) {
            return;
        }
        ChannelLinkManager mgr = project.getChannelLinkManager();
        ChannelLink link = mgr.getLink(sourceId);
        if (link == null || !link.linkMuteSolo()) {
            return;
        }
        UUID partnerId = link.partnerOf(sourceId);
        MixerChannel partner = channelByChannelId.get(partnerId);
        Button partnerBtn = muteBtnByChannelId.get(partnerId);
        if (partner == null) {
            return;
        }
        propagationSuppressed.add(partnerId);
        try {
            mgr.applyMuteChange(link, partner, muted);
            Track partnerTrack = trackByChannelId.get(partnerId);
            if (partnerTrack != null) {
                partnerTrack.setMuted(muted);
            }
            if (partnerBtn != null) {
                partnerBtn.setStyle(muted
                        ? "-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;" : "");
            }
        } finally {
            propagationSuppressed.remove(partnerId);
        }
    }

    private void propagateSoloChange(UUID sourceId, boolean solo) {
        if (propagationSuppressed.contains(sourceId)) {
            return;
        }
        ChannelLinkManager mgr = project.getChannelLinkManager();
        ChannelLink link = mgr.getLink(sourceId);
        if (link == null || !link.linkMuteSolo()) {
            return;
        }
        UUID partnerId = link.partnerOf(sourceId);
        MixerChannel partner = channelByChannelId.get(partnerId);
        Button partnerBtn = soloBtnByChannelId.get(partnerId);
        if (partner == null) {
            return;
        }
        propagationSuppressed.add(partnerId);
        try {
            mgr.applySoloChange(link, partner, solo);
            Track partnerTrack = trackByChannelId.get(partnerId);
            if (partnerTrack != null) {
                partnerTrack.setSolo(solo);
            }
            if (partnerBtn != null) {
                applySoloButtonStyle(partnerBtn, partner);
            }
        } finally {
            propagationSuppressed.remove(partnerId);
        }
    }
}
