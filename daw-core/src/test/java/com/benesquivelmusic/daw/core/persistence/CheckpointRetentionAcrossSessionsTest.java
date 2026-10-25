package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.CheckpointEvent.RearmReason;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.ManualOwner;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.Recorder;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TestSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checkpoint retention holds across sessions (story 331, "Cross-session
 * retention"; rulings F5, G6, G13 #1): the index is seeded from the
 * directory, pruning runs only after a write and only over names the
 * grammar accepts, a stray temp of a checkpoint is cleared at start, and a
 * prune that fails — even with an {@link Error} — never fails the checkpoint
 * it follows.
 */
class CheckpointRetentionAcrossSessionsTest {

    private static final int CAP = 3;

    /** A temp the atomic writer leaves after a hard kill: exactly its grammar, of a checkpoint name. */
    private static final String STRAY_CHECKPOINT_TEMP = ".checkpoint-002-20260101T000000.daw.0123456789abcdef.tmp";

    @TempDir
    Path projectDir;

    @Test
    void twoRunsNeverLeaveMoreThanTheCapAndTheIndexContinuesAcrossThem() throws Exception {
        CheckpointManager firstRun = manager(CAP, Files::delete);
        firstRun.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        for (long revision = 1; revision <= 5; revision++) {
            write(firstRun, revision);
            assertThat(checkpoints()).as("after write %s of run 1", revision).hasSizeLessThanOrEqualTo(CAP);
        }
        firstRun.stop();
        assertThat(firstRun.getCheckpointCount()).isEqualTo(5);

        Path directory = projectDir.resolve("checkpoints");
        Files.writeString(directory.resolve(STRAY_CHECKPOINT_TEMP), "<daw-project");
        Files.writeString(directory.resolve("checkpoint-050-20250101T000000.daw"), "an older session");
        Files.writeString(directory.resolve("checkpoint-051-20250101T000001.daw"), "an older session");
        Files.writeString(directory.resolve("checkpoint-099-20250101T000000.daw.bak"), "not a checkpoint");
        Files.writeString(directory.resolve("notes.txt"), "not a checkpoint");
        List<String> beforeTheSecondRun = checkpoints();

        CheckpointManager secondRun = manager(CAP, Files::delete);
        secondRun.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(checkpoints()).as("nothing is pruned at start").isEqualTo(beforeTheSecondRun).hasSize(CAP + 2);
        assertThat(secondRun.getCheckpointFiles()).hasSize(CAP + 2);
        assertThat(secondRun.getCheckpointCount()).as("the highest index on disk").isEqualTo(51);
        assertThat(directory.resolve(STRAY_CHECKPOINT_TEMP)).as("run 1's stray temp is cleared").doesNotExist();

        for (long revision = 6; revision <= 9; revision++) {
            Path written = write(secondRun, revision);
            assertThat(CheckpointFileName.parse(written.getFileName().toString()).orElseThrow().index())
                    .as("indices continue from the highest seeded one").isEqualTo(46 + revision);
            assertThat(checkpoints()).as("after write %s of run 2", revision).hasSizeLessThanOrEqualTo(CAP);
            if (revision == 6) {
                assertThat(checkpoints())
                        .as("the older session's higher indices went first: name time before index")
                        .doesNotContain("checkpoint-050-20250101T000000.daw", "checkpoint-051-20250101T000001.daw")
                        .contains(written.getFileName().toString());
            }
        }
        secondRun.stop();

        assertThat(checkpoints()).as("exactly the cap, the run's last three writes").hasSize(CAP)
                .allSatisfy(name -> assertThat(CheckpointFileName.parse(name).orElseThrow().index())
                        .isBetween(53L, 55L));
        assertThat(directory.resolve("checkpoint-099-20250101T000000.daw.bak")).as("never counted or deleted")
                .exists();
        assertThat(directory.resolve("notes.txt")).exists();
    }

    @Test
    void seedingDeletesOnlyTheWritersTempsOfCheckpointNames() throws Exception {
        Path directory = Files.createDirectories(projectDir.resolve("checkpoints"));
        List<String> kept = List.of(
                ".project.daw.0123456789abcdef.tmp",
                // Another index: Windows names are case-insensitive, so the
                // same name in uppercase would be the stray temp itself.
                ".checkpoint-003-20260101T000000.daw.0123456789ABCDEF.tmp",
                ".checkpoint-002-20260101T000000.daw.0123456789abcde.tmp",
                ".checkpoint-002-20260101T0000.daw.0123456789abcdef.tmp",
                "checkpoint-002-20260101T000000.daw.0123456789abcdef.tmp",
                "foo.tmp");
        for (String name : kept) {
            Files.writeString(directory.resolve(name), "stranger");
        }
        Files.writeString(directory.resolve(STRAY_CHECKPOINT_TEMP), "<daw-project");

        CheckpointManager manager = manager(CAP, Files::delete);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        manager.stop();

        assertThat(directory.resolve(STRAY_CHECKPOINT_TEMP)).doesNotExist();
        assertThat(checkpointNames(projectDir)).containsExactlyInAnyOrderElementsOf(kept);
        assertThat(manager.getCheckpointFiles()).as("no temp is a checkpoint").isEmpty();
    }

    @Test
    void seedingLeavesTheStrayTempsWhileTheWriteGuardFails() throws Exception {
        Path directory = Files.createDirectories(projectDir.resolve("checkpoints"));
        Files.writeString(directory.resolve(STRAY_CHECKPOINT_TEMP), "<daw-project");
        CheckpointManager manager = manager(CAP, Files::delete);
        manager.setWriteGuard(() -> {
            throw new LockStolenException("another session holds the project", null);
        });

        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        manager.stop();

        assertThat(directory.resolve(STRAY_CHECKPOINT_TEMP)).exists();
    }

    @Test
    void aPruneThatFailsLeavesTheCheckpointASuccessAndTheNextWritePrunesBackToTheCap() throws Exception {
        Set<Path> undeletable = ConcurrentHashMap.newKeySet();
        CheckpointManager manager = manager(2, file -> {
            if (undeletable.contains(file)) {
                throw new AccessDeniedException(file.toString(), null, "held by a sync client");
            }
            Files.delete(file);
        });
        ManualOwner owner = new ManualOwner();
        TestSource source = new TestSource();
        Recorder recorder = Recorder.on(manager);
        manager.setCaptureSource(owner, source);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Path oldest = write(manager, 1);
        write(manager, 2);
        undeletable.add(oldest);

        Path third = write(manager, 3);

        assertThat(third).exists();
        assertThat(checkpoints()).as("the oldest could not be pruned").hasSize(3);
        assertThat(recorder.sdk("after")).hasSize(3);
        assertThat(recorder.sdk("failed")).isEmpty();
        assertThat(recorder.events(CheckpointEvent.Failed.class)).isEmpty();

        source.revision.set(3);
        int skips = recorder.rearms(RearmReason.SKIPPED).size();
        owner.awaitPosted(1);
        owner.runPending();
        recorder.awaitRearms(RearmReason.SKIPPED, skips + 1);
        assertThat(source.captures).as("revision 3 counts as checkpointed: the tick skips").hasValue(0);

        undeletable.clear();
        write(manager, 4);
        manager.stop();

        assertThat(checkpoints()).as("the next write prunes back to the cap").hasSize(2);
        assertThat(oldest).doesNotExist();
    }

    @Test
    void aPruneThatThrowsAnErrorIsContainedAndTheCheckpointStaysOneSuccess() throws Exception {
        InternalError providerFailure = new InternalError("the file system provider failed");
        CheckpointManager manager = manager(1, file -> {
            throw providerFailure;
        });
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        write(manager, 1);

        Path second = write(manager, 2);
        recorder.awaitRearms(RearmReason.WRITTEN, 2);
        manager.stop();

        assertThat(second).as("the future names the file written").exists();
        assertThat(recorder.events(CheckpointEvent.Written.class)).as("exactly one outcome per write").hasSize(2);
        assertThat(recorder.sdk("failed")).isEmpty();
        assertThat(recorder.events(CheckpointEvent.Failed.class)).isEmpty();
        assertThat(checkpoints()).as("the oldest could not be pruned").hasSize(2);
    }

    private CheckpointManager manager(int cap, CheckpointManager.FileDeleter deleter) {
        return CheckpointTestSupport.manager(new AutoSaveConfig(Duration.ofMillis(40), cap, true),
                new AtomicFileWriter(), new CountingClock(), deleter, CheckpointManager.STOP_WAIT);
    }

    private static Path write(CheckpointManager manager, long revision) throws Exception {
        return manager.writeCheckpoint(document(revision), revision, CheckpointOrigin.AFTER_SAVE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** The checkpoint names on disk, as the grammar reads them. */
    private List<String> checkpoints() throws IOException {
        return checkpointNames(projectDir).stream()
                .filter(name -> CheckpointFileName.parse(name).isPresent())
                .toList();
    }
}
