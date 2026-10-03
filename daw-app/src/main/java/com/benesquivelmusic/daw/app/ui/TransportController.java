package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.recording.SessionInputSelection;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTransportIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.TransportIntentHandler;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.StreamStartFailure;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.MidiRecorder;
import com.benesquivelmusic.daw.core.midi.RecordMidiNotesAction;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.EarlySeal;
import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.recording.StopSealFailure;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
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
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
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

    /**
     * Story 323 (D6): an audio take streams to disk under the project's own
     * {@code audio/takes} folder and never to the OS temp directory, so a
     * project that has no directory yet cannot record audio. Shown as the
     * ERROR toast and the status-bar text when {@link #onRecord()} refuses.
     */
    static final String NO_PROJECT_FOLDER_MESSAGE =
            "Recording needs a project folder — save the project first";

    /**
     * PR #978 review 5391920205 (F2): the status-bar text while Record's take
     * is being prepared — its take directory allocated, then its files
     * created by the take's capture thread — before capture begins. The REC
     * indicator stays off meanwhile: nothing is being recorded yet.
     */
    static final String TAKE_PREPARING_MESSAGE = "Preparing the take…";

    /**
     * PR #978 review 5391920205 (F2): the status-bar text when Stop, Record
     * pressed again, or the end of a post-roll — the deferred half of a
     * Stop — cancels a take that was still being prepared (Recording
     * Reliability book §5.2, toggle semantics). Nothing was recorded.
     */
    static final String RECORDING_CANCELLED_MESSAGE = "Recording cancelled — no take was started";

    /**
     * PR #978 review 5391920205 (F1): the status-bar text from a Stop until
     * the take is published — the take's capture thread is sealing its
     * segments and writing its manifest, and the clips appear once it has
     * terminated.
     */
    static final String TAKE_FINISHING_MESSAGE = "Recording stopped — finishing the take on disk…";

    /**
     * How long after a Stop a take that has still not been published is
     * reported as {@link #TAKE_STILL_WRITING_MESSAGE}.
     */
    static final java.time.Duration TAKE_STILL_WRITING_DELAY = java.time.Duration.ofSeconds(2);

    /**
     * Story 323 review: a take that has still not been published
     * {@link #TAKE_STILL_WRITING_DELAY} after its Stop. Shown as the WARNING
     * toast and, in place of {@link #TAKE_FINISHING_MESSAGE}, as the
     * status-bar text; the clips are published when the take's capture
     * thread has finished.
     */
    static final String TAKE_STILL_WRITING_MESSAGE =
            "Recording stopped — the take is still being written to disk; its clips will appear when it finishes";

    /**
     * Replaces {@link #TAKE_FINISHING_MESSAGE} or
     * {@link #TAKE_STILL_WRITING_MESSAGE} in the status bar when that take
     * produced no clip.
     */
    static final String TAKE_WRITTEN_WITHOUT_CLIPS_MESSAGE =
            "Recording stopped — the take has been written to disk; it holds no audio";

    /**
     * Replaces {@link #TAKE_FINISHING_MESSAGE} or
     * {@link #TAKE_STILL_WRITING_MESSAGE} in the status bar when a
     * {@linkplain #retire() retired} controller's take has finished: the
     * short form of {@link #takeOfAReplacedProjectMessage}.
     */
    static final String TAKE_OF_A_REPLACED_PROJECT_STATUS =
            "Recording stopped — the take was not added to any project: the project it was recorded in was replaced";

    /**
     * Story 323 review: Record while the previous take is still being
     * written, or while the files of a cancelled or failed start are still
     * being removed (Recording Reliability book §5.2 — Record is valid only
     * from IDLE, and FINALIZING returns to IDLE once the seal and the
     * manifest are complete). Shown as the WARNING toast and the status-bar
     * text.
     */
    static final String RECORD_WHILE_WRITING_MESSAGE =
            "Record is unavailable until the last take has finished writing to disk";

    private final DawProject project;
    private final AudioEngine audioEngine;
    private final UndoManager undoManager;
    private final NotificationBar notificationBar;
    /**
     * Story 322 — the ONE session-level input device recording opens. Consulted
     * at record start to warn when an armed track's per-track input choice
     * disagrees with it (Audio Engine Wiring Design Book §5.6 "Per-track input
     * device"; multi-device capture itself is story 326).
     */
    private final SessionInputSelection sessionInputSelection;
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

    /**
     * The worker of the most recent record-start input check (story 322 fix
     * round, S7): the device enumeration behind the session-input mismatch
     * WARNING runs off the FX thread; only the comparison (over the FX-owned
     * track list) and the toast are marshalled back. Kept so a test can wait
     * for it; {@code null} until the first audio take.
     */
    private volatile Thread sessionInputCheck;

    /**
     * The audio take being recorded (RECORDING): set by the readiness turn
     * ({@link #onTakeReady}) once capture has begun, cleared by
     * {@link #stop()}. FX thread.
     */
    private RecordingPipeline recordingPipeline;
    /**
     * PR #978 review 5391920205 (F2) — the audio take Record started that has
     * not begun capture yet (PREPARING): set by {@link #onRecord()}, cleared
     * when capture begins, when the start fails, and when Stop, Record
     * pressed again, the end of a post-roll ({@link #finishPostRoll()}) or
     * {@link #retire()} cancels it. While set,
     * {@link #isRecordingInFlight()} is {@code true}. FX thread.
     */
    private PendingStart pendingStart;
    /**
     * PR #978 review 5391920205 (F2) — a start that was cancelled or failed
     * and whose files are still being removed: its capture thread, if it got
     * one, has not terminated yet, or the removal of the take directory it
     * allocated has not run yet. Cleared on the FX turn that follows that
     * removal. While set, {@link #onRecord()} refuses and
     * {@link #isTakeBeingWritten()} is {@code true} — FINALIZING, as for
     * {@link #writingPipeline}. FX thread.
     */
    private PendingStart abandonedStart;
    /**
     * Story 323 review — the take whose Stop has run and whose clips are not
     * published yet: set by {@link #stop()}, cleared on the FX turn that
     * publishes the take once its capture thread has terminated
     * ({@link #finishWrittenTake}). While set, {@link #onRecord()} refuses
     * and {@link #isTakeBeingWritten()} is {@code true}. FX thread.
     */
    private RecordingPipeline writingPipeline;
    /**
     * Story 323 review — set by {@link #retire()} once {@code MainController}
     * has replaced this controller with the next project's. FX thread.
     */
    private boolean retired;
    /**
     * Where the controller's own storage work for a take runs: allocating
     * its take directory under {@code audio/takes}, and removing the take
     * directory of a start that was cancelled or failed, if it is empty
     * ({@link #deleteEmptyTakeDirectory}). Production: a
     * new virtual thread per task ({@link #onAVirtualThread}), never the FX
     * thread; replaced only by {@link #setStorageExecutorForTest}.
     */
    private Executor storageExecutor = TransportController::onAVirtualThread;
    /**
     * What is done to a take's pipeline after it is built and before its
     * {@code prepare()} — nothing in production; replaced only by
     * {@link #setPipelineSetupForTest}.
     */
    private Consumer<RecordingPipeline> pipelineSetup = _ -> { };
    /**
     * How a stopped take is completed once its capture thread has
     * terminated — {@code RecordingPipeline::completeStop}; replaced only by
     * {@link #setTakeCompletionForTest}.
     */
    private TakeCompletion takeCompletion = RecordingPipeline::completeStop;
    /**
     * How the still-writing warning is scheduled after a Stop — a
     * {@link PauseTransition} ({@link #afterOnFx}); replaced only by
     * {@link #setStillWritingDelayForTest}.
     */
    private FxDelay stillWritingDelay = TransportController::afterOnFx;
    /**
     * How a take's early-seal signal is read — {@code RecordingPipeline::earlySeal};
     * replaced only by {@link #setEarlySealSignalForTest}.
     */
    private EarlySealSignal earlySealSignal = RecordingPipeline::earlySeal;
    /**
     * How the outcome of the seal a take's Stop requested is read —
     * {@code RecordingPipeline::stopSealFailure}; replaced only by
     * {@link #setStopSealOutcomeForTest}.
     */
    private StopSealOutcome stopSealOutcome = RecordingPipeline::stopSealFailure;
    /**
     * How {@link #startMidiRecording} finds an armed MIDI track's input
     * device by its name — {@link #resolveMidiDevice}; replaced only by
     * {@link #setMidiInputDeviceResolverForTest}.
     */
    private Function<String, MidiDevice> midiInputDeviceResolver = TransportController::resolveMidiDevice;
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
     * a pending timer before rescheduling, and {@link #retire()} can stop
     * it; dropped when it fires.
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
                        Supplier<RoundTripLatency> reportedLatency,
                        SessionInputSelection sessionInputSelection) {
        this(project, audioEngine, undoManager, notificationBar, statusLabel,
                statusBarLabel, recIndicator, playButton,
                recordButton, snapEnabled, gridResolution, countInMode,
                flashMidiActivity, applyLatencyCompensation, reportedLatency,
                sessionInputSelection,
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
                        SessionInputSelection sessionInputSelection,
                        FxDispatcher fxDispatcher) {
        this(project, audioEngine, undoManager, notificationBar, statusLabel,
                statusBarLabel, recIndicator, playButton, recordButton,
                snapEnabled, gridResolution, countInMode, flashMidiActivity,
                applyLatencyCompensation, reportedLatency, sessionInputSelection,
                () -> { }, fxDispatcher);
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
                        SessionInputSelection sessionInputSelection,
                        Runnable openAudioSettings,
                        FxDispatcher fxDispatcher) {
        this.sessionInputSelection = Objects.requireNonNull(
                sessionInputSelection, "sessionInputSelection must not be null");
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
     * only way out of record) — and while a take is being prepared, which is
     * on its way to RECORDING. The Play-toggles-pause pairing is resolved by
     * {@link #togglePlayPause()} — the gesture layer raises the toggle intent
     * and never decides for itself (story 315 review).</p>
     */
    @Override
    public void start() {
        TransportState state = project.getTransport().getState();
        if (pendingStart != null || state == TransportState.PLAYING || state == TransportState.RECORDING) {
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
        String message = streamRefusalMessage(intent, failure);
        updateStatus();
        statusBarLabel.setText(message);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.HEADPHONES, 12));
        notificationBar.show(NotificationLevel.ERROR, message,
                "Open Audio Settings", openAudioSettings);
        return false;
    }

    private String streamRefusalMessage(String intent, RuntimeException failure) {
        Optional<StreamStartFailure> attribution = failure == null
                ? Optional.empty()
                : audioEngine.takeFailedStreamStart(failure);
        String endpoint;
        if (attribution.isPresent()) {
            StreamStartFailure attempt = attribution.orElseThrow();
            String operation = switch (attempt.operation()) {
                case START -> "start";
                case RESUME -> "resume";
            };
            endpoint = "backend '" + attempt.endpoint().backendName() + "' device '"
                    + attempt.endpoint().device().name() + "' could not " + operation;
        } else {
            endpoint = "no audio backend/device was bound to this failed attempt";
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
        if (!hasGraphInstruments()) audioEngine.pauseAudioOutput();
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
     *
     * <p>A no-op while a take is being prepared, as Play is while RECORDING.</p>
     */
    @Override
    public void togglePlayPause() {
        if (pendingStart != null) {
            return;
        }
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
     * {@code RecordingPipeline.requestStop()}) must be able to stop the transport
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
     * pipeline can be ACTIVE while the transport is still STOPPED — an internal
     * caller that stopped the transport underneath a running pipeline (count-in
     * does not defer {@code Transport.record()} today; story 328 owns
     * count-in). (Before story 323 a throw inside the pipeline's start was a
     * second way in; its start is now all-or-nothing — {@code prepare()},
     * then {@code beginCapture()} — and a failed start is abandoned
     * ({@link #abandonStart}), so no active pipeline is left behind.)
     * Returning early on that
     * state would leak recording sessions and segment files, leave per-track
     * recording flags set and the REC indicator lit forever — every further
     * Stop hitting the same early return.
     * Falling through instead runs the finalize exactly once: on an
     * already-STOPPED transport {@link Transport#requestStop()} takes the
     * {@code stop()} branch, which is an idempotent no-op, so the tail below is
     * reached with nothing double-stopped.</p>
     *
     * <p>{@link TransportEvent.Stopped} carries the position at which the
     * transport actually stopped, captured <em>before</em> any internal
     * {@code Transport.stop()} (the one inside the recording pipeline's
     * {@code requestStop()}, or the one inside the transport's own
     * {@code requestStop()}) rewinds the playhead to the play-start anchor. It
     * is announced only when the transport was actually rolling on entry: on the
     * aborted-record path above it never started, so announcing would put a
     * fiction — an unmatched Stopped — on the bus (the same {@code wasRolling}
     * rule {@link #finishPostRoll()} follows).</p>
     *
     * <p><strong>A take being prepared</strong> (PR #978 review 5391920205,
     * F2). A Stop while Record's take is still being prepared cancels it
     * ({@link #cancelPendingStartByUser}): nothing was captured, the cancel
     * writes {@link #RECORDING_CANCELLED_MESSAGE} into the status bar, and
     * the removal of the take's files is arranged off the FX thread
     * ({@link #cancelPendingStart}). Over a STOPPED transport the Stop then
     * closes the output stream Record opened, as every other Stop does
     * ({@link #stopAudioOutputWhenIdle}), and that is the whole Stop — no
     * rewind, nothing announced, the transport untouched. Over a transport
     * that is not STOPPED the Stop then goes on as an ordinary Stop of that
     * playback. When it enters no post-roll — the transport is PAUSED, or no
     * post-roll is configured — it stops the transport and hands the stream
     * to {@link #stopAudioOutputWhenIdle} at once, and the cancel's text
     * stays in the status bar. Over a PLAYING transport with a post-roll
     * configured it plays the tail instead: the "Post-roll: …" text
     * replaces the cancel's, and the end of that post-roll
     * ({@link #finishPostRoll}) stops the transport, hands the stream to
     * {@link #stopAudioOutputWhenIdle} and writes "Stopped" unless the
     * status bar shows what became of a recording
     * ({@link #statusShowsARecordingOutcome}), which the tail's text does
     * not. The end of a post-roll, the deferred half of a Stop, cancels a
     * take being prepared the same way ({@link #finishPostRoll}).</p>
     *
     * <p><strong>Stop never waits for the take</strong> (PR #978 review
     * 5391920205, F1). {@code RecordingPipeline.requestStop()} removes the
     * recording callback, clears the recording flags, stops the transport and
     * asks the take's capture thread to seal the take, and returns that
     * thread's termination signal at once; this Stop never joins it. The rest
     * of this Stop runs at once (MIDI stop, the transport's
     * {@code requestStop()}, the Stopped announce, the UI reset, the REC
     * indicator), and the status bar says
     * {@link #TAKE_FINISHING_MESSAGE}; no post-roll follows a recorded take:
     * the pipeline has already stopped the transport, so the transport's
     * {@code requestStop()} finds it stopped. When the capture thread has
     * terminated, its signal hands the rest to the FX thread through
     * {@link #postFx} ({@link #publishWhenWritten}): on that later turn
     * {@link #finishWrittenTake} completes the take and publishes its clips
     * ({@link #publishRecordedTake}) — or, if that fails, the failure is
     * logged and shown as an ERROR toast. If the take has still not been
     * published {@link #TAKE_STILL_WRITING_DELAY} after this Stop, a WARNING
     * toast says {@link #TAKE_STILL_WRITING_MESSAGE}
     * ({@link #warnTakeStillWriting}). Until the take is published
     * {@link #onRecord()} refuses, and {@link #isTakeBeingWritten()} tells
     * {@code ProjectLifecycleController} to refuse the in-app doors that
     * replace the open project; a Stop meanwhile is an ordinary Stop with no
     * recording in flight (see {@link #isRecordingInFlight()}) and never calls
     * the pipeline again.</p>
     *
     * <p>The "Record Audio" undo entry of a take is pushed when its clips are
     * published, not when Stop was pressed: it lands on top of any edit made
     * while the take was being written, and — as every
     * {@link UndoManager#execute} does — clears the redo stack. The project
     * is marked dirty then too, and not before: a take still being written is
     * not in the project yet. A controller {@linkplain #retire() retired}
     * before that turn runs publishes nothing at all.</p>
     *
     * <p><strong>A take the capture thread sealed early</strong> (story 323
     * review). When the capture thread seals the take on its own — its
     * disk-headroom watch reported the disk exhausted, or writing or
     * capturing the take failed — this Stop runs for it on an FX turn
     * ({@link #stopWhenSealedEarly}), unless a Stop
     * got there first or the controller was {@linkplain #retire() retired}.</p>
     *
     * <p><strong>A take whose finalisation did not end cleanly</strong> (story
     * 323 review): the capture thread sealed it early, or a lane threw in
     * the seal this Stop requested ({@link StopSealFailure}) — a segment
     * whose own seal failed is then left as its {@code .part} file.
     * The FX turn that publishes such a take ({@link #finishWrittenTake})
     * reads how its finalisation ended once
     * ({@link #finalizationFailureReport}), publishes its clips, the "Record
     * Audio" undo entry and the dirty mark as always, but instead of the
     * SUCCESS toast and its status text it shows that report once, as the
     * ERROR toast — after this Stop's MIDI toast — and as the status text:
     * {@link #takeSealedEarlyMessage}, or {@link #stopSealFailedMessage}.</p>
     */
    @Override
    public void stop() {
        Transport transport = project.getTransport();
        boolean wasRolling = transport.getState() != TransportState.STOPPED;
        if (pendingStart != null) {
            cancelPendingStartByUser();
            if (!wasRolling) {
                stopAudioOutputWhenIdle();
                return;
            }
        }
        if (!wasRolling && !isRecordingInFlight()) {
            stopAudioOutputWhenIdle();
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

        // Stop the audio take, if one is recording: the seal is requested and
        // the take is published on a later FX turn, once its capture thread
        // has terminated. Nothing here waits for that thread.
        if (recordingPipeline != null && recordingPipeline.isActive()) {
            RecordingPipeline stopping = recordingPipeline;
            recordingPipeline = null;
            CompletionStage<Void> written = requestTakeStop(stopping);
            statusBarLabel.setText(TAKE_FINISHING_MESSAGE);
            publishWhenWritten(stopping, written);
            stillWritingDelay.after(TAKE_STILL_WRITING_DELAY, () -> warnTakeStillWriting(stopping));
        }

        // Finalize MIDI recording if any MIDI recorders are active. Its toast
        // comes before the audio take's SUCCESS or report, which the later
        // publication shows.
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
        // RecordingPipeline.requestStop() (whose internal Transport.stop()
        // makes the requestStop() above an idempotent no-op). Suppressed when
        // the transport was already STOPPED on entry (the aborted-record
        // finalize): nothing transitioned, so nothing may be announced.
        if (wasRolling) {
            EventBusPublisher.publish(new TransportEvent.Stopped(stoppedAtFrames, Instant.now()));
        }
        stopAudioOutputWhenIdle();
        updateStatus();
        if (!statusShowsARecordingOutcome()) {
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
     * Asks {@code pipeline} to stop its take and returns its capture thread's
     * termination signal, without waiting for it. When a step of the
     * pipeline's one-shot stop throws (a transport listener, say), the seal
     * has been requested all the same: the failure is logged, and a repeat
     * request — which repeats none of those steps — hands back the same
     * signal, so the take is still published. FX thread.
     */
    private static CompletionStage<Void> requestTakeStop(RecordingPipeline pipeline) {
        try {
            return pipeline.requestStop();
        } catch (RuntimeException oneShotFailure) {
            LOG.log(Level.WARNING, "A step of the stop of the take under " + pipeline.getTakeDirectory()
                    + " failed; the take is sealed all the same", oneShotFailure);
            return pipeline.requestStop();
        }
    }

    /**
     * Whether the status bar shows what became of a recording — a stopped
     * take ("Recording stopped …") or a cancelled one — which the end of a
     * Stop leaves in place instead of writing "Stopped". FX thread.
     */
    private boolean statusShowsARecordingOutcome() {
        String text = statusBarLabel.getText();
        if (text == null) {
            return false;
        }
        String cell = stripCellSeparator(text);
        return cell.startsWith("Recording stopped") || cell.equals(RECORDING_CANCELLED_MESSAGE);
    }

    private void stopAudioOutputWhenIdle() {
        if (!hasGraphInstruments()) audioEngine.stopAudioOutput();
    }

    /**
     * {@link #stopAudioOutputWhenIdle()}, unless the transport is rolling —
     * PLAYING or RECORDING — when the stream is left running. Run when
     * an audio take Record started ends before capture began: Record pressed
     * again cancels it ({@link #toggleRecord()}), its take directory could not
     * be allocated, or its pipeline could not be built
     * ({@link #onTakeDirectoryAllocated}). FX thread.
     */
    private void stopAudioOutputUnlessRolling() {
        TransportState state = project.getTransport().getState();
        if (state != TransportState.PLAYING && state != TransportState.RECORDING) {
            stopAudioOutputWhenIdle();
        }
    }

    private boolean hasGraphInstruments() {
        var mixer = project.getMixer();
        // Retain the callback for bypassed instruments so unbypassing while stopped can audition immediately.
        return mixer.getChannels().stream().anyMatch(com.benesquivelmusic.daw.core.mixer.MixerChannel::hasInstrumentInsert)
                || mixer.getReturnBuses().stream().anyMatch(com.benesquivelmusic.daw.core.mixer.MixerChannel::hasInstrumentInsert)
                || mixer.getMasterChannel().hasInstrumentInsert();
    }

    /**
     * Whether a recording is in flight and still needs finalizing — an audio
     * take being prepared ({@link #pendingStart}), an active audio pipeline
     * or any live MIDI recorder. Read by {@link #stop()} to decide between
     * the double-stop rewind gesture and the full stop flow: the pipeline can
     * be active while the transport is STOPPED (see {@link #stop()}: an
     * internal caller can stop the transport underneath a running pipeline),
     * and only the full flow finalizes it.
     * {@code MainController} also feeds the current controller's answer to
     * {@code ProjectLifecycleController}, which refuses the in-app doors that
     * replace the open project while it is {@code true} (story 323 review);
     * quitting the application does not ask (story 333). FX thread.
     *
     * <p>A take still being written to disk ({@link #writingPipeline}) does not
     * count (story 323 review). Its Stop has already run — callback removed,
     * flags cleared, transport stopped, seal requested — and what remains is
     * finished by the FX turn its capture thread's termination posts, never by
     * another Stop. So a Stop over a stopped transport while the take is being
     * written is the ordinary double-stop gesture; the rewind moves only the
     * playhead, and the take's clips are anchored where the take started.</p>
     */
    boolean isRecordingInFlight() {
        return pendingStart != null
                || (recordingPipeline != null && recordingPipeline.isActive())
                || !activeMidiRecorders.isEmpty();
    }

    /**
     * Whether Record's audio take is being prepared — its take directory
     * being allocated or its files being created — and capture has not begun
     * (PREPARING). Package-visible so a test can wait for the start to
     * settle. FX thread.
     */
    boolean isPreparingTake() {
        return pendingStart != null;
    }

    /**
     * The one publication of a stopped take's clips, on the FX turn that
     * completes the take ({@link #finishWrittenTake}): registers the
     * "Record Audio" undo action over the pipeline's recorded clips and marks
     * the project dirty, so the unsaved-changes prompt asks for the Save that
     * writes the clips' references into {@code project.daw} — as
     * {@code MainController}'s undo and redo mark it after their own
     * mutation. Whether the take was sealed early or the seal its Stop
     * requested failed makes no difference here: its clips are in the project
     * either way. The caller then shows the SUCCESS toast
     * ({@link #showTakePublished}) or the report of a finalisation that did
     * not end cleanly ({@link #finalizationFailureReport}). Nothing when the
     * take produced no clip: the pipeline's {@code completeStop()} then added
     * nothing to any track. FX thread.
     */
    private void publishRecordedTake(RecordingPipeline pipeline, List<AudioClip> recordedClips) {
        if (recordedClips.isEmpty()) {
            return;
        }
        // Register undo action for the recorded clips
        Map<Track, AudioClip> clipMap = Map.copyOf(pipeline.getRecordedClips());
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
        project.markDirty();
    }

    /**
     * The status text and SUCCESS toast of a take whose finalisation ended
     * cleanly and that produced {@code clipCount} clips; nothing when it
     * produced none. FX thread.
     */
    private void showTakePublished(int clipCount) {
        if (clipCount == 0) {
            return;
        }
        String message = "Recording stopped — " + clipsCreatedText(clipCount);
        statusBarLabel.setText(message);
        notificationBar.show(NotificationLevel.SUCCESS, message);
    }

    /**
     * Remembers {@code pipeline} as the take still being written and, when
     * its capture thread has terminated ({@code written}, the signal the
     * pipeline's {@code requestStop()} returned), posts
     * {@link #finishWrittenTake} to the FX thread. The dependent registered
     * here only posts — it may run on the capture thread as its last act, or
     * at once on this thread if the thread has already terminated; the work
     * itself always runs on a later FX turn. FX thread.
     */
    private void publishWhenWritten(RecordingPipeline pipeline, CompletionStage<Void> written) {
        writingPipeline = pipeline;
        written.whenComplete((_, _) -> postFx(() -> finishWrittenTake(pipeline)));
    }

    /**
     * The FX turn that publishes a stopped take, once its capture thread has
     * terminated: completes the take on that same pipeline
     * ({@code RecordingPipeline.completeStop()}, through {@link TakeCompletion},
     * which builds the clips without repeating the Stop's one-shot steps) and
     * publishes them through {@link #publishRecordedTake}: the "Record Audio"
     * undo entry, the dirty mark, and the SUCCESS toast and status text of a
     * take whose finalisation ended cleanly. A take that produced no clip and
     * whose finalisation ended cleanly only takes back the status bar's
     * {@link #TAKE_FINISHING_MESSAGE} or {@link #TAKE_STILL_WRITING_MESSAGE},
     * if it still says one of them, and leaves the project as it was. A take
     * whose finalisation did not end cleanly — sealed early by the capture
     * thread, or a lane that threw in the seal the Stop requested — is
     * reported here: the report of {@link #finalizationFailureReport} as the
     * ERROR toast and the status text, with or without clips. A failure of
     * the completion itself is logged SEVERE and shown as an ERROR toast.
     * After a publication or a failure, Record is available again. A
     * controller {@linkplain #retire() retired} by then does none of this —
     * it publishes nothing, marks nothing dirty and reports no seal: it only
     * reports where the take's files are, and takes back the status bar's
     * finishing or still-writing text in the same way. FX thread.
     */
    private void finishWrittenTake(RecordingPipeline pipeline) {
        if (writingPipeline == pipeline) {
            writingPipeline = null;
        }
        if (retired) {
            // The project the take was recorded in has been replaced.
            // Completing the take is not needed for the files: the capture
            // thread has terminated, so it writes nothing more, and
            // completeStop() would then only build clips onto the tracks of a
            // project that is no longer open.
            String message = takeOfAReplacedProjectMessage(project.getName(), pipeline.getTakeDirectory());
            LOG.warning(message);
            notificationBar.show(NotificationLevel.WARNING, message);
            // Nothing on the replacement path rewrites the shared status bar,
            // so its promise that the clips will appear is taken back here.
            if (statusBarSaysTheTakeIsBeingFinished()) {
                statusBarLabel.setText(TAKE_OF_A_REPLACED_PROJECT_STATUS);
            }
            return;
        }
        List<AudioClip> clips;
        try {
            clips = takeCompletion.complete(pipeline);
        } catch (RuntimeException failure) {
            LOG.log(Level.SEVERE, "Could not finish the take under " + pipeline.getTakeDirectory()
                    + " after it was written", failure);
            String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage();
            String message = "Recording could not be finished — " + reason + "; the take's files stay under "
                    + ProjectManager.AUDIO_DIR_NAME + "/" + TakeDirectories.TAKES_DIR_NAME + "/"
                    + pipeline.getTakeDirectory().getFileName();
            statusBarLabel.setText(message);
            notificationBar.show(NotificationLevel.ERROR, message);
            return;
        }
        int clipCount = clips.size();
        Optional<String> failureReport = finalizationFailureReport(pipeline, clipCount);
        if (clipCount == 0 && failureReport.isEmpty() && statusBarSaysTheTakeIsBeingFinished()) {
            statusBarLabel.setText(TAKE_WRITTEN_WITHOUT_CLIPS_MESSAGE);
        }
        publishRecordedTake(pipeline, clips);
        failureReport.ifPresentOrElse(this::reportTakeFinalizationFailure, () -> showTakePublished(clipCount));
    }

    /**
     * The delayed half of a Stop's status: when {@code pipeline}'s take has
     * still not been published {@link #TAKE_STILL_WRITING_DELAY} after its
     * Stop, a WARNING toast says {@link #TAKE_STILL_WRITING_MESSAGE}, and so
     * does the status bar if it still says {@link #TAKE_FINISHING_MESSAGE}.
     * Nothing once the take has been published, or once the controller has
     * been {@linkplain #retire() retired}. Scheduled by {@link #stop()}
     * through {@link FxDelay}; runs on the FX thread and waits for nothing.
     */
    private void warnTakeStillWriting(RecordingPipeline pipeline) {
        if (retired || writingPipeline != pipeline) {
            return;
        }
        LOG.warning(() -> "The take under " + pipeline.getTakeDirectory() + " is still being written "
                + TAKE_STILL_WRITING_DELAY.toMillis() + " ms after its Stop; its clips are published when it is");
        if (statusBarStillSays(TAKE_FINISHING_MESSAGE)) {
            statusBarLabel.setText(TAKE_STILL_WRITING_MESSAGE);
        }
        notificationBar.show(NotificationLevel.WARNING, TAKE_STILL_WRITING_MESSAGE);
    }

    /** Whether the status bar still shows {@code text} (its cell separator aside). FX thread. */
    private boolean statusBarStillSays(String text) {
        return statusBarLabel.getText() != null
                && stripCellSeparator(statusBarLabel.getText()).equals(text);
    }

    /**
     * Whether the status bar still says that a stopped take is being
     * finished — {@link #TAKE_FINISHING_MESSAGE} or
     * {@link #TAKE_STILL_WRITING_MESSAGE}. FX thread.
     */
    private boolean statusBarSaysTheTakeIsBeingFinished() {
        return statusBarStillSays(TAKE_FINISHING_MESSAGE) || statusBarStillSays(TAKE_STILL_WRITING_MESSAGE);
    }

    /**
     * The WARNING a {@linkplain #retire() retired} controller logs and shows
     * once a take it stopped has been written: it names the project the take
     * was recorded in and the take's directory, which lies under that
     * project's {@code audio/takes}. It claims no order between the
     * replacement and the end of the writing — the retirement is read when
     * the turn that would publish the take runs, which may be after the take
     * had finished — only that the replacement came before the take could be
     * added.
     */
    static String takeOfAReplacedProjectMessage(String projectName, Path takeDirectory) {
        return "The take recorded in '" + projectName + "' was not added to any project because that project"
                + " was replaced before the take could be added — its files are under " + takeDirectory;
    }

    /**
     * Story 323 review — the auto-Stop of {@code pipeline}'s take: when the
     * capture thread seals that take on its own (disk exhaustion, a write
     * failure), {@link #stopTakeSealedEarly} is posted to the FX thread
     * through {@link #postFx}. The dependent registered here only posts: it
     * runs on the capture thread as that thread signals, or at once on this
     * thread if the signal has completed already, and the Stop runs on a
     * later FX turn. One registration per take, held by that take's pipeline
     * and dropped with it; a {@linkplain #retire() retired} controller's
     * posted turn does nothing. FX thread.
     */
    private void stopWhenSealedEarly(RecordingPipeline pipeline) {
        earlySealSignal.of(pipeline).thenAccept(_ -> postFx(() -> stopTakeSealedEarly(pipeline)));
    }

    /**
     * The FX turn of the auto-Stop: runs {@link #stop()} — the user's Stop,
     * whole: callback removed, recording flags cleared, transport stopped and
     * Stopped announced, MIDI recorders stopped, REC indicator hidden, and the
     * take handed to the turn that publishes it once its capture thread has
     * terminated — clips and the "Record Audio" undo entry published, the
     * project marked dirty, the early seal reported —
     * while {@code pipeline} is still this controller's active recording
     * pipeline and the controller is not {@linkplain #retire() retired}.
     * Otherwise it does nothing: a Stop got there first, and the turn that
     * publishes the take reports the seal, or the take belongs to a project
     * that was replaced. FX thread.
     */
    private void stopTakeSealedEarly(RecordingPipeline pipeline) {
        if (retired || recordingPipeline != pipeline || !pipeline.isActive()) {
            return;
        }
        LOG.info(() -> "Stopping the recording: its capture thread sealed the take under "
                + pipeline.getTakeDirectory() + " early");
        stop();
    }

    /**
     * The early seal of {@code pipeline}'s take, if its capture thread sealed
     * it on its own. Read by the turn that publishes the take
     * ({@link #finishWrittenTake}), once the take's completion has returned
     * the clips: its capture thread has terminated then, and that thread
     * completes the signal, if at all, before it terminates. FX thread.
     */
    private Optional<EarlySeal> earlySealOf(RecordingPipeline pipeline) {
        return Optional.ofNullable(earlySealSignal.of(pipeline).toCompletableFuture().getNow(null));
    }

    /**
     * Story 323 review — how the finalisation of {@code pipeline}'s take
     * ended, read once by the turn that publishes the take, once the take's
     * completion has returned the clips: its capture thread has
     * terminated then, and before it terminated that thread completed the
     * early-seal signal, if it ever will, and recorded the failure of the
     * seal the Stop requested, if there was one. The report of a take the
     * capture thread sealed early ({@link #takeSealedEarlyMessage});
     * otherwise the report of a lane that threw in the seal the Stop
     * requested ({@link #stopSealFailedMessage}); otherwise empty — the take
     * was finalised cleanly, and the caller shows the SUCCESS. The core never
     * reports both for one take: a take sealed early has no failure of the
     * Stop's seal. Reads only; blocks on nothing. FX thread.
     *
     * @param clipCount the clips the Stop created from the take
     */
    private Optional<String> finalizationFailureReport(RecordingPipeline pipeline, int clipCount) {
        Path takeDirectory = pipeline.getTakeDirectory();
        return earlySealOf(pipeline)
                .map(seal -> takeSealedEarlyMessage(seal, clipCount, takeDirectory))
                .or(() -> stopSealOutcome.of(pipeline)
                        .map(failure -> stopSealFailedMessage(failure, clipCount, takeDirectory)));
    }

    /**
     * Logs the report of a take whose finalisation did not end cleanly and
     * shows it as the status text and the ERROR toast. FX thread.
     */
    private void reportTakeFinalizationFailure(String message) {
        LOG.warning(message);
        statusBarLabel.setText(message);
        notificationBar.show(NotificationLevel.ERROR, message);
    }

    /**
     * Story 323 review — the ERROR toast and status text of a take the
     * capture thread sealed on its own ({@link EarlySeal}), shown once by
     * the turn that publishes the take. It names the cause — free disk space
     * below the headroom watch's floor; free space that could not be read; a
     * failed write to disk, for a throwable that is an {@link IOException} or
     * has one in its cause chain, with the reason the innermost one gives
     * ({@link #ioReason}); or, for any other throwable, a failed capture,
     * with its type and, when it has one, its message — and what was kept or
     * left: the clips created from the audio recorded before it, or that none
     * had been recorded; or, when one or more segments could not be finished
     * — a seal that failed, or an empty segment that could not be discarded
     * ({@link EarlySeal#everySegmentSealed()} is {@code false}) — that one or
     * more segment files that could not be finished are left in the take's
     * folder, with the clips created or, when there are none, after saying
     * that no audio had been recorded. It never says that those files hold
     * audio, and never calls them kept, saved or sealed.
     *
     * @param seal          the early seal the capture thread signalled
     * @param clipCount     the clips the Stop created from the take
     * @param takeDirectory the take's directory under the project's {@code audio/takes}
     */
    static String takeSealedEarlyMessage(EarlySeal seal, int clipCount, Path takeDirectory) {
        String cause = switch (seal) {
            case EarlySeal.DiskExhausted disk when disk.freeSpaceUnknown() ->
                    "the free disk space could not be read";
            case EarlySeal.DiskExhausted disk -> "free disk space fell below " + sizeText(disk.floorBytes());
            case EarlySeal.WriteFailed write -> writeFailedText(write.failure());
        };
        String kept;
        if (!seal.everySegmentSealed()) {
            String left = segmentFilesLeftText(takeDirectory);
            kept = clipCount > 0
                    ? left + " (" + clipsCreatedText(clipCount) + ")"
                    : "no audio had been recorded before that, and " + left;
        } else if (clipCount > 0) {
            kept = "the audio recorded before that is kept (" + clipsCreatedText(clipCount) + ")";
        } else {
            kept = "no audio had been recorded before that";
        }
        return "Recording stopped — " + cause + "; " + kept;
    }

    /**
     * Story 323 review — the ERROR toast and status text of a take whose
     * Stop requested a seal in which a lane threw ({@link StopSealFailure}),
     * shown once by the turn that publishes the take, instead of the SUCCESS.
     * It names the cause as a failure of finishing the take, not of capturing
     * it, which had ended: finishing it on disk, for a throwable that is an
     * {@link IOException} or has one in its cause chain, with the reason the
     * innermost one gives ({@link #ioReason}); otherwise, with no word of the
     * disk (an {@link OutOfMemoryError}, say), finishing it, with the
     * throwable's type and, when it has one, its message. Then what is
     * left: when one or more segments could not be finished
     * ({@link StopSealFailure#everySegmentSealed()} is
     * {@code false}), that one or more segment files that could not be
     * finished are left in the take's folder, with the clips created or,
     * when there are none, after saying that no audio had been recorded — it
     * never says that those files hold audio, and never calls them kept,
     * saved or sealed; when every segment was finished all the same (what
     * threw kept no segment from being finished), that the audio recorded is
     * kept, with the clips created, or that no audio had been recorded.
     *
     * @param failure       the failure of the seal the Stop requested
     * @param clipCount     the clips the Stop created from the take
     * @param takeDirectory the take's directory under the project's {@code audio/takes}
     */
    static String stopSealFailedMessage(StopSealFailure failure, int clipCount, Path takeDirectory) {
        String left;
        if (!failure.everySegmentSealed()) {
            String unfinished = segmentFilesLeftText(takeDirectory);
            left = clipCount > 0
                    ? unfinished + " (" + clipsCreatedText(clipCount) + ")"
                    : "no audio had been recorded, and " + unfinished;
        } else if (clipCount > 0) {
            left = "the audio recorded is kept (" + clipsCreatedText(clipCount) + ")";
        } else {
            left = "no audio had been recorded";
        }
        return "Recording stopped — " + finishingFailedText(failure.failure()) + "; " + left;
    }

    /** {@code 1 clip created}, {@code 2 clips created}. */
    private static String clipsCreatedText(int clipCount) {
        return clipCount + " clip" + (clipCount > 1 ? "s" : "") + " created";
    }

    /** That one or more segment files that could not be finished are left in the take's folder under {@code audio/takes}. */
    private static String segmentFilesLeftText(Path takeDirectory) {
        return "one or more segment files that could not be finished are left under "
                + ProjectManager.AUDIO_DIR_NAME + "/" + TakeDirectories.TAKES_DIR_NAME + "/"
                + takeDirectory.getFileName();
    }

    /**
     * The cause of a {@link StopSealFailure}: finishing the take on disk
     * failed when {@code failure} is an {@link IOException} or has one in its
     * cause chain, with the reason of the innermost one; otherwise finishing
     * the take failed, which blames nothing on the disk, with the throwable's
     * type and, when it has one, its message.
     */
    private static String finishingFailedText(Throwable failure) {
        IOException ioFailure = innermostIOException(failure);
        return ioFailure != null
                ? "finishing the take on disk failed (" + ioReason(ioFailure) + ")"
                : "finishing the take failed (" + shortDescription(failure) + ")";
    }

    /** {@code 64 MiB} for a whole number of mebibytes, otherwise the count of bytes. */
    private static String sizeText(long bytes) {
        long mebibyte = 1L << 20;
        return bytes % mebibyte == 0 ? bytes / mebibyte + " MiB" : bytes + " bytes";
    }

    /**
     * The cause of an {@link EarlySeal.WriteFailed}: a failed write to disk
     * when {@code failure} is an {@link IOException} or has one in its cause
     * chain — every {@link java.io.UncheckedIOException} has — with the
     * reason of the innermost one; otherwise a failed capture, which blames
     * nothing on the disk (an {@link OutOfMemoryError} from growing the RAM
     * mirror, say), with the throwable's type and, when it has one, its
     * message.
     */
    private static String writeFailedText(Throwable failure) {
        IOException ioFailure = innermostIOException(failure);
        return ioFailure != null
                ? "writing the take to disk failed (" + ioReason(ioFailure) + ")"
                : "capturing the take failed (" + shortDescription(failure) + ")";
    }

    /**
     * The innermost {@link IOException} in {@code failure}'s cause chain,
     * {@code failure} itself included; {@code null} if there is none. A
     * cause seen before ends the walk, so a cyclic chain ends it too.
     */
    private static IOException innermostIOException(Throwable failure) {
        IOException innermost = null;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof IOException ioFailure) {
                innermost = ioFailure;
            }
        }
        return innermost;
    }

    /**
     * Why an I/O operation failed, in the exception's own words: a
     * {@link FileSystemException}'s reason — its message is often just a
     * path — and any other exception's message; its type when it gives
     * neither.
     */
    private static String ioReason(IOException failure) {
        String reason = failure instanceof FileSystemException fileSystemFailure
                ? fileSystemFailure.getReason()
                : failure.getMessage();
        return reason == null || reason.isBlank() ? typeName(failure) : reason;
    }

    /** The throwable's type, and its message when it has one. */
    private static String shortDescription(Throwable failure) {
        String type = typeName(failure);
        String message = failure.getMessage();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    /** The throwable's simple class name, or its full name for an anonymous class, which has no simple name. */
    private static String typeName(Throwable failure) {
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.isEmpty() ? failure.getClass().getName() : simpleName;
    }

    /**
     * Whether a take this controller stopped is still being written to disk —
     * its Stop has run and the turn that publishes it
     * ({@link #finishWrittenTake}) has not run yet — or a start that was
     * cancelled or failed is still having its files removed
     * ({@link #abandonedStart}): the FINALIZING state of Recording Reliability
     * book §5.2, which returns to IDLE only once the seal has completed.
     * {@code MainController} feeds the current controller's answer to
     * {@code ProjectLifecycleController}, which refuses the in-app doors that
     * replace the open project while it is {@code true}; quitting the
     * application does not ask (story 333). FX thread.
     */
    boolean isTakeBeingWritten() {
        return writingPipeline != null || abandonedStart != null;
    }

    /**
     * Retires this controller. {@code MainController} builds a new
     * {@code TransportController} every time it rebuilds for a project it has
     * loaded, created, imported or restored, and retires the one it replaces,
     * whose project is no longer open. javafx-application-design §15 flags
     * "Unbounded listener registration without matching removal in
     * {@code dispose()}"; the dependents this controller registers on a take
     * cannot be removed from it, so retiring neutralises them. The auto-Stop
     * of a take the capture thread seals early ({@link #stopWhenSealedEarly})
     * is retired: its posted FX turn does nothing — the take's pipeline is
     * left as it is, and nothing is published or shown. So is the turn that
     * publishes a take this controller stopped:
     * when that take's capture thread has terminated,
     * {@link #finishWrittenTake} neither completes the take nor publishes
     * anything — no clip on the replaced project's tracks,
     * no undo entry, no dirty mark, no SUCCESS toast, no report of an early
     * seal or of a failed seal — and instead logs and shows the
     * WARNING of {@link #takeOfAReplacedProjectMessage}, and replaces a status
     * bar that still says {@link #TAKE_FINISHING_MESSAGE} or
     * {@link #TAKE_STILL_WRITING_MESSAGE} with
     * {@link #TAKE_OF_A_REPLACED_PROJECT_STATUS}; the delayed still-writing
     * warning does nothing. Since
     * {@code ProjectLifecycleController} refuses the in-app doors that replace
     * the open project while {@link #isRecordingInFlight()} or
     * {@link #isTakeBeingWritten()}, this is the fallback for a take being
     * written when a replacement bypasses that guard. A take still being
     * prepared is cancelled ({@link #cancelPendingStart}): capture never
     * begins, and the removal of its files is arranged off the FX thread;
     * nothing is shown. For a recording that has begun
     * it is no fallback: retiring stops nothing — an audio take's
     * pipeline stays active with its recording callback installed on the
     * engine, a MIDI recorder stays started on its input, and nothing
     * publishes their clips or notes — and its only effects are that an audio
     * take's auto-Stop does nothing and that the end of a post-roll it was
     * recorded in never comes (below). A post-roll still running is retired
     * too: its timer is stopped and dropped, and the end of a post-roll does
     * nothing once this controller is retired ({@link #finishPostRoll}). The
     * replaced project's transport is left as it is — PLAYING, in its
     * post-roll — as the transport of a project replaced while it is simply
     * PLAYING is left PLAYING: neither this controller nor
     * {@code MainController} stops it. So the engine's output stream, which
     * the next project's controller shares, and the status bar and REC
     * indicator, which it shares too, are not touched; a take that
     * controller is preparing keeps its stream. Quitting the
     * application retires nothing: it replaces no controller. Nothing else is
     * retired: a record-start input check still waiting for its device list
     * finishes as it would have. Cannot be undone. FX thread.
     */
    void retire() {
        retired = true;
        if (pendingStart != null) {
            cancelPendingStart();
        }
        if (postRollTimer != null) {
            postRollTimer.stop();
            postRollTimer = null;
        }
    }

    /**
     * How {@link #finishWrittenTake} completes a stopped take once its
     * capture thread has terminated. Production:
     * {@code RecordingPipeline::completeStop}. A test seam for the
     * completions this module cannot cause through the real pipeline — one
     * that throws, or one that hands back no clip.
     */
    @FunctionalInterface
    interface TakeCompletion {
        List<AudioClip> complete(RecordingPipeline pipeline);
    }

    /**
     * Test seam: replaces how a stopped take is completed (see
     * {@link TakeCompletion}). FX thread, before the take is stopped.
     */
    void setTakeCompletionForTest(TakeCompletion completion) {
        takeCompletion = Objects.requireNonNull(completion, "completion must not be null");
    }

    /**
     * How {@link #stop()} schedules the still-writing warning
     * ({@link #warnTakeStillWriting}) {@link #TAKE_STILL_WRITING_DELAY} after
     * a Stop. Production: {@link #afterOnFx}, a {@link PauseTransition}
     * played on the FX thread, which runs {@code action} on a later FX pulse
     * and never blocks the FX thread. A test seam, so that a test runs the
     * action when it chooses instead of when a clock says.
     */
    @FunctionalInterface
    interface FxDelay {
        void after(java.time.Duration delay, Runnable action);
    }

    /**
     * Test seam: replaces how the still-writing warning is scheduled (see
     * {@link FxDelay}). FX thread, before the take is stopped.
     */
    void setStillWritingDelayForTest(FxDelay delay) {
        stillWritingDelay = Objects.requireNonNull(delay, "delay must not be null");
    }

    /**
     * Test seam: replaces where the controller's own storage work for a take
     * runs (see {@link #storageExecutor}), so that a test can hold the
     * allocation of a take directory. FX thread, before the take starts.
     */
    void setStorageExecutorForTest(Executor executor) {
        storageExecutor = Objects.requireNonNull(executor, "executor must not be null");
    }

    /**
     * Test seam: {@code setup} runs on each take's pipeline once it is built
     * and before its {@code prepare()} — so that a test can configure the
     * real pipeline through its public setters (its clock, say). FX thread,
     * before the take starts.
     */
    void setPipelineSetupForTest(Consumer<RecordingPipeline> setup) {
        pipelineSetup = Objects.requireNonNull(setup, "setup must not be null");
    }

    /**
     * How {@link #stopWhenSealedEarly} and the turn that publishes a take
     * read the take's early-seal signal. Production:
     * {@code RecordingPipeline::earlySeal}. A test seam — the core's ways of
     * sealing a take early (an injected disk-headroom watch, a writer set to
     * fail) are not reachable from this module.
     */
    @FunctionalInterface
    interface EarlySealSignal {
        CompletionStage<EarlySeal> of(RecordingPipeline pipeline);
    }

    /**
     * Test seam: replaces how a take's early-seal signal is read (see
     * {@link EarlySealSignal}). FX thread, before the take starts.
     */
    void setEarlySealSignalForTest(EarlySealSignal signal) {
        earlySealSignal = Objects.requireNonNull(signal, "signal must not be null");
    }

    /**
     * How the turn that publishes a take reads the outcome of the seal the
     * take's Stop requested ({@link #finalizationFailureReport}). Production:
     * {@code RecordingPipeline::stopSealFailure}, read once the take's
     * completion has returned the clips. A test seam for the failures this module
     * cannot cause on disk — an {@link Error}, a failure after every
     * segment was sealed, a reason of the test's choosing; a real one, a
     * file already at a segment's sealed path, fails the seal through the
     * production wiring.
     */
    @FunctionalInterface
    interface StopSealOutcome {
        Optional<StopSealFailure> of(RecordingPipeline pipeline);
    }

    /**
     * Test seam: replaces how the outcome of a take's Stop seal is read (see
     * {@link StopSealOutcome}). FX thread, before the take is stopped.
     */
    void setStopSealOutcomeForTest(StopSealOutcome outcome) {
        stopSealOutcome = Objects.requireNonNull(outcome, "outcome must not be null");
    }

    /**
     * Test seam: replaces how {@link #startMidiRecording} finds an armed MIDI
     * track's input device by its name (production:
     * {@link #resolveMidiDevice}, over the system's MIDI devices), so that a
     * test can record MIDI without MIDI hardware. FX thread, before the take
     * starts.
     */
    void setMidiInputDeviceResolverForTest(Function<String, MidiDevice> resolver) {
        midiInputDeviceResolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    /**
     * Completes a post-roll window: stops the transport and audio engine and
     * resets UI state — the deferred half of the Stop intent. Called
     * automatically by the {@link #postRollTimer} after the configured
     * post-roll duration. Announces {@link TransportEvent.Stopped} with the
     * position at which the tail actually stopped, captured before
     * {@link Transport#finishPostRoll()} rewinds to the play-start anchor
     * (story 315); nothing is announced if the transport was already stopped.
     *
     * <p>Being the deferred half of a Stop, it first cancels an audio take
     * that Record started inside the tail and that is still being prepared,
     * exactly as a Stop cancels it ({@link #cancelPendingStartByUser}).
     * Otherwise that take's readiness turn would still call
     * {@code RecordingPipeline.beginCapture()} after this has stopped the
     * transport and, without an instrument insert, closed the output stream:
     * the transport would go RECORDING, and the take be announced, over a
     * stream that delivers no block. As at the end of a Stop, a status bar
     * that says what became of a recording
     * ({@link #statusShowsARecordingOutcome}) is left as it is instead of
     * saying "Stopped".</p>
     *
     * <p>The cancel and the stop happen only while this controller's tail is
     * still running: the controller is not {@linkplain #retire() retired},
     * and its transport is still in the post-roll a Stop entered
     * ({@link Transport#isInPostRoll()}). The transport leaves that post-roll
     * when the tail ends some other way — for example, Shift+Space
     * ({@link #playWithPreRoll}) or Pause then Play replays, and a Stop over
     * the paused tail, a Stop once the post-roll has been turned off, or the
     * Stop of an audio or mixed take recorded inside the tail stops the
     * transport — but not on a Pause or a Record inside the tail. While a
     * post-roll is still configured, a Stop over the playing tail, or the
     * Stop of a MIDI-only take recorded inside it, does not take the
     * transport out of post-roll either: that Stop enters a new post-roll,
     * stopping this timer and starting the new tail's own. A timer
     * whose tail already ended, or whose controller was retired, does
     * nothing when it fires: no take is cancelled, no transport stopped,
     * nothing announced, and the output stream, the status bar and the REC
     * indicator are left alone. Whatever it does, the timer is
     * dropped.</p>
     */
    private void finishPostRoll() {
        postRollTimer = null;
        Transport transport = project.getTransport();
        if (retired || !transport.isInPostRoll()) {
            return;
        }
        if (pendingStart != null) {
            cancelPendingStartByUser();
        }
        long stoppedAtFrames = core.beatsToFrames(transport.getPositionInBeats());
        boolean wasRolling = transport.getState() != TransportState.STOPPED;
        transport.finishPostRoll();
        if (wasRolling) {
            EventBusPublisher.publish(new TransportEvent.Stopped(stoppedAtFrames, Instant.now()));
        }
        stopAudioOutputWhenIdle();
        updateStatus();
        if (!statusShowsARecordingOutcome()) {
            statusBarLabel.setText("Stopped");
        }
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
     * <p>While an audio take is being prepared (PREPARING), Record pressed
     * again cancels it (Recording Reliability book §5.2, toggle semantics:
     * "second press stops cleanly"), as Stop does — see
     * {@link #cancelPendingStartByUser}. Over a transport that is not rolling
     * (STOPPED or PAUSED) it then closes the output stream Record opened, as
     * a Stop does ({@link #stopAudioOutputWhenIdle}); over a rolling one
     * (PLAYING) it leaves playback, and the stream, running.</p>
     *
     * <p>ANNOUNCE (story 315): {@link TransportEvent.Started} is published at
     * the end of the start flow, and only once the transport is actually in
     * RECORDING — the audio path mutates the state inside
     * {@code RecordingPipeline.beginCapture()}, on the readiness turn
     * ({@link #onTakeReady}), the MIDI-only path via
     * {@link Transport#record()}; announcing on the actual transition keeps the
     * bus truthful for both, and declines only when an internal caller stopped
     * the transport underneath a running pipeline (count-in does not defer
     * {@code Transport.record()} today; story 328 owns count-in).</p>
     */
    @Override
    public void toggleRecord() {
        if (pendingStart != null) {
            cancelPendingStartByUser();
            stopAudioOutputUnlessRolling();
            return;
        }
        if (project.getTransport().getState() == TransportState.RECORDING) {
            stop();
            return;
        }
        onRecord();
    }

    /**
     * The Record gesture (book §5.2 "Record pressed"). It validates and
     * refuses exactly as before; a MIDI-only take starts at once. An audio
     * take opens the device, then enters PREPARING and returns: the take
     * directory is allocated on the storage executor
     * ({@link #onTakeDirectoryAllocated}), the take's capture thread creates
     * its files ({@code RecordingPipeline.prepare()}), and capture begins on
     * the readiness turn ({@link #onTakeReady}). This handler does no storage
     * I/O and never waits for the take's capture thread (PR #978 review
     * 5391920205, F2; {@code javafx-application-design} §11); its one
     * synchronous device call, the open below, predates that review. FX
     * thread.
     */
    private void onRecord() {
        // Story 323 review (book §5.2): Record is valid only from IDLE, and a
        // take still being written to disk is FINALIZING until its capture
        // thread has terminated and its clips are published — as is a start
        // that was cancelled, or failed once its take directory existed,
        // until the FX turn after the removal of its files has run, whether
        // or not everything could be removed. Refused before anything is
        // touched: no pipeline, no device, no MIDI recorder, and the
        // transport stays where it is.
        if (isTakeBeingWritten()) {
            LOG.warning("Recording refused — the last take is still being written to disk");
            statusBarLabel.setText(RECORD_WHILE_WRITING_MESSAGE);
            statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
            notificationBar.show(NotificationLevel.WARNING, RECORD_WHILE_WRITING_MESSAGE);
            recIndicator.setVisible(false);
            recIndicator.setManaged(false);
            return;
        }

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

        // Story 323 (D6): an audio take lives in the project — its segments
        // stream under <project>/audio/takes/<take>/ — so a project without a
        // directory (never saved) cannot record audio. Refused BEFORE the
        // engine, the pipeline or any MidiRecorder is touched: nothing has
        // started and the transport remains STOPPED. MIDI-only recording
        // writes no audio files and needs no folder, so it is not gated.
        Optional<Path> projectDirectory = projectDirectory();
        if (!armedAudioTracks.isEmpty() && projectDirectory.isEmpty()) {
            LOG.warning("Recording refused — the project has no directory yet");
            statusBarLabel.setText(NO_PROJECT_FOLDER_MESSAGE);
            statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
            notificationBar.show(NotificationLevel.ERROR, NO_PROJECT_FOLDER_MESSAGE);
            recIndicator.setVisible(false);
            recIndicator.setManaged(false);
            return;
        }

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

        if (armedAudioTracks.isEmpty()) {
            // MIDI-only: the output stream was proved RUNNING above, and the
            // take writes no audio files, so it starts at once and the
            // transport may enter recording.
            startMidiRecording(armedMidiTracks, countIn);
            project.getTransport().record();
            announceRecordingStarted(armedTracks.size(), null);
            return;
        }

        // Open audio I/O through the engine's provisioned device (story 316):
        // the settings-configured device identity is honoured on every open —
        // a per-track input device index no longer reaches the open path.
        // Per-track input CHANNEL routing stays on Track.getInputRouting();
        // multi-device capture is story 326. Story 316 made
        // startAudioInputOutput() ENFORCE capture — it walks the ladder with
        // CaptureRequirement.REQUIRED, refuses any rung that opens with zero
        // input channels, and throws when every rung fails or no streaming
        // provision is configured — so a throw here is the honest "nothing
        // can be captured" signal. It comes before anything of the take is
        // allocated, so a refusal leaves no take directory, pipeline or file
        // behind.
        //
        // The open stays on the FX thread: the engine opens the driver and
        // starts its stream before it returns, which takes as long as the
        // driver takes. That synchronous device call predates PR #978 review
        // 5391920205 and lies outside it — the review's findings are the
        // take's storage I/O and the Stop's join, and neither is left in this
        // handler — so it is not moved here.
        //
        // Blocks the device delivers before capture begins reach a recording
        // callback that is still null: they were never part of any take.
        try {
            if (armedAudioTracks.stream().allMatch(track ->
                    track.getInputRouting().isNone() && audioEngine.hasGraphInstrument(track))) {
                audioEngine.startAudioOutput();
            } else {
                audioEngine.startAudioInputOutput();
            }
        } catch (RuntimeException e) {
            abortRecordingTake(e);
            return;
        }

        // PREPARING (PR #978 review 5391920205, F2). The take directory is
        // allocated under the project's audio/takes folder (story 323, D6;
        // the OS temp directory is never used, Recording Reliability book
        // §9.4) on the storage executor, never on the FX thread; the take's
        // files are then created by its capture thread (prepare()), and
        // capture begins only once they exist (onTakeReady): by default the
        // capture ring holds the blocks covering
        // CaptureRing.DEFAULT_HANDOFF_TOLERANCE (250 ms), rounded up to a
        // power-of-two slot count (about 341 ms at 48 kHz with 256-frame
        // blocks), which a slow disk would overflow if capture began first.
        PendingStart start = new PendingStart(armedTracks.size(), List.copyOf(armedAudioTracks),
                List.copyOf(armedMidiTracks), countIn);
        pendingStart = start;
        statusBarLabel.setText(TAKE_PREPARING_MESSAGE);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
        recIndicator.setVisible(false);
        recIndicator.setManaged(false);
        updateStatus();
        Path audioDirectory = ProjectManager.audioDirectory(projectDirectory.get());
        Instant requested = Instant.now();
        CompletableFuture.supplyAsync(() -> allocateTakeDirectory(audioDirectory, requested), storageExecutor)
                .whenComplete((takeDirectory, failure) ->
                        postFx(() -> onTakeDirectoryAllocated(start, takeDirectory, failure)));
    }

    /**
     * The ANNOUNCE and UI tail of a take whose recording has begun — the
     * transport is RECORDING: {@link TransportEvent.Started}, the status
     * line, the INFO toast, the REC indicator and, for an audio take, the
     * session-input check. Run by {@link #onRecord()} for a MIDI-only take
     * and by the readiness turn ({@link #onTakeReady}) for an audio take. FX
     * thread.
     *
     * @param trackCount    every armed track of the take, audio and MIDI
     * @param takeDirectory the audio take's directory under
     *                      {@code audio/takes}; {@code null} for a MIDI-only take
     */
    private void announceRecordingStarted(int trackCount, Path takeDirectory) {
        // ANNOUNCE (story 315) — only once the transport actually transitioned.
        // Both paths call Transport.record(), so the guard declines only
        // when an internal caller stopped the transport underneath a running
        // pipeline; announcing then would put a fiction on the bus (count-in
        // does not defer Transport.record() today; story 328 owns count-in).
        if (project.getTransport().getState() == TransportState.RECORDING) {
            EventBusPublisher.publish(new TransportEvent.Started(
                    core.beatsToFrames(project.getTransport().getPositionInBeats()), Instant.now()));
        }
        updateStatus();
        // Status line truth (story 323, D12): an audio take names the take
        // directory its segments are streaming into; a MIDI-only take writes
        // no audio files, so it names none. Neither claims an "auto-save".
        String armedSummary = "Recording — " + trackCount + " track"
                + (trackCount > 1 ? "s" : "") + " armed";
        statusBarLabel.setText(takeDirectory == null
                ? armedSummary
                : armedSummary + " — streaming to " + ProjectManager.AUDIO_DIR_NAME + "/"
                        + TakeDirectories.TAKES_DIR_NAME + "/" + takeDirectory.getFileName());
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
        notificationBar.show(NotificationLevel.INFO,
                "Recording started — " + trackCount + " track"
                        + (trackCount > 1 ? "s" : "") + " armed");
        recIndicator.setVisible(true);
        recIndicator.setManaged(true);
        // Story 322: a per-track input choice that disagrees with the session
        // input is never silently ignored — one WARNING names the tracks and
        // both devices. Its enumeration runs off the FX thread, so the toast
        // lands on a later FX turn than the INFO above and stays visible over it.
        if (takeDirectory != null) {
            warnOnSessionInputMismatchOffFx();
        }
    }

    /**
     * The FX turn that follows the allocation of {@code start}'s take
     * directory on the storage executor. A start cancelled meanwhile is
     * already {@link #abandonedStart}: the directory just allocated, if any,
     * is removed off the FX thread. A failed allocation ends the start with
     * the ERROR "Recording failed — could not create a take folder under the
     * project's audio/takes". Otherwise the take's pipeline is built and
     * prepared ({@code RecordingPipeline.prepare()}, which touches no storage
     * and waits for nothing: the take's capture thread creates the files),
     * and the readiness turn ({@link #onTakeReady}) is posted once that
     * thread reports. A failure before the pipeline is prepared ends the
     * start with the ERROR of {@link #abortRecordingTake}, and the take
     * directory is removed off the FX thread; a failure of {@code prepare()}
     * itself abandons the start ({@link #abandonStart}).
     *
     * <p>Of the failures that end the start here, two — the allocation and
     * the building of the pipeline — also hand the output stream Record
     * opened to {@link #stopAudioOutputWhenIdle()}, unless the transport is
     * rolling ({@link #stopAudioOutputUnlessRolling}); that call keeps the
     * stream open while an instrument insert exists
     * ({@link #hasGraphInstruments()}). Record opens the device before it
     * allocates, and until PR #978 review 5391920205 both came before the
     * device open. The third, a failed {@code prepare()}, goes to
     * {@link #abandonStart} and, like a failed readiness or
     * {@code beginCapture()}, leaves the stream open
     * ({@link #abortRecordingTake}). FX thread.</p>
     */
    private void onTakeDirectoryAllocated(PendingStart start, Path takeDirectory, Throwable failure) {
        start.takeDirectory = takeDirectory;
        if (start.cancelled) {
            removeFilesOfAbandonedStart(start, CompletableFuture.completedStage(null));
            return;
        }
        if (failure != null) {
            pendingStart = null;
            Throwable cause = causeOf(failure);
            LOG.log(Level.SEVERE, "Failed to create a take directory under the project's audio/takes folder",
                    cause instanceof UncheckedIOException unchecked ? unchecked.getCause() : cause);
            String message = "Recording failed — could not create a take folder under the project's "
                    + ProjectManager.AUDIO_DIR_NAME + "/" + TakeDirectories.TAKES_DIR_NAME;
            statusBarLabel.setText(message);
            statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
            notificationBar.show(NotificationLevel.ERROR, message);
            recIndicator.setVisible(false);
            recIndicator.setManaged(false);
            stopAudioOutputUnlessRolling();
            updateStatus();
            return;
        }
        RecordingPipeline pipeline;
        try {
            pipeline = new RecordingPipeline(
                    audioEngine, project.getTransport(), project.getFormat(), takeDirectory,
                    start.armedAudioTracks, start.countIn, InputMonitoringMode.OFF, null);
            pipeline.setReportedLatency(reportedLatency.get());
            pipeline.setApplyLatencyCompensation(applyLatencyCompensation.getAsBoolean());
            pipelineSetup.accept(pipeline);
        } catch (RuntimeException e) {
            // No capture thread exists yet: only the take directory is left
            // to remove, off the FX thread.
            pendingStart = null;
            removeFilesOfAbandonedStart(start, CompletableFuture.completedStage(null));
            stopAudioOutputUnlessRolling();
            abortRecordingTake(e);
            return;
        }
        start.pipeline = pipeline;
        CompletionStage<Void> readiness;
        try {
            readiness = pipeline.prepare();
        } catch (RuntimeException e) {
            // prepare() rolled itself back without waiting and rethrew.
            abandonStart(start, e);
            return;
        }
        readiness.whenComplete((_, readinessFailure) -> postFx(() -> onTakeReady(start, readinessFailure)));
    }

    /**
     * The readiness turn: the FX turn that follows the take's capture thread
     * reporting that the take's files exist, or that they could not be
     * created. It acts only while {@code start} is still the pending start —
     * a cancel or a {@link #retire()} that got there first has already
     * handed the take back to its capture thread, and the controller's own
     * state, written on this thread, decides: a readiness that completed
     * normally is never proof that capture should begin. A failed readiness
     * abandons the start ({@link #abandonStart}). Otherwise capture begins
     * ({@code RecordingPipeline.beginCapture()}: callback installed, engine
     * started, transport recording — all or nothing, and a failure abandons
     * the start too), and then what Record did once RECORDING was real: the
     * auto-Stop of a take sealed early is registered, the armed MIDI tracks
     * of a mixed take start recording, and the take is announced
     * ({@link #announceRecordingStarted}). FX thread.
     */
    private void onTakeReady(PendingStart start, Throwable failure) {
        if (start.cancelled) {
            return;
        }
        RecordingPipeline pipeline = start.pipeline;
        if (failure != null) {
            abandonStart(start, causeOf(failure));
            return;
        }
        try {
            pipeline.beginCapture();
        } catch (RuntimeException e) {
            abandonStart(start, e);
            return;
        }
        pendingStart = null;
        recordingPipeline = pipeline;
        // Story 323 review: a take the capture thread seals on its own
        // (disk exhaustion, a write failure) is stopped like the user's
        // Stop, on a later FX turn.
        stopWhenSealedEarly(pipeline);
        if (!start.armedMidiTracks.isEmpty()) {
            startMidiRecording(start.armedMidiTracks, start.countIn);
        }
        announceRecordingStarted(start.trackCount, start.takeDirectory);
    }

    /**
     * Stop, Record pressed again, or the end of a post-roll — the deferred
     * half of a Stop — while the take is being prepared: cancels it
     * ({@link #cancelPendingStart}) and says so in the status bar. No take
     * was recorded and nothing is announced: capture never began, so the
     * transport was never put in recording. The device is left to the
     * caller, which hands the output stream Record opened to
     * {@link #stopAudioOutputWhenIdle()}: {@link #toggleRecord()} only when
     * the transport is not rolling, and {@link #stop()} and
     * {@link #finishPostRoll()} once the transport is stopped, as every Stop
     * does. FX thread.
     */
    private void cancelPendingStartByUser() {
        cancelPendingStart();
        LOG.info("Recording cancelled while its take was being prepared");
        statusBarLabel.setText(RECORDING_CANCELLED_MESSAGE);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
        recIndicator.setVisible(false);
        recIndicator.setManaged(false);
        updateStatus();
    }

    /**
     * Cancels the take being prepared, without waiting for anything: it is
     * marked cancelled — so its allocation turn and its readiness turn do
     * nothing more for it — and becomes the {@link #abandonedStart}. If its
     * pipeline exists, it is handed back to the take's capture thread
     * ({@link #discardTake}: {@code RecordingPipeline.cancelStart()}), which
     * deletes the segment and manifest files it created for the take —
     * unless that thread had already ended on its own (a throwable that
     * escaped its drain loop sealed the take early), when it deletes
     * nothing — and the take directory, if that left it empty, is removed
     * off the FX thread once that thread has terminated; if its take
     * directory is still being allocated, the allocation turn removes it. FX
     * thread.
     */
    private void cancelPendingStart() {
        PendingStart start = pendingStart;
        pendingStart = null;
        start.cancelled = true;
        abandonedStart = start;
        if (start.pipeline != null) {
            removeFilesOfAbandonedStart(start, discardTake(start.pipeline));
        }
    }

    /**
     * Ends a start that failed after its take directory was allocated — its
     * pipeline's {@code prepare()} threw, its readiness failed, or its
     * {@code beginCapture()} threw — with the take's files handed back to
     * its capture thread ({@link #discardTake}), and the take directory, if
     * that thread left it empty, removed off the FX thread once that thread
     * has terminated. Then the
     * user is told, as for a device that could not be opened
     * ({@link #abortRecordingTake}). FX thread.
     */
    private void abandonStart(PendingStart start, Throwable failure) {
        pendingStart = null;
        removeFilesOfAbandonedStart(start, discardTake(start.pipeline));
        abortRecordingTake(failure);
    }

    /**
     * Hands a take that will not be recorded back to its capture thread,
     * without waiting, and returns that thread's termination: a pipeline
     * still preparing is cancelled ({@code RecordingPipeline.cancelStart()}:
     * the thread deletes the segment and manifest files it created for the
     * take, unless it had already ended on its own); one whose own rollback
     * already ran — a failed {@code prepare()} or {@code beginCapture()},
     * which asked the thread the same — is followed through its
     * {@code termination()}. FX thread.
     */
    private static CompletionStage<Void> discardTake(RecordingPipeline pipeline) {
        return pipeline.isPreparing() ? pipeline.cancelStart() : pipeline.termination();
    }

    /**
     * Makes {@code start} the {@link #abandonedStart} until the removal of
     * its take directory has run: once {@code termination} — its capture
     * thread's — has completed, the take directory it allocated is removed,
     * if it is empty, on the storage executor
     * ({@link #deleteEmptyTakeDirectory}), never on the FX thread, and then
     * {@link #abandonedStartCleanedUp} is posted to the FX thread. The
     * dependents registered here only hand off: the removal runs on the
     * storage executor even when {@code termination} has completed already,
     * and the last one only posts. FX thread.
     */
    private void removeFilesOfAbandonedStart(PendingStart start, CompletionStage<Void> termination) {
        abandonedStart = start;
        Path takeDirectory = start.takeDirectory;
        termination.thenRunAsync(() -> deleteEmptyTakeDirectory(takeDirectory), storageExecutor)
                .whenComplete((_, _) -> postFx(() -> abandonedStartCleanedUp(start)));
    }

    /**
     * The FX turn after the removal of an abandoned start's take directory
     * has run: Record and the project-replacing doors are available again.
     * FX thread.
     */
    private void abandonedStartCleanedUp(PendingStart start) {
        if (abandonedStart == start) {
            abandonedStart = null;
        }
    }

    /**
     * Allocates a take's directory under {@code <project>/audio/takes}
     * (story 323, D6): ensures {@code audio/takes} exists, lists it, and makes
     * one {@code Files.createDirectory} per attempt. Storage I/O: run on the
     * storage executor, never on the FX thread.
     *
     * @throws UncheckedIOException if the directory cannot be created
     */
    private static Path allocateTakeDirectory(Path audioDirectory, Instant requested) {
        try {
            return TakeDirectories.allocate(audioDirectory, requested);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The cause a {@link CompletionStage} dependent was handed, unwrapped from its {@link CompletionException}. */
    private static Throwable causeOf(Throwable failure) {
        return failure instanceof CompletionException completion && completion.getCause() != null
                ? completion.getCause()
                : failure;
    }

    /** The production {@link #storageExecutor}: each task on a new virtual thread. */
    private static void onAVirtualThread(Runnable task) {
        Thread.ofVirtual().name("daw-take-storage").start(task);
    }

    /**
     * The production {@link FxDelay}: a {@link PauseTransition} of
     * {@code delay}, played on the FX thread, whose end runs {@code action}
     * on a later FX pulse. FX thread.
     */
    private static void afterOnFx(java.time.Duration delay, Runnable action) {
        PauseTransition pause = new PauseTransition(Duration.millis(delay.toMillis()));
        pause.setOnFinished(_ -> action.run());
        pause.play();
    }

    /**
     * One Record gesture's audio take from Record until capture begins or
     * the start ends (PREPARING), and afterwards — when it was cancelled, or
     * failed once its take directory existed — until the FX turn after the
     * removal of its files has run, whatever that removal could not delete
     * ({@link #abandonedStartCleanedUp}). Written on the FX thread only.
     */
    private static final class PendingStart {
        /** Every armed track of the take, audio and MIDI. */
        final int trackCount;
        final List<Track> armedAudioTracks;
        /** The armed MIDI tracks of a mixed take, which start recording once capture begins. */
        final List<Track> armedMidiTracks;
        final CountInMode countIn;
        /** The take's directory, once its allocation has returned; {@code null} before, and if it failed. */
        Path takeDirectory;
        /** The take's pipeline, once built; {@code null} while the take directory is being allocated. */
        RecordingPipeline pipeline;
        /** Set by a cancel or a retirement; the start's later turns then do nothing more for it. */
        boolean cancelled;

        PendingStart(int trackCount, List<Track> armedAudioTracks, List<Track> armedMidiTracks,
                     CountInMode countIn) {
            this.trackCount = trackCount;
            this.armedAudioTracks = armedAudioTracks;
            this.armedMidiTracks = armedMidiTracks;
            this.countIn = countIn;
        }
    }

    /**
     * Enumerates the devices on a virtual thread and, back on the FX thread
     * through {@link #postFx}, shows the session-input mismatch WARNING for
     * the armed tracks — only while this take is still in flight
     * ({@link #recIndicator} is the controller's own in-flight fact: set at the
     * end of a successful start, cleared by stop and abort), so a take that
     * ended or was abandoned before the driver answered raises no stale toast
     * over whatever replaced it. The tracks are read on the FX thread, where
     * the project's live list is owned (story 322 fix round, S7;
     * {@code javafx-application-design} §11 — no blocking I/O in a handler:
     * {@code AudioBackend.listDevices()} is a driver walk that, on ASIO, waits
     * on the control thread).
     */
    private void warnOnSessionInputMismatchOffFx() {
        sessionInputCheck = Thread.ofVirtual().name("daw-record-input-check").start(() -> {
            List<AudioDeviceInfo> devices = listAudioDevices();
            postFx(() -> {
                if (!recIndicator.isVisible()) {
                    return;
                }
                sessionInputSelection.mismatchWarning(project.getTracks(), devices)
                        .ifPresent(message -> notificationBar.show(NotificationLevel.WARNING, message));
            });
        });
    }

    /**
     * The worker of the latest record-start input check, so a test can wait
     * for it before flushing the FX queue. Package-visible for tests.
     *
     * @return the worker, or empty before the first audio take
     */
    Optional<Thread> pendingSessionInputCheck() {
        return Optional.ofNullable(sessionInputCheck);
    }

    /**
     * The take directory the active audio pipeline is streaming into, so a
     * test can pin where a take lives (story 323). Package-visible for tests;
     * FX thread.
     *
     * @return the take directory under the project's {@code audio/takes}, or
     *         empty when no audio pipeline is active
     */
    Optional<Path> activeTakeDirectory() {
        return recordingPipeline != null && recordingPipeline.isActive()
                ? Optional.of(recordingPipeline.getTakeDirectory())
                : Optional.empty();
    }

    /**
     * The project's directory, or empty while the project has never been
     * saved (metadata without a path). Read on the FX thread by
     * {@link #onRecord()}; in the app {@code ProjectLifecycleController}
     * always leaves the path set once a project is created or opened.
     */
    private Optional<Path> projectDirectory() {
        ProjectMetadata metadata = project.getMetadata();
        return metadata == null ? Optional.empty() : Optional.ofNullable(metadata.projectPath());
    }

    /**
     * Enumerates the audio devices via the engine's one SDK backend seam
     * (story 316). An absent backend or a failed enumeration yields an empty
     * list (logged), in which case no per-track index can be resolved and the
     * mismatch check has nothing to compare. Called on the input check's
     * worker, never on the FX thread.
     */
    private List<AudioDeviceInfo> listAudioDevices() {
        AudioBackend backend = audioEngine.getBackend();
        if (backend == null) {
            return List.of();
        }
        try {
            return backend.listDevices();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to enumerate audio devices for the session-input check", e);
            return List.of();
        }
    }

    /**
     * Abandons a record gesture whose capture device could not be opened
     * (story 316 review) or whose take could not start (story 323), and
     * tells the user the take did not start. It leaves no take behind,
     * unless the take's capture thread had already sealed it early: a
     * refused device open comes before anything of the take is allocated,
     * and for a take that failed later the removal of its files has been
     * arranged off the FX thread before this is called
     * ({@link #removeFilesOfAbandonedStart}): its capture thread, if it got
     * one, deletes the segment and manifest files it created — unless it had
     * already ended on its own (a throwable that escaped its drain loop
     * sealed the take early), when it deletes nothing and that take stays on
     * disk — and the take directory, if that left it empty, is then removed
     * on the storage executor. It
     * does not close the device. When the open succeeded and the take's start
     * then failed, the turn that ends the start calls
     * {@link #stopAudioOutputWhenIdle()} only when the take's pipeline could
     * not be built — or, without coming here, its take directory could not be
     * allocated — and the transport is not rolling
     * ({@link #onTakeDirectoryAllocated}, {@link #stopAudioOutputUnlessRolling});
     * after a failed {@code prepare()}, readiness or {@code beginCapture()}
     * the stream that open started stays open. This controller
     * leaves a stream closed only through {@link #stopAudioOutputWhenIdle()}
     * (a later Record's {@code startAudioInputOutput()} also closes the open
     * stream, but only to open a new full-duplex one), which a
     * later Stop calls too, and which closes it only while
     * {@link #hasGraphInstruments()} is false: while a mixer channel, a return
     * bus or the master channel holds an instrument insert, bypassed or not,
     * the stream is kept open so the instrument can be auditioned while
     * stopped. Closing it after those later failures belongs to story 325's
     * rollback of everything already started, in reverse start order (story
     * 323's Known limitations). No input-monitoring mode is left to restore: this
     * controller builds its pipeline with {@link InputMonitoringMode#OFF},
     * which {@code prepare()} applies to no track.
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
     * <p>{@link #stop()} is deliberately NOT called here. No take started, so
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
     * only by {@link #announceRecordingStarted} and cleared by {@link #stop()} /
     * {@link #finishPostRoll()}, so in the pipeline-active-over-STOPPED-transport
     * state {@link #stop()} documents it can still be burning when the user
     * presses Record again; leaving it lit over a take that never started is
     * the same lie in miniature. {@link #updateStatus()} then re-reads the
     * authoritative {@link Transport#getState()} &mdash; on the audio-armed
     * branch the only thing that moves the transport is
     * {@code RecordingPipeline.beginCapture()}, whose last step is
     * {@code transport.record()}: it either never ran (the open, the
     * allocation, the pipeline's {@code prepare()} or the take's readiness
     * failed) or threw, and its rollback stops a transport the failed step
     * left recording &mdash; so the status label and the Play enablement
     * report whatever the transport actually is.</p>
     *
     * @param failure the open failure, whose message names the actual cause
     *                (story 316 makes it specific: every ladder rung refused
     *                for want of capture channels, an ambiguous device
     *                selection, or no backend configured at all), or the
     *                take's own start failure: its pipeline could not be
     *                built, its {@code prepare()} threw, its readiness failed (the take
     *                directory, a segment or the manifest could not be
     *                created, or an unsupported bit depth), or its
     *                {@code beginCapture()} threw
     */
    private void abortRecordingTake(Throwable failure) {
        LOG.log(Level.WARNING,
                "Recording aborted — the take could not be started", failure);

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
     * Best-effort removal of the take directory allocated under the project's
     * {@code audio/takes} for a start that was cancelled or failed (story 316
     * review; story 323). Storage I/O: it runs on the storage executor, never
     * on the FX thread, and only once the take's capture thread — what
     * creates the per-track directories, segments and manifest under it —
     * has terminated, or never got one ({@link #removeFilesOfAbandonedStart}).
     * By then that thread has deleted the segment and manifest files it
     * created for the take, and each track directory it created, if that left
     * it empty (D11) — unless it had already ended on its own before the
     * discard was asked for (a throwable that escaped its drain loop sealed
     * the take early), when it deleted nothing — so the directory is normally
     * empty and a plain delete suffices; no recursive walk is warranted. A
     * directory the thread did not empty — a file it failed to delete, the
     * files of a take it sealed early and never discarded, or what another
     * process put there, a track directory the take did not create included —
     * is not empty, so the delete fails and it is left in place rather than
     * walked. A {@code .part} segment or a streaming manifest left in it is
     * what the Recording Reliability book's §4.7 recovery scan looks for, a
     * scan nothing runs yet (story 323's Known limitations: story 332's
     * recovery pass is to invoke it); that scan does not look for sealed
     * segments or an ABORTED manifest, which is what a take sealed early
     * leaves once its seal and its final manifest write succeeded, so those
     * stay on disk and no project refers to them. {@code null} deletes
     * nothing: it is passed only for a start cancelled while its take
     * directory was being allocated, when that allocation then failed — a
     * cancel does not stop the allocation, which returns a directory or fails.
     *
     * <p>A failure here is logged at FINE and swallowed on purpose: an
     * undeleted take directory is housekeeping, and escalating it would
     * replace the message the user actually needs with a filesystem
     * complaint about a directory they never asked for. (A start failure
     * itself, any suppressed rollback failure included, is logged at WARNING
     * by {@link #abortRecordingTake}.)</p>
     */
    private static void deleteEmptyTakeDirectory(Path outputDirectory) {
        if (outputDirectory == null) {
            return;
        }
        try {
            Files.deleteIfExists(outputDirectory);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE,
                    () -> "Could not delete the recording directory "
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
        // start()): Shift+Space while RECORDING must not leave record, nor
        // start playback under a take that is being prepared.
        if (pendingStart != null || project.getTransport().getState() == TransportState.RECORDING) {
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
            MidiDevice device = midiInputDeviceResolver.apply(track.getMidiInputDeviceName());
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
     * each track's recorded notes. When at least one track recorded notes —
     * the recorders put them into the tracks' MIDI clips — it marks the
     * project dirty and shows the SUCCESS toast; a take with no note leaves
     * the project as it was. FX thread.
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
            // At least one "Record MIDI" action was registered over notes the
            // recorders put into the project's clips.
            project.markDirty();
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
