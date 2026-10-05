package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.recording.SessionInputSelection;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTransportIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.TransportIntentHandler;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.StreamStartFailure;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CapturePeakSnapshot;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.EarlySeal;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.recording.StopSealFailure;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import javax.sound.midi.MidiDevice;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages playback actions and delegates recording to {@link RecordCoordinator} — the
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
     * the take is published — first the take's capture thread seals its
     * segments and writes its manifest, then the take's audio is read back
     * from those segments off the FX thread, and the clips appear once that
     * read has ended.
     */
    static final String TAKE_FINISHING_MESSAGE = "Recording stopped — finishing the take…";

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
     * thread has finished and the take's audio has been read back from its
     * segments — the wording covers both, since the delay may end in
     * either.
     */
    static final String TAKE_STILL_WRITING_MESSAGE =
            "Recording stopped — the take is still being written to disk or read back from it;"
                    + " its clips will appear when that is done";

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
     * written or read back for its publication, or while the files of a
     * cancelled or failed start are still being removed (Recording
     * Reliability book §5.2 — Record is valid only from IDLE, and FINALIZING
     * returns to IDLE once the seal and the manifest are complete). Shown as
     * the WARNING toast and the status-bar text.
     */
    static final String RECORD_WHILE_WRITING_MESSAGE =
            "Record is unavailable until the last take has been finished";

    private final DawProject project;
    private final AudioEngine audioEngine;
    private final UndoManager undoManager;
    private final NotificationBar notificationBar;
    /**
     * Story 322 — the ONE session-level input device recording opens. Consulted
     * at record start to warn when an armed track's per-track input choice
     * disagrees with it (Audio Engine Wiring Design Book §5.6 "Per-track input
     * device"; explicit track inputs are resolved by the story-326 capture union).
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
    private final RecordCoordinator recordCoordinator;

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
     * Story 323 review — set by {@link #retire()} once {@code MainController}
     * has replaced this controller with the next project's. Written on the
     * FX thread; volatile because the storage task that reads a stopped
     * take's audio back ({@link #readRecordedAudio}) reads it before each
     * clip, to stop reading for a project that is no longer open.
     */
    private volatile boolean retired;

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

    void cancelPostRollForRecording() {
        if (postRollTimer != null) {
            postRollTimer.stop();
            postRollTimer = null;
        }
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
                        SessionInputSelection sessionInputSelection) {
        this(project, audioEngine, undoManager, notificationBar, statusLabel,
                statusBarLabel, recIndicator, playButton,
                recordButton, snapEnabled, gridResolution, countInMode,
                flashMidiActivity, applyLatencyCompensation, reportedLatency,
                sessionInputSelection,
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
        this.recordCoordinator = new RecordCoordinator(this, project, audioEngine, undoManager,
                notificationBar, sessionInputSelection, statusBarLabel, recIndicator, core, countInMode,
                flashMidiActivity, applyLatencyCompensation, reportedLatency, fxDispatcher);
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
        if (recordCoordinator.isPreparingTake() || state == TransportState.PLAYING || state == TransportState.RECORDING) {
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
    boolean startAudioOutputOrRefuse(String intent) {
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
        if (recordCoordinator.isRecordingInFlight()) return;
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
        if (recordCoordinator.isPreparingTake()) {
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
     * {@link #finishWrittenTake} hands the read of the take's audio to the
     * storage executor, and on the FX turn that read posts
     * {@link #publishWrittenTake} completes the take with that audio and
     * publishes its clips ({@link #publishRecordedTake}) — or, if the
     * completion fails, the failure is
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
     * The FX turn that publishes such a take ({@link #publishWrittenTake})
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
        if (recordCoordinator.isPreparingTake()) {
            recordCoordinator.cancelPendingStartByUser();
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
        recordCoordinator.stopAudioTake();

        // Finalize MIDI recording if any MIDI recorders are active. Its toast
        // comes before the audio take's SUCCESS or report, which the later
        // publication shows.
        recordCoordinator.stopMidiRecording();

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
            PauseTransition timer = postRollTimer;
            timer.setOnFinished(_ -> {
                if (postRollTimer == timer) finishPostRoll();
            });
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
        return cell.startsWith("Recording stopped") || cell.startsWith("MIDI recording stopped")
                || cell.equals(RECORDING_CANCELLED_MESSAGE);
    }

    void stopAudioOutputWhenIdle() {
        if (recordCoordinator.deferInputStreamStop()) return;
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
    void stopAudioOutputUnlessRolling() {
        TransportState state = project.getTransport().getState();
        if (state != TransportState.PLAYING && state != TransportState.RECORDING) {
            stopAudioOutputWhenIdle();
        }
    }

    boolean hasGraphInstruments() {
        var mixer = project.getMixer();
        // Retain the callback for bypassed instruments so unbypassing while stopped can audition immediately.
        return mixer.getChannels().stream().anyMatch(com.benesquivelmusic.daw.core.mixer.MixerChannel::hasInstrumentInsert)
                || mixer.getReturnBuses().stream().anyMatch(com.benesquivelmusic.daw.core.mixer.MixerChannel::hasInstrumentInsert)
                || mixer.getMasterChannel().hasInstrumentInsert();
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
     * {@link #finishWrittenTake} reads nothing, and neither it nor — for a
     * controller retired while the take's audio was being read back, whose
     * read stops before its next clip — {@link #publishWrittenTake}
     * completes the take or publishes
     * anything — no clip on the replaced project's tracks, no audio attached,
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
     * finishes as it would have. The live peak channels of the take in hand
     * are closed ({@link #closeCapturePeaks}) — the pipeline of a recording
     * that has begun keeps publishing snapshots, into channels nothing
     * drains any more. Cannot be undone. FX thread.
     */
    void retire() {
        retired = true;
        if (recordCoordinator.isPreparingTake()) {
            recordCoordinator.cancelPendingStart();
        }
        recordCoordinator.retire();
        if (postRollTimer != null) {
            postRollTimer.stop();
            postRollTimer = null;
        }
    }

    /**
     * How {@link #publishWrittenTake} completes a stopped take once its
     * capture thread has terminated and its audio has been read back.
     * Production: {@code RecordingPipeline::completeStop}, the form that is
     * handed the audio. A test seam for the completions this module cannot
     * cause through the real pipeline — one that throws, or one that hands
     * back no clip.
     */
    @FunctionalInterface
    interface TakeCompletion {
        /**
         * @param loadedAudio from a clip's segment-path list to the audio
         *                    read back for it, or {@code null} for none
         */
        List<AudioClip> complete(RecordingPipeline pipeline, Function<List<String>, float[][]> loadedAudio);
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
        if (recordCoordinator.isPreparingTake()) {
            recordCoordinator.cancelPendingStartByUser();
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
        if (recordCoordinator.isPreparingTake() || project.getTransport().getState() == TransportState.RECORDING) {
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

    // ── Status update ────────────────────────────────────────────────────────

    void updateStatus() {
        Transport transport = project.getTransport();
        TransportState state = transport.getState();

        var recordState = recordCoordinator.getState();
        statusLabel.setText(recordState == com.benesquivelmusic.daw.app.ui.recording.RecordState.IDLE
                ? state.name() : recordState.name());
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
        // recording so the :active pseudo-class remains visible and a
        // second Record press requests toggle-stop through RecordCoordinator.
        // Story 325's double-press test pins the single-take contract. Stop stays
        // enabled while STOPPED so the double-stop rewind-to-zero gesture
        // remains reachable (story 315). The play/record/loop :active
        // pseudo-classes are the TransportControlBinder's alone — the
        // binder observes TransportVM and is the single writer (§4.4).
        playButton.setDisable(recordCoordinator.isRecordingInFlight() || state == TransportState.RECORDING);
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

    RecordCoordinator recordCoordinator() { return recordCoordinator; }
    @Override public void toggleRecord() { recordCoordinator.toggleRecord(); }
    boolean isRecordingInFlight() { return recordCoordinator.isRecordingInFlight(); }
    boolean isPreparingTake() { return recordCoordinator.isPreparingTake(); }
    boolean isTakeBeingWritten() { return recordCoordinator.isTakeBeingWritten(); }
    Optional<CapturePeakSnapshot> liveCapturePeaks(String id) { return recordCoordinator.liveCapturePeaks(id); }
    Optional<Thread> pendingSessionInputCheck() { return recordCoordinator.pendingSessionInputCheck(); }
    Optional<Path> activeTakeDirectory() { return recordCoordinator.activeTakeDirectory(); }
    void setTakeCompletionForTest(TakeCompletion value) { recordCoordinator.setTakeCompletionForTest(value); }
    void setStillWritingDelayForTest(FxDelay value) { recordCoordinator.setStillWritingDelayForTest(value); }
    void setStorageExecutorForTest(Executor value) { recordCoordinator.setStorageExecutorForTest(value); }
    void setPipelineSetupForTest(Consumer<RecordingPipeline> value) { recordCoordinator.setPipelineSetupForTest(value); }
    void setEarlySealSignalForTest(EarlySealSignal value) { recordCoordinator.setEarlySealSignalForTest(value); }
    void setStopSealOutcomeForTest(StopSealOutcome value) { recordCoordinator.setStopSealOutcomeForTest(value); }
    void setMidiInputDeviceResolverForTest(Function<String, MidiDevice> value) { recordCoordinator.setMidiInputDeviceResolverForTest(value); }
    static String takeOfAReplacedProjectMessage(String name, Path path) { return RecordCoordinator.takeOfAReplacedProjectMessage(name, path); }
    static String takeSealedEarlyMessage(EarlySeal seal, int count, Path path) { return RecordCoordinator.takeSealedEarlyMessage(seal, count, path); }
    static String stopSealFailedMessage(StopSealFailure failure, int count, Path path) { return RecordCoordinator.stopSealFailedMessage(failure, count, path); }
}
