package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.Recorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A checkpoint that refuses deletion — held open by an antivirus scanner,
 * the search indexer or a sync client — never makes the prune delete newer
 * checkpoints to make room it cannot get (story 331, ruling J1): the prune
 * skips it and goes on, the cap does not count it, each later prune retries
 * at most one such file, the one tried least recently, and a generation logs
 * its first refusal at {@code WARNING} and the rest at {@code FINE}. Anything
 * the delete throws, an {@link Error} too, is a refusal. The checkpoint just
 * written stays a success and no listener hears of a refusal.
 */
class CheckpointDeleteRefusalTest {

    private static final int CAP = 3;

    private static final String STUCK = "checkpoint-001-20250101T000000.daw";

    private static final String REASON = "held open by a sync client";

    @TempDir
    Path projectDir;

    /** Every file the prune asked the deleter to delete, in order. */
    private final List<Path> deletes = new CopyOnWriteArrayList<>();

    /** The manager's log records that name this test's project folder. */
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    private final Handler projectLog = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            String message = logRecord.getMessage();
            if (message != null && message.contains(projectDir.toString())) {
                records.add(logRecord);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private final Logger managerLog = Logger.getLogger(CheckpointManager.class.getName());
    private Level levelBefore;
    private CheckpointManager manager;
    private Recorder recorder;

    @BeforeEach
    void listenAtFine() {
        levelBefore = managerLog.getLevel();
        managerLog.setLevel(Level.FINE);
        managerLog.addHandler(projectLog);
    }

    @AfterEach
    void stop() {
        try {
            if (manager != null) {
                manager.stop();
            }
        } finally {
            managerLog.removeHandler(projectLog);
            managerLog.setLevel(levelBefore);
        }
    }

    @Test
    void aSeededCheckpointThatAlwaysRefusesStaysBesideTheCapAndIsTriedOncePerPass() throws Exception {
        Path stuck = seed(STUCK);
        start(CAP, file -> {
            deletes.add(file);
            if (file.equals(stuck)) {
                throw refusal(file);
            }
            Files.delete(file);
        });
        List<Path> own = new ArrayList<>();
        List<List<Path>> passes = new ArrayList<>();

        for (long revision = 1; revision <= 12; revision++) {
            int before = deletes.size();
            own.add(write(revision));
            passes.add(List.copyOf(deletes.subList(before, deletes.size())));
        }

        List<Path> kept = List.of(stuck, own.get(9), own.get(10), own.get(11));
        assertThat(checkpointNames(projectDir)).as("the stuck file and the three newest own writes")
                .containsExactlyInAnyOrderElementsOf(kept.stream().map(file -> file.getFileName().toString())
                        .toList());
        assertThat(manager.getCheckpointFiles()).containsExactlyInAnyOrderElementsOf(kept);
        assertThat(recorder.events(CheckpointEvent.Written.class)).hasSize(12);
        assertThat(recorder.events(CheckpointEvent.Failed.class)).isEmpty();
        assertThat(recorder.sdk("failed")).isEmpty();
        for (int write = 0; write < 12; write++) {
            List<Path> expected = switch (write) {
                case 0, 1 -> List.of();
                case 2 -> List.of(stuck);
                default -> List.of(stuck, own.get(write - 3));
            };
            assertThat(passes.get(write))
                    .as("write %s: the stuck file once per pass over the cap, then the oldest own write", write + 1)
                    .isEqualTo(expected);
        }
        List<LogRecord> refusals = recordsNaming(stuck);
        assertThat(refusals).extracting(LogRecord::getLevel).as("one WARNING, then FINE")
                .containsExactly(Level.WARNING, Level.FINE, Level.FINE, Level.FINE, Level.FINE, Level.FINE,
                        Level.FINE, Level.FINE, Level.FINE, Level.FINE);
        LogRecord first = refusals.getFirst();
        assertThat(first.getMessage()).isEqualTo("Could not delete the checkpoint " + stuck + ": "
                + first.getThrown() + ". checkpoints/ holds more than 3 checkpoints until it can be deleted; it is"
                + " retried after later checkpoints, and further refusals this session are logged at FINE");
        assertThat(first.getThrown()).isInstanceOf(AccessDeniedException.class);
        assertThat(records).filteredOn(logRecord -> logRecord.getLevel().intValue() >= Level.WARNING.intValue())
                .as("the only warning of the session").containsExactly(first);
    }

    @Test
    void aCheckpointThatRefusesOnceIsDeletedByTheNextPassAndTheFolderIsBackToTheCap() throws Exception {
        Path stuck = seed(STUCK);
        AtomicBoolean refuseOnce = new AtomicBoolean(true);
        start(CAP, file -> {
            deletes.add(file);
            if (file.equals(stuck) && refuseOnce.compareAndSet(true, false)) {
                throw refusal(file);
            }
            Files.delete(file);
        });
        List<Path> own = new ArrayList<>();
        for (long revision = 1; revision <= CAP; revision++) {
            own.add(write(revision));
        }
        assertThat(checkpointNames(projectDir)).as("the refused file stays beside the cap").hasSize(CAP + 1);
        int before = deletes.size();

        own.add(write(CAP + 1));

        assertThat(deletes.subList(before, deletes.size())).as("the retry, then the oldest own write")
                .containsExactly(stuck, own.getFirst());
        assertThat(stuck).doesNotExist();
        assertThat(checkpointNames(projectDir)).as("back to the cap")
                .containsExactlyInAnyOrderElementsOf(own.subList(1, own.size()).stream()
                        .map(file -> file.getFileName().toString()).toList());
        assertThat(manager.getCheckpointFiles()).containsExactlyInAnyOrderElementsOf(own.subList(1, own.size()));
        List<LogRecord> logged = recordsNaming(stuck);
        assertThat(logged).extracting(LogRecord::getLevel).as("the refusal, then the deletion at FINE")
                .containsExactly(Level.WARNING, Level.FINE);
        assertThat(logged.getLast().getMessage())
                .isEqualTo("The checkpoint " + stuck + ", which refused deletion before, is gone");
    }

    @Test
    void withEveryDeleteRefusedEveryWriteSucceedsAndEachPassTriesAtMostTwoFiles() throws Exception {
        start(CAP, file -> {
            deletes.add(file);
            throw refusal(file);
        });
        Map<Path, Integer> lastTried = new HashMap<>();
        int writes = 10;

        for (int write = 1; write <= writes; write++) {
            int before = deletes.size();
            write(write);
            List<Path> pass = List.copyOf(deletes.subList(before, deletes.size()));

            assertThat(checkpointNames(projectDir)).as("after write %s the folder grew by one", write)
                    .hasSize(write);
            assertThat(pass).as("write %s: at most two deletes, never the same file twice", write)
                    .hasSizeLessThanOrEqualTo(2).doesNotHaveDuplicates();
            if (write > CAP + 1) {
                Path leastRecentlyTried = lastTried.entrySet().stream().min(Map.Entry.comparingByValue())
                        .orElseThrow().getKey();
                assertThat(pass).as("write %s retries the refused file tried least recently, then one new file",
                        write).hasSize(2).first().isEqualTo(leastRecentlyTried);
                assertThat(lastTried).as("write %s's second file was never tried before", write)
                        .doesNotContainKey(pass.getLast());
            }
            int attempt = before;
            for (Path tried : pass) {
                lastTried.put(tried, attempt++);
            }
        }

        assertThat(recorder.events(CheckpointEvent.Written.class)).hasSize(writes);
        assertThat(recorder.events(CheckpointEvent.Failed.class)).isEmpty();
        assertThat(new HashSet<>(deletes)).as("every own write but the newest cap were tried")
                .hasSize(writes - CAP);
        assertThat(records).filteredOn(logRecord -> logRecord.getLevel().intValue() >= Level.WARNING.intValue())
                .as("one warning for the whole session").hasSize(1);
    }

    @Test
    void aDeleteThatThrowsAnErrorIsARefusalAndThePruneGoesOn() throws Exception {
        Path stuck = seed(STUCK);
        InternalError broken = new InternalError("the delete throws an Error");
        start(CAP, file -> {
            deletes.add(file);
            if (file.equals(stuck)) {
                throw broken;
            }
            Files.delete(file);
        });
        List<Path> own = new ArrayList<>();

        for (long revision = 1; revision <= 8; revision++) {
            own.add(write(revision));
        }

        List<Path> kept = List.of(stuck, own.get(5), own.get(6), own.get(7));
        assertThat(checkpointNames(projectDir)).as("the cap plus the file whose delete threw")
                .containsExactlyInAnyOrderElementsOf(kept.stream().map(file -> file.getFileName().toString())
                        .toList());
        assertThat(manager.getCheckpointFiles()).containsExactlyInAnyOrderElementsOf(kept);
        assertThat(recorder.events(CheckpointEvent.Written.class)).hasSize(8);
        assertThat(recorder.events(CheckpointEvent.Failed.class)).isEmpty();
        List<LogRecord> refusals = recordsNaming(stuck);
        assertThat(refusals).extracting(LogRecord::getLevel).as("one WARNING, then FINE for each later pass")
                .containsExactly(Level.WARNING, Level.FINE, Level.FINE, Level.FINE, Level.FINE, Level.FINE);
        LogRecord first = refusals.getFirst();
        assertThat(first.getMessage()).isEqualTo("Could not delete the checkpoint " + stuck + ": " + broken
                + ". checkpoints/ holds more than 3 checkpoints until it can be deleted; it is"
                + " retried after later checkpoints, and further refusals this session are logged at FINE");
        assertThat(first.getThrown()).isSameAs(broken);
        assertThat(records).filteredOn(logRecord -> logRecord.getLevel().intValue() >= Level.WARNING.intValue())
                .as("the only warning of the session").containsExactly(first);
    }

    /**
     * A failure outside the delete of one file ends the pass: here the
     * refusal's own {@code toString()} throws while its WARNING is written,
     * which only the prune's outer catch contains. The checkpoint just
     * written stays a success, re-arms the cadence, and the pass is retried
     * after the next checkpoint (ruling G13 #1).
     */
    @Test
    void aFailureOutsideTheDeleteOfOneFileEndsThePassWithAWarningAndTheCheckpointStaysASuccess()
            throws Exception {
        IllegalStateException unprintable = new IllegalStateException("the refusal cannot be printed");
        start(1, file -> {
            deletes.add(file);
            throw new AccessDeniedException(file.toString(), null, REASON) {
                @Override
                public String toString() {
                    throw unprintable;
                }
            };
        });

        Path first = write(1);
        Path second = write(2);
        recorder.awaitRearms(CheckpointEvent.RearmReason.WRITTEN, 2);

        assertThat(deletes).as("the second write's prune reached the first").containsExactly(first);
        assertThat(recorder.events(CheckpointEvent.Written.class)).extracting(CheckpointEvent.Written::file)
                .as("two checkpoints written, each a success").containsExactly(first, second);
        assertThat(recorder.events(CheckpointEvent.Failed.class)).isEmpty();
        assertThat(recorder.rearms(CheckpointEvent.RearmReason.WRITTEN)).as("a re-arm after each").hasSize(2);
        assertThat(records).filteredOn(logRecord -> logRecord.getLevel().intValue() >= Level.WARNING.intValue())
                .as("the outer catch's warning, the only one").singleElement().satisfies(logRecord -> {
                    assertThat(logRecord.getMessage()).isEqualTo("Pruning the checkpoints of " + projectDir
                            + " failed; it is retried after the next checkpoint");
                    assertThat(logRecord.getThrown()).isSameAs(unprintable);
                });
        assertThat(checkpointNames(projectDir)).as("nothing was deleted").hasSize(2);
    }

    @Test
    void aSecondStartOnTheSameFolderLogsItsFirstRefusalAtWarningAgain() throws Exception {
        Path stuck = seed(STUCK);
        start(1, file -> {
            deletes.add(file);
            if (file.equals(stuck)) {
                throw refusal(file);
            }
            Files.delete(file);
        });
        write(1);
        write(2);

        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        write(3);

        assertThat(recordsNaming(stuck)).extracting(LogRecord::getLevel)
                .as("each generation's first refusal is a warning").containsExactly(Level.WARNING, Level.FINE,
                        Level.WARNING);
        assertThat(checkpointNames(projectDir)).hasSize(2).contains(STUCK);
    }

    /** Plants a checkpoint an earlier session left. */
    private Path seed(String name) throws IOException {
        Path directory = Files.createDirectories(projectDir.resolve("checkpoints"));
        return Files.writeString(directory.resolve(name), "an earlier session");
    }

    private void start(int cap, CheckpointManager.FileDeleter deleter) throws Exception {
        manager = CheckpointTestSupport.manager(new AutoSaveConfig(Duration.ofHours(1), cap, true),
                new AtomicFileWriter(), new CountingClock(), deleter, CheckpointManager.STOP_WAIT);
        recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** A user checkpoint, so no same-revision skip ever applies; returns once its prune ran. */
    private Path write(long revision) throws Exception {
        return manager.writeCheckpoint(document(revision), revision, CheckpointOrigin.USER)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static AccessDeniedException refusal(Path file) {
        return new AccessDeniedException(file.toString(), null, REASON);
    }

    /** The log records that name {@code file}, in order. */
    private List<LogRecord> recordsNaming(Path file) {
        return records.stream().filter(logRecord -> logRecord.getMessage().contains(file.toString())).toList();
    }
}
