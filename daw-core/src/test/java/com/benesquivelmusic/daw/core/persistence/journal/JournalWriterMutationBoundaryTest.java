package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The journal writer's fact behind the exit's closing-checkpoint trigger (i)
 * (story 333, ruling R3): {@link JournalWriter#offeredMutationsAfterCheckpointBoundary()}
 * is {@code true} exactly when a non-control record was offered — queued or
 * appended — after the highest capture boundary a checkpoint marker
 * recorded. It is the writer's own fact, before anything is on disk, and it
 * predicts recovery detection: sealed as it stands, the journal owes a
 * recovery decision ({@code JournalState.needsRecovery}) exactly when the
 * fact holds. Control records — the checkpoint marker, the seal — never make
 * it true; a disabled writer accepts no record, so it never holds. A
 * checkpoint whose marker is never placed — its file cannot be read, or
 * {@code offer} refuses the marker — retires nothing: the fact and the
 * since-checkpoint count stay as they were.
 */
class JournalWriterMutationBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    @TempDir
    Path folder;

    @Test
    void aStartedJournalHasNoMutationAfterItsBoundaryAndSealsClean() throws Exception {
        ProjectContext context = context();
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();

            assertThat(writer.offeredMutationsAfterCheckpointBoundary()).isFalse();
            writer.sealAndClose(NOW);
        }
        assertThat(JournalState.needsRecovery(context)).as("recovery agrees").isFalse();
    }

    @Test
    void aMutationOfferedAfterTheBoundaryIsTheFactAtOnceAndOwesRecoveryOnceSealed() throws Exception {
        ProjectContext context = context();
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();

            writer.offer("TransportEvent.Stopped", null, null, NOW);

            assertThat(writer.offeredMutationsAfterCheckpointBoundary())
                    .as("queued, not yet appended: the writer's own fact").isTrue();
            writer.sealAndClose(NOW.plusSeconds(1));
        }
        assertThat(JournalState.needsRecovery(context)).as("recovery agrees").isTrue();
    }

    @Test
    void aCheckpointWhoseCaptureCoversTheMutationsClearsTheFactAndItsMarkerIsNoMutation() throws Exception {
        ProjectContext context = context();
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            writer.offer("TransportEvent.Started", null, null, NOW.plusSeconds(1));

            writer.onCheckpointSucceeded(writer.captureSequence(), checkpoint(), NOW.plusSeconds(2));

            assertThat(writer.offeredMutationsAfterCheckpointBoundary())
                    .as("the marker covers both, and is a control record itself").isFalse();
            writer.sealAndClose(NOW.plusSeconds(3));
        }
        assertThat(JournalState.needsRecovery(context)).as("recovery agrees").isFalse();
    }

    @Test
    void aMutationOfferedAfterTheCaptureButBeforeItsMarkerStaysTheFact() throws Exception {
        ProjectContext context = context();
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            long captured = writer.captureSequence();
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW.plusSeconds(1));

            writer.onCheckpointSucceeded(captured, checkpoint(), NOW.plusSeconds(2));

            assertThat(writer.offeredMutationsAfterCheckpointBoundary())
                    .as("the checkpoint's capture does not hold the later mutation").isTrue();
            writer.sealAndClose(NOW.plusSeconds(3));
        }
        assertThat(JournalState.needsRecovery(context)).as("recovery agrees").isTrue();
    }

    @Test
    void aRecordOfferedWithItsOwnSequenceCountsByThatSequence() throws Exception {
        ProjectContext context = context();
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer(JournalRecord.ofMutation(41L, NOW, "MixerEvent.GainChanged", UUID.randomUUID(), null));
            writer.onCheckpointSucceeded(40L, checkpoint(), NOW.plusSeconds(1));

            assertThat(writer.offeredMutationsAfterCheckpointBoundary())
                    .as("sequence 41 lies above the boundary 40").isTrue();

            writer.onCheckpointSucceeded(41L, checkpoint(), NOW.plusSeconds(2));

            assertThat(writer.offeredMutationsAfterCheckpointBoundary()).as("now the boundary holds it").isFalse();
        }
    }

    @Test
    void aCheckpointThatCannotBeReadPlacesNoMarkerAndLeavesTheFactAndTheCount() throws Exception {
        ProjectContext context = context();
        List<Integer> publishedCounts = new CopyOnWriteArrayList<>();
        Path unreadable = unreadableCheckpoint();
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, countsInto(publishedCounts))) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            writer.forceFlush();
            List<Integer> beforeTheCheckpoint = List.copyOf(publishedCounts);
            assertThat(beforeTheCheckpoint).as("fixture: the mutation is durable").last().isEqualTo(1);
            long captured = writer.captureSequence();

            assertThatThrownBy(() -> writer.onCheckpointSucceeded(captured, unreadable, NOW.plusSeconds(1)))
                    .isInstanceOf(IOException.class);

            assertThat(writer.offeredMutationsAfterCheckpointBoundary())
                    .as("no marker covers the mutation, so the closing checkpoint is still owed").isTrue();
            assertThat(writer.captureSequence()).as("a checkpoint that cannot be read takes no sequence number")
                    .isEqualTo(captured);
            assertThat(publishedCounts).as("the since-checkpoint count is not reset").isEqualTo(beforeTheCheckpoint);
            assertThat(writer.queuedSinceCheckpoint()).isEqualTo(1);
            writer.sealAndClose(NOW.plusSeconds(2));
        }
        assertThat(JournalState.records(context)).as("no checkpoint marker on disk")
                .noneMatch(JournalRecord::isCheckpointMarker);
        assertThat(JournalState.needsRecovery(context)).as("recovery agrees").isTrue();
    }

    @Test
    void aCheckpointOfTheSameCaptureThatCanBeReadAfterOneThatCouldNotClearsTheFactAndSealsClean() throws Exception {
        ProjectContext context = context();
        Path unreadable = unreadableCheckpoint();
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            long captured = writer.captureSequence();
            assertThatThrownBy(() -> writer.onCheckpointSucceeded(captured, unreadable, NOW.plusSeconds(1)))
                    .isInstanceOf(IOException.class);

            writer.onCheckpointSucceeded(captured, checkpoint(), NOW.plusSeconds(2));

            assertThat(writer.offeredMutationsAfterCheckpointBoundary()).as("its marker covers the mutation").isFalse();
            writer.sealAndClose(NOW.plusSeconds(3));
        }
        assertThat(JournalState.needsRecovery(context)).as("recovery agrees").isFalse();
    }

    @Test
    void aCheckpointWhoseMarkerIsRefusedOnceTheJournalClosedLeavesTheFactAndTheCount() throws Exception {
        ProjectContext context = context();
        List<Integer> publishedCounts = new CopyOnWriteArrayList<>();
        Path checkpoint = checkpoint();
        JournalWriter writer = closedWithOneDurableMutation(context, publishedCounts);
        long captured = writer.captureSequence();
        List<Integer> beforeTheCheckpoint = List.copyOf(publishedCounts);

        assertThatThrownBy(() -> writer.onCheckpointSucceeded(captured, checkpoint, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Journal no longer accepts records");

        assertThat(writer.offeredMutationsAfterCheckpointBoundary()).as("no marker covers the mutation").isTrue();
        assertThat(publishedCounts).as("the since-checkpoint count is not reset").isEqualTo(beforeTheCheckpoint);
        assertThat(writer.queuedSinceCheckpoint()).isEqualTo(1);
        assertThat(JournalState.records(context)).as("no checkpoint marker on disk")
                .noneMatch(JournalRecord::isCheckpointMarker);
    }

    @Test
    void aLegacyCheckpointWhoseMarkerIsRefusedOnceTheJournalClosedLeavesTheFactAndTheCount() throws Exception {
        ProjectContext context = context();
        List<Integer> publishedCounts = new CopyOnWriteArrayList<>();
        JournalWriter writer = closedWithOneDurableMutation(context, publishedCounts);
        List<Integer> beforeTheCheckpoint = List.copyOf(publishedCounts);

        assertThatThrownBy(writer::onCheckpointSucceeded)
                .isInstanceOf(IllegalStateException.class).hasMessage("Journal no longer accepts records");

        assertThat(writer.offeredMutationsAfterCheckpointBoundary()).as("no marker covers the mutation").isTrue();
        assertThat(publishedCounts).as("the since-checkpoint count is not reset").isEqualTo(beforeTheCheckpoint);
        assertThat(writer.queuedSinceCheckpoint()).isEqualTo(1);
        assertThat(JournalState.records(context)).as("no checkpoint marker on disk")
                .noneMatch(JournalRecord::isCheckpointMarker);
    }

    @Test
    void aDisabledJournalAcceptsNoRecordSoTheFactNeverHolds() throws Exception {
        try (JournalWriter writer = new JournalWriter(context(), JournalConfig.DEFAULT, false, null)) {
            writer.start();

            assertThat(writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW))
                    .isEqualTo(OfferResult.DISABLED);
            assertThat(writer.offeredMutationsAfterCheckpointBoundary()).isFalse();
        }
    }

    private ProjectContext context() throws IOException {
        Files.writeString(folder.resolve("project.daw"),
                new ProjectSerializer().serialize(new DawProject("Song", AudioFormat.CD_QUALITY)));
        return ProjectContext.forProject(folder, InstantSource.fixed(NOW));
    }

    private Path checkpoint() throws IOException {
        Path directory = Files.createDirectories(folder.resolve("checkpoints"));
        Path file = directory.resolve("checkpoint-" + UUID.randomUUID() + ".daw");
        return Files.writeString(file, new ProjectSerializer().serialize(new DawProject("Song", AudioFormat.CD_QUALITY)));
    }

    /** A checkpoint path in the folder's {@code checkpoints/} with no file behind it, so its capture cannot be read. */
    private Path unreadableCheckpoint() throws IOException {
        Path directory = Files.createDirectories(folder.resolve("checkpoints"));
        return directory.resolve("checkpoint-" + UUID.randomUUID() + ".daw");
    }

    /**
     * A started journal with one mutation, then closed: the close made the mutation durable (the count is 1), and
     * the writer thread stays published, so a checkpoint passes the started check and only {@code offer} refuses.
     */
    private static JournalWriter closedWithOneDurableMutation(ProjectContext context, List<Integer> publishedCounts)
            throws IOException {
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, countsInto(publishedCounts));
        writer.start();
        writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
        writer.close();
        assertThat(writer.writerThread()).as("fixture: the writer thread stays published").isPresent();
        assertThat(publishedCounts).as("fixture: the close made the mutation durable").last().isEqualTo(1);
        return writer;
    }

    private static JournalListener countsInto(List<Integer> publishedCounts) {
        return new JournalListener() {
            @Override
            public void onQueuedCountChanged(int queuedSinceCheckpoint) {
                publishedCounts.add(queuedSinceCheckpoint);
            }
        };
    }
}
