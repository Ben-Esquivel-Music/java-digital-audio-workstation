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
import com.benesquivelmusic.daw.core.persistence.archive.ArchiveOptions;
import com.benesquivelmusic.daw.core.persistence.archive.ArchivedProject;
import com.benesquivelmusic.daw.core.persistence.archive.MissingAssetResolver;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.plugin.LiveAnalyzerPlugin;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, the archive (Persistence Integrity book §2.3, §4.2; the story's
 * archive-isolation test, app half): {@code archiveInBackground} captures the
 * project on the FX thread and the worker writes that capture, never the
 * live model — an edit made right after the call does not reach the
 * archive. A capture that throws — anything, an {@link Error} included — is
 * contained in that same FX call into "Archive failed: &lt;reason&gt;" with
 * the strip operation removed, and no worker starts.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ArchiveCapturedOnTheFxThreadTest {

    @TempDir
    Path workspace;

    @Test
    void theArchiveIsCapturedOnTheFxThreadAndAnEditAfterTheCallNeverReachesIt() throws Exception {
        DescriptorReader reader = new DescriptorReader(null);
        DawProject project = projectWith(reader);
        Archiving archiving = new Archiving(project);
        Path archive = workspace.resolve("Song.dawz");

        runOnFx(() -> {
            archiving.lifecycle.archiveInBackground(project, archive,
                    new ArchiveSummaryDialog.ArchivePlan(ArchiveOptions.defaults(), List.of()));
            project.createAudioTrack("Added after the capture");
        });
        archiving.awaitTheOutcome();

        assertThat(archiving.message()).startsWith("Archive saved: ");
        assertThat(reader.threads).as("the model was read on the FX thread only").isNotEmpty()
                .containsOnly(archiving.fxThread);
        ArchivedProject restored = new ProjectArchiver().openArchive(archive, workspace.resolve("restored"),
                MissingAssetResolver.none());
        assertThat(restored.project().getTracks()).extracting(Track::getName)
                .as("the archive holds the project as it was captured").containsExactly("Vox");
    }

    @Test
    void aCaptureThatThrowsIsContainedInTheSameFxCallAndNoWorkerStarts() throws Exception {
        assertCaptureFailureContained(new IllegalStateException(), "Archive failed: IllegalStateException");
    }

    @Test
    void anErrorFromTheCaptureIsContainedToo() throws Exception {
        assertCaptureFailureContained(new AssertionError("the plugin's state is broken"),
                "Archive failed: the plugin's state is broken");
    }

    private void assertCaptureFailureContained(Throwable failure, String expected) throws Exception {
        DawProject project = projectWith(new DescriptorReader(failure));
        Archiving archiving = new Archiving(project);
        Path archive = workspace.resolve("Song.dawz");

        String shownInTheCall = onFx(() -> {
            archiving.lifecycle.archiveInBackground(project, archive,
                    new ArchiveSummaryDialog.ArchivePlan(ArchiveOptions.defaults(), List.of()));
            return archiving.bar.getMessage();
        });
        runOnFx(() -> { });

        assertThat(shownInTheCall).as("reported in the FX call that captured").isEqualTo(expected);
        assertThat(onFx(archiving.bar::getCurrentLevel)).isEqualTo(NotificationLevel.ERROR);
        assertThat(onFx(() -> List.copyOf(archiving.progress.getOperations()))).as("the operation is removed")
                .isEmpty();
        assertThat(Files.exists(archive)).isFalse();
        try (Stream<Path> siblings = Files.list(workspace)) {
            assertThat(siblings).as("no worker wrote anything").isEmpty();
        }
    }

    /** A one-track project whose track's analyzer insert is {@code reader}. */
    private static DawProject projectWith(DescriptorReader reader) {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track vox = project.createAudioTrack("Vox");
        project.getMixerChannelForTrack(vox).addInsert(new InsertSlot(
                "Analyzer", new LiveAnalyzerPlugin.TransparentProcessor(), null, reader));
        reader.armed = true;
        return project;
    }

    /** A lifecycle controller over {@code project}, its strip model and notification bar. */
    private static final class Archiving {
        final ProjectOperationProgress progress = new ProjectOperationProgress(new FxDispatcher());
        NotificationBar bar;
        ProjectLifecycleController lifecycle;
        Thread fxThread;

        Archiving(DawProject project) throws Exception {
            AtomicReference<DawProject> current = new AtomicReference<>(project);
            AtomicReference<UndoManager> undoManager = new AtomicReference<>(new UndoManager());
            runOnFx(() -> {
                fxThread = Thread.currentThread();
                bar = new NotificationBar();
                bar.setAnimated(false);
                bar.setAutoDismissMillis(600_000);
                lifecycle = new ProjectLifecycleController(
                        new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT)),
                        new SessionInterchangeController(), bar, progress, new BorderPane(), new VBox(),
                        new ProjectLifecycleController.Deps(current::get, current::set, undoManager::get,
                                undoManager::set, () -> { }, () -> { }, () -> { }, () -> null, _ -> { }, () -> { }),
                        new ProjectArchiver(), new FxDispatcher());
            });
        }

        /** Polls, bounded, until the worker's outcome is on the bar and the operation is gone. */
        void awaitTheOutcome() throws Exception {
            long deadline = System.nanoTime() + GUARD.toNanos();
            while (onFx(() -> !progress.getOperations().isEmpty() || bar.getCurrentLevel() == null)) {
                assertThat(System.nanoTime() - deadline < 0).as("the archive ended within %s", GUARD).isTrue();
                Thread.sleep(5);
            }
        }

        String message() throws Exception {
            return onFx(bar::getMessage);
        }
    }

    /** An analyzer insert that records the thread of every descriptor read — the capture's — and fails if asked. */
    private static final class DescriptorReader implements LiveAnalyzerPlugin {

        private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
                "test.descriptor-reader", "Descriptor Reader", "1.0", "Test", PluginType.ANALYZER);

        private final Throwable failure;
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        volatile boolean armed;

        DescriptorReader(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public PluginDescriptor getDescriptor() {
            if (armed) {
                threads.add(Thread.currentThread());
                if (failure instanceof RuntimeException unchecked) {
                    throw unchecked;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
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
}
