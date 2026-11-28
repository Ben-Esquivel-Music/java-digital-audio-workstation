package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.diagnostics.HaltContext;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.CountInSchedule;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.event.BusEvent;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.EventBusMetrics;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Transmitter;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Record with a count-in (story 328, Recording Reliability book §5.6): the
 * take enters COUNT_IN while the render thread counts on the engine's frame
 * clock with the transport held, the render thread's record start is
 * observed through the transport's change notification and announced on
 * the FX thread, and a Stop, a Record again or a device loss during the
 * count-in cancels it — a count-in that fired first is a recording take and
 * is stopped as one. A rolling transport skips the count-in and records at
 * once.
 *
 * <p>The Stop a render-drive halt runs (story 337, ruling E101) answers what
 * it did: a count-in it cancelled, audio, mixed or MIDI-only, gets no context
 * sentence; a take whose count-in fired, and a MIDI-only or mixed take
 * recording with no count-in, get the recording sentence. One case halts the
 * render drive for real and reads the halt notice's whole text from the
 * window's audio health drain.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
@Tag("slow")
class CountInRecordStartTest {

    private static final long WAIT_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);

    @TempDir
    Path tempDir;

    private final List<BusEvent> published = new CopyOnWriteArrayList<>();
    private EventBus previousBus;
    private MidiOnlyRig midiRig;
    private DeviceLossRecordingFixture audioRig;

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (midiRig != null) midiRig.close();
            if (audioRig != null) audioRig.close();
        } finally {
            if (previousBus != null) EventBusPublisher.setDefault(previousBus);
        }
    }

    private void hookTheBus() {
        previousBus = EventBusPublisher.getDefault();
        EventBusPublisher.setDefault(new PublishHookEventBus(published::add));
    }

    private long startedCount() {
        return published.stream().filter(TransportEvent.Started.class::isInstance).count();
    }

    private long stoppedCount() {
        return published.stream().filter(TransportEvent.Stopped.class::isInstance).count();
    }

    @Test
    void anAudioTakeCountsInWithTheTransportHeldAndRecordsWhenTheRenderThreadFires() throws Exception {
        hookTheBus();
        audioRig = DeviceLossRecordingFixture.withoutEngineBridge(tempDir, CountInMode.ONE_BAR);
        audioRig.project().getTransport().setTempo(120);
        Track keys = armedMidiTrack(audioRig.project());
        RecordingInFlightFixture.StubMidiInput midiInput = new RecordingInFlightFixture.StubMidiInput();
        List<RecordState> states = new CopyOnWriteArrayList<>();
        AtomicBoolean recordingEnteredOnFx = new AtomicBoolean();
        DeviceLossRecordingFixture.runOnFx(() -> {
            audioRig.controller().setMidiInputDeviceResolverForTest(
                    name -> RecordingInFlightFixture.STUB_MIDI_INPUT.equals(name) ? midiInput : null);
            audioRig.coordinator().stateProperty().addListener((_, _, next) -> {
                states.add(next);
                if (next == RecordState.RECORDING) recordingEnteredOnFx.set(Platform.isFxApplicationThread());
            });
            audioRig.controller().toggleRecord();
        });

        DeviceLossRecordingFixture.awaitOnFx(() -> audioRig.coordinator().getState() == RecordState.COUNT_IN,
                "the take counts in");
        CountInSchedule schedule = DeviceLossRecordingFixture.onFx(() -> {
            assertThat(audioRig.statusBar().getText()).startsWith("Count-in — 1 bar");
            assertThat(audioRig.project().getTransport().getState()).isEqualTo(TransportState.STOPPED);
            assertThat(audioRig.coordinator().isObservingCountInForTest()).isTrue();
            assertThat(audioRig.controller().isRecordingInFlight()).isTrue();
            assertThat(keys.isRecording()).as("the MIDI recorder is anchored at the count-in's start").isTrue();
            assertThat(audioRig.pipeline().countInSchedule()).isPresent();
            return audioRig.pipeline().countInSchedule().orElseThrow();
        });
        assertThat(schedule.totalBeats()).isEqualTo(4);
        assertThat(schedule.hasRecordStart()).as("the pipeline set the record start").isTrue();
        assertThat(startedCount()).as("nothing is announced while counting in").isZero();

        DeviceLossRecordingFixture.awaitOnFx(() -> audioRig.coordinator().getState() == RecordState.RECORDING,
                "the fire was observed and the take announced");
        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.FIRED);
        DeviceLossRecordingFixture.runOnFx(() -> {
            assertThat(audioRig.project().getTransport().getState()).isEqualTo(TransportState.RECORDING);
            assertThat(audioRig.coordinator().isObservingCountInForTest()).as("the observer is removed at the fire").isFalse();
            assertThat(audioRig.statusBar().getText()).startsWith("Recording — 2 tracks armed");
            assertThat(audioRig.recIndicator().isVisible()).isTrue();
            assertThat(keys.isRecording()).isTrue();
        });
        assertThat(states).containsExactly(RecordState.PREPARING, RecordState.COUNT_IN, RecordState.RECORDING);
        assertThat(recordingEnteredOnFx).as("the hop from the render thread lands on the FX thread").isTrue();
        assertThat(startedCount()).isEqualTo(1);
    }

    @Test
    void aMidiOnlyTakeCountsInOnTheEngineAndRecordsWhenTheRenderThreadFires() throws Exception {
        hookTheBus();
        midiRig = new MidiOnlyRig(CountInMode.ONE_BAR, 120);
        List<RecordState> states = new CopyOnWriteArrayList<>();
        AtomicReference<String> recordingEnteredOn = new AtomicReference<>();
        runOnFx(() -> {
            midiRig.controller.recordCoordinator().stateProperty().addListener((_, _, next) -> {
                states.add(next);
                if (next == RecordState.RECORDING) recordingEnteredOn.set(Thread.currentThread().getName());
            });
            midiRig.controller.toggleRecord();
        });

        CountInSchedule schedule = onFx(() -> {
            assertThat(midiRig.controller.recordCoordinator().getState()).isEqualTo(RecordState.COUNT_IN);
            assertThat(midiRig.transport().getState()).isEqualTo(TransportState.STOPPED);
            assertThat(midiRig.statusBar.getText()).startsWith("Count-in — 1 bar");
            assertThat(midiRig.keys.isRecording()).isTrue();
            assertThat(midiRig.controller.isRecordingInFlight()).isTrue();
            assertThat(midiRig.engine.pendingCountIn()).isPresent();
            return midiRig.engine.pendingCountIn().orElseThrow();
        });
        assertThat(startedCount()).isZero();

        awaitOnFx(() -> midiRig.controller.recordCoordinator().getState() == RecordState.RECORDING,
                "the fire was observed and the take announced");
        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.FIRED);
        runOnFx(() -> {
            assertThat(midiRig.transport().getState()).isEqualTo(TransportState.RECORDING);
            assertThat(midiRig.controller.recordCoordinator().isObservingCountInForTest()).isFalse();
            assertThat(midiRig.engine.pendingCountIn()).isEmpty();
            assertThat(midiRig.statusBar.getText()).startsWith("Recording — 1 track armed");
        });
        assertThat(states).containsExactly(RecordState.COUNT_IN, RecordState.RECORDING);
        assertThat(recordingEnteredOn.get()).contains("JavaFX Application Thread");
        assertThat(startedCount()).isEqualTo(1);
    }

    @Test
    void aStopDuringAMidiOnlyCountInCancelsItAndNoTakeWasStarted() throws Exception {
        hookTheBus();
        midiRig = new MidiOnlyRig(CountInMode.TWO_BARS, 60);
        runOnFx(midiRig.controller::toggleRecord);
        CountInSchedule schedule = onFx(() -> midiRig.engine.pendingCountIn().orElseThrow());

        runOnFx(midiRig.controller::stop);

        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.CANCELLED);
        assertThat(schedule.isFired()).isFalse();
        awaitOnFx(() -> midiRig.controller.recordCoordinator().getState() == RecordState.IDLE, "the machine settled");
        runOnFx(() -> {
            assertThat(midiRig.controller.recordCoordinator().isObservingCountInForTest()).isFalse();
            assertThat(midiRig.engine.pendingCountIn()).isEmpty();
            assertThat(midiRig.transport().getState()).isEqualTo(TransportState.STOPPED);
            assertThat(midiRig.controller.isRecordingInFlight()).isFalse();
            assertThat(midiRig.keys.isRecording()).isFalse();
            assertThat(midiRig.midiInput.isConnected()).isFalse();
            assertThat(midiRig.statusBar.getText()).isEqualTo(RecordCoordinator.COUNT_IN_CANCELLED_MESSAGE);
            assertThat(midiRig.keys.getMidiClip().getNotes()).isEmpty();
        });
        assertThat(startedCount()).isZero();
        assertThat(stoppedCount()).as("the transport never rolled, so nothing stopped").isZero();
    }

    @Test
    void aStopDuringAnAudioCountInCancelsItAndPublishesNoClip() throws Exception {
        hookTheBus();
        audioRig = DeviceLossRecordingFixture.withoutEngineBridge(tempDir, CountInMode.TWO_BARS);
        audioRig.project().getTransport().setTempo(60);
        DeviceLossRecordingFixture.runOnFx(audioRig.controller()::toggleRecord);
        DeviceLossRecordingFixture.awaitOnFx(() -> audioRig.coordinator().getState() == RecordState.COUNT_IN,
                "the take counts in");
        CountInSchedule schedule = DeviceLossRecordingFixture.onFx(
                () -> audioRig.pipeline().countInSchedule().orElseThrow());

        DeviceLossRecordingFixture.runOnFx(audioRig.controller()::stop);

        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.CANCELLED);
        DeviceLossRecordingFixture.runOnFx(() -> {
            assertThat(audioRig.coordinator().isObservingCountInForTest()).isFalse();
            assertThat(audioRig.coordinator().getState()).isNotIn(RecordState.COUNT_IN, RecordState.RECORDING);
            assertThat(audioRig.project().getTransport().getState()).isEqualTo(TransportState.STOPPED);
            assertThat(audioRig.statusBar().getText()).isEqualTo(RecordCoordinator.COUNT_IN_CANCELLED_MESSAGE);
        });
        audioRig.awaitPublished();
        DeviceLossRecordingFixture.awaitOnFx(() -> audioRig.coordinator().getState() == RecordState.IDLE,
                "the machine settled");
        DeviceLossRecordingFixture.runOnFx(() -> {
            assertThat(audioRig.guitar().getClips()).isEmpty();
            assertThat(audioRig.statusBar().getText()).isEqualTo(RecordCoordinator.COUNT_IN_CANCELLED_MESSAGE);
        });
        assertThat(startedCount()).isZero();
        assertThat(stoppedCount()).isZero();
    }

    @Test
    void aCountInThatFiredBeforeTheStopIsStoppedAsARecordingTakeWithItsNotesKept() throws Exception {
        hookTheBus();
        midiRig = new MidiOnlyRig(CountInMode.ONE_BAR, 120);
        runOnFx(midiRig.controller::toggleRecord);
        CountInSchedule schedule = onFx(() -> midiRig.engine.pendingCountIn().orElseThrow());

        // One FX turn: wait for the render thread's fire — its hop is queued
        // behind this turn — play a note after bar 1 began, then Stop before
        // the hop can run.
        runOnFx(() -> {
            long deadline = System.nanoTime() + WAIT_BUDGET_NANOS;
            while (!schedule.isFired()) {
                assertThat(System.nanoTime() - deadline < 0).as("the count-in fired within 30 s").isTrue();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertThat(midiRig.transport().getState()).isEqualTo(TransportState.RECORDING);
            assertThat(midiRig.controller.recordCoordinator().getState())
                    .as("the hop has not run yet").isEqualTo(RecordState.COUNT_IN);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            midiRig.playNoteOn();
            midiRig.controller.stop();
        }, WAIT_BUDGET_NANOS + TimeUnit.SECONDS.toNanos(5));

        awaitOnFx(() -> midiRig.controller.recordCoordinator().getState() == RecordState.IDLE, "the machine settled");
        runOnFx(() -> {
            assertThat(midiRig.transport().getState()).isEqualTo(TransportState.STOPPED);
            assertThat(midiRig.controller.recordCoordinator().isObservingCountInForTest()).isFalse();
            assertThat(midiRig.controller.isRecordingInFlight()).isFalse();
            assertThat(midiRig.keys.getMidiClip().getNotes()).as("the note played after the fire is kept").hasSize(1);
            assertThat(midiRig.statusBar.getText()).startsWith("Recording stopped — 1 MIDI note");
        });
        assertThat(startedCount()).as("the take is announced once, as the fire's turn would have").isEqualTo(1);
        assertThat(stoppedCount()).isEqualTo(1);
    }

    /**
     * Story 337, ruling E101: a render-drive halt while an audio or a mixed
     * take counts in. The halt's Stop cancels the count-in as the user's Stop
     * does — no take was started, the notes are discarded, the status bar
     * says so — and closes no stream. It cancelled a count-in, over a
     * transport that never rolled, so it answers no context sentence.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aStopForAnEngineHaltDuringAnAudioCountInCancelsItAndAnswersNoContextSentence(boolean mixed)
            throws Exception {
        hookTheBus();
        audioRig = DeviceLossRecordingFixture.withoutEngineBridge(tempDir, CountInMode.TWO_BARS);
        audioRig.project().getTransport().setTempo(60);
        Track keys = mixed ? armedMidiTrack(audioRig.project()) : null;
        RecordingInFlightFixture.StubMidiInput midiInput = new RecordingInFlightFixture.StubMidiInput();
        DeviceLossRecordingFixture.runOnFx(() -> {
            audioRig.controller().setMidiInputDeviceResolverForTest(
                    name -> RecordingInFlightFixture.STUB_MIDI_INPUT.equals(name) ? midiInput : null);
            audioRig.controller().toggleRecord();
        });
        DeviceLossRecordingFixture.awaitOnFx(() -> audioRig.coordinator().getState() == RecordState.COUNT_IN,
                "the take counts in");
        CountInSchedule schedule = DeviceLossRecordingFixture.onFx(
                () -> audioRig.pipeline().countInSchedule().orElseThrow());
        if (keys != null) {
            assertThat(DeviceLossRecordingFixture.onFx(keys::isRecording)).as("fixture: a mixed take").isTrue();
        }
        AtomicReference<HaltContext> answered = new AtomicReference<>();
        AtomicReference<Boolean> streamOpenAfterTheStop = new AtomicReference<>();

        DeviceLossRecordingFixture.runOnFx(() -> {
            answered.set(audioRig.controller().stopForEngineHalt());
            streamOpenAfterTheStop.set(audioRig.engine().isStreamOpen());
        });

        assertThat(answered.get()).as("the Stop cancelled a count-in").isEqualTo(HaltContext.IDLE);
        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.CANCELLED);
        assertThat(streamOpenAfterTheStop.get()).as("the halt's Stop closed no stream").isTrue();
        DeviceLossRecordingFixture.runOnFx(() -> {
            assertThat(audioRig.statusBar().getText()).isEqualTo(RecordCoordinator.COUNT_IN_CANCELLED_MESSAGE);
            assertThat(audioRig.project().getTransport().getState()).isEqualTo(TransportState.STOPPED);
            if (keys != null) {
                assertThat(keys.isRecording()).as("the notes are discarded").isFalse();
                assertThat(keys.getMidiClip().getNotes()).isEmpty();
            }
        });
        audioRig.awaitPublished();
        DeviceLossRecordingFixture.awaitOnFx(() -> audioRig.coordinator().getState() == RecordState.IDLE,
                "the machine settled");
        DeviceLossRecordingFixture.runOnFx(() -> assertThat(audioRig.guitar().getClips()).isEmpty());
        assertThat(startedCount()).as("no take was started").isZero();
        assertThat(stoppedCount()).as("the transport never rolled, so nothing stopped").isZero();
    }

    /**
     * Story 337, ruling E101: a render-drive halt while a MIDI-only take counts
     * in — over a stopped transport, and over a paused one, which a count-in
     * runs over too. The halt's Stop cancels the count-in, discards the notes
     * and closes no stream; over the paused transport it also stops that
     * transport, which was not playing. It cancelled a count-in, so it
     * answers no context sentence, and never "Playback was stopped.".
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aStopForAnEngineHaltDuringAMidiOnlyCountInCancelsItAndAnswersNoContextSentence(boolean overAPause)
            throws Exception {
        hookTheBus();
        midiRig = new MidiOnlyRig(CountInMode.TWO_BARS, 60);
        if (overAPause) {
            runOnFx(midiRig.controller::start);
            runOnFx(midiRig.controller::pause);
            runOnFx(() -> assertThat(midiRig.transport().getState()).as("fixture: paused")
                    .isEqualTo(TransportState.PAUSED));
        }
        published.clear();
        runOnFx(midiRig.controller::toggleRecord);
        CountInSchedule schedule = onFx(() -> {
            assertThat(midiRig.controller.recordCoordinator().getState()).as("fixture: counting in")
                    .isEqualTo(RecordState.COUNT_IN);
            return midiRig.engine.pendingCountIn().orElseThrow();
        });
        AtomicReference<HaltContext> answered = new AtomicReference<>();
        AtomicReference<Boolean> streamOpenAfterTheStop = new AtomicReference<>();

        runOnFx(() -> {
            answered.set(midiRig.controller.stopForEngineHalt());
            streamOpenAfterTheStop.set(midiRig.engine.isStreamOpen());
        });

        assertThat(answered.get()).as("the Stop cancelled a count-in").isEqualTo(HaltContext.IDLE);
        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.CANCELLED);
        assertThat(streamOpenAfterTheStop.get()).as("the halt's Stop closed no stream").isTrue();
        awaitOnFx(() -> midiRig.controller.recordCoordinator().getState() == RecordState.IDLE, "the machine settled");
        runOnFx(() -> {
            assertThat(midiRig.transport().getState()).isEqualTo(TransportState.STOPPED);
            assertThat(midiRig.statusBar.getText()).isEqualTo(RecordCoordinator.COUNT_IN_CANCELLED_MESSAGE);
            assertThat(midiRig.keys.isRecording()).isFalse();
            assertThat(midiRig.keys.getMidiClip().getNotes()).isEmpty();
        });
        assertThat(startedCount()).as("no take was started").isZero();
        assertThat(stoppedCount()).as("the Stop ran once: only a paused transport had anything to stop")
                .isEqualTo(overAPause ? 1 : 0);
    }

    /**
     * Story 337, ruling E101: a count-in that fired before the halt's Stop
     * started its take. The Stop finds the fire, announces the take as the
     * fire's turn would have and ends it as a recording take, its notes kept,
     * so it answers that recording was stopped.
     */
    @Test
    void aStopForAnEngineHaltAfterTheCountInFiredEndsTheTakeAndAnswersThatRecordingWasStopped() throws Exception {
        hookTheBus();
        midiRig = new MidiOnlyRig(CountInMode.ONE_BAR, 120);
        runOnFx(midiRig.controller::toggleRecord);
        CountInSchedule schedule = onFx(() -> midiRig.engine.pendingCountIn().orElseThrow());
        AtomicReference<HaltContext> answered = new AtomicReference<>();

        // One FX turn: wait for the render thread's fire — its hop is queued
        // behind this turn — play a note, then the halt's Stop before the hop
        // can run.
        runOnFx(() -> {
            long deadline = System.nanoTime() + WAIT_BUDGET_NANOS;
            while (!schedule.isFired()) {
                assertThat(System.nanoTime() - deadline < 0).as("the count-in fired within 30 s").isTrue();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertThat(midiRig.controller.isCountingIn()).as("fixture: the coordinator still counts in")
                    .isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            midiRig.playNoteOn();
            answered.set(midiRig.controller.stopForEngineHalt());
        }, WAIT_BUDGET_NANOS + TimeUnit.SECONDS.toNanos(5));

        assertThat(answered.get()).as("the take had started").isEqualTo(HaltContext.RECORDING);
        awaitOnFx(() -> midiRig.controller.recordCoordinator().getState() == RecordState.IDLE, "the machine settled");
        runOnFx(() -> {
            assertThat(midiRig.transport().getState()).isEqualTo(TransportState.STOPPED);
            assertThat(midiRig.keys.getMidiClip().getNotes()).as("the note played after the fire is kept").hasSize(1);
            assertThat(midiRig.statusBar.getText()).startsWith("Recording stopped — 1 MIDI note");
        });
        assertThat(startedCount()).as("the take is announced once").isEqualTo(1);
        assertThat(stoppedCount()).as("the Stop ran once").isEqualTo(1);
    }

    /**
     * Story 337, ruling E101: a halt's Stop ends a MIDI-only take recording
     * with no count-in, its notes kept, closes no stream, and answers that
     * recording was stopped.
     */
    @Test
    void aStopForAnEngineHaltEndsARecordingMidiOnlyTakeAndAnswersThatRecordingWasStopped() throws Exception {
        hookTheBus();
        midiRig = new MidiOnlyRig(CountInMode.OFF, 120);
        runOnFx(midiRig.controller::toggleRecord);
        runOnFx(() -> assertThat(midiRig.controller.recordCoordinator().getState()).as("fixture: recording")
                .isEqualTo(RecordState.RECORDING));
        midiRig.playNoteOn();
        AtomicReference<HaltContext> answered = new AtomicReference<>();
        AtomicReference<Boolean> streamOpenAfterTheStop = new AtomicReference<>();

        runOnFx(() -> {
            answered.set(midiRig.controller.stopForEngineHalt());
            streamOpenAfterTheStop.set(midiRig.engine.isStreamOpen());
        });

        assertThat(answered.get()).as("the take had started").isEqualTo(HaltContext.RECORDING);
        assertThat(streamOpenAfterTheStop.get()).as("the halt's Stop closed no stream").isTrue();
        awaitOnFx(() -> midiRig.controller.recordCoordinator().getState() == RecordState.IDLE, "the machine settled");
        runOnFx(() -> {
            assertThat(midiRig.transport().getState()).isEqualTo(TransportState.STOPPED);
            assertThat(midiRig.keys.getMidiClip().getNotes()).as("the notes are kept").hasSize(1);
            assertThat(midiRig.statusBar.getText()).startsWith("Recording stopped — 1 MIDI note");
        });
        assertThat(stoppedCount()).as("the Stop ran once").isEqualTo(1);
    }

    /**
     * Story 337, ruling E101: a halt's Stop ends a mixed take recording with
     * audio on disk through its normal finalization, closes no stream, and
     * answers that recording was stopped; the take keeps its audio.
     */
    @Test
    void aStopForAnEngineHaltEndsARecordingMixedTakeAndAnswersThatRecordingWasStopped() throws Exception {
        hookTheBus();
        audioRig = DeviceLossRecordingFixture.withoutEngineBridge(tempDir);
        Track keys = armedMidiTrack(audioRig.project());
        RecordingInFlightFixture.StubMidiInput midiInput = new RecordingInFlightFixture.StubMidiInput();
        DeviceLossRecordingFixture.runOnFx(() -> audioRig.controller().setMidiInputDeviceResolverForTest(
                name -> RecordingInFlightFixture.STUB_MIDI_INPUT.equals(name) ? midiInput : null));
        audioRig.recordUntilABlockIsOnDisk();
        assertThat(DeviceLossRecordingFixture.onFx(keys::isRecording)).as("fixture: a mixed take").isTrue();
        AtomicReference<HaltContext> answered = new AtomicReference<>();
        AtomicReference<Boolean> streamOpenAfterTheStop = new AtomicReference<>();

        DeviceLossRecordingFixture.runOnFx(() -> {
            answered.set(audioRig.controller().stopForEngineHalt());
            streamOpenAfterTheStop.set(audioRig.engine().isStreamOpen());
        });

        assertThat(answered.get()).as("the take had started").isEqualTo(HaltContext.RECORDING);
        assertThat(streamOpenAfterTheStop.get()).as("the halt's Stop closed no stream").isTrue();
        DeviceLossRecordingFixture.runOnFx(() -> {
            assertThat(audioRig.controller().isRecordingInFlight()).as("the take was ended").isFalse();
            assertThat(keys.isRecording()).isFalse();
        });
        audioRig.awaitPublished();
        DeviceLossRecordingFixture.runOnFx(() -> assertThat(audioRig.guitar().getClips()).as("it keeps its audio")
                .hasSize(1));
        assertThat(stoppedCount()).as("the Stop ran once").isEqualTo(1);
    }

    /**
     * Story 337, ruling E101, on the real wiring: the render drive really
     * halts while a MIDI-only take counts in, and the window's audio health
     * drain reads the halt from the engine's own record on its next frame.
     * The window is never FXML-loaded: it is given what its initialize()
     * builds and the project's transport controller, as
     * {@code FailureSpineStartupWiringTest} gives it its engine. Its halt
     * response runs the transport's halt Stop, which cancels the count-in,
     * and the halt's own close closes the halted stream. The one notice says
     * only what that Stop did: it cancelled a count-in, so it has no context
     * sentence — in the ERROR copy, with Restart Audio Engine, and in the
     * FATAL copy of a {@link VirtualMachineError} halt, with Open Log Folder.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRealHaltDuringACountInShowsTheHaltNoticeWithNoContextSentence(boolean fatal) throws Exception {
        midiRig = new MidiOnlyRig(CountInMode.TWO_BARS, 60);
        List<RecordState> states = new CopyOnWriteArrayList<>();
        runOnFx(midiRig.controller::toggleRecord);
        CountInSchedule schedule = onFx(() -> {
            midiRig.controller.recordCoordinator().stateProperty().addListener((_, _, next) -> states.add(next));
            return midiRig.engine.pendingCountIn().orElseThrow();
        });
        try (FailureSpineFixture spine = FailureSpineFixture.detached(tempDir.resolve("settings"), System::nanoTime,
                InstantSource.system(), folder -> { })) {
            FxDispatcher dispatcher = new FxDispatcher();
            NotificationHistoryService history = new NotificationHistoryService();
            NotificationBar bar = onFx(() -> {
                NotificationBar made = new NotificationBar();
                made.setAnimated(false);
                made.setAutoDismissMillis(600_000);
                made.setHistoryService(history);
                return made;
            });
            runOnFx(() -> {
                MainController window = new MainController();
                window.setFxDispatcher(dispatcher);
                setField(window, "notificationBar", bar);
                setField(window, "audioEngine", midiRig.engine);
                setField(window, "project", midiRig.project);
                setField(window, "transportController", midiRig.controller);
                window.attachFailureFunnel(spine.funnel());
            });
            try {
                haltTheRenderDrive(midiRig.engine, fatal);
                assertThat(onFx(midiRig.controller::isCountingIn)).as("fixture: the take still counts in").isTrue();
                assertThat(schedule.isPending()).as("fixture: the count-in never fired").isTrue();

                runOnFx(dispatcher::pulse);

                String base = fatal ? "Serious internal error" : "The audio engine stopped";
                String notice = fatal
                        ? "Serious internal error: the application may not work correctly from now on. When you can,"
                                + " save a copy with File ▸ Save As… and restart. Reason: the render graph recursed."
                        : "The audio engine stopped because audio processing failed for a full second without"
                                + " recovering: an insert fails every block. Restart the audio engine to continue.";
                FailureSpineFixture.await("the halt notice on the window's bar", () -> history.getEntries().stream()
                        .anyMatch(entry -> entry.message().startsWith(base)));
                assertThat(schedule.phase()).as("the halt's Stop cancelled the count-in")
                        .isEqualTo(CountInSchedule.Phase.CANCELLED);
                awaitOnFx(() -> midiRig.controller.recordCoordinator().getState() == RecordState.IDLE,
                        "the machine settled");
                runOnFx(dispatcher::pulse);
                for (int turn = 0; turn < 5; turn++) {
                    runOnFx(() -> { });
                }
                assertThat(history.getEntries()).filteredOn(entry -> entry.message().startsWith(base))
                        .as("one notice, its whole text").singleElement().satisfies(entry -> {
                            assertThat(entry.message()).isEqualTo(notice);
                            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                            assertThat(entry.actionLabel()).contains(fatal ? "Open Log Folder"
                                    : "Restart Audio Engine");
                        });
                assertThat(states).as("the Stop ran once").containsExactly(RecordState.FINALIZING, RecordState.IDLE);
                runOnFx(() -> {
                    assertThat(midiRig.statusBar.getText()).isEqualTo(RecordCoordinator.COUNT_IN_CANCELLED_MESSAGE);
                    assertThat(midiRig.keys.getMidiClip().getNotes()).isEmpty();
                    assertThat(midiRig.transport().getState()).isEqualTo(TransportState.STOPPED);
                });
                assertThat(midiRig.engine.isStreamOpen()).as("the halt's own close closed the halted stream")
                        .isFalse();
            } finally {
                spine.funnel().disarmToasts();
                runOnFx(() -> {
                    bar.dismiss();
                    dispatcher.dispose();
                });
            }
        }
    }

    @Test
    void aDeviceLossDuringTheCountInCancelsItAndRescuesNothing() throws Exception {
        hookTheBus();
        audioRig = DeviceLossRecordingFixture.withEngineBridge(tempDir, CountInMode.TWO_BARS);
        audioRig.project().getTransport().setTempo(60);
        List<RecordState> states = new CopyOnWriteArrayList<>();
        DeviceLossRecordingFixture.runOnFx(() -> {
            audioRig.coordinator().stateProperty().addListener((_, _, next) -> states.add(next));
            audioRig.controller().toggleRecord();
        });
        DeviceLossRecordingFixture.awaitOnFx(() -> audioRig.coordinator().getState() == RecordState.COUNT_IN,
                "the take counts in");
        CountInSchedule schedule = DeviceLossRecordingFixture.onFx(
                () -> audioRig.pipeline().countInSchedule().orElseThrow());
        int shownBefore = audioRig.shownCount();

        audioRig.unplug();

        DeviceLossRecordingFixture.awaitOnFx(() -> audioRig.coordinator().getState() == RecordState.ABORTED
                || audioRig.coordinator().getState() == RecordState.IDLE, "the loss ended the count-in");
        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.CANCELLED);
        assertThat(schedule.isFired()).isFalse();
        audioRig.awaitPublished();
        DeviceLossRecordingFixture.awaitOnFx(() -> audioRig.coordinator().getState() == RecordState.IDLE,
                "the machine settled");
        DeviceLossRecordingFixture.runOnFx(() -> {
            assertThat(audioRig.coordinator().isObservingCountInForTest()).isFalse();
            assertThat(audioRig.project().getTransport().getState()).isEqualTo(TransportState.STOPPED);
            assertThat(audioRig.controller().isRecordingInFlight()).isFalse();
            assertThat(audioRig.guitar().getClips()).isEmpty();
            assertThat(audioRig.statusBar().getText())
                    .startsWith("Recording aborted — no take was started: the audio device")
                    .contains("lost during the count-in");
        });
        assertThat(states).contains(RecordState.ABORTED).doesNotContain(RecordState.DEVICE_LOST, RecordState.RECORDING);
        assertThat(audioRig.entriesSince(shownBefore))
                .anyMatch(entry -> entry.message().contains("no take was started"));
        assertThat(startedCount()).isZero();
    }

    @Test
    void aRollingTransportRecordsAtOnceWithNoCountIn() throws Exception {
        hookTheBus();
        midiRig = new MidiOnlyRig(CountInMode.TWO_BARS, 120);
        runOnFx(midiRig.controller::start);
        runOnFx(() -> assertThat(midiRig.transport().getState()).isEqualTo(TransportState.PLAYING));
        long startedByPlay = startedCount();

        runOnFx(midiRig.controller::toggleRecord);

        runOnFx(() -> {
            assertThat(midiRig.controller.recordCoordinator().getState()).isEqualTo(RecordState.RECORDING);
            assertThat(midiRig.transport().getState()).isEqualTo(TransportState.RECORDING);
            assertThat(midiRig.engine.pendingCountIn()).as("no schedule for a rolling transport").isEmpty();
            assertThat(midiRig.controller.recordCoordinator().isObservingCountInForTest()).isFalse();
            assertThat(midiRig.keys.isRecording()).isTrue();
        });
        assertThat(startedCount()).as("Record's own Started, at once").isEqualTo(startedByPlay + 1);
    }

    @Test
    void seeksAreRefusedWhileTheTakeCountsIn() throws Exception {
        // Story 328 review: the take's anchors were fixed at the record
        // position when the count-in began; a seek now would record from
        // one place and place the take at another. Refused like Play.
        midiRig = new MidiOnlyRig(CountInMode.TWO_BARS, 60);
        runOnFx(() -> midiRig.transport().setPositionInBeats(8.0));
        runOnFx(midiRig.controller::toggleRecord);
        runOnFx(() -> {
            assertThat(midiRig.controller.recordCoordinator().getState()).isEqualTo(RecordState.COUNT_IN);
            midiRig.statusBar.setText("");
            midiRig.controller.skipBack();
            assertThat(midiRig.transport().getPositionInBeats()).as("Home is refused").isEqualTo(8.0);
            assertThat(midiRig.statusBar.getText()).isEqualTo(TransportController.SEEK_REFUSED_DURING_COUNT_IN);
            midiRig.statusBar.setText("");
            midiRig.controller.skipForward();
            assertThat(midiRig.transport().getPositionInBeats()).as("End is refused").isEqualTo(8.0);
            assertThat(midiRig.statusBar.getText()).isEqualTo(TransportController.SEEK_REFUSED_DURING_COUNT_IN);
            assertThat(midiRig.controller.refuseSeekWhileCountingIn())
                    .as("the ruler's and the clip gestures' seeks ask the same gate").isTrue();
            assertThat(midiRig.controller.recordCoordinator().getState()).isEqualTo(RecordState.COUNT_IN);
            assertThat(midiRig.engine.pendingCountIn()).isPresent();
        });
        runOnFx(midiRig.controller::stop);
        awaitOnFx(() -> midiRig.controller.recordCoordinator().getState() == RecordState.IDLE, "the machine settled");
        runOnFx(() -> {
            assertThat(midiRig.controller.refuseSeekWhileCountingIn()).as("after Stop seeks are free again").isFalse();
            midiRig.controller.skipBack();
            assertThat(midiRig.transport().getPositionInBeats()).isEqualTo(0.0);
        });
    }

    @Test
    void theRescuedTakeReviewActionIsRefusedWhileTheTakeCountsIn() throws Exception {
        // Story 328 review round 2: the notification history re-arms a
        // rescued take's "Review" action for as long as its entry lives,
        // so it can be clicked during a later take's count-in, when the
        // transport holds STOPPED at the record position — the state the
        // review seeks in. It asks the same gate as every other seek.
        midiRig = new MidiOnlyRig(CountInMode.TWO_BARS, 60);
        Runnable review = onFx(() -> midiRig.controller.recordCoordinator().rescuedTakeReview(2.0, tempDir, 1.5));
        runOnFx(() -> midiRig.transport().setPositionInBeats(8.0));
        runOnFx(midiRig.controller::toggleRecord);
        runOnFx(() -> {
            assertThat(midiRig.controller.recordCoordinator().getState()).isEqualTo(RecordState.COUNT_IN);
            midiRig.statusBar.setText("");
            review.run();
            assertThat(midiRig.transport().getPositionInBeats()).as("the review's seek is refused").isEqualTo(8.0);
            assertThat(midiRig.statusBar.getText()).isEqualTo(TransportController.SEEK_REFUSED_DURING_COUNT_IN);
            assertThat(midiRig.controller.recordCoordinator().getState()).isEqualTo(RecordState.COUNT_IN);
        });
        runOnFx(midiRig.controller::stop);
        awaitOnFx(() -> midiRig.controller.recordCoordinator().getState() == RecordState.IDLE, "the machine settled");
        runOnFx(() -> {
            review.run();
            assertThat(midiRig.transport().getPositionInBeats()).as("after Stop the review seeks to the anchor")
                    .isEqualTo(2.0);
            assertThat(midiRig.statusBar.getText()).startsWith("Rescued take: ").contains("00:01.5 recorded");
        });
    }

    @Test
    void theLoopToggleIsRefusedWhileALoopRecordTakeIsInProgressAndFreeAgainAfterStop() throws Exception {
        // Story 328 review round 2: a loop-record take took "loop playback
        // on" into its configuration once, before prepare; loop off during
        // the count-in or the first lap would end it as a one-lane stack
        // with a comp clip. Every loop toggle is the one command cascade
        // through TransportController.toggleLoop; the ruler's gestures are
        // not gated (future story 358).
        midiRig = new MidiOnlyRig(CountInMode.ONE_BAR, 120);
        runOnFx(() -> {
            midiRig.transport().setLoopRegion(0.0, 8.0);
            midiRig.transport().setLoopEnabled(true);
            midiRig.transport().setLoopRecordEnabled(true);
        });
        runOnFx(midiRig.controller::toggleRecord);
        runOnFx(() -> {
            assertThat(midiRig.controller.recordCoordinator().getState()).isEqualTo(RecordState.COUNT_IN);
            midiRig.statusBar.setText("");
            midiRig.controller.toggleLoop();
            assertThat(midiRig.transport().isLoopEnabled()).as("refused during the count-in").isTrue();
            assertThat(midiRig.statusBar.getText()).isEqualTo(TransportController.LOOP_REFUSED_DURING_LOOP_RECORD_TAKE);
        });
        awaitOnFx(() -> midiRig.controller.recordCoordinator().getState() == RecordState.RECORDING, "the take records");
        runOnFx(() -> {
            midiRig.statusBar.setText("");
            midiRig.controller.toggleLoop();
            assertThat(midiRig.transport().isLoopEnabled()).as("refused while recording").isTrue();
            assertThat(midiRig.statusBar.getText()).isEqualTo(TransportController.LOOP_REFUSED_DURING_LOOP_RECORD_TAKE);
        });
        runOnFx(midiRig.controller::stop);
        awaitOnFx(() -> midiRig.controller.recordCoordinator().getState() == RecordState.IDLE, "the machine settled");
        runOnFx(() -> {
            midiRig.controller.toggleLoop();
            assertThat(midiRig.transport().isLoopEnabled()).as("after Stop the toggle is accepted").isFalse();
            assertThat(midiRig.statusBar.getText()).isEqualTo("Loop: OFF");
        });
    }

    @Test
    void theLoopToggleIsAcceptedDuringATakeThatIsNotLoopRecording() throws Exception {
        midiRig = new MidiOnlyRig(CountInMode.ONE_BAR, 120);
        runOnFx(() -> {
            midiRig.transport().setLoopRegion(0.0, 8.0);
            midiRig.transport().setLoopEnabled(true);
            assertThat(midiRig.transport().isLoopRecordEnabled()).isFalse();
        });
        runOnFx(midiRig.controller::toggleRecord);
        runOnFx(() -> {
            assertThat(midiRig.controller.recordCoordinator().getState()).isEqualTo(RecordState.COUNT_IN);
            midiRig.controller.toggleLoop();
            assertThat(midiRig.transport().isLoopEnabled()).as("a plain take does not own the loop flag").isFalse();
            assertThat(midiRig.statusBar.getText()).isEqualTo("Loop: OFF");
        });
    }

    @Test
    void aMidiOnlyCountInThatFiredBeforeTheStopGetsASecondLookABlockLaterThatStopsALateRecordingTransport()
            throws Exception {
        // Story 328 review round 2: the FIRED verdict's requestStop() reads
        // the transport once; a render thread between its fired store and
        // the transport's RECORDING store makes that a no-op. The end posts
        // a second look on the next FX turn and one a block period later;
        // the late store is simulated here after the machine settled.
        midiRig = new MidiOnlyRig(CountInMode.ONE_BAR, 120);
        runOnFx(midiRig.controller::toggleRecord);
        CountInSchedule schedule = onFx(() -> midiRig.engine.pendingCountIn().orElseThrow());
        runOnFx(() -> {
            long deadline = System.nanoTime() + WAIT_BUDGET_NANOS;
            while (!schedule.isFired()) {
                assertThat(System.nanoTime() - deadline < 0).as("the count-in fired within 30 s").isTrue();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            midiRig.controller.stop();
        }, WAIT_BUDGET_NANOS + TimeUnit.SECONDS.toNanos(5));
        awaitOnFx(() -> midiRig.controller.recordCoordinator().getState() == RecordState.IDLE, "the machine settled");
        runOnFx(() -> {
            assertThat(midiRig.transport().getState()).isEqualTo(TransportState.STOPPED);
            assertThat(midiRig.delay.delays()).as("one look a block period later")
                    .containsExactly(RecordCoordinator.blockPeriodOf(schedule));
            assertThat(RecordCoordinator.blockPeriodOf(schedule).toNanos()).isEqualTo(Math.round(256 * 1e9 / 44_100));

            // The render thread's RECORDING store landing after the Stop.
            midiRig.transport().record();
            assertThat(midiRig.transport().getState()).isEqualTo(TransportState.RECORDING);
            midiRig.delay.fireAll();
            assertThat(midiRig.transport().getState()).as("the later look stopped the orphaned transport")
                    .isEqualTo(TransportState.STOPPED);
            assertThat(midiRig.controller.recordCoordinator().getState()).isEqualTo(RecordState.IDLE);
        });
    }

    @Test
    void aStopThatCancelledAMidiOnlyCountInAlsoLooksAgainABlockLaterAndLeavesAStoppedTransportAlone() throws Exception {
        midiRig = new MidiOnlyRig(CountInMode.TWO_BARS, 60);
        runOnFx(midiRig.controller::toggleRecord);
        CountInSchedule schedule = onFx(() -> midiRig.engine.pendingCountIn().orElseThrow());
        runOnFx(midiRig.controller::stop);
        awaitOnFx(() -> midiRig.controller.recordCoordinator().getState() == RecordState.IDLE, "the machine settled");
        runOnFx(() -> {
            assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.CANCELLED);
            assertThat(midiRig.delay.delays()).containsExactly(RecordCoordinator.blockPeriodOf(schedule));
            midiRig.delay.fireAll();
            assertThat(midiRig.transport().getState()).isEqualTo(TransportState.STOPPED);
            assertThat(midiRig.controller.recordCoordinator().getState()).isEqualTo(RecordState.IDLE);
        });
    }

    @Test
    void theRulerAndClipGestureSeekPathAsksTheCountInGateBeforeMovingTheTransport() throws Exception {
        // The ruler's seek listener and the clip gestures' seeks land in
        // MainController.seekToPosition, which no headless test constructs:
        // pinned by source, as EngineBinderConformanceTest pins its wiring.
        Path file = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java");
        String code = SourceScanSupport.stripStringLiterals(
                SourceScanSupport.stripComments(Files.readString(file, StandardCharsets.UTF_8)));
        int signature = code.indexOf("private void seekToPosition(double beat)");
        assertThat(signature).as("MainController.seekToPosition exists").isGreaterThanOrEqualTo(0);
        int seek = code.indexOf("setPositionInBeats(", signature);
        assertThat(seek).as("seekToPosition moves the transport").isGreaterThan(signature);
        assertThat(code.substring(signature, seek))
                .as("the ruler's seek path refuses a seek during the count-in before it moves the transport")
                .contains("transportController.refuseSeekWhileCountingIn()");
    }

    @Test
    void theRenderThreadsFireReachesTheCoordinatorThroughTheDispatchersPulseNotThroughRunLater() throws Exception {
        // Story 328 review: the count-in's observer runs on the render
        // thread, where Platform.runLater (the toolkit's queue lock, a
        // wrapper allocation) is forbidden. With a dispatcher the observer
        // publishes into its lock-free channel: a runLater drain alone does
        // not announce the take; the dispatcher's pulse does, on the FX
        // thread. (The rigs without a dispatcher take the pure-unit
        // fallback, which is runLater.)
        hookTheBus();
        FxDispatcher dispatcher = new FxDispatcher();
        runOnFx(dispatcher::pulse);
        midiRig = new MidiOnlyRig(CountInMode.ONE_BAR, 120, dispatcher);
        AtomicReference<String> recordingEnteredOn = new AtomicReference<>();
        runOnFx(() -> {
            midiRig.controller.recordCoordinator().stateProperty().addListener((_, _, next) -> {
                if (next == RecordState.RECORDING) recordingEnteredOn.set(Thread.currentThread().getName());
            });
            midiRig.controller.toggleRecord();
        });
        CountInSchedule schedule = onFx(() -> midiRig.engine.pendingCountIn().orElseThrow());
        assertThat(onFx(() -> dispatcher.openChannelCount())).as("the observer's channel is open").isEqualTo(1);

        long deadline = System.nanoTime() + WAIT_BUDGET_NANOS;
        while (!schedule.isFired()) {
            assertThat(System.nanoTime() - deadline < 0).as("the count-in fired within 30 s").isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        // Several runLater turns: nothing was posted to the FX queue by the fire.
        for (int turn = 0; turn < 3; turn++) {
            runOnFx(() -> { });
        }
        runOnFx(() -> {
            assertThat(midiRig.transport().getState()).isEqualTo(TransportState.RECORDING);
            assertThat(midiRig.controller.recordCoordinator().getState())
                    .as("no runLater carried the fire").isEqualTo(RecordState.COUNT_IN);
        });
        assertThat(startedCount()).isZero();

        runOnFx(dispatcher::pulse);
        runOnFx(() -> {
            assertThat(midiRig.controller.recordCoordinator().getState()).isEqualTo(RecordState.RECORDING);
            assertThat(midiRig.controller.recordCoordinator().isObservingCountInForTest()).isFalse();
            assertThat(dispatcher.openChannelCount()).as("the channel is closed with the observation").isZero();
        });
        assertThat(recordingEnteredOn.get()).contains("JavaFX Application Thread");
        assertThat(startedCount()).isEqualTo(1);
    }

    @Test
    void playIsRefusedWhileTheTakeCountsIn() throws Exception {
        midiRig = new MidiOnlyRig(CountInMode.TWO_BARS, 60);
        runOnFx(midiRig.controller::toggleRecord);
        runOnFx(() -> {
            assertThat(midiRig.controller.recordCoordinator().getState()).isEqualTo(RecordState.COUNT_IN);
            midiRig.controller.start();
            midiRig.controller.togglePlayPause();
            assertThat(midiRig.transport().getState()).as("the transport is still held").isEqualTo(TransportState.STOPPED);
            assertThat(midiRig.controller.recordCoordinator().getState()).isEqualTo(RecordState.COUNT_IN);
            assertThat(midiRig.engine.pendingCountIn()).isPresent();
        });
    }

    private static Track armedMidiTrack(DawProject project) {
        Track keys = new Track("Keys", TrackType.MIDI);
        keys.setArmed(true);
        keys.setMidiInputDeviceName(RecordingInFlightFixture.STUB_MIDI_INPUT);
        project.addTrack(keys);
        return keys;
    }

    /**
     * Halts {@code engine}'s render drive as a failing render does (story
     * 337, rulings E8 and E9): every block's recording callback throws, after
     * the mix and before the count-in's walk, until the drive ends itself — at
     * once for a {@link VirtualMachineError}, after about a second of blocks
     * otherwise. A count-in does not advance meanwhile, so it cannot fire.
     */
    private static void haltTheRenderDrive(AudioEngine engine, boolean fatal) {
        engine.setRecordingCallback((inputBuffer, numFrames) -> {
            if (fatal) {
                throw new StackOverflowError("the render graph recursed");
            }
            throw new IllegalStateException("an insert fails every block");
        });
        long deadline = System.nanoTime() + WAIT_BUDGET_NANOS;
        while (!engine.isRenderDriveHalted()) {
            assertThat(System.nanoTime() - deadline < 0).as("the render drive halted itself within 30 s").isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    /** Sets a field of a window that was never FXML-loaded, as {@code FailureSpineStartupWiringTest} does. */
    private static void setField(MainController window, String name, Object value) {
        try {
            Field field = MainController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(window, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /**
     * A real {@link TransportController} over a real {@link AudioEngine} on a
     * {@link MockAudioBackend} — whose paced render pump walks the count-in
     * — with one armed MIDI track whose input is a stub.
     */
    private static final class MidiOnlyRig implements AutoCloseable {
        final DawProject project = new DawProject("keys", new AudioFormat(44_100.0, 2, 16, 256));
        final Track keys = armedMidiTrack(project);
        final RecordingInFlightFixture.StubMidiInput midiInput = new RecordingInFlightFixture.StubMidiInput();
        final Label statusBar = new Label();
        final ManualFxDelay delay = new ManualFxDelay();
        AudioEngine engine;
        TransportController controller;

        MidiOnlyRig(CountInMode countIn, double tempo) throws Exception {
            this(countIn, tempo, null);
        }

        /** With {@code dispatcher} injected ({@code null}: the pure-unit fallback, {@code FxDispatcher.getDefault()}). */
        MidiOnlyRig(CountInMode countIn, double tempo, FxDispatcher dispatcher) throws Exception {
            project.getTransport().setTempo(tempo);
            MockAudioBackend backend = new MockAudioBackend();
            StreamingProvision provision = new StreamingProvision(backend.name(),
                    List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name()))));
            runOnFx(() -> {
                engine = new AudioEngine(project.getFormat());
                engine.setStreamingProvision(provision);
                NotificationBar notificationBar = new NotificationBar();
                notificationBar.setAnimated(false);
                Label recIndicator = new Label();
                recIndicator.setVisible(false);
                recIndicator.setManaged(false);
                controller = new TransportController(project, engine, new UndoManager(), notificationBar,
                        new Label(), statusBar, recIndicator, new Button(), new Button(),
                        () -> false,
                        () -> GridResolution.QUARTER,
                        () -> countIn,
                        track -> { },
                        () -> true,
                        () -> RoundTripLatency.UNKNOWN,
                        dispatcher);
                controller.setStillWritingDelayForTest(delay);
                controller.setMidiInputDeviceResolverForTest(
                        name -> RecordingInFlightFixture.STUB_MIDI_INPUT.equals(name) ? midiInput : null);
            });
        }

        Transport transport() {
            return project.getTransport();
        }

        /** Hands a note-on (middle C, velocity 100) to the recorder listening on the stub. Any thread. */
        void playNoteOn() {
            Transmitter transmitter = midiInput.getTransmitters().getFirst();
            Receiver receiver = transmitter.getReceiver();
            assertThat(receiver).as("the recorder listens to the stub input").isNotNull();
            try {
                receiver.send(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100), -1L);
            } catch (javax.sound.midi.InvalidMidiDataException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public void close() throws Exception {
            try {
                runOnFx(() -> {
                    if (controller.isRecordingInFlight()) controller.stop();
                });
            } finally {
                engine.stopAudioOutput();
                engine.stop();
            }
        }
    }

    private static void awaitOnFx(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + WAIT_BUDGET_NANOS;
        while (!onFx(condition::getAsBoolean)) {
            assertThat(System.nanoTime() - deadline < 0).as("%s, within 30 s", what).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    private static <T> T onFx(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        runOnFx(() -> result.set(action.get()));
        return result.get();
    }

    private static void runOnFx(Runnable action) throws Exception {
        runOnFx(action, TimeUnit.SECONDS.toNanos(5));
    }

    /** Runs {@code action} on the FX thread and rethrows what it threw; bounded at {@code budgetNanos}. */
    private static void runOnFx(Runnable action, long budgetNanos) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(budgetNanos, TimeUnit.NANOSECONDS)).as("the FX action returned in time").isTrue();
        if (thrown.get() instanceof Error error) throw error;
        if (thrown.get() != null) throw new AssertionError("the FX action threw", thrown.get());
    }

    /** A bus that hands every publication to the test synchronously, on the publishing thread. */
    private static final class PublishHookEventBus implements EventBus {
        private final Consumer<BusEvent> publishHook;

        private PublishHookEventBus(Consumer<BusEvent> publishHook) {
            this.publishHook = publishHook;
        }

        @Override
        public void publish(BusEvent event) {
            publishHook.accept(event);
        }

        @Override
        public <E extends BusEvent> Flow.Publisher<E> subscribe(Class<E> type) {
            throw new UnsupportedOperationException("subscriptions are not used by this test bus");
        }

        @Override
        public <E extends BusEvent> Subscription on(Class<E> type, DispatchMode mode, Consumer<? super E> handler) {
            throw new UnsupportedOperationException("subscriptions are not used by this test bus");
        }

        @Override
        public EventBusMetrics metrics() {
            throw new UnsupportedOperationException("metrics are not used by this test bus");
        }

        @Override
        public void close() {
            // No resources: publish runs synchronously on the publishing thread.
        }
    }
}
