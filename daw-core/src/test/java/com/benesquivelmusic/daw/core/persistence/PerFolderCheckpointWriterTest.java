package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One checkpoint writer per project folder (story 333; Persistence Integrity
 * book §4.2, §6.1). A write held inside its atomic write on one folder — a
 * stalled volume — holds neither the seeding nor the writes of another
 * folder's generation. Two generations of one folder — by the same path or by
 * another name of it — share that folder's writer: the later generation
 * seeds only after the write still held there landed, and before any write
 * of its own, so it never allocates the index of a checkpoint still being
 * written. A seeding reads the fingerprint of its own folder only.
 */
class PerFolderCheckpointWriterTest {

    @TempDir
    Path workspace;

    private Path songA;
    private Path songB;
    private final AtomicBoolean holdTheNextWriteInSongA = new AtomicBoolean();
    private final CountDownLatch heldEntered = new CountDownLatch(1);
    private final CountDownLatch heldReleased = new CountDownLatch(1);
    /** A further write held in Song A's folder, once the first hold was used: armed by a test that holds two. */
    private final java.util.concurrent.atomic.AtomicReference<HeldWrite> laterHoldInSongA =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** Every later hold a test made, let go after it. */
    private final List<HeldWrite> laterHolds = new CopyOnWriteArrayList<>();
    private final List<CheckpointManager> managers = new CopyOnWriteArrayList<>();

    /** A hold of one checkpoint publish: entered once the publish reached the mover; let go by the test. */
    private record HeldWrite(CountDownLatch entered, CountDownLatch released) {
        HeldWrite() {
            this(new CountDownLatch(1), new CountDownLatch(1));
        }
    }

