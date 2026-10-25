package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.Recorder;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.Gate;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.prefs.AbstractPreferences;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.HEAD;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.await;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A save's checkpoint and a failed save's safety copy belong to the save's
 * own generation (story 331, rulings F9, G5, G9): both are bound, by
 * identity, to the checkpoint generation its project's open started. A head
 * write still running when another project is created or opened, or the
 * same folder is opened again, sends nothing into the new generation's
 * {@code checkpoints/}, prunes none of it and tells its listeners nothing;
 * the save completes {@link SaveOutcome.Saved} naming no checkpoint, or
 * {@link SaveOutcome.Failed} with no safety copy. The head write is held, or
 * failed, at the {@link AtomicFileWriter} mover seam while the project is
 * switched.
 */
class SaveCheckpointGenerationTest {

    private static final int CAP = 2;

    @TempDir
    Path tempDir;

    private final RecordingMover mover = new RecordingMover();
    private final HeldPreferences recents = new HeldPreferences();
    private final List<Gate> gates = new ArrayList<>();
    private ProjectManager manager;
    private Recorder recorder;

    @BeforeEach
    void createTheManager() {
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        CheckpointManager checkpoints = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), CAP, true),
                writer);
        manager = new ProjectManager(checkpoints, new RecentProjectsStore(recents), new ProjectLockManager(),
                new ProjectDeserializer(), writer, ProjectManager.CHECKPOINT_WAIT);
        recorder = Recorder.on(checkpoints);
    }

    @AfterEach
    void releaseTheManager() {
        gates.forEach(Gate::release);
        manager.abandonProject();
    }

    @Test
    void aSaveWhoseProjectIsReplacedByANewOneWhileItsHeadIsWrittenWritesNoCheckpointIntoTheNewOne()
            throws Exception {
        Path first = manager.createProject("First", tempDir).projectPath();
        Gate head = hold(mover.holdNext(HEAD));
        CompletableFuture<SaveOutcome> pending = manager.save(project("First"));
        head.awaitReached();

        Path second = manager.createProject("Second", tempDir).projectPath();
        Map<String, String> secondBefore = checkpointsIn(second);
        head.release();

        assertWrittenWithoutACheckpoint(await(pending), "First");
        assertThat(checkpointsIn(second)).as("the new project's checkpoints are as they were")
                .isEqualTo(secondBefore);
        assertThat(checkpointNames(first)).as("and the old project's checkpoints were ended with it").isEmpty();
        assertNoCheckpointWasReported();
    }

    @Test
    void aSaveWhoseProjectIsReplacedByAnOpenProjectAtItsCapLeavesThatProjectsCheckpointsAsTheyWere()
            throws Exception {
        Path second = SaveTestSupport.projectOnDisk(manager, "Second", tempDir);
        Path seededDirectory = Files.createDirectories(second.resolve("checkpoints"));
        Files.writeString(seededDirectory.resolve("checkpoint-001-20260101T000000.daw"), "an earlier session, 1");
        Files.writeString(seededDirectory.resolve("checkpoint-002-20260101T000100.daw"), "an earlier session, 2");
        manager.createProject("First", tempDir);
        Gate head = hold(mover.holdNext(HEAD));
        CompletableFuture<SaveOutcome> pending = manager.save(project("First"));
        head.awaitReached();

        manager.openProject(second);
        Map<String, String> secondBefore = checkpointsIn(second);
        assertThat(secondBefore).as("fixture: the opened project is at its cap").hasSize(CAP);
        head.release();

        assertWrittenWithoutACheckpoint(await(pending), "First");
        assertThat(checkpointsIn(second)).as("nothing of the opened project's checkpoints is pruned or added")
                .isEqualTo(secondBefore);
        assertNoCheckpointWasReported();
    }

    @Test
    void aSaveWhoseFolderIsAbandonedAndOpenedAgainWhileItsHeadIsWrittenWritesNoCheckpointIntoTheNewGeneration()
            throws Exception {
        Path first = manager.createProject("First", tempDir).projectPath();
        DawProject project = project("First");
        saved(manager, project);
        project.createAudioTrack("Second take");
        project.markDirty();
        Gate head = hold(mover.holdNext(HEAD));
        CompletableFuture<SaveOutcome> pending = manager.save(project);
        head.awaitReached();

        manager.abandonProject();
        manager.openProject(first);
        Map<String, String> before = checkpointsIn(first);
        assertThat(before).as("fixture: the first save's checkpoint, seeded by the new generation").hasSize(1);
        int writtenBefore = recorder.events(CheckpointEvent.Written.class).size();
        head.release();

        assertWrittenWithoutACheckpoint(await(pending), "First");
        assertThat(checkpointsIn(first)).as("the same folder opened again is another generation: nothing added")
                .isEqualTo(before);
        assertThat(recorder.events(CheckpointEvent.Written.class)).hasSize(writtenBefore);
        assertThat(recorder.events(CheckpointEvent.Failed.class)).isEmpty();
    }

    @Test
    void aHeadWriteThatFailsAfterItsProjectWasReplacedWritesNoSafetyCopyAnywhere() throws Exception {
        Path first = manager.createProject("First", tempDir).projectPath();
        Path firstHead = first.resolve("project.daw");
        IOException crash = new IOException("injected head failure after the project was replaced");
        mover.failWith(firstHead::equals, crash);
        Gate head = hold(mover.holdNext(firstHead::equals));
        CompletableFuture<SaveOutcome> pending = manager.save(project("First"));
        head.awaitReached();

        Path second = manager.createProject("Second", tempDir).projectPath();
        head.release();

        assertThat(await(pending)).isInstanceOfSatisfying(SaveOutcome.Failed.class, failed -> {
            assertThat(failed.cause()).isSameAs(crash);
            assertThat(failed.safetyCheckpoint()).isEmpty();
            assertThat(failed.cause().getSuppressed()).as("no safety copy was requested, so none failed")
                    .isEmpty();
            assertThat(failed.safetyCopyStillWriting()).isFalse();
        });
        assertThat(checkpointNames(second)).isEmpty();
        assertThat(checkpointNames(first)).isEmpty();
        assertNoCheckpointWasReported();
    }

    @ParameterizedTest(name = "the same folder: {0}")
    @ValueSource(booleans = {false, true})
    void aSaveWritesNoCheckpointIntoAGenerationItsProjectsOpenDidNotStart(boolean theSameFolder) throws Exception {
        Path first = manager.createProject("First", tempDir).projectPath();
        Path other = theSameFolder ? first : Files.createDirectories(tempDir.resolve("Elsewhere"));
        Gate head = hold(mover.holdNext(HEAD));
        CompletableFuture<SaveOutcome> pending = manager.save(project("First"));
        head.awaitReached();

        // Behind the project manager: the project stays open, its save target with it, so only the binding decides.
        manager.getCheckpointManager().start(other).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        head.release();

        assertWrittenWithoutACheckpoint(await(pending), "First");
        assertThat(checkpointNames(other)).as("the generation its open did not start gets none of it").isEmpty();
        assertThat(checkpointNames(first)).isEmpty();
        assertNoCheckpointWasReported();
    }

    /**
     * An open held in its recent-projects write — after it fenced the old
     * saves, before it starts the new project's checkpoints — while the old
     * project's head write is held. This depends on {@code openProject}'s
     * code order: it ends the old project's checkpoints first, before
     * anything that can fail (story 331), and records the recent project
     * before it starts the new generation; the fixture checks below fail if
     * that order changes. Since the old generation has ended by then, the
     * save's checkpoint would be refused by its binding to that generation
     * even without the write path's own re-check of the save target, which
     * refuses it first: this test cannot tell the two apart. The re-check
     * still matters where the fence comes before the stop:
     * {@code abandonProject} and {@code closeProject}.
     */
    @Test
    void aSaveWhoseProjectAnOpenIsReplacingRequestsNoCheckpointOnceTheOpenBegan() throws Exception {
        Path second = SaveTestSupport.projectOnDisk(manager, "Second", tempDir);
        Path first = manager.createProject("First", tempDir).projectPath();
        Gate head = hold(mover.holdNext(HEAD));
        CompletableFuture<SaveOutcome> pending = manager.save(project("First"));
        head.awaitReached();
        Gate recentsWrite = hold(recents.holdNextWrite());

        CompletableFuture<ProjectMetadata> opening = openOnAnotherThread(second);
        recentsWrite.awaitReached();
        assertThat(manager.getCurrentProject().projectPath()).as("fixture: the open replaced the project")
                .isEqualTo(second);
        assertThat(manager.getCheckpointManager().runningGeneration())
                .as("fixture: the open ended the old project's checkpoints and has not started the new one's")
                .isEmpty();
        assertThat(opening).as("fixture: the open has not returned").isNotDone();
        head.release();

        assertWrittenWithoutACheckpoint(await(pending), "First");
        assertThat(checkpointNames(first)).as("no checkpoint once the session holds the next project's lock")
                .isEmpty();
        assertNoCheckpointWasReported();
        recentsWrite.release();
        assertThat(await(opening).projectPath()).isEqualTo(second);
    }

    private static void assertWrittenWithoutACheckpoint(SaveOutcome outcome, String name) {
        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Saved.class, saved -> {
            assertThat(saved.metadata().name()).as("the old project's own outcome").isEqualTo(name);
            assertThat(saved.checkpoint()).as("the head landed in its own folder; no checkpoint of it").isEmpty();
        });
    }

    private void assertNoCheckpointWasReported() {
        assertThat(recorder.sdk("before")).as("no checkpoint began").isEmpty();
        assertThat(recorder.events(CheckpointEvent.Written.class)).isEmpty();
        assertThat(recorder.events(CheckpointEvent.Failed.class)).isEmpty();
    }

    private Gate hold(Gate gate) {
        gates.add(gate);
        return gate;
    }

    private CompletableFuture<ProjectMetadata> openOnAnotherThread(Path directory) {
        CompletableFuture<ProjectMetadata> opened = new CompletableFuture<>();
        Thread.ofPlatform().name("project-opener").daemon().start(() -> {
            try {
                opened.complete(manager.openProject(directory));
            } catch (Throwable failure) {
                opened.completeExceptionally(failure);
            }
        });
        return opened;
    }

    /** Each file in {@code projectDir/checkpoints} by name, with its content; empty when there is no directory. */
    private static Map<String, String> checkpointsIn(Path projectDir) throws IOException {
        Path directory = projectDir.resolve("checkpoints");
        Map<String, String> files = new TreeMap<>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                files.put(entry.getFileName().toString(), Files.readString(entry));
            }
        }
        return files;
    }

    private static DawProject project(String name) {
        DawProject project = new DawProject(name, AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        project.markDirty();
        return project;
    }

    /**
     * An in-memory preferences node for the {@link RecentProjectsStore}
     * whose next write can be held at a {@link Gate}: it holds an open on
     * the thread that records the recent project.
     */
    private static final class HeldPreferences extends AbstractPreferences {

        private final Map<String, String> values = new ConcurrentHashMap<>();
        private Gate gate;

        HeldPreferences() {
            super(null, "");
        }

        /** Holds the next write. */
        synchronized Gate holdNextWrite() {
            gate = new Gate();
            return gate;
        }

        private synchronized Gate takeGate() {
            Gate held = gate;
            gate = null;
            return held;
        }

        @Override
        protected void putSpi(String key, String value) {
            Gate held = takeGate();
            if (held != null) {
                try {
                    held.pass();
                } catch (IOException notReleased) {
                    throw new UncheckedIOException(notReleased);
                }
            }
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
            values.clear();
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return new String[0];
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            throw new UnsupportedOperationException("the recent-projects store keeps no child nodes");
        }

        @Override
        protected void syncSpi() {
        }

        @Override
        protected void flushSpi() {
        }
    }
}
