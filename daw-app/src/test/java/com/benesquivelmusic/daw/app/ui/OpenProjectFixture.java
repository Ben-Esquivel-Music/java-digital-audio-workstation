package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audioimport.AudioFileImporter;
import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ClipRehydrator;
import com.benesquivelmusic.daw.core.persistence.MissingAssetsReport;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@link ProjectLifecycleController} without FXML for the tests of the
 * open project's audio (story 329): the controller over a real
 * {@link ProjectManager}, a {@link NotificationBar} whose history the tests
 * read, an unstarted {@link FxDispatcher} whose keyed work runs only when a
 * test {@linkplain #pulse() pulses} it, a {@link ClipRehydrator} whose
 * decode threads are {@linkplain HeldDecodeThreads held} until the test
 * releases them, and a {@linkplain CountingPresenter presenter} that shows
 * nothing and counts the report's presentations.
 *
 * <p>Every FX action runs through {@link Platform#runLater} and is bounded at
 * {@link #GUARD}; every wait on a decode thread is bounded at the same
 * guard.</p>
 */
final class OpenProjectFixture implements AutoCloseable {

    /** The bound of every FX action and of every wait on a decode thread. */
    static final Duration GUARD = Duration.ofSeconds(10);

    /** A 48 kHz project, so a 44.1 kHz import is converted. */
    static final AudioFormat PROJECT_FORMAT = new AudioFormat(48_000, 2, 16, 512);

    final Path workspace;
    final ProjectManager projectManager;
    final AtomicReference<DawProject> project = new AtomicReference<>(new DawProject("Before", PROJECT_FORMAT));
    final AtomicReference<UndoManager> undoManager = new AtomicReference<>(new UndoManager());
    final AtomicInteger rebuilds = new AtomicInteger();
    /** Every arrangement repaint the controller's {@code Deps.repaintArrangement} ran. */
    final AtomicInteger repaints = new AtomicInteger();
    /** The repaints that ran outside a {@link #pulse()}. */
    final AtomicInteger repaintsOutsidePulse = new AtomicInteger();
    final NotificationHistoryService shown = new NotificationHistoryService();
    /** Never started: its keyed work runs only inside {@link #pulse()}. */
    final FxDispatcher dispatcher = new FxDispatcher();
    final ProjectOperationProgress progress = new ProjectOperationProgress(new FxDispatcher());
    final HeldDecodeThreads decodes = new HeldDecodeThreads(dispatcher);
    final CountingPresenter presenter = new CountingPresenter();
    private boolean inPulse;
    private ProjectLifecycleController lifecycle;

    OpenProjectFixture(Path workspace) throws Exception {
        this(workspace, new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT)));
    }

    /** The fixture over {@code projectManager} (a test that needs its own deserializer). */
    OpenProjectFixture(Path workspace, ProjectManager projectManager) throws Exception {
        this.workspace = workspace;
        this.projectManager = projectManager;
        runOnFx(() -> {
            NotificationBar notificationBar = new NotificationBar();
            notificationBar.setAnimated(false);
            notificationBar.setHistoryService(shown);
            lifecycle = new ProjectLifecycleController(projectManager, new SessionInterchangeController(),
                    notificationBar, progress, new BorderPane(), new VBox(),
                    new ProjectLifecycleController.Deps(project::get, project::set, undoManager::get,
                            undoManager::set, () -> { }, () -> { }, rebuilds::incrementAndGet,
                            () -> null, _ -> { }, this::repainted),
                    new ProjectArchiver(), dispatcher);
            lifecycle.setClipRehydrator(new ClipRehydrator(decodes));
            lifecycle.setMissingAssetsPresenter(presenter);
            lifecycle.setWelcomePresenter(_ -> { });
        });
    }

    /** The controller under test; FX thread only. */
    ProjectLifecycleController lifecycle() {
        return lifecycle;
    }

    /** The owner of the open project's audio; FX thread only. */
    OpenProjectAudio audio() {
        return lifecycle.openProjectAudio();
    }

    private void repainted() {
        repaints.incrementAndGet();
        if (!inPulse) {
            repaintsOutsidePulse.incrementAndGet();
        }
    }

    /**
     * Makes the drain apply each outcome through {@code applier} instead of
     * the session's own apply — for a test that makes one apply fail.
     */
    void applyOutcomesWith(OpenProjectAudio.OutcomeApplier applier) throws Exception {
        runOnFx(() -> audio().setOutcomeApplier(applier));
    }

    /** Runs one pulse of the dispatcher on the FX thread: the only place keyed work runs. */
    void pulse() throws Exception {
        runOnFx(() -> {
            inPulse = true;
            try {
                dispatcher.pulse();
            } finally {
                inPulse = false;
            }
        });
    }

    /** Releases every held decode, waits for each to end, then pulses. */
    void releaseDecodesAndPulse() throws Exception {
        decodes.release();
        decodes.awaitEveryTaskEnded();
        pulse();
    }

    /** The notifications shown so far. */
    List<NotificationEntry> notifications() throws Exception {
        return onFx(shown::getEntries);
    }

    /** The names of the strip's operations, after one more FX turn (they are posted un-keyed). */
    List<String> operationNames() throws Exception {
        runOnFx(() -> { });
        return onFx(() -> {
            List<String> names = new ArrayList<>();
            for (ProjectOperationProgress.NamedOperation operation : progress.getOperations()) {
                names.add(operation.name());
            }
            return names;
        });
    }

    @Override
    public void close() throws Exception {
        try {
            runOnFx(() -> lifecycle.dispose());
            decodes.release();
            decodes.awaitEveryTaskEnded();
            // Story 331 — a save the controller asked for is written off the FX thread.
            SaveOutcomes.awaitIdle(projectManager);
        } finally {
            projectManager.abandonProject();
        }
    }

    // ── projects and sources on disk ─────────────────────────────────────────

    /**
     * A project saved on disk: its folder and the in-memory project that was
     * saved (its clips still hold the audio the imports attached).
     */
    record SavedProject(Path directory, DawProject saved) {

        /** The saved project's clips, in project order. */
        List<AudioClip> clips() {
            List<AudioClip> clips = new ArrayList<>();
            for (Track track : saved.getTracks()) {
                clips.addAll(track.getClips());
            }
            return clips;
        }
    }

    /**
     * Saves a project named {@code name} holding one imported clip per
     * source, each on its own track, through a manager of its own, which
     * then lets the project go.
     */
    SavedProject projectOnDisk(String name, Path... sources) throws IOException {
        ProjectManager other = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        Path directory = other.createProject(name, workspace).projectPath();
        DawProject saved = new DawProject(name, PROJECT_FORMAT);
        AudioFileImporter importer = new AudioFileImporter(saved);
        for (Path source : sources) {
            importer.importFile(source, 0.0);
        }
        SaveOutcomes.saved(other, saved);
        other.abandonProject();
        return new SavedProject(directory, saved);
    }

    /** Writes a 16-bit stereo WAV of a sine, {@code seconds} long, creating its folder. */
    static Path wav(Path file, int sampleRate, double seconds, double hz) throws IOException {
        int frames = (int) Math.round(sampleRate * seconds);
        float[][] audio = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            float sample = (float) (0.5 * Math.sin(2.0 * Math.PI * hz * i / sampleRate));
            audio[0][i] = sample;
            audio[1][i] = -sample;
        }
        Files.createDirectories(file.getParent());
        WavExporter.write(audio, sampleRate, 16, DitherType.NONE, AudioMetadata.EMPTY, file);
        return file;
    }

    /** The clips of the live project, in project order. */
    List<AudioClip> liveClips() throws Exception {
        return onFx(() -> {
            List<AudioClip> clips = new ArrayList<>();
            for (Track track : project.get().getTracks()) {
                clips.addAll(track.getClips());
            }
            return clips;
        });
    }

    // ── the arrangement ──────────────────────────────────────────────────────

    /**
     * Draws {@code track} on a 480×96 arrangement canvas in a scene — one
     * 80 px lane at 40 px per beat — with each clip's body from
     * {@code states}, and returns its pixels. FX thread.
     */
    static int[] arrangementPixels(Track track, Function<AudioClip, ClipAudioState> states) {
        ArrangementCanvas canvas = new ArrangementCanvas();
        new Scene(canvas, 480, 96);
        canvas.setTrackHeight(80.0);
        canvas.setPixelsPerBeat(40.0);
        canvas.setClipAudioStates(states);
        canvas.setTracks(List.of(track));
        canvas.applyCss();
        canvas.layout();
        WritableImage image = canvas.snapshot(new SnapshotParameters(), null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] argb = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);
        return argb;
    }

    // ── FX ───────────────────────────────────────────────────────────────────

    static <T> T onFx(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        runOnFx(() -> result.set(action.get()));
        return result.get();
    }

    /** Runs {@code action} on the FX thread and rethrows what it threw; bounded at {@link #GUARD}. */
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
        assertThat(done.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("the FX action returned within %s", GUARD).isTrue();
        if (thrown.get() instanceof Error error) {
            throw error;
        }
        if (thrown.get() != null) {
            throw new AssertionError("the FX action threw", thrown.get());
        }
    }

    // ── the held decode threads ──────────────────────────────────────────────

    /**
     * The decode threads of every rehydration session: each holds its job —
     * the decode and the report to the listener — until {@link #release()},
     * and records whether it was handed over, and whether it ran, on the FX
     * thread ({@link FxDispatcher#isFxThread()}). A job a cancelled session
     * interrupts while it is held runs at once, and finds its session
     * cancelled.
     */
    static final class HeldDecodeThreads implements ThreadFactory {

        private final FxDispatcher dispatcher;
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        /** Whether each job was handed over on the FX thread, in order. */
        final List<Boolean> handedOverOnFx = new CopyOnWriteArrayList<>();
        /** Whether each job that ran so far ran on the FX thread. */
        final List<Boolean> ranOnFx = new CopyOnWriteArrayList<>();

        HeldDecodeThreads(FxDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Override
        public Thread newThread(Runnable task) {
            handedOverOnFx.add(dispatcher.isFxThread());
            Thread thread = Thread.ofVirtual().name("held-clip-decode-" + threads.size()).unstarted(() -> {
                try {
                    release.await();
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                }
                ranOnFx.add(dispatcher.isFxThread());
                task.run();
            });
            threads.add(thread);
            return thread;
        }

        /** The jobs handed over so far. */
        int handedOver() {
            return threads.size();
        }

        /** Lets every held job run, and every later one at once. */
        void release() {
            release.countDown();
        }

        /** Waits, bounded, until every job handed over so far has ended. */
        void awaitEveryTaskEnded() throws InterruptedException {
            for (Thread thread : threads) {
                assertThat(thread.join(GUARD)).as("decode thread %s ended within %s", thread.getName(), GUARD)
                        .isTrue();
            }
        }
    }

    // ── the counting presenter ───────────────────────────────────────────────

    /** One presentation of a generation's report. */
    record Presentation(OpenProjectAudio.Generation generation, MissingAssetsReport report, CountingView view) {
    }

    /** Shows nothing; records each presentation and hands back a counting view. */
    static final class CountingPresenter implements OpenProjectAudio.ReportPresenter {

        final List<Presentation> presentations = new CopyOnWriteArrayList<>();

        @Override
        public OpenProjectAudio.ReportView present(OpenProjectAudio.Generation generation,
                                                   MissingAssetsReport report) {
            CountingView view = new CountingView(report);
            presentations.add(new Presentation(generation, report, view));
            return view;
        }
    }

    /** A report surface that counts what its owner asks of it. */
    static final class CountingView implements OpenProjectAudio.ReportView {

        final AtomicInteger presents = new AtomicInteger();
        final AtomicInteger updates = new AtomicInteger();
        final AtomicInteger dismissals = new AtomicInteger();
        volatile MissingAssetsReport report;

        CountingView(MissingAssetsReport report) {
            this.report = report;
        }

        @Override
        public void present() {
            presents.incrementAndGet();
        }

        @Override
        public void update(MissingAssetsReport report) {
            updates.incrementAndGet();
            this.report = report;
        }

        @Override
        public void dismiss() {
            dismissals.incrementAndGet();
        }
    }
}
