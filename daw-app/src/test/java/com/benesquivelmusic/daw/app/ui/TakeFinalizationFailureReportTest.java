package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Transmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A Stop whose own seal fails on disk is reported as an ERROR, never as the
 * SUCCESS (PR #978 review, the finding that a failure while sealing a
 * user-requested Stop still showed "Recording stopped — N clips created"),
 * through the controller's production wiring of the core's
 * {@code RecordingPipeline.stopSealFailure()} — no test seam stands in for
 * the core here. The seal fails for real: a file already sits where the
 * take's first segment is to be sealed, and a segment is never overwritten,
 * so that lane's seal throws and its segment is left as its {@code .part}.
 * When the same take recorded MIDI notes, their SUCCESS toast comes first
 * — the Stop shows it — and the failure last, when the take is published, so
 * the failure is what the notification bar shows.
 *
 * <p>The recording is real: a {@link TransportController} over a real
 * {@link AudioEngine} on a {@link MockAudioBackend}, streaming a stereo 24-bit
 * take into the project's {@code audio/takes}, with a MIDI input that is a
 * stub with no hardware behind it. Neither Record nor Stop waits for the
 * take's capture thread (PR #978 review 5391920205): the take records, and a
 * stopped take is published, on a later FX turn, which the test waits for.
 * The one seam set is the still-writing warning's delay
 * ({@link ManualFxDelay}, never fired), so that a seal slower than that
 * delay on a busy disk cannot slip a WARNING into the sequence asserted;
 * it stands in for nothing of the core. Every FX action and every wait is
 * bounded.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TakeFinalizationFailureReportTest {

    private static final String KEYS_INPUT = "Keys input with no hardware";
    private static final Duration FX_TURN_BUDGET = Duration.ofSeconds(5);
    private static final long WAIT_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** The hang guard of a wait for a real take's capture thread: its files created, or the take published. */
    private static final long TAKE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final String ALREADY_THERE = "not a segment of this take";

    @TempDir
    Path projectDirectory;

    private final NotificationHistoryService shown = new NotificationHistoryService();
    private final KeysInput keysInput = new KeysInput();
    private DawProject project;
    private Track guitar;
    private AudioEngine engine;
    private TransportController controller;
    private NotificationBar notificationBar;
    private Label statusBar;

    @BeforeEach
    void armAGuitarTrack() {
        project = new DawProject("Demo", new AudioFormat(44_100.0, 2, 24, 256));
        project.setMetadata(project.getMetadata().withPath(projectDirectory));
        guitar = project.createAudioTrack("Guitar");
        guitar.setArmed(true);
    }

    @AfterEach
    void stopEverything() throws Exception {
        if (controller == null) {
            return;
        }
        try {
            runOnFx(() -> {
                if (controller.isRecordingInFlight()) {
                    controller.stop();
                }
            });
            awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take's files are closed");
        } finally {
            engine.stopAudioOutput();
            engine.stop();
        }
    }

    @Test
    void aStopWhoseSealFailsOnDiskIsReportedOnceAsAnErrorAndNeverAsSuccess() throws Exception {
        newController();
        Path takeDirectory = recordUntilABlockIsOnDisk();
        Path sealedPath = occupyTheSealedPathOfTheFirstSegment(takeDirectory);
        int before = shown.size();

        runOnFx(controller::stop);
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the stopped take was published");

        String report = "Recording stopped — finishing the take on disk failed (sealed segment already exists;"
                + " a segment is never overwritten); one or more segment files that could not be finished are"
                + " left under audio/takes/" + takeDirectory.getFileName() + " (1 clip created)";
        assertThat(entriesSince(before)).as("one ERROR, and no SUCCESS")
                .extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(tuple(NotificationLevel.ERROR, report));
        assertThat(onFx(statusBar::getText)).isEqualTo(report);
        assertThat(onFx(() -> List.copyOf(guitar.getClips()))).as("the take's clip is published all the same")
                .hasSize(1);
        assertThat(onFx(project::isDirty)).isTrue();
        assertThat(sealedPath.resolveSibling("segment-000.wav.part")).as("the segment is left as its .part")
                .isRegularFile();
        assertThat(Files.readString(sealedPath, StandardCharsets.UTF_8)).as("and what was there is untouched")
                .isEqualTo(ALREADY_THERE);
    }

    @Test
    void aFailedSealIsShownAfterTheMidiNotesOfTheSameTake() throws Exception {
        Track keys = new Track("Keys", TrackType.MIDI);
        keys.setArmed(true);
        keys.setMidiInputDeviceName(KEYS_INPUT);
        project.addTrack(keys);
        newController();
        runOnFx(() -> controller.setMidiInputDeviceResolverForTest(name -> KEYS_INPUT.equals(name) ? keysInput : null));
        Path takeDirectory = recordUntilABlockIsOnDisk();
        keysInput.play(64);
        occupyTheSealedPathOfTheFirstSegment(takeDirectory);
        int before = shown.size();

        runOnFx(controller::stop);
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the stopped take was published");

        String report = "Recording stopped — finishing the take on disk failed (sealed segment already exists;"
                + " a segment is never overwritten); one or more segment files that could not be finished are"
                + " left under audio/takes/" + takeDirectory.getFileName() + " (1 clip created)";
        assertThat(entriesSince(before)).as("the MIDI notes' SUCCESS, then the failure")
                .extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(tuple(NotificationLevel.SUCCESS, "Recording stopped — 1 MIDI note captured"),
                        tuple(NotificationLevel.ERROR, report));
        assertThat(onFx(notificationBar::getCurrentLevel)).as("the failure is what the bar shows")
                .isEqualTo(NotificationLevel.ERROR);
        assertThat(onFx(notificationBar::getMessage)).isEqualTo(report);
        assertThat(onFx(statusBar::getText)).isEqualTo(report);
        assertThat(keys.getMidiClip().size()).as("fixture: the note was recorded").isEqualTo(1);
    }

    private void newController() throws Exception {
        MockAudioBackend backend = new MockAudioBackend();
        StreamingProvision provision = new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name()))));
        runOnFx(() -> {
            engine = new AudioEngine(project.getFormat());
            engine.setStreamingProvision(provision);
            notificationBar = new NotificationBar();
            notificationBar.setAnimated(false);
            notificationBar.setHistoryService(shown);
            statusBar = new Label();
            Label recIndicator = new Label();
            controller = new TransportController(project, engine, new UndoManager(), notificationBar,
                    new Label(), statusBar, recIndicator, new Button(), new Button(),
                    () -> false,
                    () -> GridResolution.QUARTER,
                    () -> CountInMode.OFF,
                    track -> { },
                    () -> true,
                    () -> RoundTripLatency.UNKNOWN,
                    new StubSessionInputSelection());
            controller.setStillWritingDelayForTest(new ManualFxDelay());
        });
    }

    /**
     * Presses Record, waits, bounded, for the take to record, for the record
     * start's input check and for the take's first segment to hold a block;
     * returns the take directory.
     */
    private Path recordUntilABlockIsOnDisk() throws Exception {
        runOnFx(controller::toggleRecord);
        awaitOnFx(() -> !controller.isPreparingTake(), "the take's files were created and capture began");
        Path takeDirectory = onFx(() -> controller.activeTakeDirectory().orElseThrow(
                () -> new AssertionError("fixture: the take streams into a take directory")));
        Optional<Thread> check = onFx(controller::pendingSessionInputCheck);
        if (check.isPresent()) {
            check.get().join(TimeUnit.SECONDS.toMillis(5));
            assertThat(check.get().isAlive()).as("fixture: the input check completed").isFalse();
        }
        runOnFx(() -> { }); // whatever the check posted has run
        Path part = takeDirectory.resolve(guitar.getId()).resolve("segment-000.wav.part");
        long deadline = System.nanoTime() + WAIT_BUDGET_NANOS;
        while (sizeOf(part) <= 44) {
            assertThat(System.nanoTime() - deadline < 0).as("fixture: a block reached %s within 10 s", part).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return takeDirectory;
    }

    /** Puts a file where the guitar's first segment is to be sealed; returns that path. */
    private Path occupyTheSealedPathOfTheFirstSegment(Path takeDirectory) throws IOException {
        Path sealedPath = takeDirectory.resolve(guitar.getId()).resolve("segment-000.wav");
        Files.writeString(sealedPath, ALREADY_THERE, StandardCharsets.UTF_8);
        return sealedPath;
    }

    private List<NotificationEntry> entriesSince(int before) {
        List<NotificationEntry> entries = shown.getEntries();
        return entries.subList(before, entries.size());
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (NoSuchFileException notYet) {
            return -1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> T onFx(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        runOnFx(() -> result.set(action.get()));
        return result.get();
    }

    /** Waits, at most {@link #TAKE_BUDGET_NANOS}, polling on the FX thread, until {@code condition} holds there. */
    private static void awaitOnFx(Supplier<Boolean> condition, String what) throws Exception {
        long deadline = System.nanoTime() + TAKE_BUDGET_NANOS;
        while (!onFx(condition)) {
            assertThat(System.nanoTime() - deadline < 0).as("%s, within 30 s", what).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    private static void runOnFx(Runnable action) throws Exception {
        runOnFx(action, FX_TURN_BUDGET);
    }

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

    /** A MIDI input with no hardware: it hands out one transmitter, and {@link #play} holds a note on it. */
    private static final class KeysInput implements MidiDevice {
        private final Info info = new Info(KEYS_INPUT, "Test", "A MIDI input with no hardware", "2.0") { };
        private volatile boolean open;
        private volatile KeysTransmitter transmitter;

        /** Holds {@code note} down on channel 1, the one the recorder records, at velocity 90; the Stop finishes it. Any thread. */
        void play(int note) throws Exception {
            KeysTransmitter current = transmitter;
            assertThat(current).as("fixture: the recorder took the transmitter").isNotNull();
            Receiver receiver = current.receiver;
            assertThat(receiver).as("fixture: the recorder listens to the input").isNotNull();
            receiver.send(new ShortMessage(ShortMessage.NOTE_ON, 0, note, 90), 0L);
        }

        @Override public Info getDeviceInfo() { return info; }
        @Override public void open() { open = true; }
        @Override public void close() { open = false; }
        @Override public boolean isOpen() { return open; }
        @Override public long getMicrosecondPosition() { return -1; }
        @Override public int getMaxReceivers() { return 0; }
        @Override public int getMaxTransmitters() { return 1; }

        @Override
        public Receiver getReceiver() throws MidiUnavailableException {
            throw new MidiUnavailableException("a MIDI input has no receiver");
        }

        @Override public List<Receiver> getReceivers() { return List.of(); }

        @Override
        public Transmitter getTransmitter() {
            KeysTransmitter handedOut = new KeysTransmitter();
            transmitter = handedOut;
            return handedOut;
        }

        @Override
        public List<Transmitter> getTransmitters() {
            KeysTransmitter current = transmitter;
            return current == null ? List.of() : List.of(current);
        }
    }

    private static final class KeysTransmitter implements Transmitter {
        private volatile Receiver receiver;

        @Override public void setReceiver(Receiver receiver) { this.receiver = receiver; }
        @Override public Receiver getReceiver() { return receiver; }
        @Override public void close() { }
    }
}
