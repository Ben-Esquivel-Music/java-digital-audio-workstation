package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import javafx.animation.FadeTransition;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages transport playback actions and recording pipeline lifecycle.
 *
 * <p>Extracted from {@link MainController} to isolate transport behavior
 * into a dedicated, independently testable class. Time-ticker animation
 * is delegated to {@link AnimationController} via the {@link Host}
 * callback interface. All dependencies are received via constructor
 * injection.</p>
 */
final class TransportController {

    private static final Logger LOG = Logger.getLogger(TransportController.class.getName());

    /**
     * Callback interface implemented by the host controller to provide
     * state that remains in the top-level controller.
     */
    interface Host {
        boolean isSnapEnabled();
        GridResolution gridResolution();
        void startTimeTicker();
        void pauseTimeTicker();
        void stopTimeTicker();
    }

    private final DawProject project;
    private final AudioEngine audioEngine;
    private final UndoManager undoManager;
    private final NotificationBar notificationBar;
    private final Label statusLabel;
    private final Label timeDisplay;
    private final Label statusBarLabel;
    private final Label recIndicator;
    private final Button playButton;
    private final Button pauseButton;
    private final Button stopButton;
    private final Button recordButton;
    private final Button loopButton;
    private final Host host;

    private RecordingPipeline recordingPipeline;
    private boolean loopEnabled;

