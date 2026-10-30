package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A capture boundary advances only once the journal accepted that checkpoint's
 * marker (story 333, trigger (i)): a checkpoint whose file cannot be read or
 * whose marker is refused changes no boundary, no durable-mutation count and no
 * rotation request, whatever order checkpoints arrive in and whichever thread
 * calls.
 */
class CheckpointBoundaryAfterMarkerContractTest {

    private static final Instant T0 = Instant.parse("2027-02-14T21:30:00Z");
    private static final long GUARD_SECONDS = 20L;
    private static final Duration CLOSE_BEGUN_WAIT = Duration.ofSeconds(5);

    @TempDir
    Path projectFolder;

    @Test
    void aFailedReadBetweenTwoRecordedCheckpointsChangesNothingTheJournalReports() throws Exception {
        ProjectContext context = projectContext();
        CountingListener listener = new CountingListener();
        Path readable = checkpointFile("first");
        Path missing = projectFolder.resolve("checkpoints").resolve("gone-" + UUID.randomUUID() + ".daw");
        Path laterReadable = checkpointFile("second");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, listener)) {
            writer.start();
            writer.offer(mutation(10L));
            writer.offer(mutation(20L));
            writer.offer(mutation(30L));
            writer.onCheckpointSucceeded(10L, readable, T0.plusSeconds(1));
            writer.forceFlush();
            assertThat(listener.counts).as("fixture: 20 and 30 are durable above boundary 10").last().isEqualTo(2);
            List<Integer> countsBefore = List.copyOf(listener.counts);
            long sequenceBefore = writer.captureSequence();
            List<String> segmentsBefore = writer.segmentFileNames();

            assertThatThrownBy(() -> writer.onCheckpointSucceeded(25L, missing, T0.plusSeconds(2)))
                    .isInstanceOf(IOException.class);
            writer.forceFlush();

            assertThat(listener.counts).as("no count is published for a marker that was never built")
                    .isEqualTo(countsBefore);
            assertThat(writer.captureSequence()).as("the failed read draws no sequence").isEqualTo(sequenceBefore);
            assertThat(writer.segmentFileNames()).as("no segment was swapped").isEqualTo(segmentsBefore);
            assertThat(writer.queuedSinceCheckpoint()).isEqualTo(2);

            writer.onCheckpointSucceeded(20L, laterReadable, T0.plusSeconds(3));
            writer.forceFlush();

            assertThat(listener.counts).as("boundary 20 retires 20 only").last().isEqualTo(1);
            assertThat(writer.offeredMutationsAfterCheckpointBoundary()).as("30 lies above boundary 20").isTrue();
            writer.sealAndClose(T0.plusSeconds(4));
        }
        assertThat(JournalState.records(context).stream().filter(JournalRecord::isCheckpointMarker)
                .map(JournalState::boundary).toList()).containsExactly(10L, 20L);
        assertThat(JournalState.needsRecovery(context)).as("recovery agrees with the writer").isTrue();
    }

    @Test
    void aCheckpointThatCannotBeReadRequestsNoRotation() throws Exception {
        ProjectContext context = projectContext();
        Path missing = projectFolder.resolve("checkpoints").resolve("gone-" + UUID.randomUUID() + ".daw");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer(mutation(10L));
            writer.forceFlush();
            List<String> segmentsBefore = writer.segmentFileNames();

            assertThatThrownBy(() -> writer.onCheckpointSucceeded(10L, missing, T0.plusSeconds(1)))
                    .isInstanceOf(IOException.class);
            writer.offer(mutation(20L));
            writer.forceFlush();

            assertThat(writer.segmentFileNames()).as("a checkpoint that cannot be read requests no rotation")
                    .isEqualTo(segmentsBefore);
        }
    }

    @Test
    void aFailedCheckpointForTheHigherBoundaryLeavesALowerBoundaryFreeToBeRecorded() throws Exception {
        ProjectContext context = projectContext();
        Path missing = projectFolder.resolve("checkpoints").resolve("gone-" + UUID.randomUUID() + ".daw");
        Path readable = checkpointFile("lower");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer(mutation(100L));
            writer.offer(mutation(200L));
            assertThatThrownBy(() -> writer.onCheckpointSucceeded(200L, missing, T0.plusSeconds(1)))
                    .isInstanceOf(IOException.class);

            writer.onCheckpointSucceeded(100L, readable, T0.plusSeconds(2));

            assertThat(writer.offeredMutationsAfterCheckpointBoundary())
                    .as("only the marker for boundary 100 was accepted, so 200 is outside every capture").isTrue();
            writer.forceFlush();
            assertThat(writer.queuedSinceCheckpoint()).isEqualTo(1);
            writer.sealAndClose(T0.plusSeconds(3));
        }
        assertThat(JournalState.needsRecovery(context)).as("recovery agrees with the writer").isTrue();
    }

    @Test
    void twoRecordedCheckpointsArrivingOutOfOrderKeepTheHigherBoundary() throws Exception {
        ProjectContext context = projectContext();
        Path higher = checkpointFile("higher");
        Path lower = checkpointFile("lower");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer(mutation(100L));
            writer.offer(mutation(200L));
            writer.onCheckpointSucceeded(200L, higher, T0.plusSeconds(1));

            writer.onCheckpointSucceeded(100L, lower, T0.plusSeconds(2));

            assertThat(writer.offeredMutationsAfterCheckpointBoundary())
                    .as("the later marker for an older capture does not lower the boundary").isFalse();
            writer.forceFlush();
            assertThat(writer.queuedSinceCheckpoint()).isZero();
            writer.sealAndClose(T0.plusSeconds(3));
        }
        assertThat(JournalState.needsRecovery(context)).as("recovery agrees with the writer").isFalse();
    }

    @Test
    void aCheckpointRefusedWhileTheCloseDrainsRetiresNothingFromTheCountsTheDrainPublishes() throws Exception {
        RefusedDuringClose outcome = refuseACheckpointWhileTheCloseDrains();

        assertThat(outcome.refusal()).hasValueSatisfying(refusal -> assertThat(refusal)
                .isInstanceOf(IllegalStateException.class).hasMessage("Journal no longer accepts records"));
        assertThat(outcome.countsAfterTheHookWasArmed())
                .as("13 made durable, then the writer thread, draining before the close could finish, "
                        + "made 29 durable; the refused marker retired nothing")
                .containsExactly(2, 3);
        assertThat(outcome.writer().queuedSinceCheckpoint()).isEqualTo(3);
        assertThat(outcome.writer().offeredMutationsAfterCheckpointBoundary()).isTrue();
        assertThat(JournalState.records(outcome.context())).noneMatch(JournalRecord::isCheckpointMarker);
    }

    @Test
    void aCheckpointRefusedWhileTheCloseDrainsRequestsNoRotation() throws Exception {
        RefusedDuringClose outcome = refuseACheckpointWhileTheCloseDrains();

        assertThat(outcome.refusal()).hasValueSatisfying(refusal -> assertThat(refusal)
                .isInstanceOf(IllegalStateException.class).hasMessage("Journal no longer accepts records"));
        assertThat(outcome.writer().segmentFileNames()).as("the refused marker requested no rotation")
                .isEqualTo(outcome.segmentsBefore());
    }

    @Test
    void aLegacyCheckpointWhoseMarkerSpillsKeepsItsBoundaryBelowARecordOfferedDuringTheSpill() throws Exception {
        ProjectContext context = projectContext();
        CountingListener listener = new CountingListener();
        JournalConfig oneSlotQueue = new JournalConfig(1, 1L << 20, 1L << 22, 8);
        CountDownLatch writerHeld = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        try (JournalWriter writer = new JournalWriter(context, oneSlotQueue, true, listener)) {
            writer.start();
            try {
                listener.onNextCount(() -> {
                    writerHeld.countDown();
                    awaitQuietly(releaseWriter);
                });
                writer.offer(mutation(5L));
                assertThat(writerHeld.await(GUARD_SECONDS, TimeUnit.SECONDS)).as("the writer appended 5").isTrue();
                writer.offer(mutation(9L));
                listener.onNextBuffering(() -> writer.offer("TransportEvent.Started", null, null, T0.plusSeconds(1)));

                writer.onCheckpointSucceeded();

                assertThat(writer.captureSequence()).as("fixture: a record was offered during the marker's spill")
                        .isEqualTo(11L);
                assertThat(writer.offeredMutationsAfterCheckpointBoundary())
                        .as("the capture boundary is 9 (the marker's own is 10), so record 11 lies outside the capture")
                        .isTrue();
            } finally {
                releaseWriter.countDown();
            }
            writer.sealAndClose(T0.plusSeconds(2));
        }
        assertThat(JournalState.needsRecovery(context)).as("recovery agrees with the writer").isTrue();
    }

    /**
     * Mutation 7 is durable; then, on the writer thread while it appends 13 and holds the append lock, 29 is
     * queued, a close begins on another thread, and a checkpoint covering 7 and 13 is refused. The writer thread
     * then appends 29 before the close's join returns.
     */
    private RefusedDuringClose refuseACheckpointWhileTheCloseDrains() throws Exception {
        ProjectContext context = projectContext();
        CountingListener listener = new CountingListener();
        Path checkpoint = checkpointFile("refused");
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, listener);
        try {
            writer.start();
            writer.offer(mutation(7L));
            writer.forceFlush();
            List<String> segmentsBefore = writer.segmentFileNames();
            int countsBeforeTheHook = listener.counts.size();
            AtomicReference<Throwable> refusal = new AtomicReference<>();
            AtomicReference<Throwable> fixtureFailure = new AtomicReference<>();
            AtomicReference<Thread> closer = new AtomicReference<>();
            CountDownLatch hookDone = new CountDownLatch(1);
            listener.onNextCount(() -> {
                try {
                    writer.offer(mutation(29L));
                    Thread closing = Thread.ofPlatform().name("journal-closer").start(writer::close);
                    closer.set(closing);
                    awaitCloseBegun(writer);
                    try {
                        writer.onCheckpointSucceeded(13L, checkpoint, T0.plusSeconds(1));
                    } catch (Throwable thrown) {
                        refusal.set(thrown);
                    }
                } catch (Throwable thrown) {
                    fixtureFailure.set(thrown);
                } finally {
                    hookDone.countDown();
                }
            });
            writer.offer(mutation(13L));
            assertThat(hookDone.await(GUARD_SECONDS, TimeUnit.SECONDS)).as("the writer appended 13").isTrue();
            assertThat(fixtureFailure.get()).as("fixture: 29 was queued and the close began before the checkpoint")
                    .isNull();
            assertThat(closer.get()).as("fixture: the close was started").isNotNull();
            assertThat(closer.get().join(Duration.ofSeconds(GUARD_SECONDS))).as("the close ended").isTrue();
            List<Integer> counts = List.copyOf(listener.counts);
            return new RefusedDuringClose(context, writer, Optional.ofNullable(refusal.get()), segmentsBefore,
                    counts.subList(countsBeforeTheHook, counts.size()));
        } finally {
            writer.close();
        }
    }

    /** {@code start()} on a started writer is a no-op until a close sets {@code stopping}; then it throws. */
    private static void awaitCloseBegun(JournalWriter writer) throws IOException {
        long deadline = System.nanoTime() + CLOSE_BEGUN_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                writer.start();
            } catch (IllegalStateException closing) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new IllegalStateException("the close did not begin within " + CLOSE_BEGUN_WAIT);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(GUARD_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static JournalRecord mutation(long sequence) {
        return JournalRecord.ofMutation(sequence, T0, "ClipEvent.Moved", UUID.randomUUID(), new byte[] {1, 2, 3});
    }

    private ProjectContext projectContext() throws IOException {
        Files.writeString(projectFolder.resolve("project.daw"),
                new ProjectSerializer().serialize(new DawProject("Boundary Contract", AudioFormat.CD_QUALITY)));
        return ProjectContext.forProject(projectFolder, InstantSource.fixed(T0));
    }

    private Path checkpointFile(String label) throws IOException {
        Path directory = Files.createDirectories(projectFolder.resolve("checkpoints"));
        return Files.writeString(directory.resolve("checkpoint-" + label + "-" + UUID.randomUUID() + ".daw"),
                new ProjectSerializer().serialize(new DawProject("Capture " + label, AudioFormat.CD_QUALITY)));
    }

    private record RefusedDuringClose(ProjectContext context, JournalWriter writer, Optional<Throwable> refusal,
                                      List<String> segmentsBefore, List<Integer> countsAfterTheHookWasArmed) {
    }

    /**
     * Records every published count; runs a one-shot hook on the thread that publishes the next count or signals
     * BUFFERING.
     */
    private static final class CountingListener implements JournalListener {
        private final List<Integer> counts = new CopyOnWriteArrayList<>();
        private final AtomicReference<Runnable> nextCount = new AtomicReference<>();
        private final AtomicReference<Runnable> nextBuffering = new AtomicReference<>();

        void onNextCount(Runnable hook) {
            nextCount.set(hook);
        }

        void onNextBuffering(Runnable hook) {
            nextBuffering.set(hook);
        }

        @Override
        public void onQueuedCountChanged(int queuedSinceCheckpoint) {
            counts.add(queuedSinceCheckpoint);
            Runnable hook = nextCount.getAndSet(null);
            if (hook != null) hook.run();
        }

        @Override
        public void onBackpressure(Backpressure level) {
            if (level != Backpressure.BUFFERING) return;
            Runnable hook = nextBuffering.getAndSet(null);
            if (hook != null) hook.run();
        }
    }
}
