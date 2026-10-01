package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CaptureFlushService;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Transmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real {@link TransportController} recording into the open project as the
 * app records (story 323 review, the project-change guard while recording):
 * an audio take on an armed audio track, streamed through a real
 * {@link AudioEngine} on a {@link MockAudioBackend} (or a backend the test
 * supplies) into the project's {@code audio/takes}; or a MIDI-only take on
 * an armed MIDI track whose input device is a stub, handed to the
 * controller through {@code setMidiInputDeviceResolverForTest}. A test of
 * the guard hands the controller's {@code isRecordingInFlight} and
 * {@code isTakeBeingWritten} to the {@link ProjectLifecycleController} it
 * drives, as {@code MainController} does for its current controller.
 *
 * <p>The controller has a notification bar of its own, so a test's
 * notification history holds what the lifecycle controller shows and none
 * of the transport's toasts. Every FX action runs through
 * {@link Platform#runLater} and is bounded: at 5 s, or — for Record and
 * Stop, which may start or stop a real take — at
 * {@link #REAL_STOP_OR_START_BUDGET}, longer than the join of the take's
 * capture thread; the waits on the test thread — for the first recorded
 * block, for the take to grow — are bounded at 10 s, as in
 * {@code TransportControllerTest}. {@link #close()} stops a recording still
 * in flight, so the take's files are closed before the temporary directory
 * is removed, and then shuts the engine down, even if that Stop failed.</p>
 */
final class RecordingInFlightFixture implements AutoCloseable {

    /** The input device name of the armed MIDI track; only the stub answers to it. */
    static final String STUB_MIDI_INPUT = "Stub MIDI input";

    private static final long WAIT_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(10);

    /** The bound of an FX action that starts or stops no take. */
    private static final Duration FX_TURN_BUDGET = Duration.ofSeconds(5);

    /**
     * The bound of an FX action that may start or stop a real take: a real
     * Stop joins the take's capture thread for up to
     * {@link CaptureFlushService#STOP_JOIN_TIMEOUT} (30 s), and so does the
     * rollback of a Record whose pipeline fails to start; this is that bound
     * plus 5 s for the rest of the FX turn.
     */
    private static final Duration REAL_STOP_OR_START_BUDGET = CaptureFlushService.STOP_JOIN_TIMEOUT.plusSeconds(5);

    private final DawProject project;
    private final Track armed;
    /** The MIDI-only take's input device; {@code null} for an audio take. */
    private final StubMidiInput midiInput;
    private final AudioEngine engine;
    private final TransportController controller;
    /** The audio take's directory, read on the FX thread when the take starts. */
    private volatile Path takeDirectory;

    private RecordingInFlightFixture(DawProject project, Track armed, AudioBackend backend,
                                     StubMidiInput midiInput) throws Exception {
        this.project = project;
        this.armed = armed;
        this.midiInput = midiInput;
        StreamingProvision provision = new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name()))));
        AtomicReference<AudioEngine> engineRef = new AtomicReference<>();
        AtomicReference<TransportController> controllerRef = new AtomicReference<>();
        runOnFx(() -> {
            AudioEngine audioEngine = new AudioEngine(project.getFormat());
            audioEngine.setStreamingProvision(provision);
            engineRef.set(audioEngine);
            NotificationBar notificationBar = new NotificationBar();
            notificationBar.setAnimated(false);
            Label recIndicator = new Label();
            recIndicator.setVisible(false);
            recIndicator.setManaged(false);
            TransportController transport = new TransportController(project, audioEngine, new UndoManager(),
                    notificationBar, new Label(), new Label(), recIndicator, new Button(), new Button(),
                    () -> false,
                    () -> GridResolution.QUARTER,
                    () -> CountInMode.OFF,
                    track -> { },
                    () -> true,
                    () -> RoundTripLatency.UNKNOWN,
                    new StubSessionInputSelection());
            if (midiInput != null) {
                transport.setMidiInputDeviceResolverForTest(
                        name -> STUB_MIDI_INPUT.equals(name) ? midiInput : null);
            }
            controllerRef.set(transport);
        });
        this.engine = engineRef.get();
        this.controller = controllerRef.get();
    }

    /** An armed audio track, "Vox", added to {@code project} and recorded through a {@link MockAudioBackend}. */
    static RecordingInFlightFixture audio(DawProject project) throws Exception {
        return audio(project, new MockAudioBackend());
    }

    /** An armed audio track, "Vox", added to {@code project} and recorded through {@code backend}. */
    static RecordingInFlightFixture audio(DawProject project, AudioBackend backend) throws Exception {
        Track vox = project.createAudioTrack("Vox");
        vox.setArmed(true);
        return new RecordingInFlightFixture(project, vox, backend, null);
    }

    /**
     * An armed MIDI track, "Keys", added to {@code project}, its input the
     * stub device; with no audio track armed, Record starts a MIDI-only take.
     */
    static RecordingInFlightFixture midiOnly(DawProject project) throws Exception {
        Track keys = new Track("Keys", TrackType.MIDI);
        keys.setArmed(true);
        keys.setMidiInputDeviceName(STUB_MIDI_INPUT);
        project.addTrack(keys);
        return new RecordingInFlightFixture(project, keys, new MockAudioBackend(), new StubMidiInput());
    }

    TransportController controller() {
        return controller;
    }

    /**
     * Presses Record as the user does and asserts that the take started —
     * without asking {@code isRecordingInFlight()}, the answer the guard
     * reads: the transport RECORDING and, for an audio take, a take
     * directory streaming; for a MIDI-only take, the track recording from
     * the stub input. FX thread.
     */
    void startOnFx() {
        controller.toggleRecord();
        assertThat(project.getTransport().getState()).as("fixture: the transport records")
                .isEqualTo(TransportState.RECORDING);
        if (midiInput == null) {
            takeDirectory = controller.activeTakeDirectory()
                    .orElseThrow(() -> new AssertionError("fixture: the audio take streams into a take directory"));
        } else {
            assertThat(armed.isRecording()).as("fixture: the MIDI track records").isTrue();
            assertThat(midiInput.isConnected()).as("fixture: from the stub input").isTrue();
        }
    }

    /**
     * {@link #startOnFx()} on the FX thread; for an audio take, then waits,
     * bounded, until a recorded block has reached the take's first segment.
     */
    void start() throws Exception {
        runOnFx(this::startOnFx, REAL_STOP_OR_START_BUDGET);
        if (midiInput == null) {
            awaitABlockOnDisk();
        }
    }

    /**
     * Asserts that the recording goes on: still in flight and the transport
     * still RECORDING; for an audio take, the engine's recording callback
     * still installed, the same take still active and its segment still
     * growing; for a MIDI-only take, the track still recording and its input
     * still connected.
     */
    void assertStillRecording() throws Exception {
        assertThat(onFx(controller::isRecordingInFlight)).as("the recording is still in flight").isTrue();
        assertThat(onFx(() -> project.getTransport().getState())).as("the transport is still RECORDING")
                .isEqualTo(TransportState.RECORDING);
        if (midiInput == null) {
            assertThat(onFx(engine::getRecordingCallback)).as("the engine's recording callback is still installed")
                    .isNotNull();
            assertThat(onFx(controller::activeTakeDirectory)).as("the same take is still active")
                    .contains(takeDirectory);
            Path segment = firstSegment();
            long size = sizeOf(segment);
            await(() -> sizeOf(segment) > size, "the take is still streaming: " + segment + " grew past " + size
                    + " bytes");
        } else {
            assertThat(onFx(armed::isRecording)).as("the MIDI track is still recording").isTrue();
            assertThat(midiInput.isConnected()).as("its input is still connected").isTrue();
        }
    }

    /**
     * The user's Stop, once an audio take has its first block on disk; then
     * nothing is in flight or being written any more, an audio take's clip
     * has been published on its track, and a MIDI-only take's input has been
     * disconnected (no note was played, so it publishes none).
     */
    void stop() throws Exception {
        if (midiInput == null) {
            awaitABlockOnDisk();
        }
        runOnFx(controller::stop, REAL_STOP_OR_START_BUDGET);
        assertThat(onFx(controller::isRecordingInFlight)).as("fixture: the Stop ended the recording").isFalse();
        assertThat(onFx(controller::isTakeBeingWritten)).as("fixture: nothing is being written").isFalse();
        if (midiInput == null) {
            assertThat(onFx(() -> List.copyOf(armed.getClips()))).as("fixture: the Stop published the take")
                    .hasSize(1);
        } else {
            assertThat(midiInput.isConnected()).as("fixture: the Stop disconnected the input").isFalse();
        }
    }

    /**
     * Stops a recording still in flight, which closes the take's files, and
     * then shuts the engine down — in a {@code finally}, so a Stop that fails
     * or outlasts its bound does not skip that.
     */
    @Override
    public void close() throws Exception {
        try {
            runOnFx(() -> {
                if (controller.isRecordingInFlight()) {
                    controller.stop();
                }
            }, REAL_STOP_OR_START_BUDGET);
        } finally {
            engine.stopAudioOutput();
            engine.stop();
        }
    }

    /** Waits, bounded, until the audio take's first segment holds a block past its 44-byte header. */
    private void awaitABlockOnDisk() {
        Path segment = firstSegment();
        await(() -> sizeOf(segment) > 44, "fixture: a recorded block reached " + segment);
    }

    private Path firstSegment() {
        return takeDirectory.resolve(armed.getId()).resolve("segment-000.wav.part");
    }

    /** The file's size, or -1 while it does not exist yet. */
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (NoSuchFileException notYetCreated) {
            return -1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + WAIT_BUDGET_NANOS;
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline < 0).as("%s, within 10 s", what).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static <T> T onFx(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        runOnFx(() -> result.set(action.get()));
        return result.get();
    }

    /** Runs {@code action} on the FX thread and rethrows what it threw; bounded at {@link #FX_TURN_BUDGET}. */
    private static void runOnFx(Runnable action) throws Exception {
        runOnFx(action, FX_TURN_BUDGET);
    }

    /** Runs {@code action} on the FX thread and rethrows what it threw; bounded at {@code budget}. */
    private static void runOnFx(Runnable action, Duration budget) throws Exception {
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
        assertThat(done.await(budget.toMillis(), TimeUnit.MILLISECONDS))
                .as("the FX action returned within %d s", budget.toSeconds()).isTrue();
        if (thrown.get() instanceof Error error) {
            throw error;
        }
        if (thrown.get() != null) {
            throw new AssertionError("the FX action threw", thrown.get());
        }
    }

    /**
     * A MIDI input with no hardware behind it: it opens and closes, and hands
     * out a transmitter that keeps the receiver set on it and records whether
     * it has been closed.
     */
    private static final class StubMidiInput implements MidiDevice {
        private final Info info = new Info(STUB_MIDI_INPUT, "Test", "A MIDI input with no hardware", "1.0") { };
        private volatile boolean open;
        private volatile StubTransmitter transmitter;

        /** Whether the transmitter handed out last has a receiver and has not been closed. */
        boolean isConnected() {
            StubTransmitter current = transmitter;
            return current != null && current.receiver != null && !current.closed;
        }

        @Override
        public Info getDeviceInfo() {
            return info;
        }

        @Override
        public void open() {
            open = true;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public long getMicrosecondPosition() {
            return -1;
        }

        @Override
        public int getMaxReceivers() {
            return 0;
        }

        @Override
        public int getMaxTransmitters() {
            return 1;
        }

        @Override
        public Receiver getReceiver() throws MidiUnavailableException {
            throw new MidiUnavailableException("a MIDI input has no receiver");
        }

        @Override
        public List<Receiver> getReceivers() {
            return List.of();
        }

        @Override
        public Transmitter getTransmitter() {
            StubTransmitter handedOut = new StubTransmitter();
            transmitter = handedOut;
            return handedOut;
        }

        @Override
        public List<Transmitter> getTransmitters() {
            StubTransmitter current = transmitter;
            return current == null ? List.of() : List.of(current);
        }
    }

    private static final class StubTransmitter implements Transmitter {
        private volatile Receiver receiver;
        private volatile boolean closed;

        @Override
        public void setReceiver(Receiver receiver) {
            this.receiver = receiver;
        }

        @Override
        public Receiver getReceiver() {
            return receiver;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
