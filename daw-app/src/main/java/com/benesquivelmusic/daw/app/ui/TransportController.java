package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTransportIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.TransportIntentHandler;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.MidiRecorder;
import com.benesquivelmusic.daw.core.midi.RecordMidiNotesAction;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
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
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages transport playback actions and recording pipeline lifecycle — the
 * <em>production</em> {@link TransportIntentHandler} that every story-290
 * {@code TransportCommand} executes against (story 315 adopted the command
 * layer; Audio Engine Wiring Design Book §5.1, Stage 2).
 *
 * <p>Extracted from {@link MainController} to isolate transport behavior into
 * a dedicated, independently testable class. All dependencies are received via
 * constructor injection (story 293 retired the former {@code Host} callback-up
 * interface). Each intent method composes the pure VALIDATE→MUTATE→ANNOUNCE
 * cascade of {@link CoreTransportIntentHandler} with the engine-lifecycle and
 * status-bar side effects that belong to this surface; where a flow cannot
 * delegate (the recording pipeline mutates the transport itself), the ANNOUNCE
 * is published here at the point where the transport actually transitions.</p>
 *
 * <p>The time display is no longer written here: it is bound to the
 * beats→time projection by {@code TransportControlBinder.bindTimeDisplay}
 * (story 315 — a bound label throws on {@code setText}). Likewise the
 * play/record/loop {@code :active} pseudo-classes are driven solely by the
 * binder from {@code TransportVM} — this class never pokes them.</p>
 */
final class TransportController implements TransportIntentHandler {

    private static final Logger LOG = Logger.getLogger(TransportController.class.getName());

    private final DawProject project;
    private final AudioEngine audioEngine;
    private final UndoManager undoManager;
    private final NotificationBar notificationBar;
    private final Label statusLabel;
    private final Label statusBarLabel;
    private final Label recIndicator;
    /**
     * Play is disabled during RECORDING by {@link #updateStatus()}; Record's
     * blink appearance is restored on stop. Stop and Loop are no longer held
     * here — their state is entirely binder-driven (story 315).
     */
    private final Button playButton;
    private final Button recordButton;

    /**
     * Story 315 — the pure VALIDATE→MUTATE→ANNOUNCE cascade over this
     * controller's transport. Composed (not inherited) so the flows that align
     * with the neutral cascade delegate to it, while the flows with UI /
     * engine side effects (stop's recording finalize, record's pipeline) keep
     * their orchestration here and announce at the true transition points.
     */
    private final CoreTransportIntentHandler core;

    /**
     * Story 293 — direct collaborators replacing the retired {@code Host}
     * callback-up interface (CONTROL_SYNCHRONIZATION_DESIGN_BOOK §9). Although
     * this controller is reconstructed on every project load (so the project /
     * undo manager are passed by value), these collaborators are
     * {@link Supplier}/functional seams because they read state that may be
     * absent at construction time or change during the controller's life
     * (snap/grid from the view-navigation controller, count-in from the
     * metronome controller, latency from the audio-engine controller) — exactly
     * what the former {@code Host} closed over lazily. MIDI activity events are
     * produced on the MIDI receiver thread; this controller marshals to the FX
     * thread via {@link #postFx(Runnable)} before invoking {@code flashMidiActivity}.
     */
    private final BooleanSupplier snapEnabled;
    private final Supplier<GridResolution> gridResolution;
    private final Supplier<CountInMode> countInMode;
    private final Consumer<Track> flashMidiActivity;
    private final BooleanSupplier applyLatencyCompensation;
    private final Supplier<RoundTripLatency> reportedLatency;

    /** Opens the Audio category from an actionable stream-refusal toast. */
    private final Runnable openAudioSettings;

    /**
     * The FX-thread marshalling seam (story 289). Injected on the production
     * path by the composition root; {@code null} in a pure-unit context (the
     * compatibility constructor defaults it to {@link FxDispatcher#getDefault()},
     * which is unset when {@code DawApplication} never started). {@link #postFx}
     * tolerates the null and falls back to the static seam.
     */
    private final FxDispatcher fxDispatcher;

    private RecordingPipeline recordingPipeline;
    private final Map<Track, MidiRecorder> activeMidiRecorders = new LinkedHashMap<>();

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
    /** Cached container returned by {@link #createPreRollPostRollControls()}. */
    private HBox preRollControlsBox;

    /** Default bar count used by the pre/post-roll spinners (range 0–8). */
    static final int DEFAULT_BARS = 2;
    /** Maximum bar count allowed by the pre/post-roll spinners. */
    static final int MAX_BARS = 8;

    /**
     * Story 134 — Post-roll completion timer. Schedules
     * {@link #finishPostRoll()} after the configured post-roll duration
     * has elapsed. Kept as a field so a second stop request can cancel
     * a pending timer before rescheduling.
     */
    private PauseTransition postRollTimer;

