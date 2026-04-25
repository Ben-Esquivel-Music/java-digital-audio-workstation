package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.InputMeterStrip;
import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitor;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.mixer.*;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    private final VBox masterStrip;
    private final List<InsertEffectRack> activeInsertRacks = new ArrayList<>();
    private final List<InputMeterStrip> activeInputMeterStrips = new ArrayList<>();
    private PluginRegistry pluginRegistry;
    private InputLevelMonitorRegistry inputLevelMonitorRegistry;

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

        Label header = new Label("MIXER");
        header.getStyleClass().add("panel-header");
        header.setGraphic(IconNode.of(DawIcon.MIXER, 16));
        header.setPadding(new Insets(0, 0, 6, 0));

        // Mixer maintenance menu — currently exposes "Reset solo safe to
        // defaults", which restores return buses to solo-safe and track /
        // master channels to not solo-safe.
        MenuButton mixerMenu = new MenuButton("⋮");
        mixerMenu.setTooltip(new Tooltip("Mixer options"));
        MenuItem resetSoloSafeItem = new MenuItem("Reset solo safe to defaults");
        resetSoloSafeItem.setOnAction(_ -> {
            project.getMixer().resetSoloSafeToDefaults();
            refresh();
        });
        mixerMenu.getItems().add(resetSoloSafeItem);

        HBox headerRow = new HBox(8, header, mixerMenu);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setPadding(new Insets(0, 0, 6, 0));

        channelStrips = new HBox(6);
        channelStrips.setAlignment(Pos.TOP_LEFT);

        returnBusStrips = new HBox(6);
        returnBusStrips.setAlignment(Pos.TOP_LEFT);

        masterStrip = buildMasterStrip();

        HBox allStrips = new HBox(6);
        allStrips.setAlignment(Pos.TOP_LEFT);
        allStrips.getChildren().addAll(
                channelStrips,
                new Separator(Orientation.VERTICAL),
                returnBusStrips,
                new Separator(Orientation.VERTICAL),
                masterStrip);
        HBox.setHgrow(channelStrips, Priority.ALWAYS);

        ScrollPane scrollPane = new ScrollPane(allStrips);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(headerRow, scrollPane);
        setPadding(new Insets(8));

        refresh();
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
     * Rebuilds the channel strips from the current project tracks and return
     * buses.
     *
     * <p>Call this method after adding or removing tracks or return buses to
     * keep the mixer view synchronized with the project model.</p>
     */
    public void refresh() {
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

        channelStrips.getChildren().clear();
        for (Track track : project.getTracks()) {
            MixerChannel mixerChannel = project.getMixerChannelForTrack(track);
            if (mixerChannel != null) {
                channelStrips.getChildren().add(buildChannelStrip(track, mixerChannel));
            }
        }

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

        // Channel name
        Label nameLabel = new Label(track.getName());
        nameLabel.getStyleClass().add("mixer-channel-name");
        nameLabel.setMaxWidth(CHANNEL_WIDTH - 12);

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
        volumeFader.valueProperty().addListener((_, _, newVal) -> {
            double value = newVal.doubleValue();
            mixerChannel.setVolume(value);
            track.setVolume(value);
        });

        // Pan control (horizontal slider)
        Slider panSlider = new Slider(-1.0, 1.0, mixerChannel.getPan());
        panSlider.setPrefWidth(CHANNEL_WIDTH - 12);
        panSlider.getStyleClass().add("mixer-fader");
        panSlider.setTooltip(new Tooltip("Pan (L/R)"));
        panSlider.valueProperty().addListener((_, _, newVal) -> {
            double value = newVal.doubleValue();
            mixerChannel.setPan(value);
            track.setPan(value);
        });
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
        });

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
        });
        installSoloSafeContextMenu(soloBtn, mixerChannel);

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
        ComboBox<String> inputRoutingCombo = buildInputRoutingSelector(track);

        // Output routing selector
        ComboBox<String> outputRoutingCombo = buildOutputRoutingSelector(mixerChannel);

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

    private ComboBox<String> buildInputRoutingSelector(Track track) {
        ComboBox<String> combo = new ComboBox<>();
        combo.setMaxWidth(CHANNEL_WIDTH - 8);
        combo.setMaxHeight(18);
        combo.setStyle("-fx-font-size: 8px;");
        combo.setTooltip(new Tooltip("Input routing"));

        List<InputRouting> options = new ArrayList<>();
        options.add(InputRouting.NONE);
        // Mono inputs
        for (int ch = 0; ch < MAX_IO_CHANNELS; ch++) {
            options.add(new InputRouting(ch, 1));
        }
        // Stereo pairs
        for (int ch = 0; ch < MAX_IO_CHANNELS; ch += 2) {
            options.add(new InputRouting(ch, 2));
        }

        for (InputRouting opt : options) {
            combo.getItems().add(opt.displayName());
        }

        // Select current
        InputRouting current = track.getInputRouting();
        int selectedIndex = 0;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equals(current)) {
                selectedIndex = i;
                break;
            }
        }
        combo.getSelectionModel().select(selectedIndex);

        combo.setOnAction(_ -> {
            int idx = combo.getSelectionModel().getSelectedIndex();
            if (idx >= 0 && idx < options.size()) {
                track.setInputRouting(options.get(idx));
            }
        });

        return combo;
    }

    private ComboBox<String> buildOutputRoutingSelector(MixerChannel channel) {
        ComboBox<String> combo = new ComboBox<>();
        combo.setMaxWidth(CHANNEL_WIDTH - 8);
        combo.setMaxHeight(18);
        combo.setStyle("-fx-font-size: 8px;");
        combo.setTooltip(new Tooltip("Output routing"));

        List<OutputRouting> options = new ArrayList<>();
        options.add(OutputRouting.MASTER);
        // Stereo output pairs
        for (int ch = 0; ch < MAX_IO_CHANNELS; ch += 2) {
            options.add(new OutputRouting(ch, 2));
        }

        for (OutputRouting opt : options) {
            combo.getItems().add(opt.displayName());
        }

        // Select current
        OutputRouting current = channel.getOutputRouting();
        int selectedIndex = 0;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equals(current)) {
                selectedIndex = i;
                break;
            }
        }
        combo.getSelectionModel().select(selectedIndex);

        combo.setOnAction(_ -> {
            int idx = combo.getSelectionModel().getSelectedIndex();
            if (idx >= 0 && idx < options.size()) {
                channel.setOutputRouting(options.get(idx));
            }
        });

        return combo;
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
    }
}
