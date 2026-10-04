package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.CaptureRequirement;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real {@link TransportController} over a real {@link AudioEngine} whose
 * device a test steps by hand: the engine's render pump runs one block for
 * each block the test {@linkplain #feed feeds}, and none on its own while a
 * take records, so a take holds exactly the blocks the test fed it, with the
 * samples the test chose. The engine is built at a format of its own, which
 * may differ from the project's.
 *
 * <p>The controller's storage executor is manual too
 * ({@link ManualStorage}): the allocation of a take directory, the removal
 * of an abandoned one and the read of a stopped take's audio, which comes
 * before its publication, each run
 * when the test runs them, on a thread that is not the FX thread. The
 * controller is handed a dispatcher of this fixture's own, never started,
 * whose pulse the test runs ({@link #pulse()}).</p>
 *
 * <p>Every wait is bounded by {@link #GUARD}. {@link #close()} ends whatever
 * the test left running; call it from {@code @AfterEach}, off the FX
 * thread.</p>
 */
final class SteppedTakeFixture {

    /** The hang guard of every wait of this fixture. */
    static final Duration GUARD = Duration.ofSeconds(30);

    final DawProject project;
    final AudioEngine engine;
    final AudioFormat engineFormat;
    final SteppedBackend backend = new SteppedBackend();
    final FxDispatcher dispatcher = new FxDispatcher();
    final ManualStorage storage = new ManualStorage();
    final List<RecordingPipeline> pipelines = new CopyOnWriteArrayList<>();
    final NotificationHistoryService shown = new NotificationHistoryService();
    /** The controller's still-writing delay, which fires only when a test fires it, on the FX thread. */
    final ManualFxDelay stillWritingDelay = new ManualFxDelay();
    final TransportController controller;
    private final CaptureFlushThreadWatch flushThreads = CaptureFlushThreadWatch.snapshot();
    private NotificationBar notificationBar;
    private Label statusBarLabel;
    private UndoManager undoManager;
    /** Frames fed so far, for {@link #feedRamp}. */
    private long fedFrames;

    /**
     * @param project         a project that has a directory and its armed tracks
     * @param engineFormat    the format the engine streams at
     * @param reportedLatency the driver latency the controller is told
     * @param pipelineSetup   run on each take's pipeline before its {@code prepare()}
     */
    SteppedTakeFixture(DawProject project, AudioFormat engineFormat, RoundTripLatency reportedLatency,
                       Consumer<RecordingPipeline> pipelineSetup) throws Exception {
        this.project = project;
        this.engineFormat = engineFormat;
        AtomicReference<AudioEngine> builtEngine = new AtomicReference<>();
        AtomicReference<TransportController> built = new AtomicReference<>();
        onFx(() -> {
            AudioEngine audioEngine = new AudioEngine(engineFormat);
            audioEngine.setStreamingProvision(new StreamingProvision(backend.name(),
                    List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name())))));
            // As the app binds them: the engine's callback advances the
            // project's transport, so a loop wraps as it does in the app.
            audioEngine.setGraph(project.getTransport(), project.getMixer(), project.getTracks());
            builtEngine.set(audioEngine);
            undoManager = new UndoManager();
            notificationBar = new NotificationBar();
            notificationBar.setAnimated(false);
            notificationBar.setHistoryService(shown);
            statusBarLabel = new Label();
            Label recIndicator = new Label();
            recIndicator.setVisible(false);
            recIndicator.setManaged(false);
            TransportController made = new TransportController(project, audioEngine, undoManager, notificationBar,
                    new Label(), statusBarLabel, recIndicator, new Button(), new Button(),
                    () -> false,
                    () -> GridResolution.QUARTER,
                    () -> CountInMode.OFF,
                    _ -> { },
                    () -> true,
                    () -> reportedLatency,
                    new StubSessionInputSelection(),
                    () -> { },
                    dispatcher);
            made.setStillWritingDelayForTest(stillWritingDelay);
            made.setStorageExecutorForTest(storage);
            made.setPipelineSetupForTest(pipeline -> {
                pipelines.add(pipeline);
                pipelineSetup.accept(pipeline);
            });
            built.set(made);
        });
        this.engine = builtEngine.get();
        this.controller = built.get();
    }

    /** The pipeline of the latest take the controller built. */
    RecordingPipeline pipeline() {
        assertThat(pipelines).as("fixture: a take's pipeline was built").isNotEmpty();
        return pipelines.getLast();
    }

    UndoManager undoManager() {
        return undoManager;
    }

    /** The status bar's text, read on the FX thread. */
    String statusText() throws Exception {
        return get(statusBarLabel::getText);
    }

    /**
     * Presses Record and brings the take to RECORDING: the device is opened
     * and its pump has rendered the one block it renders unasked and is
     * waiting for the test, before the take directory is allocated — so no
     * block reaches the take that the test did not feed.
     */
    void startRecording() throws Exception {
        pressRecord();
        storage.runNext();
        awaitOnFx(() -> !controller.isPreparingTake(), "the take's start settled");
        assertThat(get(() -> controller.activeTakeDirectory().isPresent()))
                .as("fixture: the take is recording").isTrue();
    }

    /**
     * Presses Record and leaves the take PREPARING with the allocation of
     * its take directory held in {@link #storage}; the device's pump is
     * waiting for the test.
     */
    void pressRecord() throws Exception {
        int parkedBefore = backend.parked.get();
        onFx(controller::toggleRecord);
        await(() -> backend.parked.get() > parkedBefore, "the device's pump is waiting for the test");
        storage.awaitQueued(1);
    }

    /**
     * Feeds the engine one block and waits until its pump has rendered it:
     * the recording callback has returned by then.
     *
     * @param planes the block as {@code [channel][frame]}, one buffer of the engine's format
     */
    void feed(float[][] planes) {
        int channels = planes.length;
        int frames = planes[0].length;
        float[] interleaved = new float[channels * frames];
        for (int frame = 0; frame < frames; frame++) {
            for (int channel = 0; channel < channels; channel++) {
                interleaved[frame * channels + channel] = planes[channel][frame];
            }
        }
        int parkedBefore = backend.parked.get();
        backend.deliver(new AudioBlock(engineFormat.sampleRate(), channels, frames, interleaved));
        backend.permits.release();
        await(() -> backend.parked.get() > parkedBefore, "the pump rendered the block it was fed");
    }

    /** Feeds {@code blocks} buffers of the engine's format holding the next frames of {@link #ramp}. */
    void feedRamp(int blocks) {
        for (int block = 0; block < blocks; block++) {
            float[][] planes = new float[engineFormat.channels()][engineFormat.bufferSize()];
            for (int channel = 0; channel < planes.length; channel++) {
                for (int frame = 0; frame < planes[channel].length; frame++) {
                    planes[channel][frame] = ramp(channel, fedFrames + frame) / 32767.0f;
                }
            }
            fedFrames += engineFormat.bufferSize();
            feed(planes);
        }
    }

    /** The 16-bit code of frame {@code frame} of channel {@code channel} of the ramp {@link #feedRamp} feeds. */
    static int ramp(int channel, long frame) {
        return (int) ((frame * 7 + channel * 1000L) % 20001) - 10000;
    }

    /** What a 16-bit segment decodes for frame {@code frame} of channel {@code channel} of the ramp. */
    static float decodedRamp(int channel, long frame) {
        return ramp(channel, frame) / 32768.0f;
    }

    /**
     * Presses Stop and waits until the take's capture thread has terminated
     * and the controller has handed the read of the take's audio to
     * {@link #storage}, where it is held: nothing of the take is published
     * yet. For a take that has audio to read.
     */
    void stopAndHoldTheRead() throws Exception {
        onFx(controller::stop);
        storage.awaitQueued(1);
        onFx(() -> { });
    }

    /**
     * Runs the held read of the stopped take's audio, off the FX thread,
     * and waits until the FX turn it posts has ended the take — published
     * it, or dropped it for a retired controller.
     */
    void runTheHeldRead() throws Exception {
        storage.runNext();
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the turn the read posted ended the take");
    }

    /** Presses Stop, runs the read of the take's audio off the FX thread and waits until the take has been published. */
    void stopAndAwaitThePublication() throws Exception {
        stopAndHoldTheRead();
        runTheHeldRead();
    }

    /** Runs one pulse of the fixture's dispatcher on the FX thread. */
    void pulse() throws Exception {
        onFx(dispatcher::pulse);
    }

    /** The dispatcher's open continuous channels, read on the FX thread. */
    int openChannels() throws Exception {
        return get(dispatcher::openChannelCount);
    }

    /**
     * Ends what the test left running: runs every storage task still held,
     * stops a take in flight and waits until nothing is being written or
     * read back, then retires the controller and stops the engine, which
     * interrupts the pump where it waits for the test.
     */
    void close() throws Exception {
        try {
            storage.release();
            onFx(() -> {
                if (controller.isRecordingInFlight()) {
                    controller.stop();
                }
            });
            awaitOnFx(() -> !controller.isRecordingInFlight() && !controller.isTakeBeingWritten(),
                    "the take the test left running ended");
            storage.joinEveryTask();
            AssertionError leak = flushThreads.joinNewThreads();
            if (leak != null) {
                throw leak;
            }
        } finally {
            onFx(controller::retire);
            engine.stopAudioOutput();
            engine.stop();
        }
    }

    // ── FX and waiting ───────────────────────────────────────────────────────

    /** Runs {@code work} on the FX thread and waits for it; what it throws is rethrown here. */
    static void onFx(Runnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(GUARD.toSeconds(), TimeUnit.SECONDS)).as("the FX turn ran within the guard").isTrue();
        if (thrown.get() instanceof Error error) {
            throw error;
        }
        if (thrown.get() instanceof Exception exception) {
            throw exception;
        }
    }

    /** Reads {@code value} on the FX thread. */
    static <T> T get(Supplier<T> value) throws Exception {
        AtomicReference<T> read = new AtomicReference<>();
        onFx(() -> read.set(value.get()));
        return read.get();
    }

    /** Waits, bounded, polling on the FX thread, until {@code condition} holds there. */
    static void awaitOnFx(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + GUARD.toNanos();
        while (!get(condition::getAsBoolean)) {
            assertThat(System.nanoTime() - deadline < 0).as("%s, within the guard", what).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    /** Waits, bounded, until {@code condition} holds on this thread. */
    static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + GUARD.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline < 0).as("%s, within the guard", what).isTrue();
            LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(200));
        }
    }

    /** The project directory of {@code project}, as {@code TransportController} reads it. */
    static void giveADirectory(DawProject project, Path directory) {
        project.setMetadata(project.getMetadata().withPath(directory));
    }

    // ── The stepped device ───────────────────────────────────────────────────

    /**
     * A streaming backend whose pacing is the test's: the engine's pump
     * renders one block when the stream opens and then one for each permit,
     * waiting in {@link #awaitSinkCapacity} in between; a block handed to
     * {@link #deliver} is in the pump's input queue when the call returns.
     */
    static final class SteppedBackend implements AudioBackend {
        private final MockAudioBackend delegate = new MockAudioBackend();
        final Semaphore permits = new Semaphore(0);
        /** How often the pump has finished a block and come to wait for the next permit. */
        final AtomicInteger parked = new AtomicInteger();
        private volatile Flow.Subscriber<? super AudioBlock> subscriber;

        @Override public String name() { return delegate.name(); }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean supportsStreaming() { return true; }
        @Override public List<AudioDeviceInfo> listDevices() { return delegate.listDevices(); }
        @Override public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                                   int bufferFrames) {
            delegate.open(device, format, bufferFrames);
        }
        @Override public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                                   int bufferFrames, CaptureRequirement capture) {
            delegate.open(device, format, bufferFrames);
        }
        @Override public int openedInputChannels() { return delegate.openedInputChannels(); }
        @Override public void sink(AudioBlock block) { delegate.sink(block); }
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public void close() { delegate.close(); }

        @Override
        public Flow.Publisher<AudioBlock> inputBlocks() {
            return newSubscriber -> {
                subscriber = newSubscriber;
                newSubscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) { }
                    @Override public void cancel() {
                        if (subscriber == newSubscriber) {
                            subscriber = null;
                        }
                    }
                });
            };
        }

        /** Hands {@code block} to the pump's input queue, on the caller's thread. */
        void deliver(AudioBlock block) {
            Flow.Subscriber<? super AudioBlock> current = subscriber;
            assertThat(current).as("fixture: the engine's pump subscribed to the device's input").isNotNull();
            current.onNext(block);
        }

        @Override
        public void awaitSinkCapacity(long timeoutNanos) {
            parked.incrementAndGet();
            try {
                permits.tryAcquire(GUARD.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── The manual storage executor ──────────────────────────────────────────

    /**
     * An {@link Executor} that holds every task until the test runs it
     * ({@link #runNext}), on a new thread that is never the FX thread, and
     * remembers which thread handed each task over.
     */
    static final class ManualStorage implements Executor {
        private final Deque<Runnable> queued = new ArrayDeque<>();
        private final List<Thread> started = new CopyOnWriteArrayList<>();
        /** Whether each task, in the order they were handed over, was handed over on the FX thread. */
        final List<Boolean> handedOverOnFx = new CopyOnWriteArrayList<>();
        /** Whether each task run so far ran on the FX thread. */
        final List<Boolean> ranOnFx = new CopyOnWriteArrayList<>();
        private boolean released;

        @Override
        public void execute(Runnable task) {
            handedOverOnFx.add(Platform.isFxApplicationThread());
            boolean runNow;
            synchronized (this) {
                runNow = released;
                if (!runNow) {
                    queued.add(task);
                }
            }
            if (runNow) {
                start(task);
            }
        }

        /** The tasks held now. */
        synchronized int queuedCount() {
            return queued.size();
        }

        /** Waits, bounded, until at least {@code count} tasks are held. */
        void awaitQueued(int count) {
            await(() -> queuedCount() >= count, count + " storage task(s) were handed over");
        }

        /** Runs the oldest held task on a new thread and waits, bounded, until it has ended. */
        void runNext() throws InterruptedException {
            awaitQueued(1);
            Runnable task;
            synchronized (this) {
                task = queued.poll();
            }
            Thread thread = start(task);
            thread.join(GUARD.toMillis());
            assertThat(thread.isAlive()).as("the storage task ended within the guard").isFalse();
        }

        /** Runs every held task, and every later one as it is handed over. */
        void release() {
            List<Runnable> due;
            synchronized (this) {
                released = true;
                due = List.copyOf(queued);
                queued.clear();
            }
            due.forEach(this::start);
        }

        /** Waits, bounded, until every task started so far has ended. */
        void joinEveryTask() throws InterruptedException {
            for (Thread thread : started) {
                thread.join(GUARD.toMillis());
                assertThat(thread.isAlive()).as("the storage task ended within the guard").isFalse();
            }
        }

        private Thread start(Runnable task) {
            Thread thread = new Thread(() -> {
                ranOnFx.add(Platform.isFxApplicationThread());
                task.run();
            }, "test-take-storage");
            thread.setDaemon(true);
            started.add(thread);
            thread.start();
            return thread;
        }
    }
}
