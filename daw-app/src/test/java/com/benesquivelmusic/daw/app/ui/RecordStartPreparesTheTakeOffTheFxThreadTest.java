package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CaptureFlushService;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.track.Track;
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
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;

import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #978 review 5391920205 (F2): Record does no storage I/O on the FX thread
 * and waits for no other thread. The take's directory is allocated on the
 * controller's storage executor, and its files — each armed track's first
 * segment and the initial manifest — are created by the take's
 * {@code capture-flush} thread; meanwhile the take is being prepared
 * (PREPARING), and capture begins on a later FX turn, only once its files
 * exist (Recording Reliability book §3.2: RECORDING is entered only after
 * every precondition holds; §5.2: Record pressed again is a toggle, and a
 * project replace is refused while recording). Over a stopped transport, in
 * a project whose mixer holds no instrument insert (one would keep the
 * stream open), a Stop or Record pressed again that cancels the take closes
 * the output stream Record opened, as every other Stop does, and so does a
 * take directory that cannot be allocated or a pipeline that cannot be
 * built; over a playing transport, Record pressed again and those two
 * failures leave playback and its stream running; over a paused one, Record
 * pressed again closes that stream, as over a stopped one, and the
 * transport stays paused. The end of a post-roll —
 * the deferred half of a Stop — that comes while a take Record started
 * inside the tail is being prepared cancels that take as a Stop does: the
 * transport is stopped and never records. A post-roll's timer whose tail
 * already ended, or whose controller was retired, does nothing when it
 * fires: a take being prepared afterwards — by the same controller, or by
 * the one that replaced it over the same engine, status bar and REC
 * indicator — keeps its stream and begins. A cancelled start's readiness
 * turn does nothing for it, whether its readiness failed or had completed
 * normally.
 *
 * <p>The take is real: a {@link TransportController} over a real
 * {@link AudioEngine} on a {@link MockAudioBackend}, recording into a real
 * project's {@code audio/takes}. Its capture thread is held by
 * {@link CaptureThreadHold} — during the take's preparation, or, in the test
 * whose {@code beginCapture()} fails, in a pass of its drain loop — and
 * released by every test that arms it, in a {@code finally}. A test that
 * pins what a cancelled start's readiness turn did waits, bounded, for the
 * held capture thread to end — that thread posts the turn before it ends —
 * and then runs one FX turn behind it. Seven tests hold storage work
 * through the controller's storage executor seam — the allocation; the
 * allocation of a second take; the allocation of a take over a paused
 * transport; the allocation of a take started inside a post-roll's tail;
 * the allocation of a take started after a tail ended; the allocation of
 * the take of the controller that replaced a retired one; the allocation
 * and then the removal of a failed start's directory — and every other
 * take runs the production executor, which one test pins from a failed
 * allocation's stack. The four tests that fire a post-roll's timer stop its
 * {@code PauseTransition} — the controller's {@code postRollTimer}, read
 * reflectively — in the Stop's own FX turn, so no clock fires it, and fire
 * it by running on the FX thread the handler Stop installed on that
 * transition: two while the tail still plays, one after Shift+Space ended
 * the tail, and one after the controller was retired, which happens in
 * that same FX turn. The
 * transport's announcements are collected through a synchronous bus
 * installed as the default for each test. Every FX action is bounded at 5 s,
 * except the one turn that waits inside itself, at most
 * {@link #READY_WAIT}, for a take to become ready, which is bounded at
 * {@link #READY_TURN_BUDGET}; the transport listener that fails
 * {@code beginCapture()} waits inside the readiness turn at most
 * {@link #HOLD_IN_A_PASS_WAIT}, below that 5 s; every wait for a real disk
 * is bounded at 30 s.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RecordStartPreparesTheTakeOffTheFxThreadTest {

    private static final long TAKE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** The longest the one FX turn that waits for a take to become ready waits. */
    private static final Duration READY_WAIT = Duration.ofSeconds(10);

    /** The bound of that FX turn: above {@link #READY_WAIT}. */
    private static final Duration READY_TURN_BUDGET = Duration.ofSeconds(15);

    /**
     * The longest the transport listener that fails {@code beginCapture()}
     * waits, inside the readiness turn, for the capture thread to be held in
     * a pass: below the 5 s bound of every FX action the test thread posts
     * meanwhile.
     */
    private static final Duration HOLD_IN_A_PASS_WAIT = Duration.ofSeconds(3);

    @TempDir
    Path workspace;

    private ProjectManager projectManager;
    private Path projectDirectory;
    private DawProject project;
    private Track armed;
    private AudioEngine engine;
    private TransportController controller;
    private NotificationBar notificationBar;
    private Label statusBar;
    private Label recIndicator;
    private final NotificationHistoryService shown = new NotificationHistoryService();
    private final CaptureThreadHold hold = new CaptureThreadHold();
    private final List<BusEvent> announced = new CopyOnWriteArrayList<>();
    private EventBus previousBus;

    @BeforeEach
    void armATrackInASavedProject() throws Exception {
        projectManager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        projectDirectory = projectManager.createProject("Song A", workspace).projectPath();
        project = new DawProject("Song A", new AudioFormat(48000, 2, 16, 256));
        project.setMetadata(project.getMetadata().withPath(projectDirectory));
        armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        project.getTransport().setPositionInBeats(4.0);
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
            recIndicator = new Label();
            recIndicator.setVisible(false);
            recIndicator.setManaged(false);
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
            hold.installOn(controller);
        });
        previousBus = EventBusPublisher.getDefault();
        EventBusPublisher.setDefault(new CollectingBus(announced::add));
    }

    @AfterEach
    void releaseAndStopEverything() throws Exception {
        try {
            hold.release();
            EventBusPublisher.setDefault(previousBus);
            runOnFx(() -> {
                if (controller.isRecordingInFlight()) {
                    controller.stop();
                }
            });
            awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take's files are closed");
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            projectManager.abandonProject();
        }
    }

    @Test
    void recordReturnsWhileTheTakesFilesAreBeingCreatedAndCaptureBeginsOnlyOnceTheyExist() throws Exception {
        hold.arm(); // the capture thread will be held while it creates the take's files

        runOnFx(controller::toggleRecord); // returns within the FX bound although the files are not created yet
        hold.awaitHolding(Duration.ofSeconds(10));

        try {
            assertThat(onFx(controller::isPreparingTake)).as("the take is being prepared").isTrue();
            assertThat(onFx(controller::isRecordingInFlight)).as("a take being prepared is a recording in flight")
                    .isTrue();
            assertThat(onFx(statusBar::getText)).isEqualTo(TransportController.TAKE_PREPARING_MESSAGE);
            assertThat(onFx(recIndicator::isVisible)).as("nothing is being recorded yet: REC is off").isFalse();
            assertThat(onFx(() -> project.getTransport().getState())).as("capture has not begun")
                    .isEqualTo(TransportState.STOPPED);
            assertThat(onFx(engine::getRecordingCallback)).as("no recording callback yet").isNull();
            assertThat(announced).as("nothing is announced before the transport records")
                    .noneMatch(TransportEvent.Started.class::isInstance);
            Path takeDirectory = onlyTakeDirectory();
            assertThat(takeDirectory.resolve(armed.getId()).resolve("segment-000.wav.part"))
                    .as("the capture thread is creating the take's files").isRegularFile();
            assertThat(TakeManifest.manifestPath(takeDirectory)).as("and has not written the manifest yet")
                    .doesNotExist();
        } finally {
            hold.release();
        }
        awaitOnFx(() -> !controller.isPreparingTake(), "the take's files were created and capture began");

        assertThat(onFx(() -> project.getTransport().getState())).isEqualTo(TransportState.RECORDING);
        assertThat(onFx(engine::getRecordingCallback)).as("the recording callback is installed").isNotNull();
        Path takeDirectory = onFx(controller::activeTakeDirectory).orElseThrow();
        assertThat(TakeManifest.manifestPath(takeDirectory)).as("capture began after the manifest existed")
                .isRegularFile();
        assertThat(onFx(statusBar::getText))
                .isEqualTo("Recording — 1 track armed — streaming to audio/takes/" + takeDirectory.getFileName());
        assertThat(onFx(recIndicator::isVisible)).isTrue();
        assertThat(announced).as("Started is announced once, on the actual transition")
                .filteredOn(TransportEvent.Started.class::isInstance).hasSize(1);
        assertThat(entries()).extracting(NotificationEntry::level).contains(NotificationLevel.INFO);
    }

    @Test
    void stopWhileTheTakeIsBeingPreparedCancelsItAndLeavesNoTakeDirectory() throws Exception {
        cancelWhileTheTakeIsBeingPrepared(TransportController::stop);
    }

    @Test
    void recordPressedAgainWhileTheTakeIsBeingPreparedCancelsIt() throws Exception {
        cancelWhileTheTakeIsBeingPrepared(TransportController::toggleRecord);
    }

    /**
     * Holds the take's preparation, cancels it with {@code cancel} on the FX
     * thread, and pins the outcome: no take, the transport STOPPED where it
     * was, nothing announced; the cancelled start counts as a take being
     * written until its capture thread has deleted its files, and then its
     * take directory is gone too. Released, the capture thread sees the
     * cancel, deletes the take's files, fails the take's readiness — which
     * posts the readiness turn — and ends; that turn, run after the cancel's,
     * does nothing for the cancelled start: no toast, and the status bar
     * still says that the take was cancelled.
     */
    private void cancelWhileTheTakeIsBeingPrepared(Consumer<TransportController> cancel) throws Exception {
        hold.arm();
        runOnFx(controller::toggleRecord);
        hold.awaitHolding(Duration.ofSeconds(10));
        Path takeDirectory = onlyTakeDirectory();

        try {
            runOnFx(() -> cancel.accept(controller));

            assertThat(onFx(controller::isPreparingTake)).as("the take is no longer being prepared").isFalse();
            assertThat(onFx(controller::isRecordingInFlight)).as("nothing is in flight").isFalse();
            assertThat(onFx(statusBar::getText)).isEqualTo(TransportController.RECORDING_CANCELLED_MESSAGE);
            assertThat(onFx(controller::isTakeBeingWritten))
                    .as("its capture thread has not terminated: the cancelled start is still being written").isTrue();
            assertThat(takeDirectory).as("its directory is not removed before its capture thread is done")
                    .isDirectory();
        } finally {
            hold.release(); // the capture thread deletes the take's files, fails its readiness and terminates
        }
        awaitTheHeldTakesReadinessTurn();

        assertThat(entries()).as("the cancelled start's readiness turn shows no toast").isEmpty();
        assertThat(onFx(statusBar::getText)).as("and the status bar still says that the take was cancelled")
                .isEqualTo(TransportController.RECORDING_CANCELLED_MESSAGE);
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the cancelled start's files were removed");

        assertThat(takeDirectories()).as("no take directory is left under audio/takes").isEmpty();
        assertThat(onFx(() -> project.getTransport().getState())).isEqualTo(TransportState.STOPPED);
        assertThat(onFx(() -> project.getTransport().getPositionInBeats())).as("no rewind").isEqualTo(4.0);
        assertThat(onFx(engine::getRecordingCallback)).as("capture never began").isNull();
        assertThat(onFx(armed::isRecording)).as("the track is no longer flagged recording").isFalse();
        assertThat(onFx(recIndicator::isVisible)).isFalse();
        assertThat(onFx(() -> List.copyOf(armed.getClips()))).as("no take was recorded").isEmpty();
        assertThat(announced).as("neither Started nor Stopped reached the bus")
                .noneMatch(event -> event instanceof TransportEvent.Started
                        || event instanceof TransportEvent.Stopped);
    }

    @Test
    void stopWhileTheTakeIsBeingPreparedOverAStoppedTransportClosesTheOutputStreamRecordOpened() throws Exception {
        cancelOverAStoppedTransportClosesTheOutputStream(TransportController::stop);
    }

    @Test
    void recordPressedAgainWhileTheTakeIsBeingPreparedClosesTheOutputStreamOnlyOverATransportThatIsNotRolling()
            throws Exception {
        cancelOverAStoppedTransportClosesTheOutputStream(TransportController::toggleRecord);

        // Over a playing transport the same gesture leaves playback, and the stream it runs on, alone.
        HeldExecutor storage = new HeldExecutor();
        runOnFx(() -> controller.setStorageExecutorForTest(storage));
        runOnFx(controller::start);
        assertThat(onFx(() -> project.getTransport().getState())).as("fixture: playing")
                .isEqualTo(TransportState.PLAYING);
        runOnFx(controller::toggleRecord);
        try {
            assertThat(onFx(controller::isPreparingTake)).as("fixture: a take is being prepared over playback")
                    .isTrue();
            awaitOnFx(() -> storage.pending() == 1, "input re-open finished before the held allocation");
            assertThat(onFx(engine::isStreamOpen)).as("fixture: Record opened the stream").isTrue();

            runOnFx(controller::toggleRecord);

            assertThat(onFx(controller::isPreparingTake)).as("the take was cancelled").isFalse();
            assertThat(onFx(() -> project.getTransport().getState())).as("playback goes on")
                    .isEqualTo(TransportState.PLAYING);
            assertThat(onFx(engine::isStreamOpen)).as("the stream playback runs on is left open").isTrue();
        } finally {
            runHeldStorageUntilNothingIsBeingWritten(storage); // the allocation, then the removal of what it allocated
        }
        assertThat(takeDirectories()).as("the directory the held allocation created was removed").isEmpty();
    }

    /**
     * A paused transport is not rolling: Record pressed again over it, while
     * the take is being prepared, closes the output stream Record opened, as
     * over a stopped one, and the transport stays paused. The project holds
     * no instrument insert, so only the transport's state could keep that
     * stream open.
     */
    @Test
    void recordPressedAgainWhileTheTakeIsBeingPreparedOverAPausedTransportClosesTheOutputStream() throws Exception {
        assertThatNoGraphInstrumentKeepsTheStreamOpenWhileStopped();
        HeldExecutor storage = new HeldExecutor();
        runOnFx(() -> controller.setStorageExecutorForTest(storage));
        runOnFx(controller::start);
        runOnFx(controller::pause);
        assertThat(onFx(() -> project.getTransport().getState())).as("fixture: paused")
                .isEqualTo(TransportState.PAUSED);
        runOnFx(controller::toggleRecord);
        try {
            assertThat(onFx(controller::isPreparingTake)).as("fixture: a take is being prepared over the pause")
                    .isTrue();
            awaitOnFx(() -> storage.pending() == 1, "input re-open finished before the held allocation");
            assertThat(onFx(engine::isStreamOpen)).as("fixture: Record opened the stream").isTrue();

            runOnFx(controller::toggleRecord);

            assertThat(onFx(controller::isPreparingTake)).as("the take was cancelled").isFalse();
            awaitOnFx(() -> !engine.isStreamOpen(), "the cancel's worker closed the output stream");
            assertThat(onFx(engine::isStreamOpen)).as("the cancel closed the output stream Record opened").isFalse();
            assertThat(onFx(() -> project.getTransport().getState())).as("the transport stays paused")
                    .isEqualTo(TransportState.PAUSED);
        } finally {
            runHeldStorageUntilNothingIsBeingWritten(storage); // the allocation, then the removal of what it allocated
        }
        assertThat(takeDirectories()).as("the directory the held allocation created was removed").isEmpty();
        assertThat(onFx(engine::isStreamOpen)).as("nothing opened it again").isFalse();
    }

    /**
     * Holds the take's preparation over a STOPPED transport, in a project
     * whose mixer holds no instrument insert, cancels it with {@code cancel}
     * on the FX thread, and pins that the cancel closed the output stream
     * Record opened asynchronously while the capture thread is still held.
     */
    private void cancelOverAStoppedTransportClosesTheOutputStream(Consumer<TransportController> cancel)
            throws Exception {
        assertThatNoGraphInstrumentKeepsTheStreamOpenWhileStopped();
        hold.arm();
        runOnFx(controller::toggleRecord);
        hold.awaitHolding(Duration.ofSeconds(10));

        try {
            assertThat(onFx(engine::isStreamOpen)).as("fixture: Record opened the output stream").isTrue();
            assertThat(onFx(() -> project.getTransport().getState())).as("fixture: over a stopped transport")
                    .isEqualTo(TransportState.STOPPED);

            runOnFx(() -> cancel.accept(controller));

            assertThat(onFx(controller::isPreparingTake)).as("the take was cancelled").isFalse();
            awaitOnFx(() -> !engine.isStreamOpen(), "the cancel's worker closed the output stream");
            assertThat(onFx(engine::isStreamOpen)).as("the cancel closed the output stream Record opened").isFalse();
        } finally {
            hold.release();
        }
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the cancelled start's files were removed");
        assertThat(onFx(engine::isStreamOpen)).as("nothing opened it again").isFalse();
        assertThat(onFx(() -> project.getTransport().getState())).isEqualTo(TransportState.STOPPED);
    }

    @Test
    void aProjectDoorIsRefusedWhileTheTakeIsBeingPrepared() throws Exception {
        NotificationHistoryService lifecycleShown = new NotificationHistoryService();
        AtomicReference<ProjectLifecycleController> lifecycle = new AtomicReference<>();
        runOnFx(() -> {
            NotificationBar lifecycleToasts = new NotificationBar();
            lifecycleToasts.setAnimated(false);
            lifecycleToasts.setHistoryService(lifecycleShown);
            AtomicReference<UndoManager> undoManager = new AtomicReference<>(new UndoManager());
            ProjectLifecycleController doors = new ProjectLifecycleController(projectManager,
                    new SessionInterchangeController(), lifecycleToasts,
                    new ProjectOperationProgress(new FxDispatcher()), new BorderPane(), new VBox(),
                    new ProjectLifecycleController.Deps(() -> project, _ -> { }, undoManager::get,
                            undoManager::set, () -> { }, () -> { }, () -> { }, () -> null, _ -> { }),
                    new ProjectArchiver());
            doors.setTakeBeingWrittenCheck(controller::isTakeBeingWritten);
            doors.setRecordingInFlightCheck(controller::isRecordingInFlight);
            lifecycle.set(doors);
        });
        hold.arm();
        runOnFx(controller::toggleRecord);
        hold.awaitHolding(Duration.ofSeconds(10));

        try {
            assertThat(onFx(controller::isPreparingTake)).as("fixture: the take is being prepared").isTrue();
            assertThat(onFx(lifecycle.get()::confirmProjectMayClose)).as("the door is refused").isFalse();
        } finally {
            hold.release();
        }

        assertThat(lifecycleShown.getEntries()).as("one WARNING toast, no prompt").singleElement()
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    assertThat(entry.message())
                            .isEqualTo(ProjectLifecycleController.PROJECT_CHANGE_WHILE_RECORDING_MESSAGE);
                });
    }

    @Test
    void retireWhileTheTakeIsBeingPreparedCancelsIt() throws Exception {
        hold.arm();
        runOnFx(controller::toggleRecord);
        hold.awaitHolding(Duration.ofSeconds(10));
        String statusBeforeRetiring = onFx(statusBar::getText);
        int shownBeforeRetiring = entries().size();
        assertThat(statusBeforeRetiring).as("fixture: the take is being prepared")
                .isEqualTo(TransportController.TAKE_PREPARING_MESSAGE);

        try {
            runOnFx(controller::retire);

            assertThat(onFx(controller::isPreparingTake)).as("retiring cancels the take being prepared").isFalse();
            assertThat(onFx(controller::isRecordingInFlight)).isFalse();
            assertThat(onFx(controller::isTakeBeingWritten)).as("until its capture thread has deleted its files")
                    .isTrue();
        } finally {
            hold.release(); // the capture thread deletes the take's files, fails its readiness and terminates
        }
        awaitTheHeldTakesReadinessTurn();

        assertThat(entries()).as("neither retiring nor the readiness turn after it shows a toast")
                .hasSize(shownBeforeRetiring);
        assertThat(onFx(statusBar::getText)).as("nor writes the status bar").isEqualTo(statusBeforeRetiring);
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the cancelled start's files were removed");

        assertThat(takeDirectories()).as("no take directory is left under audio/takes").isEmpty();
        assertThat(onFx(() -> project.getTransport().getState())).isEqualTo(TransportState.STOPPED);
        assertThat(onFx(engine::getRecordingCallback)).as("capture never began").isNull();
        assertThat(announced).as("nothing was announced")
                .noneMatch(event -> event instanceof TransportEvent.Started
                        || event instanceof TransportEvent.Stopped);
    }

    /**
     * The other interleaving of a cancel and the readiness turn: the take's
     * readiness has completed normally — its manifest exists and its capture
     * thread drains — but the readiness turn it posted has not run yet when
     * Stop cancels the take. That turn, run after the Stop's, begins no
     * capture: a readiness that completed normally is never proof that
     * capture is still wanted.
     */
    @Test
    void aStopAfterTheTakeBecameReadyButBeforeItsReadinessTurnRanBeginsNoCapture() throws Exception {
        hold.arm();
        runOnFx(controller::toggleRecord);
        hold.awaitHolding(Duration.ofSeconds(10));
        Thread captureThread = hold.heldThread();
        Path takeDirectory = onlyTakeDirectory();
        Path manifest = TakeManifest.manifestPath(takeDirectory);

        try {
            // One FX turn: the capture thread is released; it writes the take's
            // manifest, completes the take's readiness normally — which posts the
            // readiness turn, to run after this one — and parks in its drain loop.
            // Only then is Stop pressed, inside this same turn.
            runOnFx(() -> {
                hold.release();
                awaitOnThisThread(() -> Files.isRegularFile(manifest) && drainsItsTake(captureThread), READY_WAIT,
                        "fixture: the take became ready — its manifest exists and its capture thread drains");
                controller.stop();
            }, READY_TURN_BUDGET);
        } finally {
            hold.release();
        }
        awaitTheHeldTakesReadinessTurn();

        assertThat(entries()).as("the readiness turn found the take cancelled: no ERROR, no toast at all").isEmpty();
        assertThat(onFx(statusBar::getText)).as("the status bar still says that the take was cancelled")
                .isEqualTo(TransportController.RECORDING_CANCELLED_MESSAGE);
        assertThat(onFx(engine::getRecordingCallback)).as("capture never began").isNull();
        assertThat(onFx(() -> project.getTransport().getState())).isEqualTo(TransportState.STOPPED);
        assertThat(onFx(controller::isRecordingInFlight)).isFalse();
        assertThat(announced).as("Started was never announced").noneMatch(TransportEvent.Started.class::isInstance);
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the cancelled take's files were removed");
        assertThat(takeDirectories()).as("its take directory was removed").isEmpty();
        assertThat(onFx(armed::isRecording)).isFalse();
        assertThat(onFx(recIndicator::isVisible)).isFalse();
    }

    @Test
    void aStartCancelledWhileItsTakeDirectoryIsBeingAllocatedRemovesTheDirectoryOnceItIsAllocated()
            throws Exception {
        HeldExecutor storage = new HeldExecutor();
        runOnFx(() -> controller.setStorageExecutorForTest(storage));

        runOnFx(controller::toggleRecord);
        awaitOnFx(() -> storage.pending() == 1, "the input worker handed allocation to the held storage executor");
        assertThat(storage.pending()).as("the allocation was handed to the storage executor, and is held")
                .isEqualTo(1);
        assertThat(onFx(controller::isPreparingTake)).isTrue();
        assertThat(TakeDirectories.takesDirectory(ProjectManager.audioDirectory(projectDirectory)))
                .as("the FX thread allocated nothing").doesNotExist();

        runOnFx(controller::stop);
        assertThat(onFx(controller::isPreparingTake)).isFalse();
        assertThat(onFx(controller::isTakeBeingWritten)).as("the allocation it started is still owed").isTrue();
        runOnFx(controller::toggleRecord);
        assertThat(onFx(statusBar::getText)).as("Record is refused while the cancelled start is still owed")
                .isEqualTo(TransportController.RECORD_WHILE_WRITING_MESSAGE);
        assertThat(onFx(controller::isPreparingTake)).isFalse();
        assertThat(storage.pending()).as("the refused Record handed nothing more to the storage executor")
                .isEqualTo(1);

        long deadline = System.nanoTime() + TAKE_BUDGET_NANOS;
        while (onFx(controller::isTakeBeingWritten)) {
            assertThat(System.nanoTime() - deadline < 0).as("the cancelled start was cleaned up, within 30 s")
                    .isTrue();
            storage.runPendingOffTheFxThread(); // the allocation, then the removal of what it allocated
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }

        assertThat(storage.ran()).as("the allocation and the removal ran on the storage executor").isEqualTo(2);
        assertThat(takeDirectories()).as("the directory the allocation created was removed").isEmpty();
        assertThat(onFx(() -> project.getTransport().getState())).isEqualTo(TransportState.STOPPED);
        assertThat(announced).noneMatch(event -> event instanceof TransportEvent.Started
                || event instanceof TransportEvent.Stopped);
    }

    @Test
    void aTakeFolderThatCannotBeCreatedIsReportedAndNothingStarts() throws Exception {
        assertThatNoGraphInstrumentKeepsTheStreamOpenWhileStopped();
        makeTheTakesFolderImpossibleToCreate();

        assertThat(recordOpensTheOutputStream()).as("fixture: Record opened the output stream before it allocated")
                .isTrue();
        awaitOnFx(() -> !controller.isPreparingTake() && !controller.isTakeBeingWritten(), "the start and input cleanup settled");

        String message = onFx(statusBar::getText);
        assertThat(message).startsWith("Recording aborted — no take was started: Take-directory precondition failed:");
        assertThat(entries()).as("one ERROR").singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).isEqualTo(message);
        });
        assertThat(onFx(statusBar::getText)).isEqualTo(message);
        assertThat(onFx(() -> project.getTransport().getState())).isEqualTo(TransportState.STOPPED);
        assertThat(onFx(engine::isStreamOpen)).as("the failed allocation closed the output stream Record opened")
                .isFalse();
        assertThat(onFx(controller::isTakeBeingWritten)).as("nothing was allocated, nothing is owed").isFalse();
        assertThat(onFx(recIndicator::isVisible)).isFalse();
        assertThat(announced).noneMatch(TransportEvent.Started.class::isInstance);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void failedPreparationClosesOffFxAndKeepsOwnershipUntilARetainedHandleIsReleased(int failureStage) throws Exception {
        HeldCloseBackend backend = new HeldCloseBackend();
        backend.failOpen = failureStage == 4;
        runOnFx(() -> {
            engine.setStreamingProvision(new StreamingProvision(backend.name(),
                    List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name())))));
            if (failureStage == 1) controller.setStorageExecutorForTest(_ -> {
                throw new java.util.concurrent.RejectedExecutionException("injected storage rejection");
            });
            if (failureStage == 2) controller.setPipelineSetupForTest(_ -> {
                throw new IllegalStateException("injected pipeline failure");
            });
            if (failureStage == 3) controller.setEarlySealSignalForTest(_ ->
                    java.util.concurrent.CompletableFuture.completedStage(
                            new com.benesquivelmusic.daw.core.recording.EarlySeal.DiskExhausted(1024, false, true)));
        });
        if (failureStage == 0) makeTheTakesFolderImpossibleToCreate();
        try {
            runOnFx(controller::toggleRecord);
            assertThat(backend.closeEntered.await(10, TimeUnit.SECONDS)).isTrue();
            runOnFx(() -> {
                assertThat(controller.recordCoordinator().getState()).isEqualTo(RecordState.ABORTED);
                assertThat(controller.recordCoordinator().recordAvailableProperty().get()).isFalse();
                assertThat(controller.isTakeBeingWritten()).isTrue();
                controller.stop();
                assertThat(controller.isTakeBeingWritten()).isTrue();
            });
            assertThat(backend.closeOnFx.get()).isFalse();
            backend.release.countDown();
            awaitOnFx(() -> entries().stream().anyMatch(entry -> entry.message().startsWith("Recording input cleanup failed:")),
                    "the retained handle was reported without settling");
            runOnFx(() -> {
                assertThat(controller.isTakeBeingWritten()).isTrue();
                assertThat(controller.recordCoordinator().getState()).isEqualTo(RecordState.ABORTED);
                assertThat(backend.isOpen()).isTrue();
                assertThat(backend.isReleasePending()).isTrue();
                backend.retain = false;
                controller.stop();
            });
            awaitOnFx(() -> !controller.isTakeBeingWritten(), "retry Stop released the owned stream");
            assertThat(onFx(() -> controller.recordCoordinator().getState())).isEqualTo(RecordState.IDLE);
            assertThat(onFx(engine::isStreamOpen)).isFalse();
            assertThat(backend.isOpen()).isFalse();
            assertThat(onFx(() -> controller.recordCoordinator().recordAvailableProperty().get())).isTrue();
            assertThat(backend.closeOnFx.get()).isFalse();
        } finally {
            backend.retain = false;
            backend.release.countDown();
            runOnFx(() -> { if (controller.isTakeBeingWritten()) controller.stop(); });
        }
    }

    /**
     * A failed allocation over playback must satisfy the Record guard contract:
     * STOPPED with its stream closed, just as a failed attempt from idle.
     */
    @Test
    void aTakeFolderThatCannotBeCreatedDuringPlaybackStopsPlaybackAndClosesItsStream() throws Exception {
        assertThatNoGraphInstrumentKeepsTheStreamOpenWhileStopped();
        makeTheTakesFolderImpossibleToCreate();
        runOnFx(controller::start);
        assertThat(onFx(() -> project.getTransport().getState())).as("fixture: playing")
                .isEqualTo(TransportState.PLAYING);

        assertThat(recordOpensTheOutputStream()).as("fixture: the stream is open at the end of the Record turn")
                .isTrue();
        awaitOnFx(() -> !controller.isPreparingTake() && !controller.isTakeBeingWritten(), "the start and input cleanup settled");

        String message = onFx(statusBar::getText);
        assertThat(message).startsWith("Recording aborted — no take was started: Take-directory precondition failed:");
        assertThat(entries()).as("one ERROR").singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).isEqualTo(message);
        });
        assertThat(onFx(() -> project.getTransport().getState())).as("failed record preconditions stop playback")
                .isEqualTo(TransportState.STOPPED);
        assertThat(onFx(engine::isStreamOpen)).as("the failed allocation closes its stream")
                .isFalse();
        assertThat(onFx(controller::isTakeBeingWritten)).as("nothing was allocated, nothing is owed").isFalse();
        assertThat(onFx(recIndicator::isVisible)).isFalse();
    }

    /**
     * PR #978 review 5391920205 (F2), the production half of the storage
     * executor: every test that holds the storage work replaces the
     * executor, so this one pins the default. A failed allocation's
     * exception carries the stack of the thread that allocated — it was
     * thrown inside {@code TakeDirectories.allocate} — and that stack begins
     * in a virtual thread and holds no {@code onRecord} or
     * {@code toggleRecord} frame (the supplier lambda {@code onRecord}
     * created runs there, on the virtual thread).
     */
    @Test
    void theProductionStorageExecutorAllocatesTheTakeDirectoryOnAVirtualThreadOutsideTheRecordHandler()
            throws Exception {
        makeTheTakesFolderImpossibleToCreate();
        List<Throwable> loggedFailures = new CopyOnWriteArrayList<>();
        Handler severe = new Handler() {
            @Override
            public void publish(LogRecord logged) {
                if (logged.getLevel() == Level.SEVERE && logged.getThrown() != null) {
                    loggedFailures.add(logged.getThrown());
                }
            }

            @Override
            public void flush() {
                // Nothing is buffered.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        Logger log = Logger.getLogger(RecordCoordinator.class.getName());
        log.addHandler(severe);
        try {
            runOnFx(controller::toggleRecord);
            awaitOnFx(() -> !controller.isPreparingTake(), "the start settled");
        } finally {
            log.removeHandler(severe);
        }

        assertThat(loggedFailures).as("fixture: the failed allocation was logged SEVERE with its failure")
                .hasSize(1);
        List<StackTraceElement> frames = List.of(loggedFailures.getFirst().getStackTrace());
        assertThat(frames).as("fixture: the failure was thrown inside TakeDirectories.allocate")
                .anyMatch(frame -> frame.getClassName().equals(TakeDirectories.class.getName())
                        && frame.getMethodName().equals("allocate"));
        assertThat(frames).as("the allocation ran on a virtual thread: its stack begins in VirtualThread.run")
                .last().satisfies(frame -> {
                    assertThat(frame.getClassName()).isEqualTo("java.lang.VirtualThread");
                    assertThat(frame.getMethodName()).isEqualTo("run");
                });
        assertThat(frames).as("and never inside the Record handler, on the FX thread")
                .noneMatch(frame -> (frame.getClassName().equals(TransportController.class.getName())
                            || frame.getClassName().equals(RecordCoordinator.class.getName()))
                        && (frame.getMethodName().equals("onRecord") || frame.getMethodName().equals("toggleRecord")));
    }

    @Test
    void aTakeWhoseFilesCannotBeCreatedIsAbortedAndItsDirectoryRemovedOnceItsCaptureThreadIsDone()
            throws Exception {
        // The take directory the allocation created is replaced by a file
        // before the pipeline is prepared, so the capture thread cannot create
        // the take's files and its readiness fails.
        runOnFx(() -> controller.setPipelineSetupForTest(pipeline -> {
            Path takeDirectory = pipeline.getTakeDirectory();
            try {
                Files.delete(takeDirectory);
                Files.writeString(takeDirectory, "not a directory", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }));

        runOnFx(controller::toggleRecord);
        awaitOnFx(() -> !controller.isPreparingTake(), "the start settled");
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the failed start's files were removed");

        assertThat(entries()).as("one ERROR").singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).startsWith("Recording aborted — no take was started: cannot start capture");
        });
        assertThat(onFx(statusBar::getText)).isEqualTo(entries().getFirst().message());
        assertThat(takeDirectories()).as("nothing of the take is left under audio/takes").isEmpty();
        assertThat(onFx(() -> project.getTransport().getState())).isEqualTo(TransportState.STOPPED);
        assertThat(onFx(engine::getRecordingCallback)).isNull();
        assertThat(onFx(armed::isRecording)).isFalse();
        assertThat(onFx(recIndicator::isVisible)).isFalse();
        assertThat(announced).noneMatch(TransportEvent.Started.class::isInstance);
    }

    @Test
    void aTakeWhosePipelineCannotBeBuiltIsAbortedAndLeavesNothingBeingPrepared() throws Exception {
        assertThatNoGraphInstrumentKeepsTheStreamOpenWhileStopped();
        runOnFx(() -> controller.setPipelineSetupForTest(_ -> {
            throw new IllegalStateException("injected pipeline set-up failure");
        }));

        assertThat(recordOpensTheOutputStream()).as("fixture: Record opened the output stream before it allocated")
                .isTrue();
        awaitOnFx(() -> !controller.isPreparingTake(), "the start settled");
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the allocated take directory was removed");

        assertThat(entries()).as("one ERROR").singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message())
                    .isEqualTo("Recording aborted — no take was started: injected pipeline set-up failure");
        });
        assertThat(takeDirectories()).as("the allocated take directory was removed").isEmpty();
        assertThat(onFx(() -> project.getTransport().getState())).isEqualTo(TransportState.STOPPED);
        assertThat(onFx(engine::isStreamOpen)).as("the failed build closed the output stream Record opened")
                .isFalse();
        assertThat(onFx(armed::isRecording)).isFalse();

        runOnFx(() -> controller.setPipelineSetupForTest(_ -> { }));
        runOnFx(controller::toggleRecord);
        awaitOnFx(() -> !controller.isPreparingTake(), "the next start settled");
        assertThat(onFx(() -> project.getTransport().getState())).as("Record is available again")
                .isEqualTo(TransportState.RECORDING);
    }

    /**
     * A failed pipeline build over playback stops the transport and closes the
     * attempt's stream after the allocated directory has been cleaned up.
     */
    @Test
    void aTakeWhosePipelineCannotBeBuiltDuringPlaybackStopsPlaybackAndClosesItsStream() throws Exception {
        assertThatNoGraphInstrumentKeepsTheStreamOpenWhileStopped();
        runOnFx(() -> controller.setPipelineSetupForTest(_ -> {
            throw new IllegalStateException("injected pipeline set-up failure");
        }));
        runOnFx(controller::start);
        assertThat(onFx(() -> project.getTransport().getState())).as("fixture: playing")
                .isEqualTo(TransportState.PLAYING);

        runOnFx(controller::toggleRecord);
        awaitOnFx(() -> !controller.isPreparingTake(), "the start settled");
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the allocated take directory was removed");

        assertThat(entries()).as("one ERROR").singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message())
                    .isEqualTo("Recording aborted — no take was started: injected pipeline set-up failure");
        });
        assertThat(takeDirectories()).as("the allocated take directory was removed").isEmpty();
        assertThat(onFx(() -> project.getTransport().getState())).as("failed record preconditions stop playback")
                .isEqualTo(TransportState.STOPPED);
        assertThat(onFx(engine::isStreamOpen)).as("the failed build closes its stream after cleanup")
                .isFalse();
        assertThat(onFx(armed::isRecording)).isFalse();
    }

    /**
     * A {@code beginCapture()} that throws is rolled back and the start
     * abandoned on the readiness turn; its pipeline is no longer preparing,
     * so the take's files are followed through the pipeline's
     * {@code termination()}, and its take directory is removed only once its
     * capture thread — which deletes the take's files — has terminated. The
     * storage work is held here, so the test sees that nothing removes the
     * directory, and that the start still counts as being written, while
     * that thread is held in a pass with the take's files on disk.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTakeWhoseCaptureCannotBeginIsAbortedAndItsDirectoryRemovedOnlyOnceItsCaptureThreadIsDone(boolean failStop)
            throws Exception {
        HeldExecutor storage = new HeldExecutor();
        runOnFx(() -> controller.setStorageExecutorForTest(storage));
        Transport transport = project.getTransport();
        Track midi = new Track("Keys", com.benesquivelmusic.daw.core.track.TrackType.MIDI);
        midi.setArmed(true); midi.setMidiInputDeviceName("keys"); project.addTrack(midi);
        var midiInput = new RecordingInFlightFixture.StubMidiInput();
        runOnFx(() -> controller.setMidiInputDeviceResolverForTest(_ -> midiInput));
        AtomicBoolean failedOnce = new AtomicBoolean();
        AtomicBoolean heldInAPassWhenItFailed = new AtomicBoolean();
        IllegalStateException original = new IllegalStateException("injected failure of the transport's record()");
        IllegalStateException stopFailure = new IllegalStateException("injected failure of the transport's stop()");
        List<RecordState> states = new CopyOnWriteArrayList<>();
        runOnFx(() -> controller.recordCoordinator().stateProperty().addListener((_, _, next) -> states.add(next)));
        // beginCapture()'s last step is the transport's record(), after the recording
        // callback is installed and the engine started. This listener fails it, once,
        // on the FX thread — but first it holds the capture thread at its next clock
        // read, in its drain loop (normally the disk-headroom check of a block it
        // applies, or a force-cadence check after one; see CaptureThreadHold), so that
        // thread is still in a pass, the take's files still on disk, when the start is
        // abandoned.
        Runnable removeListener = transport.addChangeListener(kind -> {
            if (kind == Transport.ChangeKind.STATE && transport.getState() == TransportState.RECORDING
                    && failedOnce.compareAndSet(false, true)) {
                hold.arm();
                try {
                    heldInAPassWhenItFailed.set(hold.holdsWithin(HOLD_IN_A_PASS_WAIT));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw original;
            }
            if (failStop && kind == Transport.ChangeKind.STATE && transport.getState() == TransportState.STOPPED) {
                throw stopFailure;
            }
        });
        try {
            runOnFx(controller::toggleRecord);
            awaitOnFx(() -> storage.pending() == 1, "the input worker handed allocation to the held storage executor");
            storage.runPendingOffTheFxThread(); // the allocation; the turns it leads to prepare the take and begin capture
            awaitOnFx(() -> !controller.isPreparingTake(), "the start settled");
            assertThat(heldInAPassWhenItFailed).as("fixture: the capture thread was held in a pass when record() failed")
                    .isTrue();
            Path takeDirectory = onlyTakeDirectory();

            try {
                assertThat(entries()).as("one ERROR").singleElement().satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                    assertThat(entry.message()).isEqualTo(
                            "Recording aborted — no take was started: injected failure of the transport's record()");
                });
                assertThat(onFx(controller::isTakeBeingWritten)).as("the failed start is being written").isTrue();
                assertThat(onFx(() -> controller.recordCoordinator().getState())).isEqualTo(RecordState.ABORTED);
                assertThat(onFx(() -> controller.recordCoordinator().recordAvailableProperty().get())).isFalse();

                assertThat(midiInput.isConnected()).as("last-started MIDI has been drained before flush cleanup").isFalse();
                assertThat(midiInput.isOpen()).isFalse();
                assertThat(onFx(midi::isRecording)).isFalse();
                assertThat(onFx(engine::isStreamOpen)).as("the first-started stream stays open until flush termination and directory cleanup").isTrue();
                runOnFx(controller::stop);
                assertThat(onFx(engine::isStreamOpen)).as("Stop defers stream cleanup while the failed take's flush is held").isTrue();
                assertThat(onFx(controller::isTakeBeingWritten)).isTrue();
                storage.runPendingOffTheFxThread(); // whatever storage work is due while the capture thread runs
                runOnFx(() -> { });                 // and whatever FX turn that work posted

                assertThat(onFx(controller::isTakeBeingWritten))
                        .as("the failed start still counts as being written while its capture thread runs").isTrue();
                assertThat(takeDirectory).as("its directory is not removed before its capture thread is done")
                        .isDirectory();
            } finally {
                hold.release(); // the capture thread deletes the take's files and terminates
            }
            hold.awaitTheHeldThreadEnded(Duration.ofSeconds(30));
            assertThat(onFx(controller::isTakeBeingWritten)).as("and until its directory has been removed too")
                    .isTrue();
            assertThat(onFx(engine::isStreamOpen)).as("terminated flush still owes directory cleanup before stream rollback").isTrue();
            assertThat(takeDirectory).isDirectory();
            runHeldStorageUntilNothingIsBeingWritten(storage); // the removal of the take directory
        } finally {
            hold.release();
            removeListener.run();
        }

        assertThat(takeDirectories()).as("nothing of the take is left under audio/takes").isEmpty();
        assertThat(onFx(engine::isStreamOpen)).as("the stream closes last, after MIDI, flush and directory cleanup").isFalse();
        assertThat(onFx(transport::getState)).as("the rollback stopped the transport")
                .isEqualTo(TransportState.STOPPED);
        assertThat(onFx(engine::getRecordingCallback)).as("the rollback removed the recording callback").isNull();
        assertThat(onFx(armed::isRecording)).as("the track is no longer flagged recording").isFalse();
        assertThat(onFx(controller::isRecordingInFlight)).isFalse();
        assertThat(onFx(recIndicator::isVisible)).isFalse();
        assertThat(states).containsExactly(RecordState.PREPARING, RecordState.ABORTED, RecordState.IDLE);
        assertThat(onFx(() -> controller.recordCoordinator().recordAvailableProperty().get())).isTrue();
        if (failStop) assertThat(original.getSuppressed()).contains(stopFailure);
        else assertThat(original.getSuppressed()).isEmpty();
        assertThat(entries()).as("still the one ERROR").hasSize(1);
        assertThat(onFx(statusBar::getText)).isEqualTo("Returned to start");
        assertThat(onFx(notificationBar::getCurrentLevel)).isEqualTo(NotificationLevel.ERROR);
        assertThat(onFx(notificationBar::getMessage)).isEqualTo(entries().getFirst().message());
        assertThat(announced).as("no Started for a take that did not begin")
                .noneMatch(TransportEvent.Started.class::isInstance);

        runOnFx(controller::toggleRecord);
        runHeldStorageUntilTheStartHasSettled(storage);
        assertThat(onFx(() -> controller.recordCoordinator().getState())).isEqualTo(RecordState.RECORDING);
        assertThat(midiInput.isConnected()).isTrue();
        runOnFx(controller::stop);
        runHeldStorageUntilTheStartHasSettled(storage);
        assertThat(onFx(() -> controller.recordCoordinator().getState())).isEqualTo(RecordState.IDLE);
    }

    @Test
    void playWhileTheTakeIsBeingPreparedIsANoOp() throws Exception {
        hold.arm();
        runOnFx(controller::toggleRecord);
        hold.awaitHolding(Duration.ofSeconds(10));

        try {
            runOnFx(controller::togglePlayPause);
            runOnFx(controller::start);
            runOnFx(controller::playWithPreRoll);

            assertThat(onFx(() -> project.getTransport().getState())).as("Play does nothing while preparing")
                    .isEqualTo(TransportState.STOPPED);
            assertThat(onFx(controller::isPreparingTake)).isTrue();
            assertThat(onFx(statusBar::getText)).isEqualTo(TransportController.TAKE_PREPARING_MESSAGE);
        } finally {
            hold.release();
        }
        awaitOnFx(() -> !controller.isPreparingTake(), "the take's files were created and capture began");
        assertThat(onFx(() -> project.getTransport().getState())).isEqualTo(TransportState.RECORDING);
    }

    /**
     * Record pressed inside a post-roll's tail, and the tail ends while the
     * take's directory is still being allocated — the allocation is held on
     * the storage executor. The end of a post-roll is the deferred half of a
     * Stop, so it cancels the take as a Stop does: the transport it stops is
     * never put in recording over the stream it closed.
     */
    @Test
    void aPostRollThatEndsWhileTheTakeDirectoryIsBeingAllocatedCancelsTheTake() throws Exception {
        assertThatNoGraphInstrumentKeepsTheStreamOpenWhileStopped();
        HeldExecutor storage = new HeldExecutor();
        runOnFx(() -> controller.setStorageExecutorForTest(storage));
        EventHandler<ActionEvent> postRollEnd = stopIntoAPostRollTheTestEnds();
        List<TransportState> entered = statesTheTransportEntersFromNowOn();
        announced.clear(); // only what follows Record is pinned

        runOnFx(controller::toggleRecord);
        try {
            assertThat(onFx(controller::isPreparingTake)).as("fixture: a take is being prepared inside the tail")
                    .isTrue();
            awaitOnFx(() -> storage.pending() == 1, "the input worker handed allocation to the held storage executor");
            assertThat(storage.pending()).as("fixture: its allocation is held").isEqualTo(1);

            runOnFx(() -> postRollEnd.handle(new ActionEvent()));

            assertThat(onFx(controller::isPreparingTake)).as("the end of the post-roll cancelled the take")
                    .isFalse();
            assertThat(onFx(controller::isTakeBeingWritten)).as("the allocation it started is still owed").isTrue();
        } finally {
            runHeldStorageUntilTheStartHasSettled(storage); // the allocation, then the removal of what it allocated
        }

        assertThat(storage.ran()).as("the allocation and the removal of what it allocated ran").isEqualTo(2);
        assertThatThePostRollEndedWithNoTake(entered);
    }

    /**
     * Record pressed inside a post-roll's tail, and the tail ends while the
     * take's capture thread is held creating the take's files. The take is
     * cancelled as by a Stop: once that thread is released, the readiness
     * turn it posts begins no capture.
     */
    @Test
    void aPostRollThatEndsWhileTheTakesFilesAreBeingCreatedCancelsTheTake() throws Exception {
        assertThatNoGraphInstrumentKeepsTheStreamOpenWhileStopped();
        EventHandler<ActionEvent> postRollEnd = stopIntoAPostRollTheTestEnds();
        List<TransportState> entered = statesTheTransportEntersFromNowOn();
        announced.clear(); // only what follows Record is pinned
        hold.arm();
        runOnFx(controller::toggleRecord);
        hold.awaitHolding(Duration.ofSeconds(10));
        Path takeDirectory = onlyTakeDirectory();

        try {
            assertThat(onFx(controller::isPreparingTake)).as("fixture: a take is being prepared inside the tail")
                    .isTrue();

            runOnFx(() -> postRollEnd.handle(new ActionEvent()));

            assertThat(takeDirectory).as("its directory is not removed before its capture thread is done")
                    .isDirectory();
        } finally {
            hold.release(); // the capture thread sees the cancel, or, were there none, makes the take ready
        }
        awaitOnFx(() -> !controller.isPreparingTake(), "the start settled");
        assertThat(entered).as("the transport never entered RECORDING").doesNotContain(TransportState.RECORDING);
        awaitTheHeldTakesReadinessTurn();

        assertThatThePostRollEndedWithNoTake(entered);
    }

    /**
     * A post-roll's timer outlives a tail that ended some other way: here
     * Shift+Space ({@code playWithPreRoll}) inside the tail restarts
     * playback, which takes the transport out of its post-roll. Record
     * pressed over that playback is pressed inside no tail, and the old
     * timer firing while the take is being prepared does nothing: the take
     * is not cancelled, playback and its stream go on, and the take begins
     * once its directory is allocated.
     */
    @Test
    void aPostRollTimerWhoseTailAlreadyEndedLeavesALaterTakeBeingPreparedAlone() throws Exception {
        assertThatNoGraphInstrumentKeepsTheStreamOpenWhileStopped();
        HeldExecutor storage = new HeldExecutor();
        runOnFx(() -> controller.setStorageExecutorForTest(storage));
        EventHandler<ActionEvent> staleTimerEnd = stopIntoAPostRollTheTestEnds();
        Transport transport = project.getTransport();
        runOnFx(controller::playWithPreRoll); // Shift+Space inside the tail
        assertThat(onFx(transport::isInPostRoll)).as("fixture: restarting playback ended the tail").isFalse();
        assertThat(onFx(transport::getState)).as("fixture: playing").isEqualTo(TransportState.PLAYING);
        announced.clear(); // only what follows Record is pinned

        runOnFx(controller::toggleRecord);
        try {
            assertThat(onFx(controller::isPreparingTake)).as("fixture: a take is being prepared over playback")
                    .isTrue();
            awaitOnFx(() -> storage.pending() == 1, "the input worker handed allocation to the held storage executor");
            assertThat(storage.pending()).as("fixture: its allocation is held").isEqualTo(1);
            awaitOnFx(() -> storage.pending() == 1, "input re-open finished before the held allocation");
            assertThat(onFx(engine::isStreamOpen)).as("fixture: Record opened the stream").isTrue();

            runOnFx(() -> staleTimerEnd.handle(new ActionEvent()));

            assertThat(onFx(controller::isPreparingTake)).as("the old timer did not cancel the take").isTrue();
            assertThat(onFx(statusBar::getText)).as("the status bar still says that the take is being prepared")
                    .isEqualTo(TransportController.TAKE_PREPARING_MESSAGE);
            assertThat(onFx(engine::isStreamOpen)).as("the stream is left open").isTrue();
            assertThat(onFx(transport::getState)).as("playback goes on").isEqualTo(TransportState.PLAYING);
            assertThat(announced).as("nothing was announced").isEmpty();
        } finally {
            runHeldStorageUntilTheStartHasSettled(storage); // the allocation; then the take is prepared and begins
        }

        assertThat(onFx(transport::getState)).as("the take began").isEqualTo(TransportState.RECORDING);
        assertThat(onFx(engine::isStreamOpen)).as("over an open stream").isTrue();
        assertThat(onFx(engine::getRecordingCallback)).as("the recording callback is installed").isNotNull();
        runOnFx(controller::stop);
        // The read of the take's audio, which comes before its publication, is held too.
        runHeldStorageUntilTheStartHasSettled(storage);
    }

    /**
     * {@code MainController} builds the next project's controller over the
     * same audio engine, status bar and REC indicator, and retires the one it
     * replaces. Retiring a controller whose post-roll is still playing stops
     * and drops that post-roll's timer, and leaves the replaced project's
     * transport as it is; and that timer's handler, run anyway, does
     * nothing: the next controller's take being prepared keeps the shared
     * stream, the shared status bar keeps its text, and that take begins.
     */
    @Test
    void aRetiredControllersPostRollLeavesTheNextControllersTakeAndTheSharedStreamAlone() throws Exception {
        assertThatNoGraphInstrumentKeepsTheStreamOpenWhileStopped();
        Transport replacedTransport = project.getTransport();
        runOnFx(() -> replacedTransport.setPreRollPostRoll(PreRollPostRoll.enabled(0, 2)));
        runOnFx(controller::start);
        AtomicReference<Animation.Status> timerBeforeRetiring = new AtomicReference<>();
        AtomicReference<Animation.Status> timerAfterRetiring = new AtomicReference<>();
        AtomicReference<PauseTransition> keptAfterRetiring = new AtomicReference<>();
        AtomicReference<EventHandler<ActionEvent>> retiredPostRollEnd = new AtomicReference<>();
        // One FX turn, so no clock ends the tail: Stop enters the post-roll and
        // schedules its timer, the project is replaced — the controller retired —
        // and the timer, whatever retiring did to it, is stopped.
        runOnFx(() -> {
            controller.stop();
            PauseTransition postRollTimer = postRollTimerOf(controller);
            if (postRollTimer == null) {
                return; // the fixture assertion below reports it
            }
            timerBeforeRetiring.set(postRollTimer.getStatus());
            retiredPostRollEnd.set(postRollTimer.getOnFinished());
            controller.retire();
            timerAfterRetiring.set(postRollTimer.getStatus());
            keptAfterRetiring.set(postRollTimerOf(controller));
            postRollTimer.stop();
        });
        assertThat(timerBeforeRetiring.get()).as("fixture: Stop scheduled the end of a post-roll")
                .isEqualTo(Animation.Status.RUNNING);
        assertThat(retiredPostRollEnd.get()).as("fixture: the post-roll's end has a handler").isNotNull();
        assertThat(timerAfterRetiring.get()).as("retiring stopped the post-roll's timer")
                .isEqualTo(Animation.Status.STOPPED);
        assertThat(keptAfterRetiring.get()).as("and dropped it").isNull();

        DawProject nextProject = anotherSavedProjectWithAnArmedAudioTrack("Song B");
        TransportController next = controllerReplacingTheRetiredOne(nextProject);
        HeldExecutor storage = new HeldExecutor();
        runOnFx(() -> next.setStorageExecutorForTest(storage));
        try {
            runOnFx(next::toggleRecord);
            try {
                assertThat(onFx(next::isPreparingTake)).as("fixture: the next project's take is being prepared")
                        .isTrue();
                awaitOnFx(() -> storage.pending() == 1, "the input worker handed allocation to the held storage executor");
                assertThat(storage.pending()).as("fixture: its allocation is held").isEqualTo(1);
                assertThat(onFx(engine::isStreamOpen)).as("fixture: its Record opened the shared stream").isTrue();
                assertThat(onFx(statusBar::getText)).as("fixture: the shared status bar")
                        .isEqualTo(TransportController.TAKE_PREPARING_MESSAGE);

                runOnFx(() -> retiredPostRollEnd.get().handle(new ActionEvent()));

                assertThat(onFx(next::isPreparingTake)).as("the next project's take is still being prepared")
                        .isTrue();
                assertThat(onFx(engine::isStreamOpen)).as("the shared stream is left open").isTrue();
                assertThat(onFx(statusBar::getText)).as("the shared status bar is left as it is")
                        .isEqualTo(TransportController.TAKE_PREPARING_MESSAGE);
                assertThat(onFx(replacedTransport::getState)).as("the replaced project's transport is left as it is")
                        .isEqualTo(TransportState.PLAYING);
                assertThat(onFx(replacedTransport::isInPostRoll)).as("in its post-roll").isTrue();
            } finally {
                runHeldStorageUntilTheStartHasSettled(next, storage); // the allocation; then the take begins
            }

            assertThat(onFx(() -> nextProject.getTransport().getState())).as("the next project's take began")
                    .isEqualTo(TransportState.RECORDING);
            assertThat(onFx(engine::isStreamOpen)).as("over the open shared stream").isTrue();
            assertThat(onFx(recIndicator::isVisible)).as("the shared REC indicator is lit").isTrue();
        } finally {
            // @AfterEach ends only `controller`: this one is ended here on every
            // path, and retired even if its take could not be ended in time.
            try {
                runOnFx(() -> {
                    if (next.isRecordingInFlight()) {
                        next.stop();
                    }
                });
                // The read of the take's audio, which comes before its
                // publication, is held in the storage executor too.
                runHeldStorageUntilTheStartHasSettled(next, storage);
                assertThat(onFx(next::isRecordingInFlight)).as("the next project's take was ended").isFalse();
            } finally {
                runOnFx(next::retire);
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Another saved project, created and closed by a manager of its own, with an armed audio track. */
    private DawProject anotherSavedProjectWithAnArmedAudioTrack(String name) throws IOException {
        ProjectManager other = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        Path directory = other.createProject(name, workspace).projectPath();
        other.closeProject();
        DawProject saved = new DawProject(name, new AudioFormat(48000, 2, 16, 256));
        saved.setMetadata(saved.getMetadata().withPath(directory));
        saved.createAudioTrack("Vox").setArmed(true);
        return saved;
    }

    /**
     * The controller of {@code nextProject}, built as {@code MainController}
     * builds the one that replaces a retired controller: over the same audio
     * engine, notification bar, status bar and REC indicator.
     */
    private TransportController controllerReplacingTheRetiredOne(DawProject nextProject) throws Exception {
        AtomicReference<TransportController> next = new AtomicReference<>();
        runOnFx(() -> {
            TransportController replacement = new TransportController(nextProject, engine, new UndoManager(),
                    notificationBar, new Label(), statusBar, recIndicator, new Button(), new Button(),
                    () -> false,
                    () -> GridResolution.QUARTER,
                    () -> CountInMode.OFF,
                    track -> { },
                    () -> true,
                    () -> RoundTripLatency.UNKNOWN,
                    new StubSessionInputSelection());
            replacement.setStillWritingDelayForTest(new ManualFxDelay());
            next.set(replacement);
        });
        return next.get();
    }

    /**
     * Configures a post-roll, starts playback and presses Stop, so the
     * transport plays the tail — PLAYING, in post-roll — and returns the
     * handler Stop installed on the post-roll's {@link PauseTransition}
     * ({@link #postRollTimerOf}), which ends the tail. That transition is
     * stopped in the Stop's own FX turn, so no clock fires it; the test
     * fires it by running that handler on the FX thread.
     */
    private EventHandler<ActionEvent> stopIntoAPostRollTheTestEnds() throws Exception {
        Transport transport = project.getTransport();
        runOnFx(() -> transport.setPreRollPostRoll(PreRollPostRoll.enabled(0, 2)));
        runOnFx(controller::start);
        AtomicReference<EventHandler<ActionEvent>> postRollEnd = new AtomicReference<>();
        runOnFx(() -> {
            controller.stop();
            PauseTransition postRollTimer = postRollTimerOf(controller);
            assertThat(postRollTimer).as("fixture: Stop scheduled the end of a post-roll").isNotNull();
            postRollTimer.stop();
            postRollEnd.set(postRollTimer.getOnFinished());
        });
        assertThat(onFx(transport::getState)).as("fixture: the tail plays").isEqualTo(TransportState.PLAYING);
        assertThat(onFx(transport::isInPostRoll)).as("fixture: in post-roll").isTrue();
        assertThat(postRollEnd.get()).as("fixture: the post-roll's end has a handler").isNotNull();
        return postRollEnd.get();
    }

    /** The controller's post-roll timer, which its Stop schedules: the private {@code postRollTimer}, read reflectively. */
    private static PauseTransition postRollTimerOf(TransportController controller) {
        try {
            Field postRollTimer = TransportController.class.getDeclaredField("postRollTimer");
            postRollTimer.setAccessible(true);
            return (PauseTransition) postRollTimer.get(controller);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("fixture: TransportController keeps its post-roll timer in postRollTimer", e);
        }
    }

    /** Every state the transport enters from now on, recorded by a change listener on the thread that moves it. */
    private List<TransportState> statesTheTransportEntersFromNowOn() throws Exception {
        List<TransportState> entered = new CopyOnWriteArrayList<>();
        Transport transport = project.getTransport();
        runOnFx(() -> transport.addChangeListener(kind -> {
            if (kind == Transport.ChangeKind.STATE) {
                entered.add(transport.getState());
            }
        }));
        return entered;
    }

    /**
     * What the end of a post-roll leaves of a take Record started inside its
     * tail, once that take's start has settled: the playback the tail ended
     * is stopped, the take was cancelled — nothing recorded, announced or
     * shown but the cancel — and its take directory is removed.
     */
    private void assertThatThePostRollEndedWithNoTake(List<TransportState> entered) throws Exception {
        assertThat(entered).as("the transport never entered RECORDING").doesNotContain(TransportState.RECORDING);
        assertThat(onFx(() -> project.getTransport().getState())).as("the end of the post-roll stopped it")
                .isEqualTo(TransportState.STOPPED);
        assertThat(announced).as("Started was never announced").noneMatch(TransportEvent.Started.class::isInstance);
        assertThat(announced).as("the end of the tail announced the Stopped of the playback it ended")
                .filteredOn(TransportEvent.Stopped.class::isInstance).hasSize(1);
        assertThat(entries()).as("no toast: neither an ERROR nor the INFO of a take that began").isEmpty();
        assertThat(onFx(statusBar::getText)).as("the status bar says that the take was cancelled")
                .isEqualTo(TransportController.RECORDING_CANCELLED_MESSAGE);
        assertThat(onFx(recIndicator::isVisible)).as("REC is off").isFalse();
        assertThat(onFx(engine::getRecordingCallback)).as("capture never began").isNull();
        assertThat(onFx(armed::isRecording)).as("no track is left recording").isFalse();
        assertThat(onFx(engine::isStreamOpen)).as("the end of the post-roll closed the output stream").isFalse();
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the cancelled start's files were removed");
        assertThat(takeDirectories()).as("no take directory is left under audio/takes").isEmpty();
        assertThat(onFx(() -> List.copyOf(armed.getClips()))).as("no take was recorded").isEmpty();
    }

    /** The take directories under the project's {@code audio/takes}; none while it does not exist. */
    private List<Path> takeDirectories() throws IOException {
        Path takes = TakeDirectories.takesDirectory(ProjectManager.audioDirectory(projectDirectory));
        if (!Files.isDirectory(takes)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(takes)) {
            return entries.sorted().toList();
        }
    }

    /** The one take directory under {@code audio/takes}. */
    private Path onlyTakeDirectory() throws IOException {
        List<Path> directories = takeDirectories();
        assertThat(directories).as("fixture: one take directory was allocated").hasSize(1);
        return directories.getFirst();
    }

    private List<NotificationEntry> entries() {
        return shown.getEntries();
    }

    /** Asserts, as a fixture, that the project's mixer holds no instrument insert to keep the stream open while stopped. */
    private void assertThatNoGraphInstrumentKeepsTheStreamOpenWhileStopped() throws Exception {
        assertThat(onFx(() -> project.getMixer().getChannels().stream().noneMatch(MixerChannel::hasInstrumentInsert)
                && project.getMixer().getReturnBuses().stream().noneMatch(MixerChannel::hasInstrumentInsert)
                && !project.getMixer().getMasterChannel().hasInstrumentInsert()))
                .as("fixture: no graph instrument keeps the stream open while stopped").isTrue();
    }

    /** Replaces the project's (empty) audio directory with a file, so that {@code audio/takes} cannot be created. */
    private void makeTheTakesFolderImpossibleToCreate() throws IOException {
        Path audio = ProjectManager.audioDirectory(projectDirectory);
        Files.deleteIfExists(audio);
        Files.writeString(audio, "not a directory", StandardCharsets.UTF_8);
    }

    /** Presses Record and observes stream truth at the off-FX storage handoff, before allocation runs. */
    private boolean recordOpensTheOutputStream() throws Exception {
        AtomicReference<Boolean> openBeforeAllocation = new AtomicReference<>();
        runOnFx(() -> {
            controller.setStorageExecutorForTest(task -> {
                openBeforeAllocation.compareAndSet(null, engine.isStreamOpen());
                Thread.ofVirtual().name("test-take-storage").start(task);
            });
            controller.toggleRecord();
        });
        awaitOnFx(() -> openBeforeAllocation.get() != null, "input is open before allocation is dispatched");
        return openBeforeAllocation.get();
    }

    /**
     * Waits, bounded, until the capture thread the hold held has ended — it
     * posts its take's readiness turn before it ends, whether that readiness
     * failed or completed normally — and then runs one FX turn, posted after
     * that readiness turn, so that turn has run when this returns.
     */
    private void awaitTheHeldTakesReadinessTurn() throws Exception {
        hold.awaitTheHeldThreadEnded(Duration.ofSeconds(30));
        runOnFx(() -> { });
    }

    /**
     * Whether {@code captureThread} is parked in its drain loop: that loop
     * parks with the take's {@link CaptureFlushService} as the blocker, and
     * the thread enters it only once the take's readiness has completed
     * normally ({@code CaptureFlushService.runLoop}).
     */
    private static boolean drainsItsTake(Thread captureThread) {
        return LockSupport.getBlocker(captureThread) instanceof CaptureFlushService;
    }

    /** Polls {@code condition} on the calling thread until it holds, at most {@code within}. */
    private static void awaitOnThisThread(BooleanSupplier condition, Duration within, String what) {
        long deadline = System.nanoTime() + within.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline < 0).as("%s, within %d s", what, within.toSeconds()).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    /**
     * Runs what {@code storage} holds, off the FX thread, until no start is
     * still having its files removed — at most 30 s.
     */
    private void runHeldStorageUntilNothingIsBeingWritten(HeldExecutor storage) throws Exception {
        long deadline = System.nanoTime() + TAKE_BUDGET_NANOS;
        while (onFx(controller::isTakeBeingWritten)) {
            assertThat(System.nanoTime() - deadline < 0).as("the abandoned start's files were removed, within 30 s")
                    .isTrue();
            storage.runPendingOffTheFxThread();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    /**
     * Runs what {@code storage} holds, off the FX thread, until no take is
     * being prepared and no start is still having its files removed — at
     * most 30 s.
     */
    private void runHeldStorageUntilTheStartHasSettled(HeldExecutor storage) throws Exception {
        runHeldStorageUntilTheStartHasSettled(controller, storage);
    }

    /**
     * Runs what {@code storage} holds, off the FX thread, until
     * {@code recorder} prepares no take, has no start still having its
     * files removed and no stopped take still to be read back and
     * published — at most 30 s.
     */
    private static void runHeldStorageUntilTheStartHasSettled(TransportController recorder, HeldExecutor storage)
            throws Exception {
        long deadline = System.nanoTime() + TAKE_BUDGET_NANOS;
        while (onFx(() -> recorder.isPreparingTake() || recorder.isTakeBeingWritten())) {
            assertThat(System.nanoTime() - deadline < 0).as("the start settled, within 30 s").isTrue();
            storage.runPendingOffTheFxThread();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    private static <T> T onFx(Supplier<T> read) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        runOnFx(() -> value.set(read.get()));
        return value.get();
    }

    /** Runs {@code action} on the FX thread and rethrows whatever it threw; bounded at 5 s. */
    private static void runOnFx(Runnable action) throws Exception {
        runOnFx(action, Duration.ofSeconds(5));
    }

    /** Runs {@code action} on the FX thread and rethrows whatever it threw; bounded at {@code budget}. */
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

    /** Waits, at most 30 s, polling on the FX thread, until {@code condition} holds there. */
    private static void awaitOnFx(Supplier<Boolean> condition, String what) throws Exception {
        long deadline = System.nanoTime() + TAKE_BUDGET_NANOS;
        while (!onFx(condition)) {
            assertThat(System.nanoTime() - deadline < 0).as("%s, within 30 s", what).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    /**
     * A storage executor that holds every task it is handed until the test
     * runs it — on a thread of its own, never the FX thread — and counts the
     * tasks it ran.
     */
    private static final class HeldExecutor implements Executor {
        private final List<Runnable> held = new CopyOnWriteArrayList<>();
        private volatile int ran;

        @Override
        public void execute(Runnable task) {
            held.add(task);
        }

        int pending() {
            return held.size();
        }

        int ran() {
            return ran;
        }

        /** Runs every task held so far on a new thread, waiting at most 5 s for them. */
        void runPendingOffTheFxThread() throws InterruptedException {
            List<Runnable> due = List.copyOf(held);
            held.removeAll(due);
            if (due.isEmpty()) {
                return;
            }
            Thread runner = Thread.ofPlatform().name("held-storage-executor").start(() -> due.forEach(Runnable::run));
            runner.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(runner.isAlive()).as("fixture: the held storage tasks ran within 5 s").isFalse();
            ran += due.size();
        }
    }

    /** A synchronous bus that hands every event published to {@code sink}; subscriptions are not used. */
    private static final class HeldCloseBackend implements com.benesquivelmusic.daw.sdk.audio.AudioBackend {
        private final MockAudioBackend delegate = new MockAudioBackend();
        final CountDownLatch closeEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean closeOnFx = new AtomicBoolean();
        volatile boolean retain = true;
        boolean failOpen;
        Thread openThread;
        public String name() { return delegate.name(); }
        public boolean isAvailable() { return true; }
        public boolean supportsStreaming() { return true; }
        public List<com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo> listDevices() { return delegate.listDevices(); }
        public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format, int frames) {
            openThread = Thread.currentThread();
            delegate.open(device, format, frames);
            if (failOpen) throw new com.benesquivelmusic.daw.sdk.audio.AudioBackendException("injected partial input open failure");
        }
        public boolean isOpen() { return delegate.isOpen(); }
        public boolean isReleasePending() { return retain && delegate.isOpen(); }
        public int openedInputChannels() { return delegate.openedInputChannels(); }
        public Flow.Publisher<com.benesquivelmusic.daw.sdk.audio.AudioBlock> inputBlocks() { return delegate.inputBlocks(); }
        public void sink(com.benesquivelmusic.daw.sdk.audio.AudioBlock block) { delegate.sink(block); }
        public void close() {
            if (Platform.isFxApplicationThread()) closeOnFx.set(true);
            if (failOpen && Thread.currentThread() == openThread) return;
            closeEntered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("held close timed out");
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            if (!retain) delegate.close();
        }
    }

    private static final class CollectingBus implements EventBus {
        private final Consumer<BusEvent> sink;

        CollectingBus(Consumer<BusEvent> sink) {
            this.sink = sink;
        }

        @Override
        public void publish(BusEvent event) {
            sink.accept(event);
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
            // No resources: publish runs synchronously on the publisher.
        }
    }
}
