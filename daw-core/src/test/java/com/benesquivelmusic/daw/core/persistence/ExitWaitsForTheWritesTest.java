package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.Gate;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.CHECKPOINT;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.HEAD;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wait at exit (story 331, ruling M1): {@link ProjectManager#awaitWritesBeforeExit()},
 * which the application's one JVM shutdown hook calls, waits under one
 * deadline for the save writer to go idle — the head being written and its
 * checkpoint — and then for the checkpoint writer to hold no job of any
 * origin and any generation: a user's Create Checkpoint, and a save's
 * checkpoint still held after the save writer stopped waiting for it (ruling
 * J3) and after its generation ended. It returns {@code true} once they
 * landed; {@code false}, with the ruling's WARNING, when the deadline ran
 * out first — and a write still held then lands once released, since the wait
 * completes and interrupts nothing; and at once, logging nothing, when
 * nothing is being written. Writes are held at the {@link AtomicFileWriter}
 * mover seam; the wait runs on a thread of its own, as the hook does.
 */
class ExitWaitsForTheWritesTest {

    /** A bound for the checkpoints' {@code stop()} short enough to run out while a checkpoint is held. */
    private static final Duration SHORT_STOP_WAIT = Duration.ofMillis(200);

    @TempDir
    Path tempDir;

    private final RecordingMover headMover = new RecordingMover();
    private final RecordingMover checkpointMover = new RecordingMover();
    private final List<Gate> gates = new ArrayList<>();
    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Counted down by the wait's INFO, which it logs once it has looked whether the save writer runs. */
    private final CountDownLatch waitAnnounced = new CountDownLatch(1);
    private final Handler recorder = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            logged.add(logRecord);
            if (String.valueOf(logRecord.getMessage()).startsWith("Waiting up to ")) {
                waitAnnounced.countDown();
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final Logger managerLog = Logger.getLogger(ProjectManager.class.getName());
    private CheckpointManager checkpoints;
    private ProjectManager manager;
    private Path directory;

    @BeforeEach
    void openAProject() throws Exception {
        openAProjectWith(ProjectManager.CHECKPOINT_WAIT, CheckpointManager.STOP_WAIT);
        managerLog.addHandler(recorder);
    }

    /**
     * Opens a new project on new managers whose save writer waits
     * {@code checkpointWait} for a save's checkpoint (ruling J3) and whose
     * checkpoints' {@code stop()} waits at most {@code stopWait} (ruling G5).
     */
    private void openAProjectWith(Duration checkpointWait, Duration stopWait) throws Exception {
        checkpoints = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true),
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, checkpointMover), InstantSource.system(),
                Files::delete, stopWait);
        manager = SaveTestSupport.manager(checkpoints, new ProjectLockManager(),
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, headMover), checkpointWait);
        directory = manager.createProject("Song", tempDir).projectPath();
        assertThat(checkpoints.awaitWriterIdle(Duration.ofSeconds(TIMEOUT_SECONDS)))
                .as("fixture: the checkpoints were seeded").isTrue();
        headMover.clear();
        checkpointMover.clear();
    }

    @AfterEach
    void releaseTheManager() {
        managerLog.removeHandler(recorder);
        gates.forEach(Gate::release);
        manager.closeProject().join();
    }

    @Test
    void theBoundIsThirtySeconds() {
        assertThat(ProjectManager.EXIT_WRITE_WAIT).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aHeldHeadSaveIsWaitedForAndTheWaitReturnsTrueOnceItAndItsCheckpointLanded() throws Exception {
        Gate headHeld = hold(headMover.holdNext(HEAD));
        CompletableFuture<SaveOutcome> save = manager.save(project("Saved before the exit"));
        headHeld.awaitReached();

        CompletableFuture<Boolean> exit = exitWait(Duration.ofSeconds(TIMEOUT_SECONDS));
        assertThat(stillWaitingAfter(exit, Duration.ofMillis(300))).as("the held head is waited for").isTrue();

        headHeld.release();
        assertThat(await(exit)).as("everything landed within the bound").isTrue();
        assertThat(save).as("the save completed before the wait returned").isDone();
        assertThat(Files.readString(directory.resolve("project.daw"))).contains("Saved before the exit");
        assertThat(checkpointMover.publishes(CHECKPOINT)).as("and its checkpoint was written").hasSize(1);
        assertThat(messages(Level.INFO)).containsExactly("Waiting up to " + TIMEOUT_SECONDS + " s for the saves and"
                + " checkpoints still being written before the application exits");
        assertThat(messages(Level.WARNING)).isEmpty();
    }

    @Test
    void aSaveHeldPastTheBoundEndsTheWaitWithTheWarningAndStillLandsOnceReleased() throws Exception {
        Gate headHeld = hold(headMover.holdNext(HEAD));
        CompletableFuture<SaveOutcome> save = manager.save(project("Held past the bound"));
        headHeld.awaitReached();

        assertThat(await(exitWait(Duration.ofMillis(300)))).as("the bound ran out first").isFalse();

        assertThat(messages(Level.WARNING)).containsExactly("Saves or checkpoints were still being written 300 ms"
                + " after the application began to exit; the application exits without them, and project.daw holds"
                + " the last save that finished");
        assertThat(save).as("nothing was completed or cancelled").isNotDone();
        headHeld.release();
        assertThat(await(save)).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(Files.readString(directory.resolve("project.daw"))).as("the held write still landed")
                .contains("Held past the bound");
    }

    @Test
    void withNothingBeingWrittenItReturnsAtOnceAndLogsNothing() {
        long started = System.nanoTime();

        assertThat(manager.awaitWritesBeforeExit()).isTrue();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("at once").isLessThan(Duration.ofSeconds(1));
        assertThat(logged).as("no INFO, no WARNING").isEmpty();
    }

    @Test
    void aHeldCreateCheckpointIsWaitedFor() throws Exception {
        Gate checkpointHeld = hold(checkpointMover.holdNext(CHECKPOINT));
        CompletableFuture<Path> userCheckpoint = checkpoints.writeCheckpoint(
                new ProjectSerializer().serialize(project("Marked by the user")), 7L, CheckpointOrigin.USER);
        checkpointHeld.awaitReached();

        CompletableFuture<Boolean> exit = exitWait(Duration.ofSeconds(TIMEOUT_SECONDS));
        assertThat(stillWaitingAfter(exit, Duration.ofMillis(300))).as("the held checkpoint is waited for").isTrue();

        checkpointHeld.release();
        assertThat(await(exit)).isTrue();
        assertThat(userCheckpoint).as("it was written before the wait returned").isDone();
        assertThat(Files.readString(await(userCheckpoint))).contains("Marked by the user");
        assertThat(messages(Level.INFO)).hasSize(1);
    }

    @Test
    void aCheckpointHeldPastTheSaveWritersWaitAndPastTheEndOfItsGenerationIsWaitedForAfterTheSaves()
            throws Exception {
        manager.closeProject().join();
        openAProjectWith(SaveTestSupport.SHORT_CHECKPOINT_WAIT, SHORT_STOP_WAIT);
        Gate checkpointHeld = hold(checkpointMover.holdNext(CHECKPOINT));
        assertThat(await(manager.save(project("Checkpointed after the wait"))))
                .as("the save completed once ruling J3's wait ran out, without its checkpoint")
                .isInstanceOfSatisfying(SaveOutcome.Saved.class, saved -> assertThat(saved.checkpoint()).isEmpty());
        // The checkpoint cannot land while it is held: waiting for it to reach its hold keeps the assertion's meaning.
        assertThat(checkpointHeld.awaitReached(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("its checkpoint is still being written").isTrue();
        Gate headHeld = hold(headMover.holdNext(HEAD));
        CompletableFuture<SaveOutcome> second = manager.save(project("Saved as its generation ended"));
        headHeld.awaitReached();
        // Ends the checkpoint generation: stop() returns at once and its bound, a timer, runs out with the
        // checkpoint still held. The close never waits: the lock release waits for the held head write and the
        // held checkpoint instead.
        manager.closeProject();

        CompletableFuture<Boolean> exit = exitWait(Duration.ofSeconds(TIMEOUT_SECONDS));
        assertThat(waitAnnounced.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("the wait began while the save writer still ran").isTrue();
        headHeld.release();
        assertThat(await(second)).as("the save writer went idle").isInstanceOf(SaveOutcome.Saved.class);
        assertThat(stillWaitingAfter(exit, Duration.ofMillis(300)))
                .as("after the saves, the checkpoint of the ended generation is waited for").isTrue();

        checkpointHeld.release();
        assertThat(await(exit)).as("it landed within the bound").isTrue();
        List<RecordingMover.Publish> written = checkpointMover.publishes(CHECKPOINT);
        assertThat(written).as("the held checkpoint, published once").hasSize(1);
        assertThat(written.getFirst().target()).exists();
        assertThat(Files.readString(written.getFirst().target())).contains("Checkpointed after the wait");
        assertThat(messages(Level.WARNING)).as("only the save writer's own wait ran out, never the wait at exit")
                .containsExactly("The checkpoint of the save of " + directory + " was still being written after"
                        + " 300 ms; the save completes without it, and it is still written");
    }

    /** Runs the wait at exit on a thread of its own, as the shutdown hook does. */
    private CompletableFuture<Boolean> exitWait(Duration bound) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Thread.ofPlatform().name("test-exit-write-wait")
                .start(() -> result.complete(manager.awaitWritesBeforeExit(bound)));
        return result;
    }

    /** Whether {@code exit} has still not returned after {@code pause}. */
    private static boolean stillWaitingAfter(CompletableFuture<Boolean> exit, Duration pause)
            throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(pause.toMillis());
        return !exit.isDone();
    }

    private Gate hold(Gate gate) {
        gates.add(gate);
        return gate;
    }

    private List<String> messages(Level level) {
        return logged.stream().filter(entry -> entry.getLevel().equals(level)).map(LogRecord::getMessage).toList();
    }

    private static DawProject project(String trackName) {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.createAudioTrack(trackName);
        project.markDirty();
        return project;
    }
}