    TransportController(DawProject project,
                        AudioEngine audioEngine,
                        UndoManager undoManager,
                        NotificationBar notificationBar,
                        Label statusLabel,
                        Label timeDisplay,
                        Label statusBarLabel,
                        Label recIndicator,
                        Button playButton,
                        Button pauseButton,
                        Button stopButton,
                        Button recordButton,
                        Button loopButton,
                        Host host) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.audioEngine = Objects.requireNonNull(audioEngine, "audioEngine must not be null");
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        this.statusLabel = Objects.requireNonNull(statusLabel, "statusLabel must not be null");
        this.timeDisplay = Objects.requireNonNull(timeDisplay, "timeDisplay must not be null");
        this.statusBarLabel = Objects.requireNonNull(statusBarLabel, "statusBarLabel must not be null");
        this.recIndicator = Objects.requireNonNull(recIndicator, "recIndicator must not be null");
        this.playButton = Objects.requireNonNull(playButton, "playButton must not be null");
        this.pauseButton = Objects.requireNonNull(pauseButton, "pauseButton must not be null");
        this.stopButton = Objects.requireNonNull(stopButton, "stopButton must not be null");
        this.recordButton = Objects.requireNonNull(recordButton, "recordButton must not be null");
        this.loopButton = Objects.requireNonNull(loopButton, "loopButton must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");
    }

    // ── Transport action handlers ────────────────────────────────────────────

    void onPlay() {
        try {
            audioEngine.startAudioOutput();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to start audio output", e);
            notificationBar.show(NotificationLevel.ERROR,
                    "Audio device error: " + e.getMessage());
        }
        project.getTransport().play();
        host.startTimeTicker();
        updateStatus();
        statusBarLabel.setText("Playing...");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PLAY_CIRCLE, 12));
    }

    void onStop() {
        // Finalize recording if a recording pipeline is active
        if (recordingPipeline != null && recordingPipeline.isActive()) {
            List<AudioClip> recordedClips = recordingPipeline.stop();
            if (!recordedClips.isEmpty()) {
                // Register undo action for the recorded clips
                Map<Track, AudioClip> clipMap = Map.copyOf(recordingPipeline.getRecordedClips());
                undoManager.execute(new UndoableAction() {
                    @Override
                    public String description() { return "Record Audio"; }

                    @Override
                    public void execute() {
                        // Clips are already added by the pipeline on first execution;
                        // on redo, re-add them.
                        for (Map.Entry<Track, AudioClip> entry : clipMap.entrySet()) {
                            if (!entry.getKey().getClips().contains(entry.getValue())) {
                                entry.getKey().addClip(entry.getValue());
                            }
                        }
                    }

                    @Override
                    public void undo() {
                        for (Map.Entry<Track, AudioClip> entry : clipMap.entrySet()) {
                            entry.getKey().removeClip(entry.getValue());
                        }
                    }
                });
                int segmentCount = clipMap.values().size();
                statusBarLabel.setText("Recording stopped — " + segmentCount + " clip"
                        + (segmentCount > 1 ? "s" : "") + " created");
                notificationBar.show(NotificationLevel.SUCCESS,
                        "Recording stopped — " + segmentCount + " clip"
                                + (segmentCount > 1 ? "s" : "") + " created");
            }
            recordingPipeline = null;
        }

        project.getTransport().stop();
        audioEngine.stopAudioOutput();
        host.stopTimeTicker();
        updateStatus();
        timeDisplay.setText("00:00:00.0");
        if (statusBarLabel.getText() == null || !statusBarLabel.getText().startsWith("Recording stopped")) {
            statusBarLabel.setText("Stopped");
        }
        statusBarLabel.setGraphic(IconNode.of(DawIcon.POWER, 12));
        // Restore button appearance in case the record blink was active
        recordButton.setOpacity(1.0);
        recordButton.setStyle("");
        // Hide the REC indicator
        recIndicator.setVisible(false);
        recIndicator.setManaged(false);
    }

    void onPause() {
        project.getTransport().pause();
        audioEngine.pauseAudioOutput();
        host.pauseTimeTicker();
        updateStatus();
        statusBarLabel.setText("Paused");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PAUSE_CIRCLE, 12));
    }

    void onRecord() {
        // Validate that at least one track is armed for recording
        List<Track> armedTracks = RecordingPipeline.findArmedTracks(project.getTracks());
        if (armedTracks.isEmpty()) {
            notificationBar.show(NotificationLevel.WARNING,
                    "No tracks armed for recording — arm at least one track first");
            Alert alert = new Alert(Alert.AlertType.WARNING,
                    "No tracks are armed for recording. Please arm at least one track before recording.",
                    ButtonType.OK);
            alert.setTitle("Cannot Record");
            alert.setHeaderText("No Armed Tracks");
            DarkThemeHelper.applyTo(alert);
            alert.showAndWait();
            return;
        }

        // Create output directory for recording segments
        Path outputDir;
        try {
            outputDir = Files.createTempDirectory("daw-recording-");
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to create recording output directory", e);
            statusBarLabel.setText("Recording failed — could not create output directory");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
            notificationBar.show(NotificationLevel.ERROR,
                    "Recording failed — could not create output directory");
            return;
        }

        // Create and start the recording pipeline
        recordingPipeline = new RecordingPipeline(
                audioEngine, project.getTransport(), project.getFormat(), outputDir, armedTracks);
        recordingPipeline.start();

        // Open audio input stream with the first armed track's input device
        try {
            int inputDevice = armedTracks.stream()
                    .mapToInt(Track::getInputDeviceIndex)
                    .filter(idx -> idx >= 0)
                    .findFirst()
                    .orElse(0); // default input device
            audioEngine.startAudioInputOutput(inputDevice);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to start audio input for recording", e);
            notificationBar.show(NotificationLevel.ERROR,
                    "Audio device error: " + e.getMessage());
        }

        host.startTimeTicker();
        updateStatus();
        int trackCount = armedTracks.size();
        statusBarLabel.setText("Recording — " + trackCount + " track"
                + (trackCount > 1 ? "s" : "") + " armed — auto-save active");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
        notificationBar.show(NotificationLevel.INFO,
                "Recording started — " + trackCount + " track"
                        + (trackCount > 1 ? "s" : "") + " armed");
        recIndicator.setVisible(true);
        recIndicator.setManaged(true);
    }

    void onSkipBack() {
        Transport transport = project.getTransport();
        TransportState state = transport.getState();
        transport.setPositionInBeats(0.0);

        if (state == TransportState.PLAYING || state == TransportState.RECORDING) {
            // Keep the ticker running while playing/recording; it will update the
            // time display on the next tick to reflect the new position.
        } else {
            host.stopTimeTicker();
            timeDisplay.setText("00:00:00.0");
        }
        statusBarLabel.setText("Skipped to beginning");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.SKIP_BACK, 12));
    }

    void onSkipForward() {
        Transport transport = project.getTransport();
        double jump = 4.0 * transport.getTimeSignatureNumerator();
        double newPosition = transport.getPositionInBeats() + jump;
        if (host.isSnapEnabled()) {
            newPosition = SnapQuantizer.quantize(newPosition, host.gridResolution(),
                    transport.getTimeSignatureNumerator());
        }
        transport.setPositionInBeats(newPosition);
        statusBarLabel.setText("Skipped forward");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.SKIP_FORWARD, 12));
    }

    void onToggleLoop() {
        loopEnabled = !loopEnabled;
        project.getTransport().setLoopEnabled(loopEnabled);
        loopButton.setStyle(loopEnabled
                ? "-fx-background-color: #b388ff; -fx-text-fill: #0d0d0d;" : "");
        String loopState = loopEnabled ? "Loop: ON" : "Loop: OFF";
        statusBarLabel.setText(loopState);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.LOOP, 12));
        LOG.fine(loopState);
    }

    // ── Status update ────────────────────────────────────────────────────────

    void updateStatus() {
        Transport transport = project.getTransport();
        TransportState state = transport.getState();

        statusLabel.setText(state.name());
        statusLabel.getStyleClass().removeAll(
                "status-recording", "status-playing", "status-stopped", "status-paused");
        switch (state) {
            case RECORDING -> {
                statusLabel.getStyleClass().add("status-recording");
                statusLabel.setGraphic(IconNode.of(DawIcon.LIVE, 12));
            }
            case PLAYING -> {
                statusLabel.getStyleClass().add("status-playing");
                statusLabel.setGraphic(IconNode.of(DawIcon.PLAY, 12));
            }
            case PAUSED -> {
                statusLabel.getStyleClass().add("status-paused");
                statusLabel.setGraphic(IconNode.of(DawIcon.PAUSE, 12));
            }
            default -> {
                statusLabel.getStyleClass().add("status-stopped");
                statusLabel.setGraphic(IconNode.of(DawIcon.POWER, 12));
            }
        }

        // Smooth fade-in so the status label change feels polished
        statusLabel.setOpacity(0.0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), statusLabel);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.play();

        playButton.setDisable(state == TransportState.PLAYING);
        pauseButton.setDisable(state == TransportState.STOPPED || state == TransportState.PAUSED);
        recordButton.setDisable(state == TransportState.RECORDING);
        stopButton.setDisable(state == TransportState.STOPPED);
    }
}
