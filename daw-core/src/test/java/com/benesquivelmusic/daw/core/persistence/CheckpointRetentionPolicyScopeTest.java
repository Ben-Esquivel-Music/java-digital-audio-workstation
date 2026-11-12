package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.CheckpointManager.Capture;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A retention policy delivered to the checkpoint manager prunes checkpoints
 * only, in the order they were written (story 335; story 331, rulings F5,
 * H4, J1 and O): a write never deletes the ordering fact, a writer's temp
 * or a stray file; a checkpoint is ordered by its index where the folder's
 * ordering fact covers it and by the stamp in its name where it does not,
 * never by its file time; the checkpoint just written and the one holding
 * the last checkpointed revision survive every prune, also when every stamp
 * lies in the future; a folder written before story 331, whose indices
 * restarted at every launch, is ordered by its stamps; with a policy set,
 * the cap is not applied; at most one previously refused checkpoint is
 * retried per pass; and a checkpoint whose size cannot be read never
 * abandons the pass.
 */
class CheckpointRetentionPolicyScopeTest {

    /** A cap no scenario here reaches: the policy alone prunes. */
    private static final int UNREACHED_CAP = 100;

    private static final Instant NOW = CountingClock.NOW;
    private static final Instant YESTERDAY = NOW.minus(Duration.ofDays(1));
    private static final Instant TODAY = NOW.minus(Duration.ofHours(3));
    private static final Instant FUTURE = Instant.parse("2099-12-31T23:00:00Z");

    @TempDir
    Path projectDir;

    private final CountingClock clock = new CountingClock();

    /** Every file the prune asked the deleter to delete, by name, in order. */
    private final List<String> deletes = new CopyOnWriteArrayList<>();

    private CheckpointManager manager;

    @AfterEach
    void stop() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void aWritePrunesOnlyCheckpointsNeverTheFactATempOrAStrayFile() throws Exception {
        plant(1, NOW.minus(Duration.ofHours(3)));
        plant(2, NOW.minus(Duration.ofHours(2)));
        start(UNREACHED_CAP, keepRecent(1));
        // Seeding deletes checkpoint-name temps, so these are planted once the generation has started.
        Path directory = projectDir.resolve("checkpoints");
        String temp = "." + CheckpointFileName.format(7, NOW.minus(Duration.ofHours(1)), zone())
                + ".0123456789abcdef.tmp";
        Files.writeString(directory.resolve(temp), "<daw-project");
        Files.writeString(directory.resolve("notes.txt"), "not a checkpoint");

        Path written = write(1);

        assertThat(checkpointNames(projectDir)).as("only the checkpoints were pruned")
                .containsExactlyInAnyOrder(written.getFileName().toString(), temp, "notes.txt");
        assertThat(directory.resolve(CheckpointOrder.FILE_NAME)).as("the ordering fact survives").isRegularFile();
        assertThat(manager.getCheckpointFiles()).containsExactly(written);
        assertThat(deletes).as("nothing but checkpoints reached the deleter")
                .allMatch(name -> CheckpointFileName.parse(name).isPresent());
    }

    @Test
    void aCoveredCheckpointIsOrderedByItsIndexNeverByItsStampOrItsFileTime() throws Exception {
        List<Path> seeded = plantDisagreeingOrders();
        CheckpointOrder.record(projectDir.resolve("checkpoints"), seeded.stream()
                .map(file -> file.getFileName().toString()).collect(Collectors.toSet()));
        start(UNREACHED_CAP, keepRecent(2));

        Path written = write(1);

        assertThat(checkpointNames(projectDir)).as("by index: 003 is the newest seeded checkpoint")
                .containsExactlyInAnyOrder(seeded.get(2).getFileName().toString(), written.getFileName().toString());
    }

    @Test
    void anUncoveredCheckpointIsOrderedByItsStampNeverByItsIndexOrItsFileTime() throws Exception {
        List<Path> seeded = plantDisagreeingOrders();
        start(UNREACHED_CAP, keepRecent(2));

        Path written = write(1);

        assertThat(checkpointNames(projectDir)).as("by stamp: 001 is the newest seeded checkpoint")
                .containsExactlyInAnyOrder(seeded.get(0).getFileName().toString(), written.getFileName().toString());
    }

