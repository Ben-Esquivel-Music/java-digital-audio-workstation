package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.recording.LiveCapturePeaks;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTransportIntentHandler;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.MidiRecorder;
import com.benesquivelmusic.daw.core.midi.RecordMidiNotesAction;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CapturePeakSnapshot;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.EarlySeal;
import com.benesquivelmusic.daw.core.recording.FinalManifestFailure;
import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.recording.SegmentFile;
import com.benesquivelmusic.daw.core.recording.StopSealFailure;
import com.benesquivelmusic.daw.core.recording.Take;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;
import com.benesquivelmusic.daw.core.recording.TakeGroup;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;

import javafx.animation.PauseTransition;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
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
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import javafx.beans.property.*;

import static com.benesquivelmusic.daw.app.ui.TransportController.*;
import com.benesquivelmusic.daw.app.ui.TransportController.TakeCompletion;
import com.benesquivelmusic.daw.app.ui.TransportController.FxDelay;
import com.benesquivelmusic.daw.app.ui.TransportController.EarlySealSignal;
import com.benesquivelmusic.daw.app.ui.TransportController.StopSealOutcome;
import com.benesquivelmusic.daw.app.ui.TransportController.StopSealOutcomeReader;

/** Owns the record state and the asynchronous take/MIDI lifecycle on the FX thread. */
final class RecordCoordinator {
    private static final Logger LOG = Logger.getLogger(RecordCoordinator.class.getName());

    private final TransportController transport;
    private final DawProject project;
    private final AudioEngine audioEngine;
    private final UndoManager undoManager;
    private final NotificationBar notificationBar;
    private final Label statusBarLabel;
    private final Label recIndicator;
    private final CoreTransportIntentHandler core;
    private final Supplier<CountInMode> countInMode;
    private final Consumer<Track> flashMidiActivity;
    private final BooleanSupplier applyLatencyCompensation;
    private final Supplier<RoundTripLatency> reportedLatency;
    private Supplier<Optional<AudioEngineController.LatencyCalibration>> latencyCalibration;

    void setLatencyCalibrationSupplier(Supplier<Optional<AudioEngineController.LatencyCalibration>> supplier) {
        latencyCalibration = Objects.requireNonNull(supplier, "supplier must not be null");
    }
    private final FxDispatcher fxDispatcher;
    private volatile boolean retired;
    private final FxDispatcher threadVerifier = new FxDispatcher();
    private final ReadOnlyObjectWrapper<RecordState> state = new ReadOnlyObjectWrapper<>(RecordState.IDLE);
    private final ReadOnlyBooleanWrapper recording = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper recordAvailable = new ReadOnlyBooleanWrapper(true);
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("");
    private boolean configurationReserved;
    private BooleanSupplier configurationInProgress = () -> false;
    void setConfigurationInProgressCheck(BooleanSupplier check) {
        configurationInProgress = Objects.requireNonNull(check);
        refreshRecordAvailability();
    }
    private CompletableFuture<Runnable> pendingConfiguration;
    private boolean confirmingConfiguration;
    private final List<String> skippedMidiTracks = new ArrayList<>();
    private String midiStopFailure;
    private RecordingPipeline unannouncedTake;
    private PendingStart unannouncedStart;
    private boolean unannouncedCleanupRunning;
    private boolean unannouncedStreamClosed;
    private String unannouncedCleanupFailure;
    private Supplier<Boolean> confirmStopAndApply = this::showStopAndApplyConfirmation;

    ReadOnlyObjectProperty<RecordState> stateProperty() { return state.getReadOnlyProperty(); }
    ReadOnlyBooleanProperty recordingProperty() { return recording.getReadOnlyProperty(); }
    ReadOnlyBooleanProperty recordAvailableProperty() { return recordAvailable.getReadOnlyProperty(); }
    ReadOnlyStringProperty statusProperty() { return status.getReadOnlyProperty(); }
    RecordState getState() { return state.get(); }

    private void requireFxThread() {
        if (!threadVerifier.isFxThread()) throw new IllegalStateException("Record state must be changed on the FX thread");
    }

    private void transition(RecordState next) {
        requireFxThread();
        RecordState previous = state.get();
        if (previous == next) return;
        boolean allowed = switch (previous) {
            case IDLE -> next == RecordState.PREPARING || next == RecordState.COUNT_IN || next == RecordState.ABORTED;
            case PREPARING -> next == RecordState.COUNT_IN || next == RecordState.FINALIZING || next == RecordState.ABORTED;
            case COUNT_IN -> next == RecordState.RECORDING || next == RecordState.FINALIZING || next == RecordState.ABORTED;
            case RECORDING -> next == RecordState.FINALIZING || next == RecordState.DEVICE_LOST || next == RecordState.ABORTED;
            case FINALIZING -> next == RecordState.IDLE || next == RecordState.ABORTED;
            case DEVICE_LOST -> next == RecordState.IDLE || next == RecordState.FINALIZING || next == RecordState.ABORTED;
            case ABORTED -> next == RecordState.FINALIZING || next == RecordState.IDLE;
        };
        if (!allowed) throw new IllegalStateException("Invalid record transition: " + previous + " → " + next);
        state.set(next);
        recording.set(next == RecordState.RECORDING);
        refreshRecordAvailability();
        updateStatus();
    }

    void refreshRecordAvailability() {
        recordAvailable.set(!retired && !configurationInProgress.getAsBoolean() && !configurationReserved && pendingConfiguration == null
                && (state.get() == RecordState.IDLE || state.get() == RecordState.PREPARING
                    || state.get() == RecordState.COUNT_IN || state.get() == RecordState.RECORDING));
    }

    private void setRecordingStatus(String message) {
        status.set(message);
        statusBarLabel.setText(message);
    }

    private void settle() {
        if (recordingPipeline != null || pendingStart != null || writingPipeline != null || abandonedStart != null
                || !activeMidiRecorders.isEmpty()) return;
        if (state.get() == RecordState.FINALIZING || state.get() == RecordState.ABORTED) transition(RecordState.IDLE);
        // Story 327 (book §5.2 "Device returned"): settlement alone never
        // clears DEVICE_LOST — only the lost device's return does, once the
        // rescued take's publication has ended; the armed tracks are kept.
        if (state.get() == RecordState.DEVICE_LOST && lostDeviceReturned) transition(RecordState.IDLE);
        if (state.get() != RecordState.IDLE) return;
        if (pendingConfiguration != null && !confirmingConfiguration) reserveConfiguration(pendingConfiguration);
    }