    /** Holds the next checkpoint publish in Song A's folder inside its atomic write, until released. */
    private final AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
            (temp, target, options) -> {
                if (isACheckpointIn(songA, target) && holdTheNextWriteInSongA.compareAndSet(true, false)) {
                    heldEntered.countDown();
                    awaitTheRelease(heldReleased);
                } else if (isACheckpointIn(songA, target)) {
                    HeldWrite later = laterHoldInSongA.getAndSet(null);
                    if (later != null) {
                        later.entered().countDown();
                        awaitTheRelease(later.released());
                    }
                }
                AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
            });

    private static void awaitTheRelease(CountDownLatch released) throws IOException {
        try {
            if (!released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("the test never released the held write");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted inside the held write", interrupted);
        }
    }

    /** Completed when a seeding joins the writer already running jobs in its folder: the manager's FINE line. */
    private final CompletableFuture<Void> joinedTheWriterOfItsFolder = new CompletableFuture<>();
    /** How many seedings joined the writer already running jobs in their folder. */
    private final AtomicInteger joinsSoFar = new AtomicInteger();
    /** Completed when a second seeding joined such a writer. */
    private final CompletableFuture<Void> aSecondSeedingJoined = new CompletableFuture<>();
    private final Logger managerLog = Logger.getLogger(CheckpointManager.class.getName());
    private final Handler joins = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            if (String.valueOf(logRecord.getMessage()).endsWith(" are written behind the jobs still held in that"
                    + " folder")) {
                if (joinsSoFar.incrementAndGet() == 2) {
                    aSecondSeedingJoined.complete(null);
                }
                joinedTheWriterOfItsFolder.complete(null);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private Level levelBefore;

    @BeforeEach
    void twoFolders() throws IOException {
        songA = Files.createDirectories(workspace.resolve("Song A"));
        songB = Files.createDirectories(workspace.resolve("Song B"));
        levelBefore = managerLog.getLevel();
        managerLog.setLevel(Level.FINE);
        joins.setLevel(Level.FINE);
        managerLog.addHandler(joins);
    }

    @AfterEach
    void releaseEverything() {
        heldReleased.countDown();
        laterHolds.forEach(hold -> hold.released().countDown());
        managers.forEach(CheckpointManager::stop);
        managerLog.removeHandler(joins);
        managerLog.setLevel(levelBefore);
    }

    @Test
    void aWriteHeldInOneFolderHoldsNeitherTheSeedingNorTheWritesOfAnotherFolder() throws Exception {
        CheckpointManager manager = manager();
        manager.start(songA).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> heldInSongA = holdAWriteInSongA(manager);

        // The held write waits up to the whole timeout: one writer for every folder would hold Song B past half.
        manager.start(songB).get(TIMEOUT_SECONDS / 2, TimeUnit.SECONDS);
        Path writtenInSongB = manager.writeCheckpoint(document(2), 2, CheckpointOrigin.USER)
                .get(TIMEOUT_SECONDS / 2, TimeUnit.SECONDS);

        assertThat(writtenInSongB.getParent()).isEqualTo(songB.resolve("checkpoints"));
        assertThat(writtenInSongB).hasContent(document(2));
        assertThat(heldInSongA).as("Song A's write is still held").isNotDone();
        heldReleased.countDown();
        assertThat(heldInSongA.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).hasContent(document(1));
    }

    @Test
    void aSecondGenerationOfTheSameFolderSeedsAfterTheWriteHeldThereAndBeforeAWriteOfItsOwn() throws Exception {
        CheckpointManager manager = manager();
        CountDownLatch reopenedIsFingerprinting = new CountDownLatch(1);
        CountDownLatch reopenedMayFingerprint = new CountDownLatch(1);
        AtomicInteger seedings = new AtomicInteger();
        manager.setFolderFingerprints(folder -> {
            if (seedings.incrementAndGet() == 2) {
                // The reopened generation's seeding: its own write is queued behind it before it finds its writer.
                reopenedIsFingerprinting.countDown();
                awaitOrFail(reopenedMayFingerprint);
            }
            return FolderFingerprint.of(folder);
        });
        manager.start(songA).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> held = holdAWriteInSongA(manager);

        CompletableFuture<Void> reopened = manager.start(songA);
        awaitOrFail(reopenedIsFingerprinting);
        CompletableFuture<Path> ownWrite = manager.writeCheckpoint(document(2), 2, CheckpointOrigin.USER);
        reopenedMayFingerprint.countDown();
        // The held write keeps Song A's writer registered, so the reopened seeding joins it: only then is it let go.
        joinedTheWriterOfItsFolder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        heldReleased.countDown();

        Path first = held.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        reopened.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Path second = ownWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(indexOf(first)).isEqualTo(1);
        assertThat(indexOf(second)).as("seeded once the held write landed, and before its own write").isEqualTo(2);
        assertThat(first).hasContent(document(1));
        assertThat(second).hasContent(document(2));
    }

    /**
     * The final prober's P01 (story 333's studio engineer, final ruling F16):
     * the folder's writer counts every generation attached to it. Generation
     * 1 of Song A holds a write; generation 2 joins its writer; generation
     * 1's write lands and it detaches; generation 2 holds a write of its own
     * and is stopped; then generation 3 of Song A joins that writer — it
     * seeds behind generation 2's held write, not on a writer of its own,
     * whose stray-temp delete and writes would run beside that write — and
     * its own write takes the next index once the held one landed.
     */
    @Test
    void aThirdGenerationOfTheSameFolderSeedsBehindTheSecondsHeldWriteOnceTheFirstHasDetached() throws Exception {
        CheckpointManager manager = manager();
        manager.start(songA).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> firstsWrite = holdAWriteInSongA(manager);

        CompletableFuture<Void> second = manager.start(songA);
        joinedTheWriterOfItsFolder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        heldReleased.countDown();
        Path first = firstsWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // Generation 2 seeds behind generation 1's last job on the writer, so generation 1 has detached by now.
        second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        HeldWrite secondsHold = new HeldWrite();
        laterHolds.add(secondsHold);
        laterHoldInSongA.set(secondsHold);
        CompletableFuture<Path> secondsWrite = manager.writeCheckpoint(document(2), 2, CheckpointOrigin.USER);
        awaitOrFail(secondsHold.entered());
        manager.stop();

        CompletableFuture<Void> third = manager.start(songA);
        CompletableFuture<Path> thirdsWrite = manager.writeCheckpoint(document(3), 3, CheckpointOrigin.USER);
        // Either its seeding joins Song A's writer, behind the held write, or it seeds on a writer of its own.
        CompletableFuture.anyOf(aSecondSeedingJoined, third).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(joinsSoFar).as("generation 3 joined the writer still holding generation 2's write").hasValue(2);
        assertThat(third).as("its seeding is queued behind that held write").isNotDone();
        assertThat(thirdsWrite).as("and its own write behind its seeding").isNotDone();
        secondsHold.released().countDown();

        Path secondPath = secondsWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        third.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Path thirdPath = thirdsWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(List.of(indexOf(first), indexOf(secondPath), indexOf(thirdPath)))
                .as("seeded once the held write landed, and before its own write: the next index")
                .containsExactly(1L, 2L, 3L);
        assertThat(songA.resolve("checkpoints").resolve(secondPath.getFileName())).hasContent(document(2));
        assertThat(songA.resolve("checkpoints").resolve(thirdPath.getFileName())).hasContent(document(3));
    }

    @Test
    void aJunctionOrALinkToTheFolderSharesItsWriter() throws Exception {
        Path alias = workspace.resolve("Song A alias");
        createAlias(alias, songA);
        assertSharesSongAsWriter(alias);
    }

    @Test
    void theUncNameOfALocalFolderSharesItsWriter() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"),
                "UNC names are a Windows file system's");
        Path unc = FolderFingerprintTest.uncNameOf(songA);
        assumeTrue(Files.isDirectory(unc), "the administrative share of the drive is reachable: " + unc);
        assertSharesSongAsWriter(unc);
    }

    @Test
    void aSeedingReadsTheFingerprintOfItsOwnFolderOnly() throws Exception {
        CheckpointManager manager = manager();
        List<Path> fingerprinted = new CopyOnWriteArrayList<>();
        manager.setFolderFingerprints(folder -> {
            fingerprinted.add(folder);
            return FolderFingerprint.of(folder);
        });
        manager.start(songA).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> heldInSongA = holdAWriteInSongA(manager);

        manager.start(songB).get(TIMEOUT_SECONDS / 2, TimeUnit.SECONDS);

        assertThat(fingerprinted).as("each seeding read its own folder, never the other one's")
                .containsExactly(songA, songB);
        heldReleased.countDown();
        heldInSongA.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stage 1's review S2: the seeding's only read of a folder's identity is
     * the fingerprint seam, on its own folder — the join compares
     * fingerprints with no I/O. A lookup that opened another folder's path
     * ({@code Files.isSameFile}, {@code toRealPath}) would bypass the seam the
     * behavioural test watches and block on that folder's stalled volume, so
     * the comparison's source is pinned.
     */
    @Test
    void theJoinComparesFingerprintsWithoutReadingAnyFolder() throws IOException {
        String manager = Files.readString(source("CheckpointManager.java"));
        String fingerprint = Files.readString(source("FolderFingerprint.java"));
        List<String> join = List.of(body(manager, "private boolean joinedTheWriterOfItsFolder("),
                body(fingerprint, "boolean sameFolder("));
        for (String body : join) {
            assertThat(body).as("no I/O in the comparison")
                    .doesNotContain("Files.", "toRealPath", "isSameFile", "getFileStore", "readAttributes",
                            "folderFingerprints");
        }
        String seed = body(manager, "private void seed(SeedJob job)");
        assertThat(occurrences(seed, "fingerprintOf(seeding.projectDirectory)"))
                .as("the seeding reads its own folder's fingerprint, once").isEqualTo(1);
        String fingerprintOf = body(manager, "private FolderFingerprint fingerprintOf(");
        assertThat(occurrences(fingerprintOf, "folderFingerprints.apply(projectDirectory)")).isEqualTo(1);
        assertThat(fingerprintOf).doesNotContain("Files.", "toRealPath", "isSameFile");
    }

    /**
     * Stage 1's review N2: a fingerprint read that throws leaves the
     * generation neither unregistered nor unarmed — it is told apart by its
     * path alone, seeds, and writes.
     */
    @Test
    void aFingerprintReadThatThrowsStillSeedsTheGenerationAndItWrites() throws Exception {
        CheckpointManager manager = manager();
        manager.setFolderFingerprints(folder -> {
            throw new java.io.UncheckedIOException(new IOException("the identity could not be read"));
        });
        List<CheckpointEvent> events = new CopyOnWriteArrayList<>();
        manager.addEventListener(events::add);

        manager.start(songA).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Path written = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.USER)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(written).hasContent(document(1));
        assertThat(events).as("seeded, and its ticks armed").anyMatch(CheckpointEvent.Seeded.class::isInstance)
                .anyMatch(event -> event instanceof CheckpointEvent.Rearmed rearmed
                        && rearmed.reason() == CheckpointEvent.RearmReason.STARTED);
    }

    /** {@code name} under daw-core's persistence sources, from the module or the repository root. */
    private static Path source(String name) {
        String relative = "src/main/java/com/benesquivelmusic/daw/core/persistence/" + name;
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("daw-core").resolve(relative);
    }

    /** The body of the first method whose declaration starts with {@code declaration}: braces balanced. */
    private static String body(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertThat(start).as("the source declares " + declaration).isNotNegative();
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char c = source.charAt(index);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, index + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + declaration);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    /**
     * Song A's write held, a generation started on {@code otherName} — another
     * name of Song A's folder — writes its own checkpoint after the held one
     * landed, at the next index: the two shared the folder's writer.
     */
    private void assertSharesSongAsWriter(Path otherName) throws Exception {
        CheckpointManager manager = manager();
        manager.start(songA).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> held = holdAWriteInSongA(manager);

        CompletableFuture<Void> reopened = manager.start(otherName);
        CompletableFuture<Path> ownWrite = manager.writeCheckpoint(document(2), 2, CheckpointOrigin.USER);
        // Either its seeding joins Song A's writer, behind the held write, or it seeds on a writer of its own.
        CompletableFuture.anyOf(joinedTheWriterOfItsFolder, reopened).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(joinedTheWriterOfItsFolder).as("the other name joined the folder's writer").isDone();
        assertThat(ownWrite).as("queued behind the write held in the folder").isNotDone();
        heldReleased.countDown();

        Path first = held.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        reopened.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Path second = ownWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(indexOf(second)).as("seeded after the held write landed: the next index").isEqualTo(indexOf(first) + 1);
        assertThat(songA.resolve("checkpoints").resolve(first.getFileName())).hasContent(document(1));
        assertThat(songA.resolve("checkpoints").resolve(second.getFileName())).hasContent(document(2));
    }

    private CompletableFuture<Path> holdAWriteInSongA(CheckpointManager manager) throws InterruptedException {
        holdTheNextWriteInSongA.set(true);
        CompletableFuture<Path> held = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.USER);
        awaitOrFail(heldEntered);
        return held;
    }

    private CheckpointManager manager() {
        CheckpointManager manager = CheckpointTestSupport.manager(new AutoSaveConfig(Duration.ofHours(1), 10, true),
                writer, new CountingClock(), Files::delete, CheckpointManager.STOP_WAIT);
        managers.add(manager);
        return manager;
    }

    private static boolean isACheckpointIn(Path folder, Path target) {
        Path directory = target.getParent();
        return folder != null && directory != null && folder.equals(directory.getParent())
                && CheckpointFileName.parse(target.getFileName().toString()).isPresent();
    }

    private static long indexOf(Path checkpoint) {
        return CheckpointFileName.parse(checkpoint.getFileName().toString()).orElseThrow().index();
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("the latch was not released within the bound");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", interrupted);
        }
    }

    /** A junction on Windows, a symbolic link elsewhere; the test is skipped where neither can be made. */
    private void createAlias(Path alias, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
            Process command = new ProcessBuilder("cmd", "/c", "mklink", "/J", alias.toString(), target.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(workspace.resolve("mklink.log").toFile())
                    .start();
            assertThat(command.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assumeTrue(command.exitValue() == 0, "junction creation requires file system support");
        } else {
            try {
                Files.createSymbolicLink(alias, target);
            } catch (IOException | UnsupportedOperationException unsupported) {
                assumeTrue(false, "symbolic links are unavailable: " + unsupported);
            }
        }
    }
}
