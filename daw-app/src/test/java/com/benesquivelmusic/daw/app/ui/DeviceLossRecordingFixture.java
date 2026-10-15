package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real {@link TransportController} recording an audio take into the open
 * project through a real {@link AudioEngine} on a {@link MockAudioBackend},
 * whose device the test then unplugs (story 327, Recording Reliability book
 * §5.5). Two wirings:
 * <ul>
 *   <li>{@link #withEngineBridge}: the production detection path — a real
 *       {@link DefaultAudioEngineController} over the same engine, built
 *       before any stream opens so the engine's stream-open seam binds it
 *       to the device the Record opens, and an {@link EngineStateBridge}
 *       subscribed to its engine-state events exactly as
 *       {@code MainController} subscribes it. {@link #unplug()} then drives
 *       {@link MockAudioBackend#simulateDeviceRemoved} and the rescue
 *       reaches the record coordinator through the controller, the
 *       publisher's thread, the bridge and the FX thread.</li>
 *   <li>{@link #withoutEngineBridge}: the coordinator alone; a test calls
 *       {@link RecordCoordinator#onDeviceLost} (or the controller's
 *       {@code onEngineState}) on the FX thread itself — after
 *       {@link #closeTheStreamAsTheLossDoes()} when it needs the stream
 *       closed as the engine controller leaves it.</li>
 * </ul>
 *
 * <p>The notification bar has a history, so a test reads every toast the
 * transport showed, with its level and action label; the engine
 * controller's own messages go to {@link #engineNotices()}. The
 * still-writing warning's delay is a {@link ManualFxDelay} never fired, so
 * a slow disk cannot slip a WARNING into the sequence a test asserts. Every
 * FX action is bounded at 5 s; the waits on a real take's capture thread
 * at 30 s. {@link #close()} stops a take still in flight, waits until
 * nothing is being written, then shuts the engine and the engine controller
 * down.</p>
 */
final class DeviceLossRecordingFixture implements AutoCloseable {

    private static final Duration FX_TURN_BUDGET = Duration.ofSeconds(5);
    private static final long WAIT_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(10);
    /**
     * The bound of a held FX turn ({@link #holdTheFxThread()}): strictly
     * longer than the sum of the {@link #await} calls a test runs inside the
     * hold — at most two, of {@link #WAIT_BUDGET_NANOS} each — so the hold
     * cannot expire under a premise still being awaited and let the test
     * pass vacuously on a turn that ran early. The inner awaits keep their
     * own budget; only the guard is wider (PR #982 round 2).
     */
    private static final long HOLD_BUDGET_NANOS = 3 * WAIT_BUDGET_NANOS;
    /** The hang guard of a wait for a real take's capture thread: its files created, or the take published. */
    private static final long TAKE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final DawProject project;
    private final Track guitar;
    private final MockAudioBackend backend = new MockAudioBackend();
    private final NotificationHistoryService shown = new NotificationHistoryService();
    private final List<String> engineNotices = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<RecordingPipeline> pipeline = new AtomicReference<>();
    private AudioEngine engine;
    private TransportController controller;
    private NotificationBar notificationBar;
    private Label transportStatus;
    private Label statusBar;
    private Label recIndicator;
    private DefaultAudioEngineController engineController;
    private EngineStateBridge bridge;

    private DeviceLossRecordingFixture(Path projectDirectory, boolean withEngineBridge) throws Exception {
        project = new DawProject("Demo", new AudioFormat(44_100.0, 2, 24, 256));
        project.setMetadata(project.getMetadata().withPath(projectDirectory));
        guitar = project.createAudioTrack("Guitar");
        guitar.setArmed(true);
        StreamingProvision provision = new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, activeDevice())));
        runOnFx(() -> {
            engine = new AudioEngine(project.getFormat());
            if (withEngineBridge) {
                engineController = new DefaultAudioEngineController(engine, null, engineNotices::add);
            }
            engine.setStreamingProvision(provision);
            notificationBar = new NotificationBar();
            notificationBar.setAnimated(false);
            notificationBar.setHistoryService(shown);
            transportStatus = new Label();
            statusBar = new Label();
            recIndicator = new Label();
            controller = new TransportController(project, engine, new UndoManager(), notificationBar,
                    transportStatus, statusBar, recIndicator, new Button(), new Button(),
                    () -> false,
                    () -> GridResolution.QUARTER,
                    () -> CountInMode.OFF,
                    track -> { },
                    () -> true,
                    () -> RoundTripLatency.UNKNOWN);
            controller.setStillWritingDelayForTest(new ManualFxDelay());
            controller.setPipelineSetupForTest(pipeline::set);
            if (withEngineBridge) {
                bridge = new EngineStateBridge(() -> controller, null);
                bridge.subscribeTo(engineController);
            }
        });
    }

    /** The production detection wiring: engine controller plus engine-state bridge. */
    static DeviceLossRecordingFixture withEngineBridge(Path projectDirectory) throws Exception {
        return new DeviceLossRecordingFixture(projectDirectory, true);
    }

    /** The coordinator alone; the test reports the loss itself. */
    static DeviceLossRecordingFixture withoutEngineBridge(Path projectDirectory) throws Exception {
        return new DeviceLossRecordingFixture(projectDirectory, false);
    }

    DawProject project() {
        return project;
    }

    Track guitar() {
        return guitar;
    }

    MockAudioBackend backend() {
        return backend;
    }

    AudioEngine engine() {
        return engine;
    }

    TransportController controller() {
        return controller;
    }

    RecordCoordinator coordinator() {
        return controller.recordCoordinator();
    }

    NotificationBar notificationBar() {
        return notificationBar;
    }

    Label transportStatus() {
        return transportStatus;
    }

    Label statusBar() {
        return statusBar;
    }

    Label recIndicator() {
        return recIndicator;
    }

    /** The engine controller of {@link #withEngineBridge}; absent otherwise. */
    Optional<DefaultAudioEngineController> engineController() {
        return Optional.ofNullable(engineController);
    }

    /** What the engine controller told its notification manager, in order. */
    List<String> engineNotices() {
        return List.copyOf(engineNotices);
    }

    /** The pipeline of the latest audio take Record built; {@code null} before the first. */
    RecordingPipeline pipeline() {
        return pipeline.get();
    }

    /**
     * The device the Record opens: the provision's one rung, which the
     * engine's stream-open seam hands the engine controller as the device
     * to watch.
     */
    DeviceId activeDevice() {
        return DeviceId.defaultFor(backend.name());
    }

    /** The host reporting that {@link #activeDevice()} is gone. Test thread. */
    void unplug() {
        backend.simulateDeviceRemoved(activeDevice());
    }

    /** The host reporting that {@code device} appeared. Test thread. */
    void plugIn(DeviceId device) {
        backend.simulateDeviceArrived(device);
    }

    /**
     * What the engine controller does to the stream before it publishes the
     * loss ({@code DefaultAudioEngineController.onDeviceRemoved}:
     * {@code stopAudioOutput()}, then {@code stop()}, on the device-event
     * thread): for a {@link #withoutEngineBridge} test, which reports the
     * loss itself, so that the coordinator's DEVICE_LOST turn finds the
     * stream closed as it does in production — the transport's
     * {@code finishDeviceLossStop} leaves an OPEN stream alone, reading it
     * as a post-loss reopen. Test thread, before the loss is reported.
     */
    void closeTheStreamAsTheLossDoes() {
        engine.stopAudioOutput();
        engine.stop();
        assertThat(engine.isStreamOpen()).as("fixture: the loss closed the stream").isFalse();
    }

    /**
     * Presses Record, waits, bounded, for the take to record, for the record
     * start's input check and for the take's first segment to hold a block;
     * returns the take directory.
     */
    Path recordUntilABlockIsOnDisk() throws Exception {
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
        Path part = firstSegmentPart(takeDirectory);
        await(() -> sizeOf(part) > 44, "fixture: a block reached " + part);
        assertThat(pipeline.get()).as("fixture: the pipeline setup seam saw the take's pipeline").isNotNull();
        return takeDirectory;
    }

    /** The guitar's first segment while it streams. */
    Path firstSegmentPart(Path takeDirectory) {
        return takeDirectory.resolve(guitar.getId()).resolve("segment-000.wav.part");
    }

    /**
     * Waits, bounded, until the coordinator has entered DEVICE_LOST and the
     * rescued take's publication has ended — the order matters: the
     * publication is pending only from the FX turn that enters DEVICE_LOST.
     */
    void awaitRescued() throws Exception {
        awaitOnFx(() -> coordinator().getState() == RecordState.DEVICE_LOST, "the coordinator entered DEVICE_LOST");
        awaitPublished();
    }

    /** Waits, bounded, until no take is being written or read back any more. */
    void awaitPublished() throws Exception {
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take was published");
    }

    /** The toasts shown since there were {@code before} of them. */
    List<NotificationEntry> entriesSince(int before) {
        List<NotificationEntry> entries = shown.getEntries();
        return entries.subList(before, entries.size());
    }

    int shownCount() {
        return shown.size();
    }

    @Override
    public void close() throws Exception {
        try {
            runOnFx(() -> {
                if (controller.isRecordingInFlight()) {
                    controller.stop();
                }
            });
            awaitPublished();
        } finally {
            if (bridge != null) {
                bridge.cancel();
            }
            engine.stopAudioOutput();
            engine.stop();
            if (engineController != null) {
                engineController.shutdown();
            }
        }
    }

    static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (NoSuchFileException notYet) {
            return -1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Waits, at most 10 s, on the test thread, until {@code condition} holds. */
    static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + WAIT_BUDGET_NANOS;
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline < 0).as("%s, within 10 s", what).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    /** Waits, at most 30 s, polling on the FX thread, until {@code condition} holds there. */
    static void awaitOnFx(Supplier<Boolean> condition, String what) throws Exception {
        long deadline = System.nanoTime() + TAKE_BUDGET_NANOS;
        while (!onFx(condition)) {
            assertThat(System.nanoTime() - deadline < 0).as("%s, within 30 s", what).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    /**
     * A held FX thread: {@link #release()} lets the held turn end, after
     * which every turn posted meanwhile runs in posting order.
     */
    interface FxHold extends AutoCloseable {
        void release();

        @Override
        default void close() {
            release();
        }
    }

    /**
     * Holds the FX thread on its next turn until the hold is released, so a
     * test can order what the device-event thread and the engine-state
     * publisher's executor post behind it — the queued-loss interleaving of
     * story 327's fix round (PR #982 review): the loss's FX turn runs only
     * after the watch has moved. Returns once the hold is in place. The held
     * turn is bounded at {@link #HOLD_BUDGET_NANOS} (30 s) — strictly longer
     * than the two 10 s {@link #await} calls a test may run inside it, so
     * the hold cannot end under a premise still being awaited — and the hold
     * is always released in the test's {@code try}-with-resources; nothing
     * that needs the FX thread —
     * {@link #runOnFx}, {@link #onFx}, {@link #awaitOnFx} — may be called
     * while it is held.
     */
    static FxHold holdTheFxThread() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        Platform.runLater(() -> {
            held.countDown();
            try {
                released.await(HOLD_BUDGET_NANOS, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(held.await(FX_TURN_BUDGET.toMillis(), TimeUnit.MILLISECONDS))
                .as("the FX thread is held within %d s", FX_TURN_BUDGET.toSeconds()).isTrue();
        return released::countDown;
    }

    static <T> T onFx(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        runOnFx(() -> result.set(action.get()));
        return result.get();
    }

    /** Runs {@code action} on the FX thread and rethrows what it threw; bounded at 5 s. */
    static void runOnFx(Runnable action) throws Exception {
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
        assertThat(done.await(FX_TURN_BUDGET.toMillis(), TimeUnit.MILLISECONDS))
                .as("the FX action returned within %d s", FX_TURN_BUDGET.toSeconds()).isTrue();
        if (thrown.get() instanceof Error error) {
            throw error;
        }
        if (thrown.get() != null) {
            throw new AssertionError("the FX action threw", thrown.get());
        }
    }
}
