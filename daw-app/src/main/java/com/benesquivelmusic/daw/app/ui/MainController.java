package com.benesquivelmusic.daw.app.ui;

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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
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
 * during long-running recording sessions.</p>
 */
public class MainController {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());

    @FXML private Button playButton;
    @FXML private Button stopButton;
    @FXML private Button recordButton;
    @FXML private Label statusLabel;
    @FXML private Label tempoLabel;
    @FXML private Label timeDisplay;
    @FXML private Label projectInfoLabel;
    @FXML private Label checkpointLabel;
    @FXML private Label statusBarLabel;
    @FXML private Label arrangementPlaceholder;
    @FXML private VBox trackListPanel;

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

        updateStatus();
        updateTempoDisplay();
        updateProjectInfo();
        updateCheckpointStatus();

        LOG.info("DAW initialized with studio quality format");
    }

    @FXML
    private void onPlay() {
        project.getTransport().play();
        updateStatus();
        statusBarLabel.setText("▶ Playing...");
    }

    @FXML
    private void onStop() {
        project.getTransport().stop();
        updateStatus();
        timeDisplay.setText("00:00:00.0");
        statusBarLabel.setText("■ Stopped");
    }

    @FXML
    private void onRecord() {
        project.getTransport().record();
        updateStatus();
        statusBarLabel.setText("● Recording — auto-save active");
    }

    @FXML
    private void onAddAudioTrack() {
        audioTrackCounter++;
        String name = "Audio " + audioTrackCounter;
        Track track = project.createAudioTrack(name);
        addTrackToUI(track);
        updateArrangementPlaceholder();
        statusBarLabel.setText("Added audio track: " + name);
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
            checkpointLabel.setText("✓ Saved (checkpoint #" + count + ")");
            statusBarLabel.setText("💾 Project saved");
            LOG.info("Project saved successfully");
        } catch (IOException e) {
            statusBarLabel.setText("⚠ Save failed: " + e.getMessage());
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

        var nameLabel = new Label(track.getName());
        nameLabel.getStyleClass().add("track-name");

        String typeSymbol = switch (track.getType()) {
            case AUDIO  -> "🎤";
            case MIDI   -> "🎹";
            case AUX    -> "🔀";
            case MASTER -> "🔊";
        };
        var typeLabel = new Label(typeSymbol);
        typeLabel.getStyleClass().add("track-type-label");

        var muteBtn = new Button("M");
        muteBtn.getStyleClass().add("track-mute-button");
        muteBtn.setOnAction(_ -> {
            track.setMuted(!track.isMuted());
            muteBtn.setStyle(track.isMuted()
                    ? "-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;" : "");
        });

        var soloBtn = new Button("S");
        soloBtn.getStyleClass().add("track-solo-button");
        soloBtn.setOnAction(_ -> {
            track.setSolo(!track.isSolo());
            soloBtn.setStyle(track.isSolo()
                    ? "-fx-background-color: #00e676; -fx-text-fill: #0d0d0d;" : "");
        });

        var armBtn = new Button("R");
        armBtn.getStyleClass().add("track-arm-button");

        trackItem.getChildren().addAll(typeLabel, nameLabel, muteBtn, soloBtn, armBtn);
        trackListPanel.getChildren().add(trackItem);
    }

    private void updateStatus() {
        Transport transport = project.getTransport();
        TransportState state = transport.getState();

        statusLabel.setText(state.name());
        statusLabel.getStyleClass().removeAll("status-recording", "status-playing", "status-stopped");
        switch (state) {
            case RECORDING -> statusLabel.getStyleClass().add("status-recording");
            case PLAYING   -> statusLabel.getStyleClass().add("status-playing");
            default        -> statusLabel.getStyleClass().add("status-stopped");
        }

        playButton.setDisable(state == TransportState.PLAYING);
        recordButton.setDisable(state == TransportState.RECORDING);
        stopButton.setDisable(state == TransportState.STOPPED);
    }

    private void updateTempoDisplay() {
        tempoLabel.setText(String.format("♩ %.1f BPM", project.getTransport().getTempo()));
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