    TransportController(DawProject project,
                        AudioEngine audioEngine,
                        UndoManager undoManager,
                        NotificationBar notificationBar,
                        Label statusLabel,
                        Label statusBarLabel,
                        Label recIndicator,
                        Button playButton,
                        Button recordButton,
                        BooleanSupplier snapEnabled,
                        Supplier<GridResolution> gridResolution,
                        Supplier<CountInMode> countInMode,
                        Consumer<Track> flashMidiActivity,
                        BooleanSupplier applyLatencyCompensation,
                        Supplier<RoundTripLatency> reportedLatency) {
        this(project, audioEngine, undoManager, notificationBar, statusLabel,
                statusBarLabel, recIndicator, playButton,
                recordButton, snapEnabled, gridResolution, countInMode,
                flashMidiActivity, applyLatencyCompensation, reportedLatency,
                () -> { },
                FxDispatcher.getDefault());
    }

    TransportController(DawProject project,
                        AudioEngine audioEngine,
                        UndoManager undoManager,
                        NotificationBar notificationBar,
                        Label statusLabel,
                        Label statusBarLabel,
                        Label recIndicator,
                        Button playButton,
                        Button recordButton,
                        BooleanSupplier snapEnabled,
                        Supplier<GridResolution> gridResolution,
                        Supplier<CountInMode> countInMode,
                        Consumer<Track> flashMidiActivity,
                        BooleanSupplier applyLatencyCompensation,
                        Supplier<RoundTripLatency> reportedLatency,
                        FxDispatcher fxDispatcher) {
        this(project, audioEngine, undoManager, notificationBar, statusLabel,
                statusBarLabel, recIndicator, playButton, recordButton,
                snapEnabled, gridResolution, countInMode, flashMidiActivity,
                applyLatencyCompensation, reportedLatency, () -> { }, fxDispatcher);
    }

