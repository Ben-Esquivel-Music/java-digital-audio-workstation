package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.Recorder;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.persistence.journal.RecoveryScanner;
import com.benesquivelmusic.daw.core.persistence.journal.RecoverySummary;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.core.session.WorkingSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A checkpoint write that dies between its forced temp and its publish
 * (story 331, crash-shaped atomicity, checkpoint half; Persistence Integrity
 * book §4.1, §6.4): the existing checkpoints stay byte for byte as they
 * were, nothing new and no temp is left, and the injected exception reaches
 * the listeners — when the atomic move is refused and the plain-replace
 * fallback fails too, the fallback's, with the refusal suppressed. A torn
 * temp a hard kill left is a checkpoint to no reader.
 */
class CheckpointCrashWindowTest {

    private static final String OLDER = "checkpoint-001-20260101T090000.daw";
    private static final String NEWER = "checkpoint-002-20260101T100000.daw";
    private static final String TORN_TEMP = ".checkpoint-009-20261231T235959.daw.00000000deadbeef.tmp";

    @TempDir
    Path projectDir;

    private Path checkpoints;

    @BeforeEach
    void twoCheckpointsOnDisk() throws IOException {
        checkpoints = Files.createDirectories(projectDir.resolve("checkpoints"));
        Files.writeString(checkpoints.resolve(OLDER), document(1));
        Files.writeString(checkpoints.resolve(NEWER), document(2));
    }

    @Test
    void aCheckpointThatFailsBeforeItsPublishLeavesTheExistingOnesExactlyAsTheyWere() throws Exception {
        IOException crash = new IOException("the process died after the temp was forced");
        AtomicFileWriter crashing = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, target, options) -> {
                    if (CheckpointFileName.parse(target.getFileName().toString()).isEmpty()) {
                        AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
                        return;
                    }
                    assertThat(temp).as("the forced temp is complete when the crash comes").hasContent(document(3));
                    throw crash;
                });
        CheckpointManager manager = CheckpointTestSupport.manager(new AutoSaveConfig(Duration.ofHours(1), 10, true),
                crashing, new CountingClock(), Files::delete, CheckpointManager.STOP_WAIT);
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        byte[] older = Files.readAllBytes(checkpoints.resolve(OLDER));
        byte[] newer = Files.readAllBytes(checkpoints.resolve(NEWER));

        CompletableFuture<Path> failed = manager.writeCheckpoint(document(3), 3, CheckpointOrigin.AFTER_SAVE);
        assertThat(failed).failsWithin(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        manager.stop();

        assertThat(failed.exceptionNow()).isSameAs(crash);
        assertThat(recorder.sdk("failed")).singleElement()
                .satisfies(call -> assertThat(call.cause()).isSameAs(crash));
        assertThat(checkpointNames(projectDir)).as("no new checkpoint and no temp").containsExactly(OLDER, NEWER);
        assertThat(Files.readAllBytes(checkpoints.resolve(OLDER))).isEqualTo(older);
        assertThat(Files.readAllBytes(checkpoints.resolve(NEWER))).isEqualTo(newer);
        assertThat(manager.getCheckpointCount()).as("the failed write used no index").isEqualTo(2);
    }

    @Test
    void aCheckpointWhoseAtomicMoveAndFallbackBothFailReportsTheFallbacksFailureAndLeavesNoTemp()
            throws Exception {
        AtomicMoveNotSupportedException notAtomic = new AtomicMoveNotSupportedException("temp", "checkpoint",
                "the volume refuses atomic moves");
        IOException fallbackFailure = new IOException("the plain-replace fallback failed too");
        List<List<CopyOption>> attempts = new CopyOnWriteArrayList<>();
        AtomicFileWriter refusing = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, target, options) -> {
                    if (CheckpointFileName.parse(target.getFileName().toString()).isEmpty()) {
                        AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
                        return;
                    }
                    attempts.add(List.of(options));
                    throw Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE) ? notAtomic
                            : fallbackFailure;
                });
        CheckpointManager manager = CheckpointTestSupport.manager(new AutoSaveConfig(Duration.ofHours(1), 10, true),
                refusing, new CountingClock(), Files::delete, CheckpointManager.STOP_WAIT);
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        byte[] older = Files.readAllBytes(checkpoints.resolve(OLDER));
        byte[] newer = Files.readAllBytes(checkpoints.resolve(NEWER));

        CompletableFuture<Path> failed = manager.writeCheckpoint(document(3), 3, CheckpointOrigin.AFTER_SAVE);
        assertThat(failed).failsWithin(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        manager.stop();

        assertThat(failed.exceptionNow()).as("the fallback's failure, as thrown").isSameAs(fallbackFailure);
        assertThat(fallbackFailure.getSuppressed()).as("the atomic move's refusal").containsExactly(notAtomic);
        assertThat(attempts).containsExactly(
                List.of(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                List.of(StandardCopyOption.REPLACE_EXISTING));
        assertThat(recorder.sdk("failed")).singleElement()
                .satisfies(call -> assertThat(call.cause()).isSameAs(fallbackFailure));
        assertThat(checkpointNames(projectDir)).as("no new checkpoint and no temp").containsExactly(OLDER, NEWER);
        assertThat(Files.readAllBytes(checkpoints.resolve(OLDER))).isEqualTo(older);
        assertThat(Files.readAllBytes(checkpoints.resolve(NEWER))).isEqualTo(newer);
    }

    @Test
    void aTornCheckpointTempIsListedByNoReader() throws Exception {
        Files.writeString(checkpoints.resolve(TORN_TEMP), "<daw-project version=\"2\"><tracks>");

        Optional<RecoverySummary> recovery = new RecoveryScanner(ZoneId.of("UTC"))
                .scan(ProjectContext.forProject(projectDir));
        List<WorkingSession> history = new SessionManager().history(projectDir);
        CheckpointManager manager = CheckpointTestSupport.manager(new AutoSaveConfig(Duration.ofHours(1), 10, true),
                new AtomicFileWriter(), new CountingClock(), Files::delete, CheckpointManager.STOP_WAIT);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        manager.stop();

        assertThat(recovery).hasValueSatisfying(summary -> {
            assertThat(summary.checkpointFileName()).isEqualTo(NEWER);
            assertThat(summary.checkpointIndex()).isEqualTo(2);
        });
        assertThat(history).flatExtracting(WorkingSession::checkpointIds).containsExactly(OLDER, NEWER);
        assertThat(manager.getCheckpointFiles()).extracting(path -> path.getFileName().toString())
                .containsExactly(OLDER, NEWER);
        assertThat(manager.getCheckpointCount()).as("index 9 is no checkpoint's").isEqualTo(2);
    }
}