    @Test
    void theJustWrittenAndTheLastCheckpointedSurviveEveryPruneEvenWithEveryStampInTheFuture() throws Exception {
        for (int i = 0; i < 3; i++) {
            plant(i + 1, FUTURE.plus(Duration.ofMinutes(5L * i)));
        }
        start(UNREACHED_CAP, new BackupRetentionPolicy(0, 0, 0, 0, Duration.ZERO, 0L));
        // Each write is stamped in 2099; its prune runs after the Written event, with the clock back at NOW.
        manager.addEventListener(event -> {
            if (event instanceof CheckpointEvent.Written) {
                clock.set(NOW);
            }
        });

        clock.set(FUTURE.plus(Duration.ofMinutes(59)));
        Path first = manager.writeCheckpoint(document(1), new Capture(1, OptionalLong.of(10)), CheckpointOrigin.USER)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        clock.set(FUTURE.plus(Duration.ofMinutes(59)));
        Path second = manager.writeCheckpoint(document(2), new Capture(2, OptionalLong.of(5)), CheckpointOrigin.USER)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(List.of(first, second)).as("both writes carry a stamp after the prune's now")
                .allMatch(file -> stampOf(file).isAfter(NOW));
        assertThat(checkpointNames(projectDir))
                .as("the one just written and the one holding the greatest journal boundary")
                .containsExactlyInAnyOrder(first.getFileName().toString(), second.getFileName().toString());
        assertThat(manager.getCheckpointFiles()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void aFolderWrittenBeforeStory331IsPrunedByItsStampsNeverTreatingTodaysRestartedIndicesAsOlder() throws Exception {
        List<Path> yesterday = plantRun(1, 50, YESTERDAY);
        List<Path> today = plantRun(1, 3, TODAY);
        makeNewest(yesterday);
        start(UNREACHED_CAP, keepRecent(4));

        Path written = write(1);

        assertThat(CheckpointFileName.parse(written.getFileName().toString()).orElseThrow().index()).isEqualTo(51);
        assertThat(checkpointNames(projectDir)).as("yesterday's checkpoints went, today's 001-003 stayed")
                .containsExactlyInAnyOrderElementsOf(namesOf(today, written));
    }

    @Test
    void aFolderWrittenBeforeStory331WhoseIndicesNeverRepeatIsStillPrunedByItsStamps() throws Exception {
        List<Path> yesterday = plantRun(11, 60, YESTERDAY);
        List<Path> today = plantRun(1, 5, TODAY);
        makeNewest(yesterday);
        start(UNREACHED_CAP, keepRecent(6));

        Path written = write(1);

        assertThat(CheckpointFileName.parse(written.getFileName().toString()).orElseThrow().index()).isEqualTo(61);
        assertThat(checkpointNames(projectDir)).as("yesterday's 011-060 went, today's 001-005 stayed")
                .containsExactlyInAnyOrderElementsOf(namesOf(today, written));
    }

    @Test
    void withAPolicyTheCapIsNotApplied() throws Exception {
        start(1, keepRecent(3));
        List<Path> own = new ArrayList<>();

        for (long revision = 1; revision <= 4; revision++) {
            own.add(write(revision));
        }

        assertThat(manager.getCheckpointFiles()).as("keep recent 3 over a cap of 1")
                .containsExactlyElementsOf(own.subList(1, 4));
    }

    @Test
    void aPolicyPruneRetriesAtMostOneRefusedCheckpointPerPassAndGoesOn() throws Exception {
        Path x = plant(1, NOW.minus(Duration.ofHours(3)));
        Path y = plant(2, NOW.minus(Duration.ofHours(2)));
        Path z = plant(3, NOW.minus(Duration.ofHours(1)));
        Map<Path, Integer> attempts = new HashMap<>();
        start(UNREACHED_CAP, keepRecent(1), file -> {
            int attempt = attempts.merge(file, 1, Integer::sum);
            if (file.equals(y) || (file.equals(x) && attempt <= 2)) {
                throw new AccessDeniedException(file.toString(), null, "held open by a sync client");
            }
            Files.delete(file);
        });
        List<Path> own = new ArrayList<>();
        List<List<String>> passes = new ArrayList<>();

        for (long revision = 1; revision <= 4; revision++) {
            int before = deletes.size();
            own.add(write(revision));
            passes.add(List.copyOf(deletes.subList(before, deletes.size())));
        }

        String xName = x.getFileName().toString();
        String yName = y.getFileName().toString();
        assertThat(passes).as("each pass tries every new discard and at most one earlier refusal, least recently tried")
                .containsExactly(
                        List.of(xName, yName, nameOf(z)),
                        List.of(xName, nameOf(own.get(0))),
                        List.of(yName, nameOf(own.get(1))),
                        List.of(xName, nameOf(own.get(2))));
        assertThat(checkpointNames(projectDir)).containsExactlyInAnyOrder(yName, nameOf(own.get(3)));
        assertThat(manager.getCheckpointFiles()).containsExactly(y, own.get(3));
    }

    /**
     * J1 under a policy: a pass's one retry is the refused checkpoint tried
     * least recently among those that pass's plan discards. X, refused first
     * and then kept by a daily slot of a raised policy, is never tried while
     * kept; it must not hold the retry from Y, refused after it and still
     * discarded, for as long as it holds that slot.
     */
    @Test
    void aRefusedCheckpointThePlanNowKeepsNeverHoldsTheRetryFromOneItDiscards() throws Exception {
        Path x = plant(1, YESTERDAY);
        Path y = plant(2, NOW);
        AtomicBoolean heldOpen = new AtomicBoolean(true);
        start(UNREACHED_CAP, keepRecent(1), file -> {
            if (heldOpen.get() && (file.equals(x) || file.equals(y))) {
                throw new AccessDeniedException(file.toString(), null, "held open by a sync client");
            }
            Files.delete(file);
        });

        Path first = write(1);
        assertThat(deletes).as("both discarded and refused, X tried first").containsExactly(nameOf(x), nameOf(y));

        heldOpen.set(false);
        // Keep daily 2: day 0 goes to the newer checkpoint written today, day 1 to X; Y holds no slot.
        manager.setRetentionPolicy(new BackupRetentionPolicy(1, 0, 2, 0, Duration.ZERO, 0L));
        int before = deletes.size();
        Path second = write(2);

        assertThat(deletes.subList(before, deletes.size()))
                .as("X, kept, is not tried; Y, discarded, is this pass's retry").containsExactly(nameOf(y));
        assertThat(checkpointNames(projectDir))
                .containsExactlyInAnyOrder(nameOf(x), nameOf(first), nameOf(second));
        assertThat(manager.getCheckpointFiles()).containsExactly(x, first, second);
    }

    @Test
    void aCheckpointGoneBeforeItsSizeIsReadLeavesTheIndexAndThePassGoesOn() throws Exception {
        Path oldest = plant(1, NOW.minus(Duration.ofHours(3)));
        Path middle = plant(2, NOW.minus(Duration.ofHours(2)));
        Path gone = plant(3, NOW.minus(Duration.ofHours(1)));
        start(UNREACHED_CAP, keepRecent(2));
        Files.delete(gone);
        manager.setFileSizes(file -> {
            if (file.equals(gone)) {
                throw new NoSuchFileException(file.toString());
            }
            return Files.size(file);
        });

        Path written = write(1);

        assertThat(manager.getCheckpointFiles()).as("the vanished checkpoint left the index and holds no slot")
                .containsExactly(middle, written);
        assertThat(deletes).as("the pass deleted the other discard and never asked to delete the gone one")
                .containsExactly(oldest.getFileName().toString());
        assertThat(checkpointNames(projectDir)).containsExactlyInAnyOrder(nameOf(middle), nameOf(written));
    }

    @Test
    void aCheckpointWhoseSizeCannotBeReadIsPlannedLikeAnyOtherAndThePassGoesOn() throws Exception {
        List<Path> seeded = plantRun(1, 3, NOW.minus(Duration.ofHours(3)));
        Path unreadable = seeded.get(1);
        start(UNREACHED_CAP, keepRecent(1));
        manager.setFileSizes(file -> {
            if (file.equals(unreadable)) {
                throw new AccessDeniedException(file.toString());
            }
            return Files.size(file);
        });

        Path written = write(1);

        assertThat(deletes).as("discarded by recency, in the prune's order, with the pass going on")
                .containsExactlyElementsOf(namesOf(seeded));
        assertThat(checkpointNames(projectDir)).containsExactly(nameOf(written));
    }

    @Test
    void aCheckpointWhoseSizeCannotBeReadCountsAsZeroBytes() throws Exception {
        List<Path> seeded = plantRun(1, 3, NOW.minus(Duration.ofHours(3)));
        Path unreadable = seeded.get(1);
        long seededSize = Files.size(seeded.get(2));
        long writtenSize = document(1).getBytes(StandardCharsets.UTF_8).length;
        // Room for the written file and the newest seeded one, with one byte to spare: 002 fits only at 0 bytes.
        start(UNREACHED_CAP, new BackupRetentionPolicy(3, 0, 0, 0, Duration.ZERO, writtenSize + seededSize + 1));
        manager.setFileSizes(file -> {
            if (file.equals(unreadable)) {
                throw new AccessDeniedException(file.toString());
            }
            return Files.size(file);
        });

        Path written = write(1);

        assertThat(checkpointNames(projectDir))
                .containsExactlyInAnyOrder(nameOf(unreadable), nameOf(seeded.get(2)), nameOf(written));
    }

    @Test
    void aSparedCheckpointWhoseSizeCannotBeReadStaysSpared() throws Exception {
        plantRun(1, 2, NOW.minus(Duration.ofHours(3)));
        start(UNREACHED_CAP, new BackupRetentionPolicy(0, 0, 0, 0, Duration.ZERO, 0L));
        manager.setFileSizes(file -> {
            if (CheckpointFileName.parse(file.getFileName().toString()).orElseThrow().index() == 3) {
                throw new NoSuchFileException(file.toString());
            }
            return Files.size(file);
        });

        Path written = write(1);

        assertThat(manager.getCheckpointFiles()).containsExactly(written);
        assertThat(checkpointNames(projectDir)).containsExactly(nameOf(written));
    }

    /**
     * Plants 001-003 whose three orders disagree: by index 003 is the newest; by stamp 001 (its clock ran ahead);
     * by file time 002 (a copy rewrote it).
     */
    private List<Path> plantDisagreeingOrders() throws IOException {
        Path one = plant(1, NOW.minus(Duration.ofHours(1)));
        Path two = plant(2, NOW.minus(Duration.ofHours(3)));
        Path three = plant(3, NOW.minus(Duration.ofHours(2)));
        Instant real = Instant.now();
        Files.setLastModifiedTime(one, FileTime.from(real.minus(Duration.ofDays(2))));
        Files.setLastModifiedTime(two, FileTime.from(real.plus(Duration.ofDays(1))));
        Files.setLastModifiedTime(three, FileTime.from(real.minus(Duration.ofDays(3))));
        return List.of(one, two, three);
    }

    /** Plants checkpoints {@code from}-{@code to} of one earlier launch, a minute apart from {@code first}. */
    private List<Path> plantRun(long from, long to, Instant first) throws IOException {
        List<Path> run = new ArrayList<>();
        for (long index : LongStream.rangeClosed(from, to).toArray()) {
            run.add(plant(index, first.plus(Duration.ofMinutes(index - from))));
        }
        return run;
    }

    /** Makes {@code files} the newest by file time, as a copy or a sync client would. */
    private static void makeNewest(List<Path> files) throws IOException {
        Instant real = Instant.now().plus(Duration.ofDays(1));
        for (int i = 0; i < files.size(); i++) {
            Files.setLastModifiedTime(files.get(i), FileTime.from(real.plusSeconds(i)));
        }
    }

    private Path plant(long index, Instant stamp) throws IOException {
        Path directory = Files.createDirectories(projectDir.resolve("checkpoints"));
        return Files.writeString(directory.resolve(CheckpointFileName.format(index, stamp, zone())), "an earlier session");
    }

    private void start(int cap, BackupRetentionPolicy policy) throws Exception {
        start(cap, policy, Files::delete);
    }

    private void start(int cap, BackupRetentionPolicy policy, CheckpointManager.FileDeleter deleter) throws Exception {
        manager = CheckpointTestSupport.manager(new AutoSaveConfig(Duration.ofHours(1), cap, false),
                new AtomicFileWriter(), clock, file -> {
                    deletes.add(file.getFileName().toString());
                    deleter.delete(file);
                }, CheckpointManager.STOP_WAIT);
        manager.setRetentionPolicy(policy);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private Path write(long revision) throws Exception {
        return manager.writeCheckpoint(document(revision), revision, CheckpointOrigin.USER)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static BackupRetentionPolicy keepRecent(int count) {
        return new BackupRetentionPolicy(count, 0, 0, 0, Duration.ZERO, 0L);
    }

    private static ZoneId zone() {
        return ZoneId.systemDefault();
    }

    private static Instant stampOf(Path file) {
        return CheckpointFileName.parse(file.getFileName().toString()).orElseThrow().instantIn(zone());
    }

    private static String nameOf(Path file) {
        return file.getFileName().toString();
    }

    private static List<String> namesOf(List<Path> files, Path... more) {
        List<String> names = new ArrayList<>(files.stream().map(CheckpointRetentionPolicyScopeTest::nameOf).toList());
        for (Path file : more) {
            names.add(nameOf(file));
        }
        return names;
    }
}
