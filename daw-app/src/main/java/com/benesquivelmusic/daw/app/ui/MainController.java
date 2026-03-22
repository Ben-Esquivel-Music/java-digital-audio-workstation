package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;

import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controller for the main DAW window.
 *
 * <p>Manages the project lifecycle, transport controls, track list,
 * and coordinates with the {@link ProjectManager} for auto-save
 * during long-running recording sessions. Uses the {@link DawIcon}
 * icon pack throughout the UI via {@link IconNode}.</p>
 */
public final class MainController {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());

    /** Icon size for transport-bar buttons (play, stop, record). */
    private static final double TRANSPORT_ICON_SIZE = 18;
    /** Icon size for toolbar buttons (add track, save, plugins). */
    private static final double TOOLBAR_ICON_SIZE = 16;
    /** Icon size for track-strip controls (mute, solo, arm). */
    private static final double TRACK_CONTROL_ICON_SIZE = 14;
    /** Icon size for track-type indicators. */
    private static final double TRACK_TYPE_ICON_SIZE = 18;
    /** Icon size for panel-header labels. */
    private static final double PANEL_ICON_SIZE = 16;

    @FXML private Button playButton;
    @FXML private Button stopButton;
    @FXML private Button recordButton;
    @FXML private Button addAudioTrackButton;
    @FXML private Button addMidiTrackButton;
    @FXML private Button saveButton;
    @FXML private Button pluginsButton;
    @FXML private Label statusLabel;
    @FXML private Label tempoLabel;
    @FXML private Label timeDisplay;
    @FXML private Label projectInfoLabel;
    @FXML private Label checkpointLabel;
    @FXML private Label statusBarLabel;
    @FXML private Label arrangementPlaceholder;
    @FXML private Label tracksPanelHeader;
    @FXML private VBox trackListPanel;
    @FXML private HBox vizTileRow;

    private DawProject project;
    private PluginRegistry pluginRegistry;
    private ProjectManager projectManager;
    private int audioTrackCounter;
    private int midiTrackCounter;

    @FXML
    private void initialize() {
        project = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
        pluginRegistry = new PluginRegistry();

        var checkpointManager = new CheckpointManager(AutoSaveConfig.DEFAULT);
        projectManager = new ProjectManager(checkpointManager);

        audioTrackCounter = 0;
        midiTrackCounter = 0;

        applyIcons();
        buildVisualizationTiles();
        updateStatus();
        updateTempoDisplay();
        updateProjectInfo();
        updateCheckpointStatus();

        LOG.info("DAW initialized with studio quality format");
    }

    /**
     * Applies SVG icons from the DAW icon pack to all UI controls.
     */
    private void applyIcons() {
        // Transport controls
        playButton.setGraphic(IconNode.of(DawIcon.PLAY, TRANSPORT_ICON_SIZE));
        stopButton.setGraphic(IconNode.of(DawIcon.STOP, TRANSPORT_ICON_SIZE));
        recordButton.setGraphic(IconNode.of(DawIcon.RECORD, TRANSPORT_ICON_SIZE));

        // Toolbar buttons
        addAudioTrackButton.setGraphic(IconNode.of(DawIcon.MICROPHONE, TOOLBAR_ICON_SIZE));
        addMidiTrackButton.setGraphic(IconNode.of(DawIcon.MIDI, TOOLBAR_ICON_SIZE));
        saveButton.setGraphic(IconNode.of(DawIcon.DOWNLOAD, TOOLBAR_ICON_SIZE));
        pluginsButton.setGraphic(IconNode.of(DawIcon.SETTINGS, TOOLBAR_ICON_SIZE));

        // Time display — clock icon prefix
        timeDisplay.setGraphic(IconNode.of(DawIcon.CLOCK, PANEL_ICON_SIZE));

        // Panel headers
        tracksPanelHeader.setGraphic(IconNode.of(DawIcon.MIXER, PANEL_ICON_SIZE));

        // Arrangement placeholder
        arrangementPlaceholder.setGraphic(IconNode.of(DawIcon.WAVEFORM, 24));

        // Status bar icons
        projectInfoLabel.setGraphic(IconNode.of(DawIcon.FOLDER, 12));
        checkpointLabel.setGraphic(IconNode.of(DawIcon.SYNC, 12));

        LOG.fine("Applied SVG icons from DAW icon pack");
    }

    /**
     * Builds the visualization tile row at the bottom of the main content area.
     * Each tile is a styled card containing a labeled display placeholder.
     */
    private void buildVisualizationTiles() {
        vizTileRow.setPrefHeight(120);
        vizTileRow.setMinHeight(100);

        vizTileRow.getChildren().addAll(
                createVizTile("WAVEFORM", DawIcon.WAVEFORM, "tile-header-accent-cyan"),
                createVizTile("SPECTRUM", DawIcon.SPECTRUM, "tile-header-accent-green"),
                createVizTile("LEVELS", DawIcon.VU_METER, "tile-header-accent-orange"),
                createVizTile("LOUDNESS", DawIcon.LOUDNESS_METER, "tile-header-accent-purple"),
                createVizTile("CORRELATION", DawIcon.CORRELATION, "tile-header-accent-red")
        );

        LOG.fine("Built visualization tile row with 5 display tiles");
    }

    /**
     * Creates a single visualization tile with a header label and display area.
     */
    private VBox createVizTile(String title, DawIcon icon, String accentClass) {
        var header = new Label(title);
        header.getStyleClass().addAll("viz-tile-label", accentClass);
        header.setGraphic(IconNode.of(icon, 12));

        var displayArea = new StackPane();
        displayArea.setStyle("-fx-background-color: #0a0a0a; -fx-background-radius: 6;");
        VBox.setVgrow(displayArea, Priority.ALWAYS);

        var tile = new VBox(4, header, displayArea);
        tile.getStyleClass().add("viz-tile");
        tile.setPadding(new Insets(8));
        HBox.setHgrow(tile, Priority.ALWAYS);

        return tile;
    }

    @FXML
    private void onPlay() {
        project.getTransport().play();
        updateStatus();
        statusBarLabel.setText("Playing...");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PLAY, 12));
    }

    @FXML
    private void onStop() {
        project.getTransport().stop();
        updateStatus();
        timeDisplay.setText("00:00:00.0");
        statusBarLabel.setText("Stopped");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STOP, 12));
    }

    @FXML
    private void onRecord() {
        project.getTransport().record();
        updateStatus();
        statusBarLabel.setText("Recording — auto-save active");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.RECORD, 12));
    }

    @FXML
    private void onAddAudioTrack() {
        audioTrackCounter++;
        String name = "Audio " + audioTrackCounter;
        Track track = project.createAudioTrack(name);
        addTrackToUI(track);
        updateArrangementPlaceholder();
        statusBarLabel.setText("Added audio track: " + name);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.MICROPHONE, 12));
        LOG.fine(() -> "Added audio track: " + name);
    }

    @FXML
    private void onAddMidiTrack() {
        midiTrackCounter++;
        String name = "MIDI " + midiTrackCounter;
        Track track = project.createMidiTrack(name);
        addTrackToUI(track);
        updateArrangementPlaceholder();
        statusBarLabel.setText("Added MIDI track: " + name);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.MIDI, 12));
        LOG.fine(() -> "Added MIDI track: " + name);
    }

    @FXML
    private void onSaveProject() {
        try {
            if (projectManager.getCurrentProject() == null) {
                Path tempDir = Files.createTempDirectory("daw-project-");
                projectManager.createProject(project.getName(), tempDir.getParent());
            }
            projectManager.saveProject();
            int count = projectManager.getCheckpointManager().getCheckpointCount();
            checkpointLabel.setText("Saved (checkpoint #" + count + ")");
            checkpointLabel.setGraphic(IconNode.of(DawIcon.SUCCESS, 12));
            statusBarLabel.setText("Project saved");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.DOWNLOAD, 12));
            LOG.info("Project saved successfully");
        } catch (IOException e) {
            statusBarLabel.setText("Save failed: " + e.getMessage());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            LOG.log(Level.WARNING, "Failed to save project", e);
        }
    }

    @FXML
    private void onManagePlugins() {
        var dialog = new PluginManagerDialog(pluginRegistry);
        dialog.showAndWait();
    }

    private void addTrackToUI(Track track) {
        var trackItem = new HBox(8);
        trackItem.getStyleClass().add("track-item");
        trackItem.setPadding(new Insets(6, 8, 6, 8));
        trackItem.setAlignment(Pos.CENTER_LEFT);

        // Track type icon
        Node typeIcon = switch (track.getType()) {
            case AUDIO  -> IconNode.of(DawIcon.MICROPHONE, TRACK_TYPE_ICON_SIZE);
            case MIDI   -> IconNode.of(DawIcon.KEYBOARD, TRACK_TYPE_ICON_SIZE);
            case AUX    -> IconNode.of(DawIcon.MIXER, TRACK_TYPE_ICON_SIZE);
            case MASTER -> IconNode.of(DawIcon.SPEAKER, TRACK_TYPE_ICON_SIZE);
        };

        var nameLabel = new Label(track.getName());
        nameLabel.getStyleClass().add("track-name");

        // Mute button with icon
        var muteBtn = new Button();
        muteBtn.setGraphic(IconNode.of(DawIcon.MUTE, TRACK_CONTROL_ICON_SIZE));
        muteBtn.getStyleClass().add("track-mute-button");
        muteBtn.setOnAction(_ -> {
            track.setMuted(!track.isMuted());
            muteBtn.setStyle(track.isMuted()
                    ? "-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;" : "");
        });

        // Solo button with icon
        var soloBtn = new Button();
        soloBtn.setGraphic(IconNode.of(DawIcon.SOLO, TRACK_CONTROL_ICON_SIZE));
        soloBtn.getStyleClass().add("track-solo-button");
        soloBtn.setOnAction(_ -> {
            track.setSolo(!track.isSolo());
            soloBtn.setStyle(track.isSolo()
                    ? "-fx-background-color: #00e676; -fx-text-fill: #0d0d0d;" : "");
        });

        // Arm button with icon
        var armBtn = new Button();
        armBtn.setGraphic(IconNode.of(DawIcon.ARM_TRACK, TRACK_CONTROL_ICON_SIZE));
        armBtn.getStyleClass().add("track-arm-button");

        trackItem.getChildren().addAll(typeIcon, nameLabel, muteBtn, soloBtn, armBtn);
        trackListPanel.getChildren().add(trackItem);
    }

    private void updateStatus() {
        Transport transport = project.getTransport();
        TransportState state = transport.getState();

        statusLabel.setText(state.name());
        statusLabel.getStyleClass().removeAll("status-recording", "status-playing", "status-stopped");
        switch (state) {
            case RECORDING -> {
                statusLabel.getStyleClass().add("status-recording");
                statusLabel.setGraphic(IconNode.of(DawIcon.RECORD, 12));
            }
            case PLAYING -> {
                statusLabel.getStyleClass().add("status-playing");
                statusLabel.setGraphic(IconNode.of(DawIcon.PLAY, 12));
            }
            default -> {
                statusLabel.getStyleClass().add("status-stopped");
                statusLabel.setGraphic(IconNode.of(DawIcon.STOP, 12));
            }
        }

        playButton.setDisable(state == TransportState.PLAYING);
        recordButton.setDisable(state == TransportState.RECORDING);
        stopButton.setDisable(state == TransportState.STOPPED);
    }

    private void updateTempoDisplay() {
        tempoLabel.setText(String.format("%.1f BPM", project.getTransport().getTempo()));
        tempoLabel.setGraphic(IconNode.of(DawIcon.METRONOME, PANEL_ICON_SIZE));
    }

    private void updateProjectInfo() {
        AudioFormat fmt = project.getFormat();
        projectInfoLabel.setText(String.format("%s  ·  %.0f kHz / %d-bit / %dch",
                project.getName(),
                fmt.sampleRate() / 1000.0,
                fmt.bitDepth(),
                fmt.channels()));
    }

    private void updateCheckpointStatus() {
        checkpointLabel.setText("Auto-save: ON");
    }

    private void updateArrangementPlaceholder() {
        arrangementPlaceholder.setVisible(project.getTracks().isEmpty());
    }
}
