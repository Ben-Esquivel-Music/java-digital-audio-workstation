package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.Recorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Across a restart the prune and the newest seeded checkpoint follow the
 * order the checkpoints were written in (story 335; story 331, rulings F5,
 * H4, K1 and O; story 332's recorded ordering fact): the seeded checkpoints
 * the fact covers are ordered by their index and come after every other
 * seeded checkpoint, which keeps the stamp order. Each restart is a new
 * manager and a new start over the same folder, on the clock seam; no
 * retention policy is set, so the cap prunes.
 */
class CheckpointRestartOrderTest {

    private static final Instant NOW = CountingClock.NOW;
    private static final Instant CLOCK_SET_BACK = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant YESTERDAY = NOW.minus(Duration.ofDays(1));
    private static final Instant TODAY = NOW.minus(Duration.ofHours(3));

    @TempDir
    Path projectDir;

    private final CountingClock clock = new CountingClock();
    private CheckpointManager manager;
    private Recorder recorder;

    @AfterEach
    void stop() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void aRunWrittenWithTheClockSetBackIsStillTheNewestAfterARestart() throws Exception {
        List<Path> written = new ArrayList<>();
        restart(5);
        for (long revision = 1; revision <= 3; revision++) {
            written.add(write(revision));
        }
        clock.set(CLOCK_SET_BACK);
        restart(5);
        for (long revision = 4; revision <= 6; revision++) {
            written.add(write(revision));
        }
        clock.set(NOW);
        restart(5);

        assertThat(newestSeeded()).as("checkpoint 6, written last though stamped 2000-01-01")
                .contains(written.get(5));
        assertThat(checkpointNames(projectDir)).containsExactlyInAnyOrderElementsOf(names(written.subList(1, 6)));

        Path seventh = write(7);

        assertThat(checkpointNames(projectDir)).as("checkpoint 2 went; 4-6 stayed")
                .containsExactlyInAnyOrderElementsOf(names(List.of(written.get(2), written.get(3), written.get(4),
                        written.get(5), seventh)));
    }

    @Test
    void aFolderWrittenBeforeStory331WithRepeatedIndicesSeedsAndPrunesByItsStamps() throws Exception {
        List<Path> yesterday = plantRun(1, 50, YESTERDAY);
        List<Path> today = plantRun(1, 3, TODAY);
        restart(50);

        assertThat(newestSeeded()).as("today's 003").contains(today.get(2));

        Path written = write(1);

        assertThat(CheckpointFileName.parse(written.getFileName().toString()).orElseThrow().index()).isEqualTo(51);
        assertThat(checkpointNames(projectDir)).as("yesterday's 001-004 went, none of today's")
                .doesNotContainAnyElementsOf(names(yesterday.subList(0, 4)))
                .containsAll(names(yesterday.subList(4, 50)))
                .containsAll(names(today))
                .contains(written.getFileName().toString())
                .hasSize(50);
    }

    @Test
    void aFolderWrittenBeforeStory331WhoseIndicesNeverRepeatSeedsAndPrunesByItsStampsThenByTheFact()
            throws Exception {
        List<Path> yesterday = plantRun(11, 60, YESTERDAY);
        List<Path> today = plantRun(1, 5, TODAY);
        restart(50);

        assertThat(newestSeeded()).as("today's 005").contains(today.get(4));

        clock.set(CLOCK_SET_BACK);
        Path sixtyFirst = write(1);

        assertThat(CheckpointFileName.parse(sixtyFirst.getFileName().toString()).orElseThrow().index())
                .isEqualTo(61);
        assertThat(checkpointNames(projectDir)).as("yesterday's 011-016 went, none of today's")
                .doesNotContainAnyElementsOf(names(yesterday.subList(0, 6)))
                .containsAll(names(yesterday.subList(6, 50)))
                .containsAll(names(today))
                .contains(sixtyFirst.getFileName().toString())
                .hasSize(50);

        clock.set(NOW);
        restart(50);

        assertThat(newestSeeded()).as("061, which the ordering fact covers, though stamped 2000-01-01")
                .contains(sixtyFirst);

        Path next = write(2);

        assertThat(checkpointNames(projectDir)).as("yesterday's 017 went; 061 stayed")
                .doesNotContain(yesterday.get(6).getFileName().toString())
                .containsAll(names(yesterday.subList(7, 50)))
                .containsAll(names(today))
                .contains(sixtyFirst.getFileName().toString(), next.getFileName().toString())
                .hasSize(50);
    }

    /** Stops the running manager, if any, and starts a new one over the same folder with {@code cap}. */
    private void restart(int cap) throws Exception {
        if (manager != null) {
            manager.stop().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        manager = CheckpointTestSupport.manager(new AutoSaveConfig(Duration.ofHours(1), cap, false),
                new AtomicFileWriter(), clock, Files::delete, CheckpointManager.STOP_WAIT);
        recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private Optional<Path> newestSeeded() {
        List<CheckpointEvent.Seeded> seeded = recorder.events(CheckpointEvent.Seeded.class);
        assertThat(seeded).as("one seeding per start").hasSize(1);
        return seeded.getFirst().newest();
    }

    private Path write(long revision) throws Exception {
        return manager.writeCheckpoint(document(revision), revision, CheckpointOrigin.USER)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Plants checkpoints {@code from}-{@code to} of one launch before story 331, a minute apart from {@code first}:
     * no ordering fact is recorded for them.
     */
    private List<Path> plantRun(long from, long to, Instant first) throws IOException {
        Path directory = Files.createDirectories(projectDir.resolve("checkpoints"));
        List<Path> run = new ArrayList<>();
        for (long index = from; index <= to; index++) {
            Instant stamp = first.plus(Duration.ofMinutes(index - from));
            run.add(Files.writeString(directory.resolve(CheckpointFileName.format(index, stamp, ZoneId.systemDefault())),
                    "an earlier launch"));
        }
        return run;
    }

    private static List<String> names(List<Path> files) {
        return files.stream().map(file -> file.getFileName().toString()).toList();
    }
}