    /** Worker callers receive an exclusive permit only after take publication/cleanup. */
    CompletionStage<Runnable> requestConfigurationChange() {
        CompletableFuture<Runnable> result = new CompletableFuture<>();
        postFx(() -> {
            try {
                if (retired || configurationReserved || pendingConfiguration != null) {
                    result.completeExceptionally(new IllegalStateException("Audio configuration is already pending or the project was replaced"));
                    return;
                }
                if (state.get() == RecordState.IDLE) {
                    reserveConfiguration(result);
                    return;
                }
                pendingConfiguration = result;
                confirmingConfiguration = true;
                refreshRecordAvailability();
                boolean accepted = !isRecordingInFlight() || confirmStopAndApply.get();
                if (retired || pendingConfiguration != result) return;
                confirmingConfiguration = false;
                if (!accepted) {
                    pendingConfiguration = null;
                    refreshRecordAvailability();
                    result.completeExceptionally(new AudioConfigurationDeclinedException());
                    return;
                }
                if (isRecordingInFlight()) stop();
                // Story 327: a configuration change while DEVICE_LOST leaves
                // it as the user's Stop does — nothing is in flight, the
                // rescued take is published or being published — and the
                // request is reserved once that has settled to IDLE.
                if (state.get() == RecordState.DEVICE_LOST) transition(RecordState.FINALIZING);
                settle();
            } catch (RuntimeException failure) {
                if (pendingConfiguration == result) {
                    pendingConfiguration = null;
                    confirmingConfiguration = false;
                    refreshRecordAvailability();
                }
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void reserveConfiguration(CompletableFuture<Runnable> result) {
        pendingConfiguration = null;
        configurationReserved = true;
        refreshRecordAvailability();
        result.complete(() -> postFx(() -> {
            configurationReserved = false;
            refreshRecordAvailability();
        }));
    }

    private boolean showStopAndApplyConfirmation() {
        Alert prompt = new Alert(Alert.AlertType.CONFIRMATION,
                "Stop the take and apply? Audio settings will apply after the take has finished.",
                ButtonType.OK, ButtonType.CANCEL);
        prompt.setTitle("Apply audio settings");
        prompt.setHeaderText("Stop the take and apply?");
        ThemeManager.getDefault().applyTo(prompt.getDialogPane());
        return prompt.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    void setStopAndApplyConfirmationForTest(Supplier<Boolean> confirmation) {
        confirmStopAndApply = Objects.requireNonNull(confirmation);
    }

    RecordCoordinator(TransportController transport, DawProject project, AudioEngine audioEngine,
                      UndoManager undoManager, NotificationBar notificationBar,
                      Label statusBarLabel, Label recIndicator, CoreTransportIntentHandler core,
                      Supplier<CountInMode> countInMode, Consumer<Track> flashMidiActivity,
                      BooleanSupplier applyLatencyCompensation, Supplier<RoundTripLatency> reportedLatency,
                      FxDispatcher fxDispatcher) {
        this.transport = transport;
        this.project = project;
        this.audioEngine = audioEngine;
        this.undoManager = undoManager;
        this.notificationBar = notificationBar;
        this.statusBarLabel = statusBarLabel;
        this.recIndicator = recIndicator;
        this.core = core;
        this.countInMode = countInMode;
        this.flashMidiActivity = flashMidiActivity;
        this.applyLatencyCompensation = applyLatencyCompensation;
        this.reportedLatency = reportedLatency;
        this.fxDispatcher = fxDispatcher;
        recIndicator.visibleProperty().bind(recording);
        recIndicator.managedProperty().bind(recording);
    }

    private void postFx(Runnable action) { FxDispatcher.runOnFx(fxDispatcher, action); }
    private void updateStatus() { if (!retired) transport.updateStatus(); }
    private void stop() { transport.stop(); }
    private void stopAudioOutputUnlessRolling() { transport.stopAudioOutputUnlessRolling(); }
    private void stopAudioOutputWhenIdle() { transport.stopAudioOutputWhenIdle(); }
    private boolean startAudioOutputOrRefuse(String intent) { return transport.startAudioOutputOrRefuse(intent); }
    private static String stripCellSeparator(String text) {
        return text.startsWith(StatusCellLabel.CELL_SEPARATOR)
                ? text.substring(StatusCellLabel.CELL_SEPARATOR.length()) : text;
    }

    void retire() {
        retired = true;
        if (pendingConfiguration != null) {
            pendingConfiguration.completeExceptionally(new IllegalStateException("The project was replaced"));
            pendingConfiguration = null;
        }
        refreshRecordAvailability();
        if (pendingStart != null) cancelPendingStart();
        closeCapturePeaks();
    }

    void stopAudioTake() {
        requireFxThread();
        if (state.get() == RecordState.DEVICE_LOST
                && (recordingPipeline == null || !recordingPipeline.isActive())) {
            // Story 327 (book §5.2 "Rescue seal"): the user's Stop is one of
            // the exits from DEVICE_LOST. The rescued take was sealed when
            // the device was lost; nothing is in flight, so this only leaves
            // through FINALIZING, which settles to IDLE once the take's
            // publication has ended. The armed tracks are left as they are.
            // A DEVICE_LOST that somehow still holds an ACTIVE pipeline —
            // no path here does today; a future one (story 338's watchdog)
            // might — falls through to the ordinary stop below instead, so
            // the machine can never hold an active pipeline with no exit.
            transition(RecordState.FINALIZING);
            settle();
            return;
        }
        if (isRecordingInFlight() && state.get() != RecordState.ABORTED) transition(RecordState.FINALIZING);
        if (recordingPipeline != null && recordingPipeline.isActive()) {
            RecordingPipeline stopping = recordingPipeline;
            recordingPipeline = null;
            CompletionStage<Void> written = requestTakeStop(stopping, TakeManifest.SealedBy.STOP);
            setRecordingStatus(TAKE_FINISHING_MESSAGE);
            publishWhenWritten(stopping, written);
            stillWritingDelay.after(TAKE_STILL_WRITING_DELAY, () -> warnTakeStillWriting(stopping));
        }
    }

    /**
     * The device of the take being recorded was lost (Recording Reliability
     * book §2.8, §5.5; story 327): the engine has already stopped its stream
     * and declared {@code EngineState.DEVICE_LOST}, and the bridge in
     * {@code TransportController} posts that fact here. What happens depends
     * on the take in flight:
     * <ul>
     *   <li>RECORDING with an audio pipeline: the take is rescued FIRST —
     *       the pipeline's ordinary stop with reason
     *       {@link TakeManifest.SealedBy#DEVICE_LOSS}
     *       ({@link #requestTakeRescue}): gate closed, callback removed,
     *       flags cleared, transport stopped, and the seal requested of the
     *       capture thread, which the take's publication then follows on a
     *       later FX turn exactly as after a Stop ({@link #publishWhenWritten},
     *       the still-writing warning included). Only then, each step on
     *       its own and logging its own failure ({@link #rescueStep}): the
     *       state becomes DEVICE_LOST (REC off), the status bar says the
     *       device was lost and the take is being saved, MIDI recorders of
     *       a mixed take are stopped as the Stop path stops them, and the
     *       transport's Stop tail — the Stopped announce, the stream, the
     *       status — runs through
     *       {@code TransportController.finishDeviceLossStop}. A UI step
     *       that throws therefore never skips the rescue.</li>
     *   <li>RECORDING with only MIDI recorders: there is no audio to rescue
     *       and the engine that drove the transport is stopped, so the take
     *       is stopped as the user's Stop stops it; the controller's own
     *       notification covers the loss.</li>
     *   <li>PREPARING: the start is cancelled as Stop cancels it
     *       ({@link #cancelPendingStart}) and ends ABORTED with an ERROR
     *       naming the device — book §3.2 has no arrow from PREPARING to
     *       DEVICE_LOST, and no take had begun.</li>
     *   <li>Anything else — IDLE, or the FINALIZING or ABORTED of an earlier
     *       take — changes nothing here: the controller's own notification
     *       covers the playback loss.</li>
     * </ul>
     * A {@linkplain #retire() retired} coordinator ignores it: its take was
     * handed to {@link #reportTakeOfAReplacedProject}. Nothing here touches
     * storage or waits. FX thread.
     *
     * @param device the lost device, as the engine controller watched it
     */
    void onDeviceLost(DeviceId device) {
        requireFxThread();
        Objects.requireNonNull(device, "device must not be null");
        if (retired) return;
        if (pendingStart != null) {
            LOG.warning("Audio device " + device.name() + " was lost while the take was being prepared");
            cancelPendingStart();
            Throwable failure = new IllegalStateException("audio device " + device.name()
                    + " was lost while the take was being prepared");
            abortStep(failure, () -> transition(RecordState.ABORTED));
            stopMidiRecording(failure);
            abortStep(failure, project.getTransport()::stop);
            String message = "Recording aborted — no take was started: " + failure.getMessage();
            abortStep(failure, () -> setRecordingStatus(message));
            abortStep(failure, () -> statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12)));
            abortStep(failure, () -> notificationBar.show(NotificationLevel.ERROR, message));
            abortStep(failure, this::stopAudioOutputUnlessRolling);
            abortStep(failure, this::updateStatus);
            if (failure.getSuppressed().length > 0) {
                LOG.log(Level.WARNING, "A step of the lost-device start cancellation failed", failure);
            }
            return;
        }
        if (state.get() != RecordState.RECORDING) return;
        if (recordingPipeline == null || !recordingPipeline.isActive()) {
            LOG.warning("Audio device " + device.name() + " was lost during a MIDI-only take; stopping it");
            stop();
            return;
        }
        RecordingPipeline rescued = recordingPipeline;
        Transport transportModel = project.getTransport();
        boolean wasRolling = transportModel.getState() != TransportState.STOPPED;
        long stoppedAtFrames = core.beatsToFrames(transportModel.getPositionInBeats());
        LOG.warning("Audio device " + device.name() + " was lost while recording the take under "
                + rescued.getTakeDirectory() + "; rescuing it");
        lostDevice = device;
        lostDeviceReturned = false;
        rescuedTakePipeline = rescued;
        // The rescue first, independent of every step after it (book §5.5):
        // the seal is requested before the state changes, before the status
        // bar, the MIDI recorders, the transport tail or any message — so a
        // step of the UI that throws cannot leave an active pipeline behind
        // — and each later step logs its own failure (rescueStep). Nothing
        // posted here can run before this method returns.
        recordingPipeline = null;
        CompletionStage<Void> written = requestTakeRescue(rescued);
        publishWhenWritten(rescued, written);
        stillWritingDelay.after(TAKE_STILL_WRITING_DELAY, () -> warnTakeStillWriting(rescued));
        rescueStep("Entering DEVICE_LOST", () -> transition(RecordState.DEVICE_LOST));
        rescueStep("The device-lost status", () -> setRecordingStatus(deviceLostStatus(device, false)));
        rescueStep("The device-lost glyph", () -> statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12)));
        rescueStep("Stopping the MIDI recorders", this::stopMidiRecording);
        rescueStep("The transport's stop", () -> transport.finishDeviceLossStop(wasRolling, stoppedAtFrames));
    }

    /**
     * One step of {@link #onDeviceLost} after the rescue was requested: its
     * failure is logged at WARNING, named by {@code what}, and the steps
     * after it still run. FX thread.
     */
    private static void rescueStep(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, what + " after the device loss failed", failure);
        }
    }

    /**
     * The loss is over: the engine has a stream open again, on
     * {@code device} (book §5.2 "Device returned", §5.5 step 6; story 327).
     * While DEVICE_LOST, when {@code device} is the lost device — the
     * controller's own predicate,
     * {@link DefaultAudioEngineController#sameDevice} — the transport is
     * seeked to the rescued take's start anchor — the beat the pipeline
     * read at {@code prepare()},
     * {@code RecordingPipeline.getRecordingStartBeat()}, which outlives the
     * take — and the user is told it reconnected, that the tracks are
     * still armed and that Record continues. When it is another device
     * (story 327 fix round: the instrument-audio open of
     * {@code MainController.pluginGraphChanged} walks the provision ladder
     * while the interface is unplugged and opens whatever rung it yields),
     * nothing is seeked — the playhead belongs to whatever opened that
     * stream — and the INFO is {@link #lossEndedOnAnotherDeviceMessage}.
     * Either way the state settles to IDLE at once, or once the rescued
     * take's publication has ended. Recording never restarts itself, and
     * nothing here disarms a track: the armed set is exactly what it was
     * when the device was lost. Ignored in any other state, and by a
     * {@linkplain #retire() retired} coordinator. FX thread.
     *
     * @param device the device the engine's stream is open on now
     */
    void onLostDeviceReturned(DeviceId device) {
        requireFxThread();
        Objects.requireNonNull(device, "device must not be null");
        if (retired || state.get() != RecordState.DEVICE_LOST) return;
        String rescuedTake = rescuedTakePipeline == null ? "(none)" : rescuedTakePipeline.getTakeDirectory().toString();
        lostDeviceReturned = true;
        if (lostDevice == null || DefaultAudioEngineController.sameDevice(lostDevice, device)) {
            LOG.info("Audio device " + device.name() + " returned after the rescue of the take under " + rescuedTake);
            if (rescuedTakePipeline != null) {
                try {
                    project.getTransport().setPositionInBeats(rescuedTakePipeline.getRecordingStartBeat());
                } catch (RuntimeException failure) {
                    LOG.log(Level.WARNING, "Could not seek to the rescued take's start", failure);
                }
            }
            notificationBar.show(NotificationLevel.INFO, device.name()
                    + " reconnected. Tracks are still armed; press Record to continue.");
        } else {
            LOG.info("Audio is on " + device.name() + " after the rescue of the take under " + rescuedTake
                    + "; the lost device " + lostDevice.name() + " did not return");
            notificationBar.show(NotificationLevel.INFO, lossEndedOnAnotherDeviceMessage(device, lostDevice));
        }
        settle();
        updateStatus();
    }

    /**
     * The INFO shown when the loss ends with a stream open on a device that
     * is not the lost one (story 327 fix round): the one the audio is on
     * now, that the lost device did not return, and that the armed tracks
     * are kept. Nothing is seeked: the playhead belongs to whatever opened
     * that stream, not to the rescued take.
     */
    static String lossEndedOnAnotherDeviceMessage(DeviceId current, DeviceId lost) {
        return "Audio is now on " + current.name() + "; " + lost.name()
                + " did not return. Tracks are still armed; press Record to continue.";
    }

    /**
     * Asks {@code pipeline} to rescue its take — its one-shot stop with
     * reason {@link TakeManifest.SealedBy#DEVICE_LOSS}, after which the
     * manifest reads {@code aborted}/{@code device-loss} and every lane is
     * sealed as after a Stop — and returns its capture thread's termination
     * signal, as {@link #requestTakeStop} does, with the same retry. FX thread.
     */
    private static CompletionStage<Void> requestTakeRescue(RecordingPipeline pipeline) {
        return requestTakeStop(pipeline, TakeManifest.SealedBy.DEVICE_LOSS);
    }

    /**
     * The status text while DEVICE_LOST: the lost device's name and, before
     * the rescued take is published, that it is being saved; afterwards that
     * it is preserved.
     */
    static String deviceLostStatus(DeviceId device, boolean published) {
        return "Audio device lost — " + device.name() + (published ? "; take preserved" : "; saving the take");
    }

    /**
     * The WARNING shown once the rescued take is published (book §5.5 step
     * 5): the device, that the take was saved up to the dropout, and what
     * it holds; or, with no clip, that nothing had reached disk yet. Not an
     * ERROR: nothing failed on our side.
     */
    static String rescuedTakeMessage(DeviceId device, int clipCount, int trackCount) {
        if (clipCount == 0) {
            return deviceDisconnectedText(device) + "; nothing had reached disk yet.";
        }
        return deviceDisconnectedText(device) + ". The take was saved up to the dropout: "
                + clipsOnTracksText(clipCount, trackCount) + ".";
    }

    /**
     * The ERROR of a device-loss rescue whose every lane sealed but whose
     * final manifest write failed on every attempt
     * ({@link FinalManifestFailure}; PR #982 review): it leads with the
     * device-loss wording, says that the audio was saved up to the dropout
     * — the segments are sealed — but that the take's manifest could not be
     * written, with the reason ({@link #manifestNotWrittenText}), and that
     * take recovery rebuilds it ({@link #MANIFEST_REBUILT_BY_RECOVERY_TEXT}).
     * Such a take is not a rescued take on disk, so it is never registered
     * and never called preserved. With no clip — no track recorded a frame,
     * so no segment holds audio and the live seal deleted the empty tails —
     * that nothing had reached disk yet and the manifest could not be
     * written, with no word of a rebuild: there is no segment to rebuild a
     * take from (story 327, Known limitations).
     */
    static String rescuedTakeManifestNotWrittenMessage(DeviceId device, FinalManifestFailure failure,
                                                      int clipCount, int trackCount) {
        if (clipCount == 0) {
            return deviceDisconnectedText(device) + "; nothing had reached disk yet, and "
                    + manifestNotWrittenText(failure);
        }
        return deviceDisconnectedText(device) + ". The audio was saved up to the dropout ("
                + clipsOnTracksText(clipCount, trackCount) + "), but " + manifestNotWrittenText(failure) + "; "
                + MANIFEST_REBUILT_BY_RECOVERY_TEXT;
    }

    /** The lead every device-loss report shares: {@code <device> disconnected during recording}. */
    private static String deviceDisconnectedText(DeviceId device) {
        return device.name() + " disconnected during recording";
    }

    /** {@code 1 clip on 1 track}, {@code 2 clips on 2 tracks}. */
    private static String clipsOnTracksText(int clipCount, int trackCount) {
        return clipCount + " clip" + (clipCount == 1 ? "" : "s") + " on " + trackCount + " track"
                + (trackCount == 1 ? "" : "s");
    }

    /**
     * The status text the "Review" action of {@link #rescuedTakeMessage}
     * sets: the take's folder and the recorded length as {@code mm:ss.t}.
     */
    static String rescuedTakeReviewStatus(Path takeDirectory, double recordedSeconds) {
        long tenths = Math.round(Math.max(0.0, recordedSeconds) * 10.0);
        long minutes = tenths / 600;
        long seconds = (tenths % 600) / 10;
        long tenth = tenths % 10;
        return "Rescued take: " + ProjectManager.AUDIO_DIR_NAME + "/" + TakeDirectories.TAKES_DIR_NAME + "/"
                + takeDirectory.getFileName() + " — " + String.format("%02d:%02d.%d", minutes, seconds, tenth)
                + " recorded";
    }

    /**
     * The registry of this project's rescued takes (book §2.9), created on
     * the first rescue from the project's directory; empty while the project
     * has never been saved — an audio take is refused without a directory,
     * so a rescue always has one, and a MIDI-only take has nothing to
     * rescue. Package-visible for tests. FX thread.
     */
    Optional<IncompleteTakeStore> rescuedTakeStore() {
        if (rescuedTakeStore == null) {
            projectDirectory().ifPresent(directory -> rescuedTakeStore = new IncompleteTakeStore(directory));
        }
        return Optional.ofNullable(rescuedTakeStore);
    }

    /**
     * Test seam: replaces how a rescued take is registered (production:
     * {@link #registerRescuedTake}, into {@link #rescuedTakeStore()}), so
     * that a test can make the registration step fail and see the steps
     * around it complete all the same. FX thread, before the device is lost.
     */
    void setRescuedTakeRegistrationForTest(Consumer<IncompleteTakeStore.RescuedTake> registration) {
        rescuedTakeRegistration = Objects.requireNonNull(registration, "registration must not be null");
    }

    /** The production {@link #rescuedTakeRegistration}: into this project's registry. FX thread. */
    private void registerRescuedTake(IncompleteTakeStore.RescuedTake take) {
        rescuedTakeStore().orElseThrow(() -> new IllegalStateException(
                "the project has no directory, so it has no rescued-take registry")).register(take);
    }

    /**
     * Test seam: replaces how the rescued take's WARNING is shown
     * (production: {@link #showRescuedTakeNotification}, through the
     * notification bar with its "Review" action), so that a test can make
     * the notification step fail and see the steps before it — the seal,
     * the publication, the registration — complete all the same. The bar
     * and its history are final classes, so no failing subclass can stand
     * in for them. FX thread, before the device is lost.
     *
     * @param notification receives the message and the "Review" action
     */
    void setRescuedTakeNotificationForTest(BiConsumer<String, Runnable> notification) {
        rescuedTakeNotification = Objects.requireNonNull(notification, "notification must not be null");
    }

    /** The production {@link #rescuedTakeNotification}: the WARNING with its "Review" action. FX thread. */
    private void showRescuedTakeNotification(String message, Runnable review) {
        notificationBar.show(NotificationLevel.WARNING, message, "Review", review);
    }

    /**
     * What follows the publication of a rescued take's clips once it has
     * been {@linkplain #registerSealedRescue registered} (book §5.5 step 5):
     * the status bar says the take is preserved and the WARNING of
     * {@link #rescuedTakeMessage} is shown ({@link #rescuedTakeNotification})
     * with a "Review" action that puts the take's folder and recorded
     * length in the status bar ({@link #rescuedTakeReviewStatus}) and, while
     * the transport is STOPPED or PAUSED (a paused playhead is not playing),
     * seeks it to the take's start anchor — over a rolling transport the
     * review only sets the status text; it never moves a playhead that is
     * playing. The review seeks only: there is no selection seam here to
     * select the rescued clips with. A failure is logged, never propagated.
     * FX thread.
     *
     * @param clips      the clips the completion returned
     * @param trackCount how many tracks the take recorded onto
     */
    private void showRescuedTake(RecordingPipeline pipeline, List<AudioClip> clips, int trackCount) {
        DeviceId device = lostDevice;
        String message = rescuedTakeMessage(device, clips.size(), trackCount);
        LOG.warning(message);
        try {
            setRecordingStatus(deviceLostStatus(device, true));
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            double recordedSeconds = recordedSeconds(clips);
            double anchor = pipeline.getRecordingStartBeat();
            Path takeDirectory = pipeline.getTakeDirectory();
            rescuedTakeNotification.accept(message, () -> {
                Transport transportModel = project.getTransport();
                TransportState transportState = transportModel.getState();
                if (transportState == TransportState.STOPPED || transportState == TransportState.PAUSED) {
                    transportModel.setPositionInBeats(anchor);
                }
                setRecordingStatus(rescuedTakeReviewStatus(takeDirectory, recordedSeconds));
            });
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "Could not show the rescued take under " + pipeline.getTakeDirectory(), failure);
        }
    }

    /**
     * The WARNING a Record or a Play pressed while DEVICE_LOST gets (book
     * §5.2 "Record while lost"; story 327): {@code action} is unavailable,
     * the lost device is named, and the three ways out — reconnecting it,
     * Stop, or another device in Audio Settings.
     *
     * @param action what was pressed, as a sentence subject ("Record", "Playback")
     * @param device the lost device; empty when none is remembered
     */
    static String unavailableWhileDeviceLostMessage(String action, Optional<DeviceId> device) {
        return action + " is unavailable — audio device " + device.map(lost -> lost.name() + " ").orElse("")
                + "is still disconnected; reconnect it, press Stop, or choose another device in Audio Settings";
    }

    /**
     * Refuses {@code action} because the record state is DEVICE_LOST: logs,
     * puts {@link #unavailableWhileDeviceLostMessage} in the status bar with
     * the warning glyph, and shows it as a WARNING. Nothing else is touched.
     * Called by {@link #onRecord} and by the transport controller's Play
     * paths. FX thread, in state DEVICE_LOST.
     */
    void refuseWhileDeviceLost(String action) {
        requireFxThread();
        String message = unavailableWhileDeviceLostMessage(action, Optional.ofNullable(lostDevice));
        LOG.warning(message);
        setRecordingStatus(message);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
        notificationBar.show(NotificationLevel.WARNING, message);
    }

    /** The longest clip's duration, in seconds at the transport's current tempo; zero with no clip. FX thread. */
    private double recordedSeconds(List<AudioClip> clips) {
        double beats = 0.0;
        for (AudioClip clip : clips) {
            beats = Math.max(beats, clip.getDurationBeats());
        }
        double tempo = project.getTransport().getTempo();
        return tempo > 0.0 ? beats * 60.0 / tempo : 0.0;
    }


    /** Off-FX input plan/open worker of the latest audio start, retained for deterministic tests. */
    private volatile Thread sessionInputCheck;

    /**
     * The audio take being recorded (RECORDING): set by the readiness turn
     * ({@link #onTakeReady}) once capture has begun, cleared by
     * {@code transport.stop()}. FX thread.
     */
    private RecordingPipeline recordingPipeline;
    /**
     * PR #978 review 5391920205 (F2) — the audio take Record started that has
     * not begun capture yet (PREPARING): set by {@link #onRecord()}, cleared
     * when capture begins, when the start fails, and when Stop, Record
     * pressed again, the end of a post-roll ({@code TransportController.finishPostRoll()}) or
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
     * published yet: set by {@code transport.stop()}, cleared on the FX turn that
     * publishes the take ({@link #publishWrittenTake}) — after its capture
     * thread has terminated and its audio has been read back from its
     * segments — or that ends it without a publication. While set,
     * {@link #onRecord()} refuses and {@link #isTakeBeingWritten()} is
     * {@code true}. FX thread.
     */
    private RecordingPipeline writingPipeline;
    /**
     * Story 327 — the device whose loss put this coordinator in DEVICE_LOST,
     * named by the rescue's status and messages; set by {@link #onDeviceLost}
     * and kept until the next loss. FX thread.
     */
    private DeviceId lostDevice;
    /**
     * Story 327 — whether the lost device has returned
     * ({@link #onLostDeviceReturned}): the one thing that lets
     * {@link #settle()} leave DEVICE_LOST for IDLE. FX thread.
     */
    private boolean lostDeviceReturned;
    /**
     * Story 327 — the pipeline of the take the device loss rescued: its
     * publication registers it and shows the rescue message instead of the
     * SUCCESS, and its start anchor is where the lost device's return seeks
     * to. Set by {@link #onDeviceLost}; kept until the next rescue. FX thread.
     */
    private RecordingPipeline rescuedTakePipeline;
    /**
     * Story 327 — this project's rescued-take registry (book §2.9), created
     * on first use by {@link #rescuedTakeStore()}. FX thread.
     */
    private IncompleteTakeStore rescuedTakeStore;
    /**
     * Story 327 — how a rescued take is registered: {@link #registerRescuedTake};
     * replaced only by {@link #setRescuedTakeRegistrationForTest}.
     */
    private Consumer<IncompleteTakeStore.RescuedTake> rescuedTakeRegistration = this::registerRescuedTake;
    /**
     * Story 327 — how the rescued take's WARNING is shown:
     * {@link #showRescuedTakeNotification}; replaced only by
     * {@link #setRescuedTakeNotificationForTest}.
     */
    private BiConsumer<String, Runnable> rescuedTakeNotification = this::showRescuedTakeNotification;
    /**
     * The live peaks of the audio take in hand — one continuous channel per
     * armed audio track (Recording Reliability book §4.5): opened when the
     * take's pipeline is built ({@link #onTakeDirectoryAllocated}), closed
     * when the take ends — its capture thread has terminated after a Stop,
     * it was cancelled while it was being prepared, its start failed — and
     * by {@link #retire()}
     * ({@link #closeCapturePeaks}). {@code null} between takes, and for a
     * take recorded with no {@link FxDispatcher} to open the channels on.
     * FX thread.
     */
    private LiveCapturePeaks capturePeaks;
    /**
     * Where the controller's own storage work for a take runs: allocating
     * its take directory under {@code audio/takes}, removing the take
     * directory of a start that was cancelled or failed, if it is empty
     * ({@link #deleteEmptyTakeDirectory}), and reading a stopped take's
     * audio back from its sealed segments before the take is published
     * ({@link #readRecordedAudio}).
     * Production: a
     * new virtual thread per task ({@link #onAVirtualThread}), never the FX
     * thread; replaced only by {@link #setStorageExecutorForTest}.
     */
    private Executor storageExecutor = RecordCoordinator::onAVirtualThread;
    /**
     * What is done to a take's pipeline after it is built and before its
     * {@code prepare()} — nothing in production; replaced only by
     * {@link #setPipelineSetupForTest}.
     */
    private Consumer<RecordingPipeline> pipelineSetup = _ -> { };
    /**
     * How a stopped take is completed once its capture thread has
     * terminated and its audio has been read back —
     * {@code RecordingPipeline::completeStop}, the form that is handed the
     * audio; replaced only by {@link #setTakeCompletionForTest}.
     */
    private TakeCompletion takeCompletion = RecordingPipeline::completeStop;
    /**
     * How the still-writing warning is scheduled after a Stop — a
     * {@link PauseTransition} ({@link #afterOnFx}); replaced only by
     * {@link #setStillWritingDelayForTest}.
     */
    private FxDelay stillWritingDelay = RecordCoordinator::afterOnFx;
    /**
     * How a take's early-seal signal is read — {@code RecordingPipeline::earlySeal};
     * replaced only by {@link #setEarlySealSignalForTest}.
     */
    private EarlySealSignal earlySealSignal = RecordingPipeline::earlySeal;
    /**
     * How the outcome of the seal a take's Stop or rescue requested is read
     * — {@link StopSealOutcome#of}, the pipeline's {@code stopSealFailure()}
     * and {@code finalManifestFailure()}; replaced only by
     * {@link #setStopSealOutcomeForTest}.
     */
    private StopSealOutcomeReader stopSealOutcome = StopSealOutcome::of;
    /**
     * How {@link #startMidiRecording} finds an armed MIDI track's input
     * device by its name — {@link #resolveMidiDevice}; replaced only by
     * {@link #setMidiInputDeviceResolverForTest}.
     */
    private Function<String, MidiDevice> midiInputDeviceResolver = RecordCoordinator::resolveMidiDevice;
    private LongSupplier midiMonotonicNanos = System::nanoTime;
    private final Map<Track, MidiRecorder> activeMidiRecorders = new LinkedHashMap<>();

    /**
     * Asks {@code pipeline} to stop its take and returns its capture thread's
     * termination signal, without waiting for it. When a step of the
     * pipeline's one-shot stop throws (a transport listener, say), the seal
     * has been requested all the same: the failure is logged, and a repeat
     * request — which repeats none of those steps — hands back the same
     * signal, so the take is still published. FX thread.
     */
    private static CompletionStage<Void> requestTakeStop(RecordingPipeline pipeline, TakeManifest.SealedBy reason) {
        try {
            return pipeline.requestStop(reason);
        } catch (RuntimeException oneShotFailure) {
            LOG.log(Level.WARNING, "A step of the stop of the take under " + pipeline.getTakeDirectory()
                    + " failed; the take is sealed all the same", oneShotFailure);
            return pipeline.requestStop(reason);
        }
    }

    /**
     * Whether a recording is in flight and still needs finalizing — an audio
     * take being prepared ({@link #pendingStart}), an active audio pipeline
     * or any live MIDI recorder. Read by {@code transport.stop()} to decide between
     * the double-stop rewind gesture and the full stop flow: the pipeline can
     * be active while the transport is STOPPED (see {@code transport.stop()}: an
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
     * finished by the FX turns that follow its capture thread's termination
     * (the read of its audio is handed off, then the take is published),
     * never by another Stop. So a Stop over a stopped transport while the take is being
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
     * completes the take ({@link #publishWrittenTake}): registers the
     * "Record Audio" undo action over the pipeline's recorded clips and marks
     * the project dirty, so the unsaved-changes prompt asks for the Save that
     * writes the clips' references into {@code project.daw} — as
     * {@code MainController}'s undo and redo mark it after their own
     * mutation. Whether the take was sealed early or the seal its Stop
     * requested failed makes no difference here: its clips are in the project
     * either way. The caller then shows the SUCCESS toast
     * ({@link #showTakePublished}), the report of a finalisation that did
     * not end cleanly ({@link #finalizationFailureReport}), or the report of
     * audio that could not be read back ({@link #unloadedAudioReport}).
     * Nothing when the take produced no clip: the pipeline's
     * {@code completeStop} then added nothing to any track. The undo
     * manager's history listener in {@code MainController} repaints the
     * arrangement when the entry is pushed, which is when the clips — and
     * their waveforms — first show. FX thread.
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
     * cleanly, whose audio was read back for every clip, and that produced
     * {@code clipCount} clips; nothing when it produced none. FX thread.
     */
    private void showTakePublished(int clipCount) {
        if (clipCount == 0) {
            return;
        }
        String message = "Recording stopped — " + clipsCreatedText(clipCount);
        setRecordingStatus(message);
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
     * The FX turn that follows the termination of a stopped take's capture
     * thread: the take's segments are final, and its audio is read back from
     * them before anything is published (Recording Reliability book §4.4).
     * It asks the pipeline for the segment-path list of every clip the take
     * will publish ({@code RecordingPipeline.recordedSegmentPaths()}, which
     * touches no storage) and hands the read of those lists to the storage
     * executor ({@link #readRecordedAudio}); when that task has ended —
     * normally or not — {@link #publishWrittenTake} is posted to the FX
     * thread with what it read. Nothing here reads a file or waits. A take
     * with nothing to read — it recorded nothing, or none of its segments
     * was sealed — is published on this turn. If the lists cannot be had,
     * the take ends here as a completion that failed
     * ({@link #reportTakeCompletionFailure}).
     *
     * <p>The take stays "being written" for all of this:
     * {@link #writingPipeline} is cleared only by the turn that publishes
     * the take, so through the read Record is refused, the doors that
     * replace the open project are refused and the delayed still-writing
     * warning fires, exactly as they do while the capture thread is still
     * running. No clip of the take is on any track until that turn, and
     * there each clip is given its audio before it is added to its track —
     * so playback, a bounce, an export or a split never meets a recorded
     * clip whose audio is still on its way.</p>
     *
     * <p>The take's live peak channels are closed here, whatever follows
     * ({@link #closeCapturePeaks}). A controller
     * {@linkplain #retire() retired} by now reads nothing and publishes
     * nothing ({@link #reportTakeOfAReplacedProject}). FX thread.</p>
     */
    private void finishWrittenTake(RecordingPipeline pipeline) {
        closeCapturePeaks();
        if (unannouncedTake == pipeline && !unannouncedStreamClosed) {
            unannouncedStart.filesCleanupComplete = true;
            if (unannouncedCleanupRunning) return;
            unannouncedCleanupRunning = true;
            Object streamGeneration = unannouncedStart.streamGeneration;
            CompletableFuture.runAsync(() -> audioEngine.stopOwnedAudioStream(streamGeneration),
                    RecordCoordinator::onAVirtualThread).whenComplete((_, closeFailure) -> postFx(() -> {
                unannouncedCleanupRunning = false;
                if (closeFailure != null) {
                    rememberUnannouncedCleanupFailure(new IllegalStateException("Recording input cleanup failed", causeOf(closeFailure)));
                    if (!retired) notificationBar.show(NotificationLevel.ERROR,
                            "Recording input cleanup failed: " + shortDescription(causeOf(closeFailure)));
                } else {
                    unannouncedStreamClosed = true;
                    finishWrittenTake(pipeline);
                }
            }));
            return;
        }
        if (retired) {
            try { reportTakeOfAReplacedProject(pipeline); }
            finally { endWrittenTake(pipeline); }
            return;
        }
        List<List<String>> segmentLists;
        try {
            segmentLists = pipeline.recordedSegmentPaths();
        } catch (RuntimeException failure) {
            try { reportTakeCompletionFailure(pipeline, failure); }
            finally { endWrittenTake(pipeline); }
            return;
        }
        if (segmentLists.isEmpty()) {
            publishWrittenTake(pipeline, segmentLists, LoadedTake.NOTHING, null);
            return;
        }
        BooleanSupplier abandoned = () -> retired;
        try {
            CompletableFuture.supplyAsync(() -> readRecordedAudio(segmentLists, abandoned), storageExecutor)
                .whenComplete((loaded, readFailure) ->
                        postFx(() -> publishWrittenTake(pipeline, segmentLists, loaded, readFailure)));
        } catch (RuntimeException rejected) {
            publishWrittenTake(pipeline, segmentLists, null, rejected);
        }
    }

    /**
     * The FX turn that publishes a stopped take, once its capture thread has
     * terminated and the read of its audio has ended: completes the take on
     * that same pipeline ({@code RecordingPipeline.completeStop(Function)},
     * through {@link TakeCompletion}, which builds the clips without
     * repeating the Stop's one-shot steps, gives each clip the audio read
     * for its segment list and only then adds it to its track) and
     * publishes the clips through {@link #publishRecordedTake}: the "Record
     * Audio" undo entry, the dirty mark, and the SUCCESS toast and status
     * text of a take whose finalisation ended cleanly and whose audio was
     * read for every clip. A take that produced no clip and whose
     * finalisation ended cleanly only takes back the status bar's
     * {@link #TAKE_FINISHING_MESSAGE} or {@link #TAKE_STILL_WRITING_MESSAGE},
     * if it still says one of them, and leaves the project as it was. A take
     * whose finalisation did not end cleanly — sealed early by the capture
     * thread, a lane that threw in the seal the Stop requested, or a final
     * manifest write that failed on every attempt — is
     * reported here: the report of {@link #finalizationFailureReport} as the
     * ERROR toast and the status text, with or without clips. A failure of
     * the completion itself is logged SEVERE and shown as an ERROR toast
     * ({@link #reportTakeCompletionFailure}). From this turn on Record is
     * available again, whatever the outcome.
     *
     * <p><strong>Audio that could not be read back.</strong> The take is
     * published all the same; nothing is deleted. A clip whose segments
     * could not be read ({@code loaded.failures()}: a file missing or
     * unreadable, segments that disagree in channel count or rate) is
     * published without audio and keeps its segment references. A read that
     * failed as a whole ({@code readFailure}: what the storage task threw,
     * an {@link Error} such as {@link OutOfMemoryError} included) leaves
     * every clip that had something to read without audio. Either way the
     * take gets one ERROR toast, which is the status text too, in place of
     * the SUCCESS: what became of the take — the clips created, or the
     * report of a finalisation that did not end cleanly — followed by the
     * clips left without audio and the take's folder
     * ({@link #unloadedAudioReport}).</p>
     *
     * <p>A controller {@linkplain #retire() retired} by now does none of
     * this — it completes nothing, publishes nothing, marks nothing dirty,
     * reports no seal and drops what was read: it only reports where the
     * take's files are ({@link #reportTakeOfAReplacedProject}). FX
     * thread.</p>
     *
     * @param segmentLists the lists that were handed to the read
     * @param loaded       what the read returned; {@code null} when it failed as a whole
     * @param readFailure  what the storage task threw; {@code null} when it returned
     */
    private void publishWrittenTake(RecordingPipeline pipeline, List<List<String>> segmentLists,
                                   LoadedTake loaded, Throwable readFailure) {
        try {
            completeWrittenTake(pipeline, segmentLists, loaded, readFailure);
        } finally {
            endWrittenTake(pipeline);
            settle();
        }
    }

    private void completeWrittenTake(RecordingPipeline pipeline, List<List<String>> segmentLists,
                                     LoadedTake loaded, Throwable readFailure) {
        if (retired) {
            reportTakeOfAReplacedProject(pipeline);
            return;
        }
        Map<List<String>, float[][]> audio;
        Map<List<String>, String> unread;
        if (readFailure != null) {
            Throwable cause = causeOf(readFailure);
            LOG.log(Level.SEVERE, "Could not read the recorded audio of the take under "
                    + pipeline.getTakeDirectory(), cause);
            String reason = shortDescription(cause);
            audio = Map.of();
            unread = new LinkedHashMap<>();
            for (List<String> segmentPaths : segmentLists) {
                unread.put(segmentPaths, reason);
            }
        } else {
            audio = loaded.audio();
            unread = loaded.failures();
        }
        List<AudioClip> clips;
        try {
            clips = takeCompletion.complete(pipeline, audio::get);
        } catch (RuntimeException failure) {
            reportTakeCompletionFailure(pipeline, failure);
            return;
        }
        int clipCount = clips.size();
        int trackCount = pipeline.getRecordedClips().size();
        boolean rescue = isRescue(pipeline);
        // Read once: the early seal, the lanes' seals and the final manifest
        // write, final now that the take's capture thread has terminated.
        Optional<EarlySeal> earlySeal = earlySealOf(pipeline);
        StopSealOutcome sealOutcome = stopSealOutcome.of(pipeline);
        Optional<String> failureReport = finalizationFailureReport(pipeline, earlySeal, sealOutcome, clipCount,
                trackCount);
        if (unannouncedTake == pipeline && failureReport.isEmpty()) {
            failureReport = Optional.of("Recording aborted — take readiness precondition failed:"
                    + " the flush service ended before capture; " + clipsCreatedText(clipCount));
        }
        if (unannouncedTake == pipeline && unannouncedCleanupFailure != null) {
            String cleanupFailure = unannouncedCleanupFailure;
            failureReport = Optional.of(failureReport.orElseThrow() + "; take cleanup failed: " + cleanupFailure);
        }
        // Story 327 (book §5.5 step 3): a device-loss rescue whose every lane
        // sealed, and whose manifest says so on disk, is a rescued take on
        // disk whatever follows — its audio may still fail to load, a MIDI
        // recorder of a mixed take may have failed to close — so it is
        // registered before any report below, and those reports lead with
        // the device-loss wording instead of "Recording stopped". A rescue
        // whose seal failed — the capture thread sealed the take early
        // (earlySeal: a stop requested first does not prevent it — an
        // exhaustion or a write failure applying a block the final sweep
        // drains completes it — and the manifest then reads write-failure
        // or disk-exhaustion, not device-loss), a lane threw
        // (stopSealFailure), or the final manifest write failed on every
        // attempt (finalManifestFailure: the manifest on disk still reads
        // streaming, and the recovery scan — TakeRecovery.scan, which story
        // 332 runs on project open — rebuilds it as crash recovery) — is not
        // a rescued take and stays unregistered (PR #982 review; round 2 for
        // the early seal).
        boolean rescueSealed = rescue && earlySeal.isEmpty() && sealOutcome.isClean();
        if (rescueSealed) {
            int sealedSegments = 0;
            for (List<String> segmentPaths : segmentLists) sealedSegments += segmentPaths.size();
            registerSealedRescue(pipeline, clipCount, sealedSegments);
        }
        if (midiStopFailure != null) {
            String midiFailure = midiStopFailure;
            String outcome = rescueSealed ? rescuedTakeMessage(lostDevice, clipCount, trackCount)
                    : "Recording stopped — " + clipsCreatedText(clipCount);
            failureReport = Optional.of(failureReport.map(report -> joinedReport(report, midiFailure))
                    .orElse(joinedReport(outcome, midiFailure)));
        }
        Optional<String> unloadedReport = unloadedAudioReport(pipeline, clips, unread);
        if (clipCount == 0 && failureReport.isEmpty() && unloadedReport.isEmpty()
                && statusBarSaysTheTakeIsBeingFinished()) {
            setRecordingStatus(TAKE_WRITTEN_WITHOUT_CLIPS_MESSAGE);
        }
        publishRecordedTake(pipeline, clips);
        if (unloadedReport.isPresent()) {
            String outcome = failureReport.orElseGet(() -> rescueSealed
                    ? rescuedTakeMessage(lostDevice, clipCount, trackCount)
                    : "Recording stopped" + (clipCount == 0 ? "" : " — " + clipsCreatedText(clipCount)));
            reportTakeFinalizationFailure(joinedReport(outcome, unloadedReport.get()));
        } else if (failureReport.isPresent()) {
            // A rescued take whose seal failed takes this path too (book
            // §5.2 "Rescue failure": DEVICE_LOST → ABORTED): a lane that
            // threw with the ERROR of stopSealFailedMessage and no "saved"
            // wording; a final manifest write that failed with the ERROR of
            // rescuedTakeManifestNotWrittenMessage, which leads with the
            // device-loss wording and never calls the take preserved.
            reportTakeFinalizationFailure(failureReport.get());
        } else if (rescue) {
            // Story 327 (book §5.5 step 5): a device-loss rescue with no
            // early seal, whose every lane sealed and whose manifest says so
            // on disk. finalizationFailureReport is empty for it: the early
            // seal is empty (a stop requested first does not prevent one —
            // an exhaustion or a write failure in the final sweep completes
            // it — and such a rescue was reported above, not shown here),
            // stopSealFailure() is empty when every lane sealed, whatever
            // the reason the stop was requested with, and
            // finalManifestFailure() is empty when the final write landed
            // (DeviceLossSealContractTest).
            showRescuedTake(pipeline, clips, trackCount);
        } else {
            showTakePublished(clipCount);
        }
    }

    /**
     * {@code head}, then {@code "; "}, then {@code tail} — with the period
     * that ends a standalone message ({@link #rescuedTakeMessage}) stripped
     * from {@code head} first, so a report that joins it to a detail never
     * reads {@code ".;"} (story 327 fix round).
     */
    static String joinedReport(String head, String tail) {
        String lead = head.endsWith(".") ? head.substring(0, head.length() - 1) : head;
        return lead + "; " + tail;
    }

    /**
     * Registers the rescued take {@code pipeline} recorded — no early seal,
     * every lane sealed, and the final manifest that says so on disk — with
     * the project's rescued-take registry
     * ({@link #rescuedTakeRegistration}; book §5.5 step 3). A failure is
     * logged, never propagated: the publication and the report that follow
     * do not depend on it. Not an overload of {@link #registerRescuedTake}
     * (the registration itself, the production target of the seam) — the
     * two were, and a method reference to the seam's target must not be
     * ambiguous. FX thread.
     *
     * @param clipCount      the clips the completion returned
     * @param sealedSegments how many segments the take's clips reference
     */
    private void registerSealedRescue(RecordingPipeline pipeline, int clipCount, int sealedSegments) {
        try {
            rescuedTakeRegistration.accept(new IncompleteTakeStore.RescuedTake(
                    pipeline.getTakeDirectory().toAbsolutePath(), TakeManifest.SealedBy.DEVICE_LOSS,
                    Optional.of(lostDevice.name()), Instant.now(), clipCount, sealedSegments));
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "Could not register the rescued take under " + pipeline.getTakeDirectory(), failure);
        }
    }

    /**
     * {@code pipeline}'s take is no longer being written: it is about to be
     * published, or has ended without a publication. FX thread.
     */
    private void endWrittenTake(RecordingPipeline pipeline) {
        if (writingPipeline == pipeline) {
            writingPipeline = null;
            if (unannouncedTake == pipeline) {
                unannouncedTake = null;
                unannouncedStart = null;
                unannouncedStreamClosed = false;
                unannouncedCleanupFailure = null;
            }
            postFx(this::settle);
        }
    }

    /**
     * What a {@linkplain #retire() retired} controller does with a take it
     * stopped, in place of reading or publishing it: the project the take
     * was recorded in has been replaced, the capture thread has terminated
     * and writes nothing more, and completing the take would only build
     * clips onto the tracks of a project that is no longer open. It logs and
     * shows the WARNING of {@link #takeOfAReplacedProjectMessage}, and
     * replaces a status bar that still says the take is being finished with
     * {@link #TAKE_OF_A_REPLACED_PROJECT_STATUS} — nothing on the
     * replacement path rewrites the shared status bar, so its promise that
     * the clips will appear is taken back here. FX thread.
     */
    private void reportTakeOfAReplacedProject(RecordingPipeline pipeline) {
        String message = takeOfAReplacedProjectMessage(project.getName(), pipeline.getTakeDirectory());
        LOG.warning(message);
        notificationBar.show(NotificationLevel.WARNING, message);
        if (statusBarSaysTheTakeIsBeingFinished()) {
            setRecordingStatus(TAKE_OF_A_REPLACED_PROJECT_STATUS);
        }
    }

    /**
     * Logs SEVERE and shows, as the status text and an ERROR toast, that
     * {@code pipeline}'s take could not be completed after it was written,
     * with the reason {@code failure} gives and the take's folder, where its
     * files stay. FX thread.
     */
    private void reportTakeCompletionFailure(RecordingPipeline pipeline, RuntimeException failure) {
        transition(RecordState.ABORTED);
        LOG.log(Level.SEVERE, "Could not finish the take under " + pipeline.getTakeDirectory()
                + " after it was written", failure);
        String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        String message = "Recording could not be finished — " + reason + "; the take's files stay under "
                + ProjectManager.AUDIO_DIR_NAME + "/" + TakeDirectories.TAKES_DIR_NAME + "/"
                + pipeline.getTakeDirectory().getFileName();
        setRecordingStatus(message);
        notificationBar.show(NotificationLevel.ERROR, message);
    }

    /**
     * What {@link #readRecordedAudio} read of one take, keyed by the
     * segment-path lists it was handed.
     *
     * @param audio    the audio read for a list, as {@code [channel][frame]}
     * @param failures why a list could not be read, in the order the lists were handed over
     */
    private record LoadedTake(Map<List<String>, float[][]> audio, Map<List<String>, String> failures) {
        /** Of a take with nothing to read. */
        static final LoadedTake NOTHING = new LoadedTake(Map.of(), Map.of());
    }

    /**
     * Reads the audio of each of {@code segmentLists} from its segment files
     * ({@code SegmentFile.readFrames}), one list after the other. A list
     * that cannot be read — a file that is missing or unreadable, segments
     * that disagree in channel count or sample rate — is logged and entered
     * as a failure with its reason, and the others are still read. Before
     * each list {@code abandoned} is asked, and once it answers
     * {@code true} — the controller was retired — the remaining lists are
     * not read: what this returns is then dropped. It touches paths only,
     * never a clip or any other model object. Storage I/O: run on the
     * storage executor, never on the FX thread.
     */
    private static LoadedTake readRecordedAudio(List<List<String>> segmentLists, BooleanSupplier abandoned) {
        Map<List<String>, float[][]> audio = new HashMap<>();
        Map<List<String>, String> failures = new LinkedHashMap<>();
        for (List<String> segmentPaths : segmentLists) {
            if (abandoned.getAsBoolean()) {
                break;
            }
            try {
                audio.put(segmentPaths, SegmentFile.readFrames(segmentPaths.stream().map(Path::of).toList()));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not read the recorded audio in " + segmentPaths, e);
                failures.put(segmentPaths, e instanceof IOException ioFailure ? ioReason(ioFailure) : shortDescription(e));
            }
        }
        return new LoadedTake(audio, failures);
    }

    /**
     * The report of a published take's audio that could not be read back:
     * empty when {@code unread} is; otherwise a clause that names each clip
     * that was published without its audio — by the clip's name, looked up
     * here, on the FX thread, among {@code clips} and every take of the
     * pipeline's take groups by its segment-path list; by the file name of
     * its first segment when no published clip has that list — with the
     * reason, and names the take's folder under {@code audio/takes}, where
     * the files stay. The caller puts what became of the take in front of
     * it. FX thread.
     *
     * @param clips  the clips the completion returned
     * @param unread why a segment-path list could not be read, by list
     */
    private static Optional<String> unloadedAudioReport(RecordingPipeline pipeline, List<AudioClip> clips,
                                                        Map<List<String>, String> unread) {
        if (unread.isEmpty()) {
            return Optional.empty();
        }
        Map<List<String>, String> names = new HashMap<>();
        for (AudioClip clip : clips) {
            names.putIfAbsent(clip.getSourceSegmentPaths(), clip.getName());
        }
        for (TakeGroup group : pipeline.getTakeGroups().values()) {
            for (Take take : group.takes()) {
                names.putIfAbsent(take.clip().getSourceSegmentPaths(), take.clip().getName());
            }
        }
        List<String> unloaded = new ArrayList<>();
        unread.forEach((segmentPaths, reason) -> {
            String name = names.get(segmentPaths);
            unloaded.add("'" + (name != null ? name : fileNameOf(segmentPaths.getFirst())) + "' (" + reason + ")");
        });
        return Optional.of("the recorded audio of " + String.join(", ", unloaded)
                + " could not be loaded for playback; the take's files stay under "
                + ProjectManager.AUDIO_DIR_NAME + "/" + TakeDirectories.TAKES_DIR_NAME + "/"
                + pipeline.getTakeDirectory().getFileName());
    }

    /** What follows the last {@code /} or {@code \} of {@code path}; all of it when it has neither. */
    private static String fileNameOf(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }


    /**
     * The newest peaks delivered for the lane {@code trackId} is recording
     * in the take in hand — the feed of a live capture waveform, which this
     * controller does not draw. Delivered by the dispatcher's pulse, at most
     * once per frame (Recording Reliability book §4.5). FX thread.
     *
     * @param trackId the id of an armed audio track
     * @return the snapshot, or empty when no take is in hand, before the
     *         first snapshot was delivered, and once the take has ended
     */
    Optional<CapturePeakSnapshot> liveCapturePeaks(String trackId) {
        return capturePeaks == null ? Optional.empty() : capturePeaks.latest(trackId);
    }

    /**
     * Opens the take's live peak channels, one per armed audio track, on
     * the injected {@link FxDispatcher}, else the app-scoped default; with
     * neither (a pure-unit context) no channel is opened and the pipeline
     * is given no sink, so it builds no snapshots. The channels are closed by
     * {@link #closeCapturePeaks}. FX thread.
     */
    private void openCapturePeaks(RecordingPipeline pipeline, List<Track> armedAudioTracks) {
        closeCapturePeaks();
        FxDispatcher dispatcher = fxDispatcher != null ? fxDispatcher : FxDispatcher.getDefault();
        if (dispatcher == null) {
            return;
        }
        LiveCapturePeaks peaks = LiveCapturePeaks.open(
                dispatcher, armedAudioTracks.stream().map(Track::getId).toList());
        capturePeaks = peaks;
        // Runs on the take's capture-flush thread: it only publishes into
        // the channel of the snapshot's track.
        pipeline.setPeakSnapshotSink(peaks::publish);
    }

    /**
     * Closes the live peak channels of the take in hand, if any, and forgets
     * its snapshots: a channel left open is drained on every pulse for the
     * life of the dispatcher. A snapshot the take's capture thread publishes
     * afterwards is never delivered. Idempotent. FX thread.
     */
    private void closeCapturePeaks() {
        if (capturePeaks != null) {
            capturePeaks.close();
            capturePeaks = null;
        }
    }

    /**
     * The delayed half of a Stop's status: when {@code pipeline}'s take has
     * still not been published {@link #TAKE_STILL_WRITING_DELAY} after its
     * Stop, a WARNING toast says {@link #TAKE_STILL_WRITING_MESSAGE}, and so
     * does the status bar if it still says {@link #TAKE_FINISHING_MESSAGE}.
     * Nothing once the take has been published, or once the controller has
     * been {@linkplain #retire() retired}. Scheduled by {@code transport.stop()}
     * through {@link FxDelay}; runs on the FX thread and waits for nothing.
     */
    private void warnTakeStillWriting(RecordingPipeline pipeline) {
        if (retired || writingPipeline != pipeline) {
            return;
        }
        LOG.warning(() -> "The take under " + pipeline.getTakeDirectory() + " is still being written or read back "
                + TAKE_STILL_WRITING_DELAY.toMillis() + " ms after its Stop; its clips are published when that is done");
        if (statusBarStillSays(TAKE_FINISHING_MESSAGE)) {
            setRecordingStatus(TAKE_STILL_WRITING_MESSAGE);
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
     * The FX turn of the auto-Stop: runs {@code transport.stop()} — the user's Stop,
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
        transition(RecordState.ABORTED);
        stop();
    }

    /**
     * The early seal of {@code pipeline}'s take, if its capture thread sealed
     * it on its own. Read by the turn that publishes the take
     * ({@link #publishWrittenTake}), once the take's completion has returned
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
     * early-seal signal, if it ever will ({@code earlySeal}, read by the
     * caller through {@link #earlySealOf}), recorded the failure of the
     * seal the Stop requested, if there was one, and recorded the failure
     * of the final manifest write, if it failed on every attempt
     * ({@code sealOutcome}). The report of a take the capture thread sealed
     * early — for a Stop {@link #takeSealedEarlyMessage}, for a device-loss
     * rescue {@link #rescuedTakeSealedEarlyMessage}: a stop requested first
     * does not prevent an early seal, which an exhaustion or a write
     * failure in the final sweep completes (PR #982 review round 2);
     * otherwise the report of a lane that threw in the seal the Stop
     * requested ({@link #stopSealFailedMessage}); either of them, when the final
     * manifest write failed as well, with that fact joined on
     * ({@link #joinedReport}); otherwise, for a final manifest write that
     * failed after a clean seal of every lane, the report of a manifest
     * that could not be written — for a device-loss rescue leading with the
     * device-loss wording ({@link #rescuedTakeManifestNotWrittenMessage}),
     * for a Stop as {@link #finalManifestNotWrittenMessage} — so that a
     * take whose manifest on disk still reads streaming is never reported
     * as stopped cleanly or as preserved (PR #982 review); otherwise empty
     * — the take was finalised cleanly, and the caller shows the SUCCESS.
     * The core never reports an early seal and a failure of the Stop's seal
     * for one take. This is the one predicate the Stop and the rescue
     * share. Reads only; blocks on nothing. FX thread.
     *
     * @param earlySeal   the early seal the capture thread signalled, if it sealed the take on its own
     * @param sealOutcome how the seal the Stop or the rescue requested ended
     * @param clipCount   the clips the Stop created from the take
     * @param trackCount  how many tracks the take recorded onto
     */
    private Optional<String> finalizationFailureReport(RecordingPipeline pipeline, Optional<EarlySeal> earlySeal,
                                                       StopSealOutcome sealOutcome, int clipCount, int trackCount) {
        Path takeDirectory = pipeline.getTakeDirectory();
        boolean rescue = isRescue(pipeline);
        Optional<String> sealReport = earlySeal
                .map(seal -> rescue
                        ? rescuedTakeSealedEarlyMessage(lostDevice, seal, clipCount, takeDirectory)
                        : takeSealedEarlyMessage(seal, clipCount, takeDirectory))
                .or(() -> sealOutcome.laneFailure()
                        .map(failure -> stopSealFailedMessage(failure, clipCount, takeDirectory)));
        Optional<FinalManifestFailure> manifestFailure = sealOutcome.manifestFailure();
        if (manifestFailure.isEmpty()) {
            return sealReport;
        }
        FinalManifestFailure failure = manifestFailure.get();
        if (sealReport.isPresent()) {
            return Optional.of(joinedReport(sealReport.get(),
                    manifestNotWrittenText(failure) + "; " + MANIFEST_REBUILT_BY_RECOVERY_TEXT));
        }
        return Optional.of(rescue
                ? rescuedTakeManifestNotWrittenMessage(lostDevice, failure, clipCount, trackCount)
                : finalManifestNotWrittenMessage(failure, clipCount));
    }

    /** Whether {@code pipeline}'s take is the one a device loss rescued, with the lost device remembered. FX thread. */
    private boolean isRescue(RecordingPipeline pipeline) {
        return pipeline == rescuedTakePipeline && lostDevice != null;
    }

    /**
     * Logs the report of a take whose finalisation did not end cleanly, or
     * whose audio could not be read back, or both, and shows it as the
     * status text and the ERROR toast. FX thread.
     */
    private void reportTakeFinalizationFailure(String message) {
        transition(RecordState.ABORTED);
        LOG.warning(message);
        setRecordingStatus(message);
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
        return "Recording stopped — " + sealedEarlyText(seal, clipCount, takeDirectory);
    }

    /**
     * The ERROR toast and status text of a device-loss rescue whose take the
     * capture thread sealed early — a stop requested first does not prevent
     * an early seal: an exhaustion, or a write failure applying a block the
     * rescue's own final sweep drained, completes it (PR #982 review round
     * 2) — shown once by the turn that publishes the take: the device-loss
     * lead ({@link #deviceDisconnectedText}), that saving the take ended
     * early, and the cause and what was kept or left in the words of
     * {@link #takeSealedEarlyMessage}. Such a take is not a rescued take on
     * disk — its manifest reads {@code aborted}/{@code write-failure} or
     * {@code disk-exhaustion}, not {@code device-loss} — so it is never
     * registered and never called saved or preserved.
     *
     * @param device        the device whose loss requested the rescue
     * @param seal          the early seal the capture thread signalled
     * @param clipCount     the clips the rescue created from the take
     * @param takeDirectory the take's directory under the project's {@code audio/takes}
     */
    static String rescuedTakeSealedEarlyMessage(DeviceId device, EarlySeal seal, int clipCount, Path takeDirectory) {
        return deviceDisconnectedText(device) + ". Saving the take ended early — "
                + sealedEarlyText(seal, clipCount, takeDirectory);
    }

    /** The cause of an early seal and what it kept or left: the body {@link #takeSealedEarlyMessage} and {@link #rescuedTakeSealedEarlyMessage} share. */
    private static String sealedEarlyText(EarlySeal seal, int clipCount, Path takeDirectory) {
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
        return cause + "; " + kept;
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

    /**
     * What rebuilds the manifest of a take whose final write failed: the
     * recovery scan ({@code TakeRecovery.scan}), from the sealed segments.
     * {@code IncompleteTakeStore.registerRecoveredTakes} runs the scan;
     * story 332 calls it on project open, and until then
     * {@code registerRecoveredTakes} has no production caller. The
     * single-failure reports ({@link #finalManifestNotWrittenMessage} and
     * {@link #rescuedTakeManifestNotWrittenMessage}) say it only of a take
     * that holds a clip: with no clip there is no segment to rebuild it
     * from (story 327, Known limitations). The joined report of
     * {@link #finalizationFailureReport} — an early seal or a lane failure
     * beside the failed write — says it whatever the clip count, since a
     * lane's {@code .part} may remain for the scan.
     */
    private static final String MANIFEST_REBUILT_BY_RECOVERY_TEXT = "take recovery rebuilds it from the segments";

    /**
     * The ERROR toast and status text of a Stop whose every lane sealed but
     * whose final manifest write failed on every attempt
     * ({@link FinalManifestFailure}; PR #982 review), shown once by the turn
     * that publishes the take instead of the SUCCESS: the manifest could not
     * be written, with the reason ({@link #manifestNotWrittenText}); that
     * the audio recorded is kept, with the clips created, and that take
     * recovery rebuilds the manifest ({@link #MANIFEST_REBUILT_BY_RECOVERY_TEXT});
     * or, with no clip — no track recorded a frame, and the live seal
     * deleted the empty tails — that no audio had been recorded, with no
     * word of a rebuild. Never "Recording stopped — N clips created" alone:
     * the manifest on disk still reads streaming, and the next scan treats
     * the take as a crash candidate.
     *
     * @param failure   the failure of the final manifest write
     * @param clipCount the clips the Stop created from the take
     */
    static String finalManifestNotWrittenMessage(FinalManifestFailure failure, int clipCount) {
        if (clipCount == 0) {
            return "Recording stopped — " + manifestNotWrittenText(failure) + "; no audio had been recorded";
        }
        return "Recording stopped — " + manifestNotWrittenText(failure) + "; the audio recorded is kept ("
                + clipsCreatedText(clipCount) + "); " + MANIFEST_REBUILT_BY_RECOVERY_TEXT;
    }

    /**
     * The cause of a {@link FinalManifestFailure}: that the take's manifest
     * could not be written, with the reason of the innermost
     * {@link IOException} when there is one in the cause chain, otherwise
     * the throwable's type and, when it has one, its message.
     */
    private static String manifestNotWrittenText(FinalManifestFailure failure) {
        IOException ioFailure = innermostIOException(failure.failure());
        String reason = ioFailure != null ? ioReason(ioFailure) : shortDescription(failure.failure());
        return "the take's manifest could not be written (" + reason + ")";
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
     * nothing on the disk (an {@link OutOfMemoryError}, say), with the
     * throwable's type and, when it has one, its message.
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
     * Whether a take this controller stopped is still being written to disk
     * or read back from it — its Stop has run and the turn that publishes it
     * ({@link #publishWrittenTake}) has not run yet: its capture thread is
     * still sealing it, or the storage executor is still reading its audio
     * back from the sealed segments — or a start that was
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
     * Test seam: replaces how a stopped take is completed (see
     * {@link TakeCompletion}). FX thread, before the take is stopped.
     */
    void setTakeCompletionForTest(TakeCompletion completion) {
        takeCompletion = Objects.requireNonNull(completion, "completion must not be null");
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
     * allocation of a take directory or the read of a stopped take's audio.
     * FX thread, before the take starts.
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
     * Test seam: replaces how a take's early-seal signal is read (see
     * {@link EarlySealSignal}). FX thread, before the take starts.
     */
    void setEarlySealSignalForTest(EarlySealSignal signal) {
        earlySealSignal = Objects.requireNonNull(signal, "signal must not be null");
    }

    /**
     * Test seam: replaces how the outcome of a take's Stop or rescue seal is
     * read (see {@link StopSealOutcomeReader}). FX thread, before the take
     * is stopped.
     */
    void setStopSealOutcomeForTest(StopSealOutcomeReader outcome) {
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

    /** Supplies the shared capture origin and every MIDI receipt timestamp. FX thread, before Record. */
    void setMidiMonotonicClockForTest(LongSupplier clock) {
        midiMonotonicNanos = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Toggles recording (§5.2 "Record"): while RECORDING, delegates to
     * {@code transport.stop()} (Stop is the only way out of record — which also runs
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
    public void toggleRecord() {
        requireFxThread();
        if (retired || configurationInProgress.getAsBoolean() || configurationReserved || pendingConfiguration != null) return;
        if (pendingStart != null) {
            cancelPendingStartByUser();
            stopAudioOutputUnlessRolling();
            return;
        }
        if (state.get() == RecordState.COUNT_IN || state.get() == RecordState.RECORDING || recordingPipeline != null || !activeMidiRecorders.isEmpty()) {
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
        // thread has terminated, its audio has been read back and its clips
        // are published — as is a start
        // that was cancelled, or failed once its take directory existed,
        // until the FX turn after the removal of its files has run, whether
        // or not everything could be removed. Refused before anything is
        // touched: no pipeline, no device, no MIDI recorder, and the
        // transport stays where it is.
        if (state.get() == RecordState.DEVICE_LOST) {
            // Story 327: Record is refused while the device is lost — the
            // WARNING names the device and the ways out (refuseWhileDeviceLost).
            refuseWhileDeviceLost("Record");
            return;
        }
        if (state.get() != RecordState.IDLE || isTakeBeingWritten()) {
            LOG.warning("Recording refused — the last take has not been finished yet");
            setRecordingStatus(RECORD_WHILE_WRITING_MESSAGE);
            statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
            notificationBar.show(NotificationLevel.WARNING, RECORD_WHILE_WRITING_MESSAGE);
            return;
        }

        skippedMidiTracks.clear();
        midiStopFailure = null;
        unannouncedCleanupFailure = null;

        // Validate that at least one track is armed for recording
        List<Track> armedTracks = RecordingPipeline.findArmedTracks(project.getTracks());
        if (armedTracks.isEmpty()) {
            abortRecordingTake(new IllegalStateException("Armed-track precondition failed — arm at least one track first"));
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
            abortRecordingTake(new IllegalStateException(NO_PROJECT_FOLDER_MESSAGE));
            setRecordingStatus(NO_PROJECT_FOLDER_MESSAGE);
            notificationBar.show(NotificationLevel.ERROR, NO_PROJECT_FOLDER_MESSAGE);
            return;
        }

        // MIDI-only recording still needs the output callback to drive time
        // and play existing tracks. Open it before creating any MidiRecorder:
        // on refusal nothing has started, no track is marked recording and
        // the transport remains STOPPED (story 317).
        if (armedAudioTracks.isEmpty()
                && !startAudioOutputOrRefuse("Recording")) {
            Throwable failure = new IllegalStateException(statusBarLabel.getText());
            abortStep(failure, () -> transition(RecordState.ABORTED));
            abortStep(failure, project.getTransport()::stop);
            rollbackStream(failure);
            abortStep(failure, () -> setRecordingStatus(statusBarLabel.getText()));
            abortStep(failure, this::settle);
            if (failure.getSuppressed().length > 0) {
                LOG.log(Level.WARNING, "A step of the refused recording's cleanup failed", failure);
            }
            return;
        }

        if (armedAudioTracks.isEmpty()) {
            // MIDI-only: the output stream was proved RUNNING above, and the
            // take writes no audio files, so it starts at once and the
            // transport may enter recording.
            try {
                startMidiRecording(armedMidiTracks, countIn);
                if (activeMidiRecorders.isEmpty()) {
                    throw new IllegalStateException("MIDI input precondition failed — no armed MIDI track opened a device");
                }
                transition(RecordState.COUNT_IN);
                project.getTransport().record();
                activateMidiRecording();
            } catch (RuntimeException | Error failure) {
                abortRecordingTake(failure);
                return;
            }
            announceRecordingStarted(activeMidiRecorders.size(), null, false);
            return;
        }

        PendingStart start = new PendingStart(armedTracks.size(), List.copyOf(armedAudioTracks),
                List.copyOf(armedMidiTracks), countIn);
        pendingStart = start;
        transition(RecordState.PREPARING);
        setRecordingStatus(TAKE_PREPARING_MESSAGE);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
        updateStatus();
        Path audioDirectory = ProjectManager.audioDirectory(projectDirectory.get());
        Instant requested = Instant.now();
        // Driver enumeration and union-open run off FX. No take file exists until validation succeeds.
        sessionInputCheck = Thread.ofVirtual().name("daw-record-input-check").start(() -> {
            Throwable failure = null;
            try {
                audioEngine.requireCaptureRoutingUnchanged(start.armedAudioTracks, start.routeSnapshots);
                if (start.armedAudioTracks.stream().allMatch(track ->
                        track.getInputRouting().isNone() && audioEngine.hasGraphInstrument(track))) {
                    start.streamGeneration = audioEngine.startAudioOutputOwned(start.streamGeneration);
                } else start.streamGeneration = audioEngine.startAudioInputOutput(start.armedAudioTracks, start.routeSnapshots, start.provision, start.streamGeneration);
            } catch (RuntimeException | Error e) { failure = e; }
            Throwable result = failure;
            postFx(() -> onInputStreamsPrepared(start, audioDirectory, requested, result));
        });
    }

    private void onInputStreamsPrepared(PendingStart start, Path audioDirectory, Instant requested, Throwable failure) {
        start.streamPrepared = true;
        if (start.cancelled || retired) {
            start.filesCleanupComplete = true;
            if (failure == null && !retired && (project.getTransport().getState() == TransportState.PLAYING || transport.hasGraphInstruments())) finishAbandonedStartCleanup(start);
            else cleanUpPreparedInputStream(start);
            return;
        }
        if (pendingStart != start) return;
        if (failure != null) {
            failPreparedInputStart(start, failure);
            return;
        }
        try {
            audioEngine.requireCaptureRoutingUnchanged(start.armedAudioTracks, start.routeSnapshots);
            CompletableFuture.supplyAsync(() -> allocateTakeDirectory(audioDirectory, requested), storageExecutor)
                .whenComplete((takeDirectory, allocationFailure) ->
                        postFx(() -> onTakeDirectoryAllocated(start, takeDirectory, allocationFailure)));
        } catch (RuntimeException rejected) {
            failPreparedInputStart(start, new IllegalStateException("Take-directory scheduling precondition failed: " + shortDescription(rejected), rejected));
        }
    }

    private void failPreparedInputStart(PendingStart start, Throwable failure) {
        pendingStart = null;
        abandonedStart = start;
        start.failure = failure;
        start.filesCleanupComplete = true;
        abortRecordingTake(failure);
        cleanUpPreparedInputStream(start);
    }

    boolean deferInputStreamStop() {
        if (unannouncedTake != null && !unannouncedStreamClosed) {
            if (unannouncedStart.filesCleanupComplete && !unannouncedCleanupRunning) finishWrittenTake(unannouncedTake);
            return true;
        }
        PendingStart start = pendingStart != null ? pendingStart : abandonedStart;
        if (start == null) return false;
        if (!start.streamPrepared || start.inputCleanupRunning) return true;
        if (start.failure != null && !start.filesCleanupComplete) return true;
        if (start.inputCleanupFailed || start.failure != null || start.cancelled
                && (retired || !transport.hasGraphInstruments() && project.getTransport().getState() != TransportState.PLAYING)) {
            cleanUpPreparedInputStream(start);
            return true;
        }
        return false;
    }
    private void cleanUpPreparedInputStream(PendingStart start) {
        if (start.inputCleanupRunning || start.inputCleanupComplete) return;
        start.inputCleanupRunning = true;
        CompletableFuture.runAsync(() -> audioEngine.stopOwnedAudioStream(start.streamGeneration), RecordCoordinator::onAVirtualThread)
            .whenComplete((_, closeFailure) -> postFx(() -> {
                start.inputCleanupRunning = false;
                if (closeFailure != null) {
                    start.inputCleanupFailed = true;
                    if (!retired) notificationBar.show(NotificationLevel.ERROR,
                            "Recording input cleanup failed: " + shortDescription(causeOf(closeFailure)));
                } else {
                    start.inputCleanupFailed = false;
                    start.inputCleanupComplete = true;
                    finishAbandonedStartCleanup(start);
                }
            }));
    }

    /**
     * The ANNOUNCE and UI tail of a take whose recording has begun — the
     * transport is RECORDING: {@link TransportEvent.Started}, the status
     * line, the INFO toast, the REC indicator and, for an audio take, the
     * input preparation. Run by {@link #onRecord()} for a MIDI-only take
     * and by the readiness turn ({@link #onTakeReady}) for an audio take. FX
     * thread.
     *
     * @param trackCount    every armed track of the take, audio and MIDI
     * @param takeDirectory the audio take's directory under
     *                      {@code audio/takes}; {@code null} for a MIDI-only take
     */
    private void announceRecordingStarted(int trackCount, Path takeDirectory, boolean preserveWarning) {
        transport.cancelPostRollForRecording();
        trackCount = (recordingPipeline == null ? 0 : recordingPipeline.getArmedTracks().size()) + activeMidiRecorders.size();
        if (state.get() == RecordState.PREPARING) transition(RecordState.COUNT_IN);
        transition(RecordState.RECORDING);
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
        setRecordingStatus(takeDirectory == null
                ? armedSummary
                : armedSummary + " — streaming to " + ProjectManager.AUDIO_DIR_NAME + "/"
                        + TakeDirectories.TAKES_DIR_NAME + "/" + takeDirectory.getFileName());
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
        if (!preserveWarning) notificationBar.show(NotificationLevel.INFO,
                "Recording started — " + trackCount + " track"
                        + (trackCount > 1 ? "s" : "") + " armed");
        if (!skippedMidiTracks.isEmpty()) {
            notificationBar.show(NotificationLevel.WARNING, "MIDI recording skipped: " + String.join("; ", skippedMidiTracks));
            skippedMidiTracks.clear();
        }

    }

    /**
     * The FX turn that follows the allocation of {@code start}'s take
     * directory on the storage executor. A start cancelled meanwhile is
     * already {@link #abandonedStart}: the directory just allocated, if any,
     * is removed off the FX thread. A failed allocation ends the start with
     * one ERROR naming the failed take-directory precondition. Otherwise the take's pipeline is built — at
     * the format the engine is streaming ({@code AudioEngine.getFormat()}),
     * never the project's, and with the take's live peak channels opened
     * and set as its peak sink ({@link #openCapturePeaks}) — and
     * prepared ({@code RecordingPipeline.prepare()}, which touches no storage
     * and waits for nothing: the take's capture thread creates the files),
     * and the readiness turn ({@link #onTakeReady}) is posted once that
     * thread reports. A failure before the pipeline is prepared ends the
     * start with the ERROR of {@link #abortRecordingTake}, and the take
     * directory is removed off the FX thread; a failure of {@code prepare()}
     * itself abandons the start ({@link #abandonStart}).
     *
     * <p>Failed allocation closes the opened stream immediately. Once a take directory
     * exists, a failed build/preparation/readiness/capture start drains MIDI, discards the
     * flush service, waits for termination and removes the directory off FX, then closes
     * the attempt's stream and stops the engine. User cancellation and retirement retain
     * their separate playback/resource ownership. FX thread.</p>
     */
    private void onTakeDirectoryAllocated(PendingStart start, Path takeDirectory, Throwable failure) {
        start.takeDirectory = takeDirectory;
        if (start.cancelled) {
            removeFilesOfAbandonedStart(start, CompletableFuture.completedStage(null));
            return;
        }
        if (failure != null) {
            Throwable cause = causeOf(failure);
            LOG.log(Level.SEVERE, "Failed to create a take directory under the project's audio/takes folder",
                    cause instanceof UncheckedIOException unchecked ? unchecked.getCause() : cause);
            failPreparedInputStart(start, new IllegalStateException("Take-directory precondition failed: " + shortDescription(cause)));
            return;
        }
        RecordingPipeline pipeline;
        try {
            audioEngine.requireCaptureRoutingUnchanged(start.armedAudioTracks, start.routeSnapshots);
            // The take is captured at the format the engine is streaming
            // (Recording Reliability book §2.7), read here, on the FX turn
            // after Record opened the device stream: the engine refuses a
            // format change while it runs, so this is the rate, the width
            // and the block size of the blocks the take will be handed.
            // Project punch frames still use the project's timeline rate;
            // pass that rate separately so the pipeline can convert them.
            pipeline = new RecordingPipeline(
                    audioEngine, project.getTransport(), audioEngine.getFormat(),
                    project.getFormat().sampleRate(), takeDirectory,
                    start.armedAudioTracks, start.countIn, InputMonitoringMode.OFF, null);
            pipeline.setWarningSink(message -> postFx(() -> {
                RecordState current = state.get();
                if (!retired && (current == RecordState.PREPARING || current == RecordState.COUNT_IN || current == RecordState.RECORDING)
                        && (pendingStart == start || recordingPipeline == start.pipeline)) {
                    start.warningPublished = true;
                    notificationBar.show(NotificationLevel.WARNING, message);
                }
            }));
            if (latencyCalibration == null) {
                pipeline.setReportedLatency(reportedLatency.get());
            } else {
                latencyCalibration.get().ifPresent(calibration ->
                        pipeline.setReportedLatency(calibration.device(), calibration.latency()));
            }
            pipeline.setApplyLatencyCompensation(applyLatencyCompensation.getAsBoolean());
            openCapturePeaks(pipeline, start.armedAudioTracks);
            pipelineSetup.accept(pipeline);
        } catch (RuntimeException e) {
            // No capture thread exists yet: only the take directory is left
            // to remove, off the FX thread.
            closeCapturePeaks();
            pendingStart = null;
            start.failure = e;
            removeFilesOfAbandonedStart(start, CompletableFuture.completedStage(null));
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
     * abandons the start ({@link #abandonStart}). Otherwise the current flush
     * viability and early-seal signal are checked before opening mixed-take
     * MIDI inputs and again before the guarded {@code beginCapture()} call.
     * That call installs capture, starts the engine and records the transport;
     * only a still-viable take is then registered for early-seal stopping and
     * announced ({@link #announceRecordingStarted}). A take that ends before
     * announcement follows {@link #finishUnannouncedTake}, preserving its
     * partial files and publication fence without announcing RECORDING.
     * Other capture-start failures abandon the start. FX thread.
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
        if (earlySealOf(pipeline).isPresent() || !pipeline.hasViableCaptureService()) {
            finishUnannouncedTake(start);
            return;
        }
        try {
            audioEngine.requireCaptureRoutingUnchanged(start.armedAudioTracks, start.routeSnapshots);
            if (!start.armedMidiTracks.isEmpty()) startMidiRecording(start.armedMidiTracks, start.countIn);
            if (earlySealOf(pipeline).isPresent() || !pipeline.hasViableCaptureService()) {
                finishUnannouncedTake(start);
                return;
            }
            pipeline.beginCapture(this::activateMidiRecording);
        } catch (RuntimeException | Error e) {
            if (pipeline.isPreparing() && !pipeline.hasViableCaptureService()) finishUnannouncedTake(start);
            else abandonStart(start, e);
            return;
        }
        if (earlySealOf(pipeline).isPresent() || !pipeline.hasViableCaptureService()) {
            finishUnannouncedTake(start);
            return;
        }
        pendingStart = null;
        recordingPipeline = pipeline;
        // Story 323 review: a take the capture thread seals on its own
        // (disk exhaustion, a write failure) is stopped like the user's
        // Stop, on a later FX turn.
        stopWhenSealedEarly(pipeline);
        if (earlySealOf(pipeline).isPresent() || !pipeline.hasViableCaptureService()) {
            finishUnannouncedTake(start);
            return;
        }
        announceRecordingStarted(start.armedAudioTracks.size() + activeMidiRecorders.size(), start.takeDirectory, start.warningPublished);
    }

    /** Preserves a take that ended during readiness without ever announcing a live recording. */
    private void finishUnannouncedTake(PendingStart start) {
        RecordingPipeline pipeline = start.pipeline;
        writingPipeline = pipeline;
        unannouncedTake = pipeline;
        unannouncedStart = start;
        recordingPipeline = null;
        pendingStart = null;
        transition(RecordState.ABORTED);
        stopMidiRecording();
        CompletionStage<Void> written;
        if (pipeline.isPreparing()) {
            try { written = pipeline.requestStopBeforeCapture(); }
            catch (RuntimeException stopFailure) {
                rememberUnannouncedCleanupFailure(stopFailure);
                written = pipeline.termination();
            }
        } else {
            written = requestTakeStop(pipeline, TakeManifest.SealedBy.STOP);
        }
        try {
            if (project.getTransport().getState() != TransportState.STOPPED) project.getTransport().stop();
            setRecordingStatus(TAKE_FINISHING_MESSAGE);
        } catch (RuntimeException cleanupFailure) {
            rememberUnannouncedCleanupFailure(cleanupFailure);
        } finally {
            publishWhenWritten(pipeline, written);
        }
        stillWritingDelay.after(TAKE_STILL_WRITING_DELAY, () -> warnTakeStillWriting(pipeline));
    }

    private void rememberUnannouncedCleanupFailure(RuntimeException failure) {
        LOG.log(Level.WARNING, "A step of the unannounced take's cleanup failed; the take remains owned until cleanup settles", failure);
        String reason = shortDescription(failure);
        unannouncedCleanupFailure = unannouncedCleanupFailure == null ? reason : unannouncedCleanupFailure + "; " + reason;
    }

    /**
     * Stop, Record pressed again, or the end of a post-roll — the deferred
     * half of a Stop — while the take is being prepared: cancels it
     * ({@link #cancelPendingStart}) and says so in the status bar. No take
     * was recorded and nothing is announced: capture never began, so the
     * transport was never put in recording. The device is left to the
     * caller, which hands the output stream Record opened to
     * {@link #stopAudioOutputWhenIdle()}: {@link #toggleRecord()} only when
     * the transport is not rolling, and {@code transport.stop()} and
     * {@code TransportController.finishPostRoll()} once the transport is stopped, as every Stop
     * does. FX thread.
     */
    void cancelPendingStartByUser() {
        requireFxThread();
        cancelPendingStart();
        LOG.info("Recording cancelled while its take was being prepared");
        setRecordingStatus(RECORDING_CANCELLED_MESSAGE);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
        updateStatus();
    }

    /**
     * Cancels the take being prepared, without waiting for anything: it is
     * marked cancelled — so its allocation turn and its readiness turn do
     * nothing more for it — and becomes the {@link #abandonedStart}; its
     * live peak channels, if they were opened, are closed. If its
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
    void cancelPendingStart() {
        transition(RecordState.FINALIZING);
        PendingStart start = pendingStart;
        pendingStart = null;
        start.cancelled = true;
        abandonedStart = start;
        closeCapturePeaks();
        if (start.pipeline != null) {
            removeFilesOfAbandonedStart(start, discardTake(start.pipeline));
        }
    }

    /**
     * Ends a start that failed after its take directory was allocated — its
     * pipeline's {@code prepare()} threw, its readiness failed, or its
     * {@code beginCapture()} threw — with its live peak channels closed, the
     * take's files handed back to
     * its capture thread ({@link #discardTake}), and the take directory, if
     * that thread left it empty, removed off the FX thread once that thread
     * has terminated. Then the
     * user is told, as for a device that could not be opened
     * ({@link #abortRecordingTake}). FX thread.
     */
    private void abandonStart(PendingStart start, Throwable failure) {
        start.failure = failure;
        pendingStart = null;
        stopMidiRecording(failure);
        CompletionStage<Void> terminated;
        try {
            terminated = discardTake(start.pipeline);
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            terminated = start.pipeline.termination();
        }
        abortStep(failure, this::closeCapturePeaks);
        removeFilesOfAbandonedStart(start, terminated);
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
        start.filesCleanupComplete = true;
        if (abandonedStart == start && !start.inputCleanupComplete
                && (start.failure != null || retired || !transport.hasGraphInstruments()
                    && project.getTransport().getState() != TransportState.PLAYING)) {
            cleanUpPreparedInputStream(start);
        } else {
            finishAbandonedStartCleanup(start);
        }
    }

    private void finishAbandonedStartCleanup(PendingStart start) {
        if (!start.filesCleanupComplete || start.inputCleanupRunning || start.inputCleanupFailed) return;
        if (abandonedStart == start) {
            abandonedStart = null;
            settle();
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
    private final class PendingStart {
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
        boolean streamPrepared;
        Object streamGeneration = new Object();
        boolean inputCleanupRunning;
        boolean inputCleanupFailed;
        boolean inputCleanupComplete;
        boolean filesCleanupComplete;
        boolean warningPublished;
        final List<com.benesquivelmusic.daw.core.audio.CaptureRoutingPlan.Route> routeSnapshots;
        final com.benesquivelmusic.daw.core.audio.StreamingProvision provision;
        Throwable failure;

        PendingStart(int trackCount, List<Track> armedAudioTracks, List<Track> armedMidiTracks,
                     CountInMode countIn) {
            this.trackCount = trackCount;
            this.armedAudioTracks = armedAudioTracks;
            this.routeSnapshots = com.benesquivelmusic.daw.core.audio.CaptureRoutingPlan.snapshot(armedAudioTracks);
            this.provision = audioEngine.getStreamingProvision();
            this.armedMidiTracks = armedMidiTracks;
            this.countIn = countIn;
        }
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

    private void abortRecordingTake(Throwable failure) {
        abortStep(failure, () -> transition(RecordState.ABORTED));
        stopMidiRecording(failure);
        abortStep(failure, project.getTransport()::stop);
        if (abandonedStart == null) rollbackStream(failure);
        String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        String message = "Recording aborted — no take was started: " + reason;
        abortStep(failure, () -> setRecordingStatus(message));
        abortStep(failure, () -> statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12)));
        abortStep(failure, () -> notificationBar.show(NotificationLevel.ERROR, message));
        abortStep(failure, this::updateStatus);
        if (abandonedStart == null) abortStep(failure, this::settle);
        LOG.log(Level.WARNING,
                "Recording aborted — the take could not be started", failure);
    }

    private void rollbackStream(Throwable failure) {
        abortStep(failure, audioEngine::stopAudioOutput);
        abortStep(failure, audioEngine::stop);
    }

    /** Completes every start-rollback step without replacing the cause of the failed start. */
    private static void abortStep(Throwable failure, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        }
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

    // ── MIDI recording helpers ───────────────────────────────────────────────

    /**
     * Prepares a {@link MidiRecorder} for each armed MIDI track. Receivers
     * discard all events until {@link #activateMidiRecording()} activates the
     * successful inputs together at the transport's recording transition.
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
            MidiDevice device;
            try {
                device = midiInputDeviceResolver.apply(track.getMidiInputDeviceName());
            } catch (RuntimeException failure) {
                skippedMidiTracks.add(track.getName() + " (" + shortDescription(failure) + ")");
                notificationBar.show(NotificationLevel.WARNING, "MIDI input discovery failed on track: " + track.getName());
                continue;
            }
            if (device == null) {
                LOG.warning("No MIDI input device found for track: " + track.getName()
                        + " (device name: " + track.getMidiInputDeviceName() + ")");
                skippedMidiTracks.add(track.getName() + " (device not found)");
                notificationBar.show(NotificationLevel.WARNING,
                        "MIDI device not found for track: " + track.getName());
                continue;
            }

            MidiRecorder recorder = new MidiRecorder(
                    device, track.getMidiClip(), transport.getTempo(), 0, midiMonotonicNanos);
            recorder.setStartColumnOffset(startColumnOffset);
            recorder.setCountInDurationUs(countInDurationUs);

            // Wire MIDI activity indicator — flash the track strip on each
            // event. The recorder fires on the MIDI receiver thread, so the
            // flash is marshalled onto the FX thread through the FxDispatcher
            // seam (story 289; Control Synchronization Design Book §1.5, §4.5).
            recorder.addEventListener(_ -> postFx(
                    () -> flashMidiActivity.accept(track)));

            try {
                recorder.prepareRecording();
                activeMidiRecorders.put(track, recorder);
                LOG.fine(() -> "Prepared MIDI recording on track: " + track.getName());
            } catch (MidiUnavailableException | RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to start MIDI recording on track: "
                        + track.getName(), e);
                skippedMidiTracks.add(track.getName() + " (" + shortDescription(e) + ")");
                notificationBar.show(NotificationLevel.WARNING,
                        "MIDI recording skipped track: " + track.getName() + " — " + shortDescription(e));
            }
        }
    }

    /** No device calls: every ready input receives the same capture origin. */
    private void activateMidiRecording() {
        if (project.getTransport().getState() != TransportState.RECORDING) {
            throw new IllegalStateException("Transport recording precondition failed — the transport stopped during start");
        }
        if (activeMidiRecorders.isEmpty()) return;
        long originNanos = midiMonotonicNanos.getAsLong();
        for (Map.Entry<Track, MidiRecorder> entry : activeMidiRecorders.entrySet()) {
            entry.getValue().beginRecording(originNanos);
            entry.getKey().setRecording(true);
        }
    }

    /**
     * Stops all active MIDI recorders and registers an undoable action for
     * each track's recorded notes. When at least one track recorded notes —
     * the recorders put them into the tracks' MIDI clips — it marks the
     * project dirty and shows the SUCCESS toast; a take with no note leaves
     * the project as it was. Provider close failures are reported after every
     * recorder has been drained, preserving captured notes and undo history. FX thread.
     */
    void stopMidiRecording() {
        stopMidiRecording(null);
    }

    /** A failed start preserves captured notes, but only its original failure is announced. */
    private void stopMidiRecording(Throwable startFailure) {
        requireFxThread();
        if (activeMidiRecorders.isEmpty()) {
            if (startFailure == null) postFx(this::settle);
            return;
        }

        int totalNotes = 0;
        List<String> stopFailures = new ArrayList<>();
        List<Map.Entry<Track, MidiRecorder>> stopping = new ArrayList<>(activeMidiRecorders.entrySet());
        activeMidiRecorders.clear();
        for (Map.Entry<Track, MidiRecorder> entry : stopping.reversed()) {
            Track track = entry.getKey();
            MidiRecorder recorder = entry.getValue();
            try {
                try { recorder.stopRecording(); }
                catch (RuntimeException | Error stopFailure) {
                    if (startFailure != null && stopFailure != startFailure) startFailure.addSuppressed(stopFailure);
                    stopFailures.add(track.getName() + " (" + shortDescription(stopFailure) + ")");
                    LOG.log(Level.WARNING, "MIDI provider close failed on track: " + track.getName(), stopFailure);
                }
                List<MidiNoteData> recordedNotes = recorder.getRecordedNotes();
                if (!recordedNotes.isEmpty()) {
                    totalNotes += recordedNotes.size();
                    undoManager.execute(new RecordMidiNotesAction(track.getMidiClip(), recordedNotes));
                }
            } catch (RuntimeException | Error failure) {
                stopFailures.add(track.getName() + " (" + shortDescription(failure) + ")");
                if (startFailure == null) {
                    notificationBar.show(NotificationLevel.ERROR,
                            "MIDI stop failed on track: " + track.getName() + " — " + shortDescription(failure));
                } else if (failure != startFailure) {
                    startFailure.addSuppressed(failure);
                }
                LOG.log(Level.WARNING, "MIDI stop failed on track: " + track.getName(), failure);
            } finally {
                if (startFailure == null) track.setRecording(false);
                else abortStep(startFailure, () -> track.setRecording(false));
            }
        }
        if (startFailure != null) {
            if (totalNotes > 0) abortStep(startFailure, project::markDirty);
            return;
        }
        postFx(this::settle);

        if (totalNotes > 0) {
            // At least one "Record MIDI" action was registered over notes the
            // recorders put into the project's clips.
            project.markDirty();
            String msg = "Recording stopped — " + totalNotes + " MIDI note"
                    + (totalNotes > 1 ? "s" : "") + " captured";
            if (statusBarLabel.getText() == null
                    || !stripCellSeparator(statusBarLabel.getText()).startsWith("Recording stopped")) {
                setRecordingStatus(msg);
            }
            if (stopFailures.isEmpty()) notificationBar.show(NotificationLevel.SUCCESS, msg);
        }
        if (!stopFailures.isEmpty()) {
            transition(RecordState.ABORTED);
            String message = "MIDI recording stopped with errors — " + String.join("; ", stopFailures);
            midiStopFailure = message;
            setRecordingStatus(message);
            notificationBar.show(NotificationLevel.ERROR, message);
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
                } catch (MidiUnavailableException | RuntimeException e) {
                    // skip unavailable device
                }
            }
        }
        return null;
    }
}
