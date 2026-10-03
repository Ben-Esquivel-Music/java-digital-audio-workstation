package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.project.DawProject;
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

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
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
 * {@link Platform#runLater} and is bounded at 5 s: neither Record nor Stop
 * waits for the take's capture thread on the FX thread (PR #978 review
 * 5391920205). An audio take's Record only enters PREPARING; the take
 * records once its capture thread has created its files, on a later FX turn,
 * and its Stop publishes it on a later FX turn still, once that thread has
 * terminated. So the waits on the test thread — for the take to record, for
 * the first recorded block, for the take to grow, for the stopped take to be
 * published — are bounded, at 10 s, or at {@link #TAKE_BUDGET_NANOS} for
 * what waits on a real disk. {@link #close()} stops a recording still in
 * flight and waits until nothing is being written, so the take's files are
 * closed before the temporary directory is removed, and then shuts the
 * engine down, even if that Stop failed.</p>
 */
final class RecordingInFlightFixture implements AutoCloseable {

    /** The input device name of the armed MIDI track; only the stub answers to it. */
    static final String STUB_MIDI_INPUT = "Stub MIDI input";

    private static final long WAIT_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(10);

    /**
     * The bound of a wait for a real take's capture thread — its files
     * created, or the take sealed and published: a hang guard, which a
     * passing wait never comes near.
     */
    private static final long TAKE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** The bound of an FX action. */
    private static final Duration FX_TURN_BUDGET = Duration.ofSeconds(5);

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
     * reads: for an audio take, that its take is being prepared (it records
     * on a later FX turn, once its capture thread has created its files —
     * {@link #awaitTheAudioTakeRecording()}); for a MIDI-only take, which
     * starts at once, the transport RECORDING and the track recording from
     * the stub input. FX thread.
     */
    void startOnFx() {
        controller.toggleRecord();
        if (midiInput == null) {
            assertThat(controller.isPreparingTake()).as("fixture: the audio take is being prepared").isTrue();
        } else {
            assertThat(project.getTransport().getState()).as("fixture: the transport records")
                    .isEqualTo(TransportState.RECORDING);
            assertThat(armed.isRecording()).as("fixture: the MIDI track records").isTrue();
            assertThat(midiInput.isConnected()).as("fixture: from the stub input").isTrue();
        }
    }

    /**
     * {@link #startOnFx()} on the FX thread; for an audio take, then waits,
     * bounded, until the take records and a recorded block has reached its
     * first segment.
     */
    void start() throws Exception {
        runOnFx(this::startOnFx);
        if (midiInput == null) {
            awaitTheAudioTakeRecording();
            awaitABlockOnDisk();
        }
    }

    /**
     * Waits, bounded, until the audio take Record started has left PREPARING,
     * and asserts that it records: the transport RECORDING and a take
     * directory streaming, which it remembers. Once remembered, returns at
     * once.
     */
    private void awaitTheAudioTakeRecording() throws Exception {
        if (takeDirectory != null) {
            return;
        }
        await(() -> !onFxUnchecked(controller::isPreparingTake), TAKE_BUDGET_NANOS,
                "fixture: the audio take's files were created and capture began");
        assertThat(onFx(() -> project.getTransport().getState())).as("fixture: the transport records")
                .isEqualTo(TransportState.RECORDING);
        takeDirectory = onFx(controller::activeTakeDirectory)
                .orElseThrow(() -> new AssertionError("fixture: the audio take streams into a take directory"));
    }

    /**
     * Asserts that the recording goes on: still in flight and the transport
     * still RECORDING; for an audio take — first waited for, bounded, if it
     * was started by {@link #startOnFx()} and has not been seen recording
     * yet — the engine's recording callback still installed, the same take
     * still active and its segment still growing; for a MIDI-only take, the
     * track still recording and its input still connected.
     */
    void assertStillRecording() throws Exception {
        if (midiInput == null) {
            awaitTheAudioTakeRecording();
        }
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
     * The user's Stop, once an audio take has its first block on disk; then,
     * once the take's capture thread has terminated and the take has been
     * published on a later FX turn (waited for, bounded), nothing is in
     * flight or being written any more, an audio take's clip has been
     * published on its track — which marks the project dirty — and a
     * MIDI-only take's input has been disconnected (it publishes only the
     * notes played through {@link #holdANote()}).
     */
    void stop() throws Exception {
        if (midiInput == null) {
            awaitTheAudioTakeRecording();
            awaitABlockOnDisk();
        }
        runOnFx(controller::stop);
        assertThat(onFx(controller::isRecordingInFlight)).as("fixture: the Stop ended the recording").isFalse();
        awaitNothingBeingWritten();
        if (midiInput == null) {
            assertThat(onFx(() -> List.copyOf(armed.getClips()))).as("fixture: the Stop published the take")
                    .hasSize(1);
            assertThat(onFx(project::isDirty)).as("fixture: publishing the take marked the project dirty")
                    .isTrue();
        } else {
            assertThat(midiInput.isConnected()).as("fixture: the Stop disconnected the input").isFalse();
        }
    }

    /**
     * Holds one note down on a MIDI-only take's stub input: the stub's
     * transmitter hands a note-on (middle C, channel 1, velocity 100) to the
     * recorder's receiver, and the Stop finalises the held note into the
     * track's MIDI clip. Any thread.
     */
    void holdANote() throws InvalidMidiDataException {
        assertThat(midiInput).as("fixture: a MIDI-only take").isNotNull();
        StubTransmitter transmitter = midiInput.transmitter;
        assertThat(transmitter).as("fixture: the take's recorder took the stub's transmitter").isNotNull();
        Receiver receiver = transmitter.receiver;
        assertThat(receiver).as("fixture: the recorder listens to the stub input").isNotNull();
        receiver.send(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100), 0L);
    }

    /**
     * Stops a recording still in flight — a take still being prepared is
     * cancelled — and waits, bounded, until nothing is being written, which
     * closes the take's files, and then shuts the engine down — in a
     * {@code finally}, so a Stop that fails or a take that outlasts its bound
     * does not skip that.
     */
    @Override
    public void close() throws Exception {
        try {
            runOnFx(() -> {
                if (controller.isRecordingInFlight()) {
                    controller.stop();
                }
            });
            awaitNothingBeingWritten();
        } finally {
            engine.stopAudioOutput();
            engine.stop();
        }
    }

    /** Waits, bounded, until no take of the controller is being written or having its files removed. */
    private void awaitNothingBeingWritten() {
        await(() -> !onFxUnchecked(controller::isTakeBeingWritten), TAKE_BUDGET_NANOS,
                "fixture: the stopped take was published and nothing is being written");
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
        await(condition, WAIT_BUDGET_NANOS, what);
    }

    private static void await(BooleanSupplier condition, long budgetNanos, String what) {
        long deadline = System.nanoTime() + budgetNanos;
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline < 0)
                    .as("%s, within %d s", what, TimeUnit.NANOSECONDS.toSeconds(budgetNanos)).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static <T> T onFx(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        runOnFx(() -> result.set(action.get()));
        return result.get();
    }

    /** {@link #onFx}, for a condition polled by {@link #await}: a failed FX turn is rethrown unchecked. */
    private static boolean onFxUnchecked(Supplier<Boolean> action) {
        try {
            return onFx(action);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
