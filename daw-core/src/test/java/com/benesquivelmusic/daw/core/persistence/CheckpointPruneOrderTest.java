package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prune after a write keeps that write and deletes the seeded
 * checkpoints before this generation's own, then this generation's own in
 * the order they were written (story 331, ruling H4). The stamp in a name
 * can contradict the order the files were written in — a folder synced from
 * a later time zone, a clock that ran ahead, a clock set back — and the
 * newest writes still survive; the display order stays the stamps'.
 */
class CheckpointPruneOrderTest {

    private static final int CAP = 3;

    @TempDir
    Path projectDir;

    private final CountingClock clock = new CountingClock();
    private CheckpointManager manager;

    @AfterEach
    void stop() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void aWriteIntoACapOfFutureStampedCheckpointsKeepsItsFileForEverySameRevisionRequest() throws Exception {
        List<String> future = seed("checkpoint-001-20991231T230000.daw", "checkpoint-002-20991231T230500.daw",
                "checkpoint-003-20991231T231000.daw");
        start(CAP);

        Path written = write(1, CheckpointOrigin.AFTER_SAVE);
        Path afterSave = write(1, CheckpointOrigin.AFTER_SAVE);
        Path safetyCopy = write(1, CheckpointOrigin.SAFETY_COPY);

        assertThat(written).as("the file the write returned").exists();
        assertThat(afterSave).as("a same-revision save's checkpoint names it").isEqualTo(written);
        assertThat(safetyCopy).as("so does a same-revision safety copy").isEqualTo(written);
        assertThat(checkpointNames(projectDir)).as("the oldest seeded checkpoint went instead")
                .containsExactlyInAnyOrder(future.get(1), future.get(2), written.getFileName().toString());
    }

    @Test
    void afterACapOfWritesEverySeededCheckpointIsGoneAndEveryOwnWriteIsKept() throws Exception {
        seed("checkpoint-007-20991231T230000.daw", "checkpoint-008-20250101T090000.daw",
                "checkpoint-009-20991231T231000.daw");
        start(CAP);
        List<String> own = new ArrayList<>();

        for (long revision = 1; revision <= CAP; revision++) {
            own.add(write(revision, CheckpointOrigin.AFTER_SAVE).getFileName().toString());
        }

        assertThat(checkpointNames(projectDir)).containsExactlyInAnyOrderElementsOf(own);
        assertThat(manager.getCheckpointFiles()).extracting(file -> file.getFileName().toString())
                .containsExactlyInAnyOrderElementsOf(own);
    }

    @Test
    void withTheClockSteppingBackOwnWritesArePrunedInTheOrderTheyWereWritten() throws Exception {
        start(2);
        List<String> own = new ArrayList<>();

        for (int write = 0; write < 5; write++) {
            clock.set(CountingClock.NOW.minus(Duration.ofHours(write)));
            own.add(write(write + 1, CheckpointOrigin.AFTER_SAVE).getFileName().toString());

            assertThat(checkpointNames(projectDir)).as("after write %s", write + 1)
                    .containsExactlyInAnyOrderElementsOf(own.subList(Math.max(0, own.size() - 2), own.size()));
        }
        assertThat(manager.getCheckpointFiles()).extracting(file -> file.getFileName().toString())
                .as("the display order is still the stamps'").containsExactly(own.get(4), own.get(3));
    }

    /** Plants checkpoints an earlier run, or a synced folder, left. */
    private List<String> seed(String... names) throws IOException {
        Path directory = Files.createDirectories(projectDir.resolve("checkpoints"));
        for (String name : names) {
            Files.writeString(directory.resolve(name), "an earlier session");
        }
        return List.of(names);
    }

    private void start(int cap) throws Exception {
        manager = CheckpointTestSupport.manager(new AutoSaveConfig(Duration.ofHours(1), cap, true),
                new AtomicFileWriter(), clock, Files::delete, CheckpointManager.STOP_WAIT);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private Path write(long revision, CheckpointOrigin origin) throws Exception {
        return manager.writeCheckpoint(document(revision), revision, origin).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
