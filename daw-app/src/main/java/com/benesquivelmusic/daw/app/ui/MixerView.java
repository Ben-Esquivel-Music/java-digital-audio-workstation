package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Objects;

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
    private static final double CONTROL_ICON_SIZE = 14;
    private static final double SEND_SLIDER_WIDTH = 60;

    private final DawProject project;
    private final UndoManager undoManager;
    private final HBox channelStrips;
    private final HBox returnBusStrips;
    private final VBox masterStrip;

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

        getChildren().addAll(header, scrollPane);
        setPadding(new Insets(8));

        refresh();
    }

    /**
     * Rebuilds the channel strips from the current project tracks and return
     * buses.
     *
     * <p>Call this method after adding or removing tracks or return buses to
     * keep the mixer view synchronized with the project model.</p>
     */
    public void refresh() {
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
        addReturnBusBtn.setOnAction(_ -> {
            int busCount = project.getMixer().getReturnBusCount();
            project.getMixer().addReturnBus("Return " + (busCount + 1));
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

        // Solo button
        Button soloBtn = new Button("S");
        soloBtn.getStyleClass().add("track-solo-button");
        soloBtn.setTooltip(new Tooltip("Solo"));
        soloBtn.setGraphic(IconNode.of(DawIcon.SOLO, CONTROL_ICON_SIZE));
        soloBtn.setOnAction(_ -> {
            boolean solo = !mixerChannel.isSolo();
            mixerChannel.setSolo(solo);
            track.setSolo(solo);
            soloBtn.setStyle(solo
                    ? "-fx-background-color: #00e676; -fx-text-fill: #0d0d0d;" : "");
        });

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

        // Send controls — one slider per return bus
        VBox sendBox = new VBox(2);
        for (MixerChannel returnBus : project.getMixer().getReturnBuses()) {
            Label sendLabel = new Label("→ " + returnBus.getName());
            sendLabel.getStyleClass().add("mixer-channel-name");
            sendLabel.setMaxWidth(CHANNEL_WIDTH - 12);

            Send existingSend = mixerChannel.getSendForTarget(returnBus);
            double initialLevel = existingSend != null ? existingSend.getLevel() : 0.0;

            Slider sendSlider = new Slider(0.0, 1.0, initialLevel);
            sendSlider.setPrefWidth(SEND_SLIDER_WIDTH);
            sendSlider.getStyleClass().add("mixer-fader");
            sendSlider.setTooltip(new Tooltip("Send to " + returnBus.getName()));

            MixerChannel targetBus = returnBus;
            sendSlider.valueProperty().addListener((_, _, newVal) -> {
                double value = newVal.doubleValue();
                Send send = mixerChannel.getSendForTarget(targetBus);
                if (send != null) {
                    send.setLevel(value);
                } else if (value > 0.0) {
                    mixerChannel.addSend(new Send(targetBus, value, SendMode.POST_FADER));
                }
            });

            // Right-click context menu to toggle pre/post fader
            ContextMenu sendMenu = new ContextMenu();
            MenuItem preFaderItem = new MenuItem("Pre-Fader");
            preFaderItem.setOnAction(_ -> {
                Send send = mixerChannel.getSendForTarget(targetBus);
                if (send != null) {
                    send.setMode(SendMode.PRE_FADER);
                }
            });
            MenuItem postFaderItem = new MenuItem("Post-Fader");
            postFaderItem.setOnAction(_ -> {
                Send send = mixerChannel.getSendForTarget(targetBus);
                if (send != null) {
                    send.setMode(SendMode.POST_FADER);
                }
            });
            sendMenu.getItems().addAll(preFaderItem, postFaderItem);
            sendLabel.setContextMenu(sendMenu);

            sendBox.getChildren().addAll(sendLabel, sendSlider);
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

        // Insert effects rack
        int channels = project.getFormat().channels();
        double sr = project.getFormat().sampleRate();
        InsertEffectRack insertRack = new InsertEffectRack(mixerChannel, channels, sr, undoManager);

        strip.getChildren().addAll(
                nameLabel, typeIcon, insertRack, levelMeter, volumeFader,
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

        HBox buttonRow = new HBox(2, muteBtn);
        buttonRow.setAlignment(Pos.CENTER);

        Node busIcon = IconNode.of(DawIcon.MIXER, CONTROL_ICON_SIZE);

        strip.getChildren().addAll(
                nameLabel, busIcon, levelMeter, volumeFader,
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
}
