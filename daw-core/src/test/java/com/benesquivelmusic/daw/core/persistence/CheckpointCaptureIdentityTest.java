package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointCaptureIdentityTest {
    @TempDir Path folder;

    @Test
    void equalRevisionsKeepTheirOwnBoundariesWhenSubmittedInReverseCaptureOrder() throws Exception {
        CheckpointManager manager = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 1, false));
        AtomicLong sequence = new AtomicLong(3);
        Runnable removal = manager.observeJournalSequence(sequence::get);
        List<CheckpointEvent.Written> written = new CopyOnWriteArrayList<>();
        manager.addEventListener(event -> { if (event instanceof CheckpointEvent.Written done) written.add(done); });
        try {
            manager.start(folder).get(10, TimeUnit.SECONDS);
            CheckpointManager.Capture first = manager.captureStarted(42);
            sequence.set(7);
            CheckpointManager.Capture second = manager.captureStarted(42);
            Path latestCapture = manager.writeCheckpoint("newer", second, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
            Path lateOlderCapture = manager.writeCheckpoint("older", first, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);

            assertThat(written).extracting(CheckpointEvent.Written::journalSequence)
                    .containsExactly(OptionalLong.of(7), OptionalLong.of(3));
            assertThat(written).extracting(CheckpointEvent.Written::revision).containsExactly(42L, 42L);
            assertThat(manager.getCheckpointFiles()).as("cap+1 preserves the newest capture and the just-returned write")
                    .containsExactlyInAnyOrder(latestCapture, lateOlderCapture);
            sequence.set(11);
            Path next = manager.writeCheckpoint("newest", manager.captureStarted(42), CheckpointOrigin.USER)
                    .get(10, TimeUnit.SECONDS);
            assertThat(manager.getCheckpointFiles()).containsExactly(next);
            assertThat(CheckpointOrder.read(folder.resolve("checkpoints"))).containsExactly(next.getFileName().toString());
        } finally {
            removal.run();
            manager.stop();
        }
    }

    @Test
    void scheduledCaptureKeepsTheBoundaryFromBeforeTheModelWalk() throws Exception {
        CheckpointManager manager = new CheckpointManager(new AutoSaveConfig(Duration.ofMillis(40), 3, true));
        CheckpointTestSupport.ManualOwner owner = new CheckpointTestSupport.ManualOwner();
        CheckpointTestSupport.TestSource source = new CheckpointTestSupport.TestSource();
        AtomicLong sequence = new AtomicLong(3);
        Runnable removal = manager.observeJournalSequence(sequence::get);
        CheckpointTestSupport.Recorder recorder = CheckpointTestSupport.Recorder.on(manager);
        source.captureWith(() -> {
            sequence.set(7);
            return () -> "captured before the later transport event";
        });
        manager.setCaptureSource(owner, source);
        try {
            manager.start(folder).get(10, TimeUnit.SECONDS);
            owner.awaitPosted(1);
            owner.runPending();
            recorder.awaitWritten(1);
            assertThat(recorder.events(CheckpointEvent.Written.class)).singleElement().satisfies(written -> {
                assertThat(written.origin()).isEqualTo(CheckpointOrigin.SCHEDULED);
                assertThat(written.journalSequence()).hasValue(3L);
            });
        } finally {
            removal.run();
            manager.stop();
        }
    }

    @Test
    void removingAnOldJournalCannotRemoveItsReplacementAndLegacyRequestsHaveNoInventedBoundary() throws Exception {
        CheckpointManager manager = new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false));
        Runnable old = manager.observeJournalSequence(() -> 3L);
        Runnable current = manager.observeJournalSequence(() -> 8L);
        old.run();
        assertThat(manager.captureStarted(1).journalSequence()).hasValue(8L);
        current.run();
        assertThat(manager.captureStarted(1).journalSequence()).isEmpty();
        List<CheckpointEvent.Written> written = new CopyOnWriteArrayList<>();
        manager.addEventListener(event -> { if (event instanceof CheckpointEvent.Written done) written.add(done); });
        try {
            manager.start(folder).get(10, TimeUnit.SECONDS);
            manager.observeJournalSequence(() -> 99L);
            manager.writeCheckpoint("legacy captured bytes", 1L, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
            assertThat(written.getFirst().journalSequence()).isEmpty();
        } finally { manager.stop(); }
    }
}
