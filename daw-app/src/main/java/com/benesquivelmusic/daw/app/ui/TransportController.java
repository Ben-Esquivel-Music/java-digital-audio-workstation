package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.MidiRecorder;
import com.benesquivelmusic.daw.core.midi.RecordMidiNotesAction;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import javafx.animation.FadeTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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
        Metronome metronome();
        CountInMode countInMode();
        void startTimeTicker();
        void pauseTimeTicker();
        void stopTimeTicker();

        /**
         * Flashes a MIDI activity indicator on the given track's strip.
         * Called from the MIDI receiver thread via {@link javafx.application.Platform#runLater}.
         *
         * @param track the track that received MIDI activity
         */
        void flashMidiActivity(Track track);

        /**
         * Returns whether the recording pipeline should compensate for the
         * driver's reported round-trip latency on captured takes. Reads the
         * "Apply latency compensation to recorded takes" toggle from
         * {@link SettingsModel}.
         *
         * @return {@code true} when compensation is enabled
         */
        default boolean isApplyLatencyCompensation() {
            return true;
        }

        /**
         * Returns the driver-reported round-trip latency for the currently
         * opened audio stream. Delegates to
         * {@link AudioEngineController#reportedLatency()}.
         *
         * @return the reported latency; never {@code null}
         */
        default RoundTripLatency reportedLatency() {
            return RoundTripLatency.UNKNOWN;
        }
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
    private final Map<Track, MidiRecorder> activeMidiRecorders = new LinkedHashMap<>();
    private boolean loopEnabled;

    /**
     * Story 134 — Pre-roll / Post-Roll transport controls. The toggle
     * buttons reflect whether the feature is currently enabled, and the
     * spinners hold the bar counts (range 0–8, default 2). They are
     * created lazily by {@link #createPreRollPostRollControls()}; if the
     * controls are never mounted, the transport's pre/post-roll
     * configuration remains at {@link PreRollPostRoll#DISABLED}.
     */
    private ToggleButton preRollToggle;
    private ToggleButton postRollToggle;
    private Spinner<Integer> preRollSpinner;
    private Spinner<Integer> postRollSpinner;

    /** Default bar count used by the pre/post-roll spinners (range 0–8). */
    static final int DEFAULT_BARS = 2;
    /** Maximum bar count allowed by the pre/post-roll spinners. */
    static final int MAX_BARS = 8;

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

        // Finalize MIDI recording if any MIDI recorders are active
        stopMidiRecording();

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

        // Partition armed tracks into audio and MIDI
        List<Track> armedAudioTracks = new ArrayList<>();
        List<Track> armedMidiTracks = new ArrayList<>();
        for (Track track : armedTracks) {
            if (track.getType() == TrackType.MIDI) {
                armedMidiTracks.add(track);
            } else {
                armedAudioTracks.add(track);
            }
        }

        CountInMode countIn = host.countInMode();

        // Start audio recording pipeline for non-MIDI armed tracks
        if (!armedAudioTracks.isEmpty()) {
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

            recordingPipeline = new RecordingPipeline(
                    audioEngine, project.getTransport(), project.getFormat(), outputDir,
                    armedAudioTracks, countIn, InputMonitoringMode.OFF, null);
            recordingPipeline.setReportedLatency(host.reportedLatency());
            recordingPipeline.setApplyLatencyCompensation(host.isApplyLatencyCompensation());
            recordingPipeline.start();

            // Open audio input stream with the first armed audio track's input device
            try {
                int inputDevice = armedAudioTracks.stream()
                        .mapToInt(Track::getInputDeviceIndex)
                        .filter(idx -> idx >= 0)
                        .findFirst()
                        .orElse(0);
                audioEngine.startAudioInputOutput(inputDevice);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to start audio input for recording", e);
                notificationBar.show(NotificationLevel.ERROR,
                        "Audio device error: " + e.getMessage());
            }
        }

        // Start MIDI recording for armed MIDI tracks
        if (!armedMidiTracks.isEmpty()) {
            startMidiRecording(armedMidiTracks, countIn);
        }

        // If no audio pipeline was started, transition transport to recording
        // and start audio output (for playback of existing tracks during MIDI recording)
        if (armedAudioTracks.isEmpty()) {
            try {
                audioEngine.startAudioOutput();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to start audio output", e);
            }
            project.getTransport().record();
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

    // ── Pre-Roll / Post-Roll (Story 134) ─────────────────────────────────────

    /**
     * Starts playback with pre-roll applied (Story 134). The transport
     * is seeked back by the configured pre-roll bar count and playback
     * begins. If pre-roll is disabled or {@code preBars == 0}, this is
     * equivalent to {@link #onPlay()}.
     *
     * <p>During the pre-roll window the transport's
     * {@link Transport#isInputCaptureGated()} flag is {@code true} —
     * the recording pipeline reads it to suppress capture so the user
     * hears context but no input is recorded.</p>
     */
    void onPlayWithPreRoll() {
        try {
            audioEngine.startAudioOutput();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to start audio output", e);
            notificationBar.show(NotificationLevel.ERROR,
                    "Audio device error: " + e.getMessage());
        }
        double shift = project.getTransport().playWithPreRoll();
        host.startTimeTicker();
        updateStatus();
        if (shift > 0) {
            statusBarLabel.setText(String.format(
                    "Pre-roll: %.1f beats (monitoring only)…", shift));
        } else {
            statusBarLabel.setText("Playing...");
        }
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PLAY_CIRCLE, 12));
    }

    /** Toggles the pre-roll {@code enabled} flag while preserving bar counts. */
    void onTogglePreRoll() {
        Transport transport = project.getTransport();
        PreRollPostRoll current = transport.getPreRollPostRoll();
        boolean newEnabled = !current.enabled();
        // If toggling on but both bar counts are zero, seed with the default
        // pre-roll value so the user sees an effect immediately.
        int pre = (newEnabled && current.preBars() == 0 && current.postBars() == 0)
                ? DEFAULT_BARS : current.preBars();
        transport.setPreRollPostRoll(new PreRollPostRoll(pre, current.postBars(), newEnabled));
        syncPreRollControls();
        statusBarLabel.setText("Pre-roll: " + (newEnabled ? "ON" : "OFF"));
    }

    /** Toggles the post-roll {@code enabled} flag while preserving bar counts. */
    void onTogglePostRoll() {
        Transport transport = project.getTransport();
        PreRollPostRoll current = transport.getPreRollPostRoll();
        boolean newEnabled = !current.enabled();
        int post = (newEnabled && current.preBars() == 0 && current.postBars() == 0)
                ? DEFAULT_BARS : current.postBars();
        transport.setPreRollPostRoll(new PreRollPostRoll(current.preBars(), post, newEnabled));
        syncPreRollControls();
        statusBarLabel.setText("Post-roll: " + (newEnabled ? "ON" : "OFF"));
    }

    /**
     * Builds the toggle buttons and bar-count spinners for pre-roll and
     * post-roll, returning an {@link HBox} suitable for mounting on the
     * transport bar. The controls are wired bidirectionally with
     * {@link Transport#setPreRollPostRoll}: changing a spinner updates
     * the configuration; calling {@link #onTogglePreRoll()} updates the
     * toggle state. Range 0–8, default {@value #DEFAULT_BARS}.
     *
     * <p>This method is package-private and idempotent: calling it more
     * than once returns a fresh container but reuses the existing
     * controls, so the {@code MainController} can mount the same node
     * once during initialization.</p>
     *
     * @return an {@link HBox} containing the pre-roll and post-roll
     *         toggles and spinners
     */
    HBox createPreRollPostRollControls() {
        if (preRollToggle == null) {
            preRollToggle = new ToggleButton("Pre-Roll");
            preRollToggle.getStyleClass().addAll("transport-button", "pre-roll-button");
            preRollToggle.setGraphic(IconNode.of(DawIcon.REWIND, 12));
            preRollToggle.setTooltip(new Tooltip(
                    "Pre-Roll: seek back by N bars before playback (Story 134)"));
            preRollToggle.setOnAction(_ -> onTogglePreRoll());

            postRollToggle = new ToggleButton("Post-Roll");
            postRollToggle.getStyleClass().addAll("transport-button", "post-roll-button");
            postRollToggle.setGraphic(IconNode.of(DawIcon.FAST_FORWARD, 12));
            postRollToggle.setTooltip(new Tooltip(
                    "Post-Roll: keep playing for N bars after stop (Story 134)"));
            postRollToggle.setOnAction(_ -> onTogglePostRoll());

            preRollSpinner = createBarSpinner();
            preRollSpinner.valueProperty().addListener((_, _, newVal) ->
                    applySpinnerChange(newVal, /*pre=*/true));

            postRollSpinner = createBarSpinner();
            postRollSpinner.valueProperty().addListener((_, _, newVal) ->
                    applySpinnerChange(newVal, /*pre=*/false));

            syncPreRollControls();
        }
        HBox box = new HBox(4, preRollToggle, preRollSpinner, postRollToggle, postRollSpinner);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("toolbar-button-group");
        return box;
    }

    private static Spinner<Integer> createBarSpinner() {
        SpinnerValueFactory.IntegerSpinnerValueFactory factory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, MAX_BARS, DEFAULT_BARS);
        Spinner<Integer> spinner = new Spinner<>(factory);
        spinner.setEditable(true);
        spinner.setPrefWidth(64);
        spinner.getStyleClass().add("pre-roll-spinner");
        return spinner;
    }

    private void applySpinnerChange(Integer newVal, boolean pre) {
        int v = (newVal == null) ? 0 : Math.max(0, Math.min(MAX_BARS, newVal));
        Transport transport = project.getTransport();
        PreRollPostRoll current = transport.getPreRollPostRoll();
        int preBars = pre ? v : current.preBars();
        int postBars = pre ? current.postBars() : v;
        // Spinner edits do not toggle the enabled flag — preserve it.
        transport.setPreRollPostRoll(
                new PreRollPostRoll(preBars, postBars, current.enabled()));
    }

    /**
     * Pushes the current {@link Transport#getPreRollPostRoll()} state onto
     * the toggle buttons and spinners, if mounted. Safe to call when
     * controls have not been built — it is a no-op.
     */
    void syncPreRollControls() {
        if (preRollToggle == null) {
            return;
        }
        PreRollPostRoll prpr = project.getTransport().getPreRollPostRoll();
        preRollToggle.setSelected(prpr.enabled() && prpr.preBars() > 0);
        postRollToggle.setSelected(prpr.enabled() && prpr.postBars() > 0);
        // setValue fires the spinner listener; skip if value already matches
        // to avoid feedback loops between Transport ↔ spinner.
        if (preRollSpinner.getValue() == null
                || preRollSpinner.getValue() != prpr.preBars()) {
            preRollSpinner.getValueFactory().setValue(prpr.preBars());
        }
        if (postRollSpinner.getValue() == null
                || postRollSpinner.getValue() != prpr.postBars()) {
            postRollSpinner.getValueFactory().setValue(prpr.postBars());
        }
    }

    /** Returns the pre-roll toggle button (for tests). May be {@code null}. */
    ToggleButton preRollToggleForTest() { return preRollToggle; }
    /** Returns the post-roll toggle button (for tests). May be {@code null}. */
    ToggleButton postRollToggleForTest() { return postRollToggle; }
    /** Returns the pre-roll spinner (for tests). May be {@code null}. */
    Spinner<Integer> preRollSpinnerForTest() { return preRollSpinner; }
    /** Returns the post-roll spinner (for tests). May be {@code null}. */
    Spinner<Integer> postRollSpinnerForTest() { return postRollSpinner; }

    // ── MIDI recording helpers ───────────────────────────────────────────────

    /**
     * Creates and starts a {@link MidiRecorder} for each armed MIDI track.
     *
     * <p>When a count-in mode is active, the recorder's count-in duration is
     * set so that notes played during the pre-roll are discarded. An event
     * listener is registered on each recorder to flash a MIDI activity
     * indicator on the track's arm button during capture.</p>
     *
     * @param midiTracks the armed MIDI tracks
     * @param countIn    the count-in mode (may be {@link CountInMode#OFF})
     */
    private void startMidiRecording(List<Track> midiTracks, CountInMode countIn) {
        Transport transport = project.getTransport();
        double startBeat = transport.getPositionInBeats();
        int startColumnOffset = (int) Math.round(startBeat / MidiRecorder.BEATS_PER_COLUMN);

        // Compute count-in duration in microseconds
        int beatsPerBar = transport.getTimeSignatureNumerator();
        int countInBeats = countIn.getTotalBeats(beatsPerBar);
        double countInSeconds = countInBeats * (60.0 / transport.getTempo());
        long countInDurationUs = Math.round(countInSeconds * 1_000_000L);

        for (Track track : midiTracks) {
            MidiDevice device = resolveMidiDevice(track.getMidiInputDeviceName());
            if (device == null) {
                LOG.warning("No MIDI input device found for track: " + track.getName()
                        + " (device name: " + track.getMidiInputDeviceName() + ")");
                notificationBar.show(NotificationLevel.WARNING,
                        "MIDI device not found for track: " + track.getName());
                continue;
            }

            MidiRecorder recorder = new MidiRecorder(
                    device, track.getMidiClip(), transport.getTempo(), 0);
            recorder.setStartColumnOffset(startColumnOffset);
            recorder.setCountInDurationUs(countInDurationUs);

            // Wire MIDI activity indicator — flash the track strip on each event
            recorder.addEventListener(_ -> javafx.application.Platform.runLater(
                    () -> host.flashMidiActivity(track)));

            try {
                recorder.startRecording();
                track.setRecording(true);
                activeMidiRecorders.put(track, recorder);
                LOG.fine(() -> "Started MIDI recording on track: " + track.getName());
            } catch (MidiUnavailableException e) {
                LOG.log(Level.WARNING, "Failed to start MIDI recording on track: "
                        + track.getName(), e);
                notificationBar.show(NotificationLevel.ERROR,
                        "MIDI recording failed on track: " + track.getName());
            }
        }
    }

    /**
     * Stops all active MIDI recorders and registers an undoable action for
     * each track's recorded notes.
     */
    private void stopMidiRecording() {
        if (activeMidiRecorders.isEmpty()) {
            return;
        }

        int totalNotes = 0;
        for (Map.Entry<Track, MidiRecorder> entry : activeMidiRecorders.entrySet()) {
            Track track = entry.getKey();
            MidiRecorder recorder = entry.getValue();
            recorder.stopRecording();
            track.setRecording(false);

            List<MidiNoteData> recordedNotes = recorder.getRecordedNotes();
            if (!recordedNotes.isEmpty()) {
                totalNotes += recordedNotes.size();
                undoManager.execute(new RecordMidiNotesAction(
                        track.getMidiClip(), recordedNotes));
            }
        }
        activeMidiRecorders.clear();

        if (totalNotes > 0) {
            String msg = "Recording stopped — " + totalNotes + " MIDI note"
                    + (totalNotes > 1 ? "s" : "") + " captured";
            if (statusBarLabel.getText() == null
                    || !statusBarLabel.getText().startsWith("Recording stopped")) {
                statusBarLabel.setText(msg);
            }
            notificationBar.show(NotificationLevel.SUCCESS, msg);
        }
    }

    /**
     * Resolves a MIDI input device by name from the system's available devices.
     *
     * @param deviceName the device name to look up
     * @return the MIDI device, or {@code null} if not found or unavailable
     */
    private static MidiDevice resolveMidiDevice(String deviceName) {
        if (deviceName == null) {
            return null;
        }
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            if (info.getName().equals(deviceName)) {
                try {
                    MidiDevice device = MidiSystem.getMidiDevice(info);
                    if (device.getMaxTransmitters() != 0) {
                        return device;
                    }
                } catch (MidiUnavailableException e) {
                    // skip unavailable device
                }
            }
        }
        return null;
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