    TransportController(DawProject project,
                        AudioEngine audioEngine,
                        UndoManager undoManager,
                        NotificationBar notificationBar,
                        Label statusLabel,
                        Label statusBarLabel,
                        Label recIndicator,
                        Button playButton,
                        Button recordButton,
                        BooleanSupplier snapEnabled,
                        Supplier<GridResolution> gridResolution,
                        Supplier<CountInMode> countInMode,
                        Consumer<Track> flashMidiActivity,
                        BooleanSupplier applyLatencyCompensation,
                        Supplier<RoundTripLatency> reportedLatency,
                        Runnable openAudioSettings,
                        FxDispatcher fxDispatcher) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.audioEngine = Objects.requireNonNull(audioEngine, "audioEngine must not be null");
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        this.statusLabel = Objects.requireNonNull(statusLabel, "statusLabel must not be null");
        this.statusBarLabel = Objects.requireNonNull(statusBarLabel, "statusBarLabel must not be null");
        this.recIndicator = Objects.requireNonNull(recIndicator, "recIndicator must not be null");
        this.playButton = Objects.requireNonNull(playButton, "playButton must not be null");
        this.recordButton = Objects.requireNonNull(recordButton, "recordButton must not be null");
        this.snapEnabled = Objects.requireNonNull(snapEnabled, "snapEnabled must not be null");
        this.gridResolution = Objects.requireNonNull(gridResolution, "gridResolution must not be null");
        this.countInMode = Objects.requireNonNull(countInMode, "countInMode must not be null");
        this.flashMidiActivity = Objects.requireNonNull(flashMidiActivity, "flashMidiActivity must not be null");
        this.applyLatencyCompensation = Objects.requireNonNull(
                applyLatencyCompensation, "applyLatencyCompensation must not be null");
        this.reportedLatency = Objects.requireNonNull(reportedLatency, "reportedLatency must not be null");
        this.openAudioSettings = Objects.requireNonNull(
                openAudioSettings, "openAudioSettings must not be null");
        // May be null in a pure-unit context; postFx() falls back to the
        // static seam, preserving today's behaviour byte-for-byte.
        this.fxDispatcher = fxDispatcher;
        // Story 315 — the composed VALIDATE→MUTATE→ANNOUNCE cascade over this
        // controller's (per-project-load) transport. It also owns the single
        // beats→frames conversion behind every TransportEvent frame field
        // (core.beatsToFrames); this class keeps no copy.
        this.core = new CoreTransportIntentHandler(
                project.getTransport(), project.getFormat().sampleRate());
    }

    /**
     * Posts {@code work} to the FX thread through the injected
     * {@link FxDispatcher} when present, else the static app-scoped seam — the
     * null branch reproduces today's behaviour exactly (story 289).
     */
    private void postFx(Runnable work) {
        FxDispatcher.runOnFx(fxDispatcher, work);
    }

    // ── Transport intent handlers (story-290 command layer, adopted in 315) ──

    /**
     * Starts playback from the current position (§5.2 "Start / Play").
     *
     * <p>VALIDATE mirrors the retired {@code onPlay} guard: a no-op while
     * already PLAYING (a stale Start intent) and while RECORDING (Stop is the
     * only way out of record). The Play-toggles-pause pairing is resolved by
     * {@link #togglePlayPause()} — the gesture layer raises the toggle intent
     * and never decides for itself (story 315 review).</p>
     */
    @Override
    public void start() {
        TransportState state = project.getTransport().getState();
        if (state == TransportState.PLAYING || state == TransportState.RECORDING) {
            return;
        }
        if (!startAudioOutputOrRefuse("Playback")) {
            return;
        }
        // MUTATE + ANNOUNCE (Transport.play() → TransportEvent.Started).
        core.start();
        updateStatus();
        statusBarLabel.setText("Playing...");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PLAY_CIRCLE, 12));
    }

    /**
     * Starts the callback that owns transport time, refusing the caller's
     * state transition on either a thrown open or a normally-returning open
     * that did not produce a RUNNING stream (story 317).
     */
    private boolean startAudioOutputOrRefuse(String intent) {
        boolean resuming = audioEngine.isStreamPaused();
        RuntimeException failure = null;
        try {
            audioEngine.startAudioOutput();
        } catch (RuntimeException openFailure) {
            failure = openFailure;
        }
        if (failure == null
                && audioEngine.isStreamOpen()
                && !audioEngine.isStreamPaused()) {
            return true;
        }

        if (failure != null) {
            LOG.log(Level.WARNING, "Failed to start audio output", failure);
        } else {
            LOG.warning("Audio output start returned without a running callback stream");
        }
        String message = streamRefusalMessage(intent, failure, resuming);
        updateStatus();
        statusBarLabel.setText(message);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.HEADPHONES, 12));
        notificationBar.show(NotificationLevel.ERROR, message,
                "Open Audio Settings", openAudioSettings);
        return false;
    }

    private String streamRefusalMessage(
            String intent, RuntimeException failure, boolean resuming) {
        StreamingProvision provision = audioEngine.getStreamingProvision();
        Optional<String> openBackend = audioEngine.openStreamBackendName();
        Optional<DeviceId> openDevice = audioEngine.openStreamDevice();
        String endpoint;
        if (openBackend.isPresent() && openDevice.isPresent()) {
            endpoint = "backend '" + openBackend.orElseThrow() + "' device '"
                    + openDevice.orElseThrow().name() + "' could not "
                    + (resuming ? "resume" : "start");
        } else if (provision != null) {
            endpoint = "backend '" + provision.requestedBackendName() + "' device '"
                    + provision.requestedDevice().name() + "' could not start";
        } else {
            endpoint = "no audio backend/device is configured";
        }
        String reason = failure == null || failure.getMessage() == null
                || failure.getMessage().isBlank()
                ? "no running audio callback was created"
                : failure.getMessage();
        return "Audio output refused for " + intent.toLowerCase(Locale.ROOT)
                + " — " + endpoint + ": " + reason
                + ". Reconnect the device or choose another in Audio Settings.";
    }

    /**
     * Pauses playback at the current position (§5.2; story 315). VALIDATE (in
     * {@link CoreTransportIntentHandler#pause()} and re-checked here for the UI
     * tail): only meaningful from PLAYING or RECORDING — a stale Pause intent
     * leaves both the transport and the status bar untouched.
     */
    @Override
    public void pause() {
        TransportState state = project.getTransport().getState();
        if (state != TransportState.PLAYING && state != TransportState.RECORDING) {
            return;
        }
        core.pause();
        audioEngine.pauseAudioOutput();
        updateStatus();
        statusBarLabel.setText("Paused");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PAUSE_CIRCLE, 12));
    }

    /**
     * Resolves the Play gesture here, where the authoritative
     * {@link Transport#getState()} lives (story 315 review): PLAYING pauses,
     * anything else starts. Delegating to this class's own {@link #pause()} /
     * {@link #start()} keeps the engine lifecycle and status-bar tails on both
     * halves — a direct {@code core.togglePlayPause()} would mutate the
     * transport without ever touching the audio output.
     *
     * <p>Every Play surface (toolbar button, spacebar, Performance Stage) now
     * raises the same {@code TogglePlayPauseCommand} into here, so none of them
     * can decide from the async {@code TransportVM} mirror and drop a click into
     * the one-FX-turn lag window (§2.8 "one path").</p>
     */
    @Override
    public void togglePlayPause() {
        if (project.getTransport().getState() == TransportState.PLAYING) {
            pause();
        } else {
            start();
        }
    }

    /**
     * Stops the transport (§5.2 "Stop"), finalizing any active recording and
     * honouring a configured post-roll.
     *
     * <p><strong>Double-stop gesture</strong> (story 315): a Stop while the
     * transport is already STOPPED <em>and nothing is recording</em> rewinds the
     * playhead to zero. This is a gesture-level semantic deliberately NOT in
     * {@link Transport#stop()} — internal callers (e.g.
     * {@code RecordingPipeline.stop()}) must be able to stop the transport
     * without a follow-up UI stop yanking recordings to zero. No
     * {@link TransportEvent.Stopped} is announced for the rewind: the transport
     * did not stop again.</p>
     *
     * <p>The rewind honours {@link Transport#isReturnToStartOnStop()} (story 315
     * review): the shipped {@code transport.returnToStartOnStop} preference
     * promises "when off, the playhead stays where it stopped", so with the
     * setting off a second Stop leaves the playhead — and the status bar —
     * alone.</p>
     *
     * <p><strong>Why the rewind is gated on "nothing is recording"</strong>: the
     * pipeline can be ACTIVE while the transport is still STOPPED.
     * {@code RecordingPipeline.start()} sets {@code active = true} at its top and
     * only calls {@code Transport.record()} at the very end, so a throw in
     * between (session creation, temp-file I/O, engine start) escapes
     * {@link #onRecord()} with the pipeline assigned and running and the
     * transport untouched. Returning early on that state would leak recording
     * sessions and temp files, leave per-track recording flags set and the REC
     * indicator lit forever — every further Stop hitting the same early return.
     * Falling through instead runs the finalize exactly once: on an
     * already-STOPPED transport {@link Transport#requestStop()} takes the
     * {@code stop()} branch, which is an idempotent no-op, so the tail below is
     * reached with nothing double-stopped.</p>
     *
     * <p>{@link TransportEvent.Stopped} carries the position at which the
     * transport actually stopped, captured <em>before</em> any internal
     * {@code Transport.stop()} (the recording pipeline's, or the one inside
     * {@code requestStop()}) rewinds the playhead to the play-start anchor. It
     * is announced only when the transport was actually rolling on entry: on the
     * aborted-record path above it never started, so announcing would put a
     * fiction — an unmatched Stopped — on the bus (the same {@code wasRolling}
     * rule {@link #finishPostRoll()} follows).</p>
     */
    @Override
    public void stop() {
        Transport transport = project.getTransport();
        boolean wasRolling = transport.getState() != TransportState.STOPPED;
        if (!wasRolling && !isRecordingInFlight()) {
            // Double-stop: second Stop returns to zero, if the preference says
            // so. Immediate while stopped — fires POSITION so the VM/playhead/
            // time display follow.
            if (transport.isReturnToStartOnStop()) {
                transport.setPositionInBeats(0.0);
                statusBarLabel.setText("Returned to start");
                statusBarLabel.setGraphic(IconNode.of(DawIcon.SKIP_BACK, 12));
            }
            return;
        }

        // Captured before the rewind-to-anchor inside any stop() below.
        long stoppedAtFrames = core.beatsToFrames(transport.getPositionInBeats());

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

        // Story 134 — use requestStop() so that a configured post-roll
        // keeps the transport running for postBars × barLength before
        // finally stopping. If no post-roll is configured (or disabled),
        // requestStop() is equivalent to stop().
        boolean enteredPostRoll = transport.requestStop();
        if (enteredPostRoll) {
            // Transport is still running in post-roll — schedule
            // finishPostRoll() after the configured duration. No Stopped
            // announce yet: the deferred stop announces in finishPostRoll().
            PreRollPostRoll prpr = transport.getPreRollPostRoll();
            double postRollBeats = prpr.postRollBeats(
                    transport.getTimeSignatureNumerator());
            double postRollSeconds = postRollBeats * (60.0 / transport.getTempo());
            statusBarLabel.setText(String.format(
                    "Post-roll: %.1f beats — tail playing…", postRollBeats));
            statusBarLabel.setGraphic(IconNode.of(DawIcon.PLAY_CIRCLE, 12));
            // Cancel any pending post-roll timer from a previous stop request
            if (postRollTimer != null) {
                postRollTimer.stop();
            }
            postRollTimer = new PauseTransition(
                    Duration.seconds(postRollSeconds));
            postRollTimer.setOnFinished(_ -> finishPostRoll());
            postRollTimer.play();
            updateStatus();
            return;
        }

        // ANNOUNCE (story 315): the transport actually stopped on this path —
        // either just now inside requestStop(), or earlier inside
        // RecordingPipeline.stop() (whose internal Transport.stop() makes the
        // requestStop() above an idempotent no-op). Suppressed when the
        // transport was already STOPPED on entry (the aborted-record finalize):
        // nothing transitioned, so nothing may be announced.
        if (wasRolling) {
            EventBusPublisher.publish(new TransportEvent.Stopped(stoppedAtFrames, Instant.now()));
        }
        audioEngine.stopAudioOutput();
        updateStatus();
        if (statusBarLabel.getText() == null
                || !stripCellSeparator(statusBarLabel.getText()).startsWith("Recording stopped")) {
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

    /**
     * Whether a recording is in flight and still needs finalizing — an active
     * audio pipeline or any live MIDI recorder. Read by {@link #stop()} to
     * decide between the double-stop rewind gesture and the full stop flow: the
     * pipeline can be active while the transport is STOPPED (see {@link #stop()}
     * for how {@code RecordingPipeline.start()} leaves that state behind when it
     * throws), and only the full flow finalizes it.
     */
    private boolean isRecordingInFlight() {
        return (recordingPipeline != null && recordingPipeline.isActive())
                || !activeMidiRecorders.isEmpty();
    }

    /**
     * Completes a post-roll window: stops the transport and audio engine and
     * resets UI state — the deferred half of the Stop intent. Called
     * automatically by the {@link #postRollTimer} after the configured
     * post-roll duration. Announces {@link TransportEvent.Stopped} with the
     * position at which the tail actually stopped, captured before
     * {@link Transport#finishPostRoll()} rewinds to the play-start anchor
     * (story 315); nothing is announced if the transport was already stopped.
     */
    private void finishPostRoll() {
        Transport transport = project.getTransport();
        long stoppedAtFrames = core.beatsToFrames(transport.getPositionInBeats());
        boolean wasRolling = transport.getState() != TransportState.STOPPED;
        transport.finishPostRoll();
        if (wasRolling) {
            EventBusPublisher.publish(new TransportEvent.Stopped(stoppedAtFrames, Instant.now()));
        }
        audioEngine.stopAudioOutput();
        updateStatus();
        statusBarLabel.setText("Stopped");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.POWER, 12));
        recordButton.setOpacity(1.0);
        recordButton.setStyle("");
        recIndicator.setVisible(false);
        recIndicator.setManaged(false);
    }

    /**
     * Toggles recording (§5.2 "Record"): while RECORDING, delegates to
     * {@link #stop()} (Stop is the only way out of record — which also runs
     * the recording finalize + Stopped announce); otherwise starts the
     * recording flow below.
     *
     * <p>ANNOUNCE (story 315): {@link TransportEvent.Started} is published at
     * the end of the start flow, and only once the transport is actually in
     * RECORDING — the audio path mutates the state inside
     * {@code RecordingPipeline.start()} (possibly deferred by a count-in), the
     * MIDI-only path via {@link Transport#record()}; announcing on the actual
     * transition keeps the bus truthful for both.</p>
     */
    @Override
    public void toggleRecord() {
        if (project.getTransport().getState() == TransportState.RECORDING) {
            stop();
            return;
        }
        onRecord();
    }

    private void onRecord() {
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
            ThemeManager.getDefault().applyTo(alert.getDialogPane());
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

        CountInMode countIn = countInMode.get();

        // MIDI-only recording still needs the output callback to drive time
        // and play existing tracks. Open it before creating any MidiRecorder:
        // on refusal nothing has started, no track is marked recording and
        // the transport remains STOPPED (story 317).
        if (armedAudioTracks.isEmpty()
                && !startAudioOutputOrRefuse("Recording")) {
            recIndicator.setVisible(false);
            recIndicator.setManaged(false);
            return;
        }

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
            recordingPipeline.setReportedLatency(reportedLatency.get());
            recordingPipeline.setApplyLatencyCompensation(applyLatencyCompensation.getAsBoolean());

            // Open audio I/O through the engine's provisioned device (story
            // 316): the settings-configured device identity is honoured on
            // every open — a per-track input device index no longer reaches
            // the open path. Per-track input CHANNEL routing stays on
            // Track.getInputRouting(); multi-device capture is story 326.
            //
            // The open runs BEFORE RecordingPipeline.start() (story 316
            // review). Story 316 made startAudioInputOutput() genuinely
            // ENFORCE capture — it walks the ladder with
            // CaptureRequirement.REQUIRED, refuses any rung that opens with
            // zero input channels, and throws when every rung fails or no
            // streaming provision is configured at all — so the throw below
            // is now the honest "nothing can be captured" signal it never
            // used to be, and starting the pipeline first meant acting on it
            // too late to matter.
            //
            // Reordering costs nothing recordable. RecordingPipeline.start()
            // is what installs the engine's recording callback
            // (audioEngine.setRecordingCallback), starts the engine and calls
            // transport.record(), and it computes the take's anchor
            // (recordingStartBeat — from the punch region when one is
            // enabled, otherwise the transport's position) inside itself. The
            // take therefore begins wherever the PIPELINE starts and the
            // anchor is read at that same moment, so opening first cannot
            // desynchronize the two. Blocks the device delivers between the
            // open and the pipeline start reach a recording callback that is
            // still null: they were never part of any take.
            try {
                audioEngine.startAudioInputOutput();
            } catch (RuntimeException e) {
                abortRecordingTake(outputDir, e);
                return;
            }
            recordingPipeline.start();
        }

        // Start MIDI recording for armed MIDI tracks
        if (!armedMidiTracks.isEmpty()) {
            startMidiRecording(armedMidiTracks, countIn);
        }

        // If no audio pipeline was started, the output stream was already
        // proved RUNNING above; now the transport may enter recording.
        if (armedAudioTracks.isEmpty()) {
            project.getTransport().record();
        }

        // ANNOUNCE (story 315) — only once the transport actually transitioned
        // (a count-in pipeline may not be RECORDING yet; announcing then would
        // put a fiction on the bus).
        if (project.getTransport().getState() == TransportState.RECORDING) {
            EventBusPublisher.publish(new TransportEvent.Started(
                    core.beatsToFrames(project.getTransport().getPositionInBeats()), Instant.now()));
        }
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

    /**
     * Abandons a record gesture whose capture device could not be opened
     * (story 316 review), leaving nothing behind and telling the user the take
     * did not start.
     *
     * <p><strong>The WHOLE take is abandoned, armed MIDI tracks included.</strong>
     * That is deliberate, not collateral damage. Silently continuing with the
     * MIDI half would hand back a take that is missing exactly the audio tracks
     * the user armed &mdash; the same dishonesty this review finding is about,
     * only harder to notice, because a partially-populated take looks like a
     * successful one until the audio lanes turn out to be empty. Aborting whole
     * keeps one rule the user can hold: either the take they armed started, or
     * no take started and they were told why.</p>
     *
     * <p>{@link #stop()} is deliberately NOT called here. Nothing started, so
     * there is nothing to finalize, and on the ordinary Record-from-stopped
     * gesture {@link #isRecordingInFlight()} is now false over a transport that
     * is still STOPPED &mdash; which is exactly the double-stop rewind
     * gesture's precondition. {@code stop()} would therefore yank the playhead
     * back to zero (honouring {@code Transport.isReturnToStartOnStop()}, which
     * defaults to true) and overwrite this message with "Returned to start".
     * It would not, for the record, announce a spurious
     * {@link TransportEvent.Stopped} &mdash; that publish is gated on the
     * transport having been rolling on entry &mdash; so the rewind, not a
     * phantom announce, is the reason to stay away from it.</p>
     *
     * <p>The REC indicator is cleared rather than merely left alone. It is lit
     * only by the tail of {@link #onRecord()} and cleared by {@link #stop()} /
     * {@link #finishPostRoll()}, so in the pipeline-active-over-STOPPED-transport
     * state {@link #stop()} documents it can still be burning when the user
     * presses Record again; leaving it lit over a take that never started is
     * the same lie in miniature. {@link #updateStatus()} then re-reads the
     * authoritative {@link Transport#getState()}, which this path has not
     * touched &mdash; on the audio-armed branch the only thing that moves the
     * transport is {@code RecordingPipeline.start()}, and it never ran &mdash;
     * so the status label and the Play enablement report whatever the
     * transport actually is.</p>
     *
     * @param outputDirectory the temp directory {@link #onRecord()} had just
     *                        created for this take's segments; still empty,
     *                        because the pipeline that would have populated it
     *                        never started
     * @param failure         the open failure, whose message names the actual
     *                        cause (story 316 makes it specific: every ladder
     *                        rung refused for want of capture channels, an
     *                        ambiguous device selection, or no backend
     *                        configured at all)
     */
    private void abortRecordingTake(Path outputDirectory, RuntimeException failure) {
        LOG.log(Level.WARNING,
                "Recording aborted — the capture device could not be opened", failure);
        recordingPipeline = null;
        deleteEmptyTakeDirectory(outputDirectory);

        String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        String message = "Recording aborted — no take was started: " + reason;
        statusBarLabel.setText(message);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
        notificationBar.show(NotificationLevel.ERROR, message);
        recIndicator.setVisible(false);
        recIndicator.setManaged(false);
        updateStatus();
    }

    /**
     * Best-effort removal of the take directory created for a record gesture
     * that then aborted (story 316 review). It is still empty: nothing has
     * been written beneath it, because {@code RecordingPipeline.start()} is
     * what resolves the per-track paths under it and it never ran. A plain
     * delete therefore suffices and no recursive walk is warranted.
     *
     * <p>A failure here is logged at FINE and swallowed on purpose: an
     * undeleted empty temp directory is housekeeping, and escalating it would
     * replace the device message the user actually needs with a filesystem
     * complaint about a directory they never asked for.</p>
     */
    private static void deleteEmptyTakeDirectory(Path outputDirectory) {
        try {
            Files.deleteIfExists(outputDirectory);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE,
                    () -> "Could not delete the empty recording directory "
                            + outputDirectory + ": " + e);
        }
    }

    @Override
    public void skipBack() {
        // While PLAYING/RECORDING the seek is queued and lands at the next
        // block boundary (story 315); when idle it applies immediately. Either
        // way the bound time display follows the VM playhead — no direct write.
        // Absolute target (zero), so no seek base is read at all.
        project.getTransport().setPositionInBeats(0.0);
        statusBarLabel.setText("Skipped to beginning");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.SKIP_BACK, 12));
    }

    @Override
    public void skipForward() {
        Transport transport = project.getTransport();
        double jump = 4.0 * transport.getTimeSignatureNumerator();
        // RELATIVE seek — compose against the pending seek target, not the
        // committed position (story 315 review). While the RT clock owns the
        // transport a seek sits in a single-slot, last-writer-wins queue and the
        // committed position does not move until the next block boundary; two
        // Skip Forwards inside one block would both add the jump to the same
        // base and the queue would keep only the second, landing one jump ahead
        // instead of two. getSeekTargetInBeats() returns the queued target when
        // one is pending, so successive relative seeks accumulate.
        double newPosition = transport.getSeekTargetInBeats() + jump;
        if (snapEnabled.getAsBoolean()) {
            newPosition = SnapQuantizer.quantize(newPosition, gridResolution.get(),
                    transport.getTimeSignatureNumerator());
        }
        transport.setPositionInBeats(newPosition);
        statusBarLabel.setText("Skipped forward");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.SKIP_FORWARD, 12));
    }

    /**
     * Sets the initial tempo (§5.2 "Set tempo") — a pure delegate to the
     * composed cascade: VALIDATE throws {@link IllegalArgumentException} for
     * an out-of-range/NaN tempo (the contract the tempo-field binder's
     * snap-back relies on), MUTATE fires the core signal, ANNOUNCE publishes
     * {@link TransportEvent.TempoChanged}.
     *
     * @param bpm the requested tempo in beats per minute
     */
    @Override
    public void setTempo(double bpm) {
        core.setTempo(bpm);
    }

    /**
     * Toggles loop playback (§5.2 "Toggle loop"). MUTATE + ANNOUNCE delegate
     * to the composed cascade; the Loop button's {@code :active} pseudo-class
     * is driven solely by {@code TransportControlBinder.bindLoop} observing
     * {@code TransportVM.loopRegion} — the imperative poke (and the retired
     * {@code syncLoopButtonState()}) are gone, so a loop defined from ANY
     * surface (toolbar, ruler Shift-click/drag) lights the button the same way
     * (story 315).
     */
    @Override
    public void toggleLoop() {
        core.toggleLoop();
        String loopState = project.getTransport().isLoopEnabled() ? "Loop: ON" : "Loop: OFF";
        statusBarLabel.setText(loopState);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.LOOP, 12));
        LOG.fine(loopState);
    }

    // ── Pre-Roll / Post-Roll (Story 134) ─────────────────────────────────────

    /**
     * Starts playback with pre-roll applied (Story 134). The transport
     * is seeked back by the configured pre-roll bar count and playback
     * begins. If pre-roll is disabled or {@code preBars == 0}, this is
     * equivalent to {@link #start()}.
     *
     * <p>During the pre-roll window the transport's
     * {@link Transport#isInputCaptureGated()} flag is {@code true} —
     * the recording pipeline reads it to suppress capture so the user
     * hears context but no input is recorded.</p>
     *
     * <p>VALIDATE (story 315 review): a no-op while RECORDING — Stop is the
     * only way out of record, exactly as in {@link #start()}. The guard sits
     * before the engine call so neither the audio output nor the status bar is
     * touched by a rejected intent; {@link Transport#playWithPreRoll()} itself
     * is permissive and would otherwise drop the transport out of record
     * without finalizing the active recording pipeline.</p>
     *
     * <p>ANNOUNCE (story 315 review): {@link TransportEvent.Started} is
     * published here too, carrying the <em>post-rewind</em> position — this was
     * the one start path that announced nothing, so a bus consumer pairing
     * Started with Stopped saw an unmatched Stopped whenever the user started
     * with pre-roll. {@link Transport#playWithPreRoll()} always transitions the
     * transport to PLAYING (re-anchoring and rewinding when a pre-roll is
     * configured), so once VALIDATE passes the announce always follows the
     * actual transition.</p>
     */
    @Override
    public void playWithPreRoll() {
        // Story 315 review — VALIDATE before touching the engine (mirrors
        // start()): Shift+Space while RECORDING must not leave record.
        if (project.getTransport().getState() == TransportState.RECORDING) {
            return;
        }
        if (!startAudioOutputOrRefuse("Pre-roll playback")) {
            return;
        }
        double shift = project.getTransport().playWithPreRoll();
        EventBusPublisher.publish(new TransportEvent.Started(
                core.beatsToFrames(project.getTransport().getPositionInBeats()), Instant.now()));
        updateStatus();
        if (shift > 0) {
            statusBarLabel.setText(String.format(
                    "Pre-roll: %.1f beats (monitoring only)…", shift));
        } else {
            statusBarLabel.setText("Playing...");
        }
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PLAY_CIRCLE, 12));
    }

    /**
     * Toggles the pre-roll feature independently of post-roll. When
     * toggling on and {@code preBars} is zero, seeds with
     * {@value #DEFAULT_BARS}. When toggling off, sets {@code preBars}
     * to zero. The {@code enabled} flag is derived: {@code true} iff
     * either side has a non-zero bar count.
     */
    void onTogglePreRoll() {
        Transport transport = project.getTransport();
        PreRollPostRoll current = transport.getPreRollPostRoll();
        boolean preActive = current.preBars() > 0;
        int newPre = preActive ? 0 : Math.max(DEFAULT_BARS, current.preBars());
        boolean newEnabled = newPre > 0 || current.postBars() > 0;
        transport.setPreRollPostRoll(
                new PreRollPostRoll(newPre, current.postBars(), newEnabled));
        syncPreRollControls();
        statusBarLabel.setText("Pre-roll: " + (!preActive ? "ON" : "OFF"));
    }

    /**
     * Toggles the post-roll feature independently of pre-roll. When
     * toggling on and {@code postBars} is zero, seeds with
     * {@value #DEFAULT_BARS}. When toggling off, sets {@code postBars}
     * to zero. The {@code enabled} flag is derived: {@code true} iff
     * either side has a non-zero bar count.
     */
    void onTogglePostRoll() {
        Transport transport = project.getTransport();
        PreRollPostRoll current = transport.getPreRollPostRoll();
        boolean postActive = current.postBars() > 0;
        int newPost = postActive ? 0 : Math.max(DEFAULT_BARS, current.postBars());
        boolean newEnabled = current.preBars() > 0 || newPost > 0;
        transport.setPreRollPostRoll(
                new PreRollPostRoll(current.preBars(), newPost, newEnabled));
        syncPreRollControls();
        statusBarLabel.setText("Post-roll: " + (!postActive ? "ON" : "OFF"));
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
     * than once returns the <em>same</em> cached {@link HBox} instance,
     * avoiding the JavaFX restriction that a {@code Node} can only have
     * one parent.</p>
     *
     * @return an {@link HBox} containing the pre-roll and post-roll
     *         toggles and spinners
     */
    HBox createPreRollPostRollControls() {
        if (preRollControlsBox != null) {
            return preRollControlsBox;
        }
        preRollToggle = new ToggleButton("Pre-Roll");
        preRollToggle.getStyleClass().addAll("dawg-button", "size-transport", "pre-roll-button");
        preRollToggle.setGraphic(IconNode.of(DawIcon.REWIND, 12));
        preRollToggle.setTooltip(new Tooltip(
                "Pre-Roll: seek back by N bars before playback (Story 134)"));
        preRollToggle.setOnAction(_ -> onTogglePreRoll());

        postRollToggle = new ToggleButton("Post-Roll");
        postRollToggle.getStyleClass().addAll("dawg-button", "size-transport", "post-roll-button");
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

        preRollControlsBox = new HBox(4, preRollToggle, preRollSpinner,
                postRollToggle, postRollSpinner);
        preRollControlsBox.setAlignment(Pos.CENTER);
        preRollControlsBox.getStyleClass().add("toolbar-button-group");
        return preRollControlsBox;
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
        // Derive the enabled flag: true iff either side has a non-zero
        // bar count. This keeps enabled state consistent when the user
        // zeroes out a spinner.
        boolean enabled = preBars > 0 || postBars > 0;
        transport.setPreRollPostRoll(
                new PreRollPostRoll(preBars, postBars, enabled));
        syncPreRollControls();
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

            // Wire MIDI activity indicator — flash the track strip on each
            // event. The recorder fires on the MIDI receiver thread, so the
            // flash is marshalled onto the FX thread through the FxDispatcher
            // seam (story 289; Control Synchronization Design Book §1.5, §4.5).
            recorder.addEventListener(_ -> postFx(
                    () -> flashMidiActivity.accept(track)));

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
                    || !stripCellSeparator(statusBarLabel.getText()).startsWith("Recording stopped")) {
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

        // Smooth fade-in so the status label change feels polished —
        // decorative transitional motion, gated by global Reduce Motion
        // (story 279). With Reduce Motion on the label is shown at once.
        if (MotionManager.getDefault().isAnimationAllowed()) {
            statusLabel.setOpacity(0.0);
            FadeTransition fadeIn = new FadeTransition(Duration.millis(200), statusLabel);
            fadeIn.setFromValue(0.0);
            fadeIn.setToValue(1.0);
            fadeIn.play();
        } else {
            statusLabel.setOpacity(1.0);
        }

        // Play is a toggle (UI Design Book §5.1) — it stays enabled while
        // playing so the user can pause. Record stays enabled during
        // recording so the :active pseudo-class (danger fill) is not
        // undermined by the :disabled 0.35 opacity; start() is a no-op
        // while already recording because of the state guard. Stop stays
        // enabled while STOPPED so the double-stop rewind-to-zero gesture
        // remains reachable (story 315). The play/record/loop :active
        // pseudo-classes are the TransportControlBinder's alone — the
        // binder observes TransportVM and is the single writer (§4.4).
        playButton.setDisable(state == TransportState.RECORDING);
    }

    /**
     * Strips the leading {@link StatusCellLabel#CELL_SEPARATOR} so callers
     * can use {@code startsWith} on the raw cell text (story 274).
     */
    private static String stripCellSeparator(String text) {
        return text.startsWith(StatusCellLabel.CELL_SEPARATOR)
                ? text.substring(StatusCellLabel.CELL_SEPARATOR.length())
                : text;
    }
}
