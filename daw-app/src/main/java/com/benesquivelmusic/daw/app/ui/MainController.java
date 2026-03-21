package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

/**
 * Controller for the main DAW window.
 */
public class MainController {

    @FXML private Button playButton;
    @FXML private Button stopButton;
    @FXML private Button recordButton;
    @FXML private Label statusLabel;
    @FXML private Label tempoLabel;

    private DawProject project;

    @FXML
    private void initialize() {
        project = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
        updateStatus();
        updateTempoDisplay();
    }

    @FXML
    private void onPlay() {
        project.getTransport().play();
        updateStatus();
    }

    @FXML
    private void onStop() {
        project.getTransport().stop();
        updateStatus();
    }

    @FXML
    private void onRecord() {
        project.getTransport().record();
        updateStatus();
    }

    private void updateStatus() {
        Transport transport = project.getTransport();
        TransportState state = transport.getState();
        statusLabel.setText("Status: " + state.name());

        playButton.setDisable(state == TransportState.PLAYING);
        recordButton.setDisable(state == TransportState.RECORDING);
        stopButton.setDisable(state == TransportState.STOPPED);
    }

    private void updateTempoDisplay() {
        tempoLabel.setText(String.format("%.1f BPM", project.getTransport().getTempo()));
    }
}
