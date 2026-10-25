package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.analysis.AnalyzerProcessor;
import com.benesquivelmusic.daw.core.analysis.AnalyzerSnapshot;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.persistence.archive.ArchiveOptions;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.plugin.LiveAnalyzerPlugin;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The archive worker of {@link ProjectLifecycleController} clears its strip
 * operation and reports the failure whatever the archiver throws: an
 * unchecked exception from the real {@link ProjectArchiver} (here an
 * {@link IllegalStateException} with no message, raised while the archive
 * captures the live model, on the FX thread since story 331) must not leave
 * a spinner behind or an
 * "Archive failed: null" notification, and must leave the target untouched.
 *
 * <p>The injected failure fires while the archive captures the project,
 * before any worker starts or any file is written, so these tests do not pin the cleanup of a
 * temporary archive: that is pinned in daw-core by
 * {@code ProjectArchiverAssetWriteFailureTest}, whose failure fires part-way
 * through the asset pack. What they pin here is the user-facing contract: no
 * file is written beside the target, and an existing archive stays byte
 * identical.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectLifecycleArchiveFailureTest {

    private static final long DEADLINE_MILLIS = 10_000;

    @TempDir
    Path tmp;

    @Test
    void anUncheckedArchiverFailureClearsTheOperationAndReportsItsTypeWhenItHasNoMessage() throws Exception {
        Path target = tmp.resolve("Song.dawz");
        DescriptorFailingAnalyzer analyzer = new DescriptorFailingAnalyzer();

        ArchiveRun run = archiveAndAwaitTheOutcome(projectThatFailsToSerialize(analyzer), target);

        assertThat(analyzer.failures())
                .as("guard: the IllegalStateException was raised inside the archiver")
                .isPositive();
        assertThat(run.operationsLeft()).as("the strip operation is removed").isEmpty();
        assertThat(run.level()).isEqualTo(NotificationLevel.ERROR);
        assertThat(run.message())
                .isEqualTo("Archive failed: IllegalStateException")
                .doesNotContain("null");
        assertThat(target).doesNotExist();
        try (var siblings = Files.list(tmp)) {
            assertThat(siblings).as("nothing written beside the target").isEmpty();
        }
    }

    @Test
    void anUncheckedArchiverFailureLeavesAnExistingArchiveByteIdentical() throws Exception {
        Path target = tmp.resolve("Song.dawz");
        byte[] previous = {'P', 'K', 3, 4, 42, 0, 7};
        Files.write(target, previous);
        DescriptorFailingAnalyzer analyzer = new DescriptorFailingAnalyzer();

        ArchiveRun run = archiveAndAwaitTheOutcome(projectThatFailsToSerialize(analyzer), target);

        assertThat(analyzer.failures()).as("guard: the archiver raised the failure").isPositive();
        assertThat(run.operationsLeft()).isEmpty();
        assertThat(run.level()).isEqualTo(NotificationLevel.ERROR);
        assertThat(run.message()).startsWith("Archive failed: ").doesNotContain("null");
        assertThat(Files.readAllBytes(target))
                .as("the existing archive is byte identical")
                .isEqualTo(previous);
    }

    /** A project whose one insert hosts an analyzer that fails once the archiver serializes it. */
    private static DawProject projectThatFailsToSerialize(DescriptorFailingAnalyzer analyzer) {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track vox = project.createAudioTrack("Vox");
        project.getMixerChannelForTrack(vox).addInsert(new InsertSlot(
                "Analyzer", new LiveAnalyzerPlugin.TransparentProcessor(), null, analyzer));
        analyzer.failFromNowOn();
        return project;
    }

    private record ArchiveRun(List<ProjectOperationProgress.NamedOperation> operationsLeft,
                              NotificationLevel level,
                              String message) { }

    /**
     * Starts the archive on the FX thread, as the menu action does, then polls
     * the FX thread until the worker's outcome has been published: the
     * notification is shown in the same FX runnable that asks the strip to
     * remove the operation, so once the operation is gone the outcome is final.
     */
    private ArchiveRun archiveAndAwaitTheOutcome(DawProject project, Path target) throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        ProjectOperationProgress progress = new ProjectOperationProgress(dispatcher);
        AtomicReference<NotificationBar> bar = new AtomicReference<>();
        AtomicReference<ProjectLifecycleController> controller = new AtomicReference<>();
        onFx(() -> {
            NotificationBar notificationBar = new NotificationBar();
            notificationBar.setAutoDismissMillis(60_000);
            bar.set(notificationBar);
            controller.set(new ProjectLifecycleController(
                    projectManager(), new SessionInterchangeController(), notificationBar,
                    progress, new BorderPane(), new VBox(), deps(project),
                    new ProjectArchiver(), dispatcher));
            controller.get().archiveInBackground(project, target,
                    new ArchiveSummaryDialog.ArchivePlan(ArchiveOptions.defaults(), List.of()));
        });

        long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<ArchiveRun> seen = new AtomicReference<>();
            onFx(() -> seen.set(new ArchiveRun(List.copyOf(progress.getOperations()),
                    bar.get().getCurrentLevel(), bar.get().getMessage())));
            if (seen.get().operationsLeft().isEmpty() && seen.get().level() != null) {
                return seen.get();
            }
            Thread.sleep(10);
        }
        AtomicReference<ArchiveRun> last = new AtomicReference<>();
        onFx(() -> last.set(new ArchiveRun(List.copyOf(progress.getOperations()),
                bar.get().getCurrentLevel(), bar.get().getMessage())));
        return last.get();
    }

    /** An analyzer insert whose descriptor read fails, with no message, once armed. */
    private static final class DescriptorFailingAnalyzer implements LiveAnalyzerPlugin {

        private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
                "test.failing-analyzer", "Failing Analyzer", "1.0", "Test", PluginType.ANALYZER);

        private volatile boolean armed;
        private final AtomicInteger failures = new AtomicInteger();

        void failFromNowOn() {
            armed = true;
        }

        int failures() {
            return failures.get();
        }

        @Override
        public PluginDescriptor getDescriptor() {
            if (armed) {
                failures.incrementAndGet();
                throw new IllegalStateException();
            }
            return DESCRIPTOR;
        }

        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
        @Override public AnalyzerProcessor createAnalysisConsumer(Consumer<AnalyzerSnapshot> publish) { return null; }
        @Override public void acceptAnalysis(AnalyzerSnapshot snapshot) { }
        @Override public boolean isActive() { return true; }
    }

    private static ProjectManager projectManager() {
        Preferences prefs = Preferences.userRoot().node("daw-test-archive-failure-" + System.nanoTime());
        return new ProjectManager(
                new CheckpointManager(AutoSaveConfig.DEFAULT),
                new RecentProjectsStore(prefs));
    }

    private static ProjectLifecycleController.Deps deps(DawProject project) {
        AtomicReference<DawProject> current = new AtomicReference<>(project);
        AtomicReference<UndoManager> undoManager = new AtomicReference<>(new UndoManager());
        Supplier<String> noLayout = () -> null;
        return new ProjectLifecycleController.Deps(
                current::get,
                current::set,
                undoManager::get,
                undoManager::set,
                () -> { },
                () -> { },
                () -> { },
                noLayout,
                json -> { },
                () -> { });
    }

    private static void onFx(Runnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).as("the FX action completed").isTrue();
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
