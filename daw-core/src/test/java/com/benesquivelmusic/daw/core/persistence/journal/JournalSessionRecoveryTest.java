package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionPolicyStore;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JournalSessionRecoveryTest {
    @TempDir Path folder;
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    private ProjectContext context(String name) throws IOException {
        Files.writeString(folder.resolve("project.daw"), xml(name));
        return ProjectContext.forProject(folder, InstantSource.fixed(NOW));
    }

    private static String xml(String name) throws IOException {
        return new ProjectSerializer().serialize(new DawProject(name, AudioFormat.CD_QUALITY));
    }

    private Path checkpoint(String name) throws IOException {
        Path directory = Files.createDirectories(folder.resolve("checkpoints"));
        return Files.writeString(directory.resolve("checkpoint-001-20261025T080000.daw"), xml(name));
    }

    @Test void capturePointPreservesRecordsPublishedWhileCheckpointWritesAndDispatchesThemOnce() throws Exception {
        ProjectContext context = context("Start");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            long captured = writer.captureSequence();
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW.plusSeconds(1));
            writer.onCheckpointSucceeded(captured, checkpoint("Captured"), NOW.plusSeconds(2));
            writer.forceFlush();
            assertThat(writer.queuedSinceCheckpoint()).isEqualTo(1);
        }
        RecoveryResult replay = new JournalReplayer().recover(context);
        assertThat(replay.recoveredProject().getName()).isEqualTo("Captured");
        assertThat(replay.appliedRecordCount()).isEqualTo(1);
        assertThat(JournalState.records(context).stream().filter(record -> !record.isControl())).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void laterMarkerForAnOlderCaptureCannotOverrideTheHighestVerifiedBoundary(boolean corruptNewer)
            throws Exception {
        ProjectContext context = context("Start");
        Path older = checkpoint("Older capture");
        Path newer = Files.writeString(context.checkpointDirectory().resolve("checkpoint-002-20261025T080100.daw"),
                xml("Newer capture"));
        long earlierBoundary;
        long laterBoundary;
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            earlierBoundary = writer.captureSequence();
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW);
            laterBoundary = writer.captureSequence();
            writer.onCheckpointSucceeded(laterBoundary, newer, NOW);
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            writer.onCheckpointSucceeded(earlierBoundary, older, NOW);
            writer.forceFlush();
            assertThat(writer.queuedSinceCheckpoint()).isEqualTo(1);
        }
        if (corruptNewer) Files.writeString(newer, xml("Unrelated replacement"));

        RecoveryPlan.Available base = recoveryPoint(context);
        assertThat(base).isInstanceOf(RecoveryPlan.CheckpointCapture.class);
        assertThat(base.file()).isEqualTo(corruptNewer ? older : newer);
        assertThat(base.boundary()).isEqualTo(corruptNewer ? earlierBoundary : laterBoundary);
        RecoveryResult recovered = new JournalReplayer().recover(context);
        assertThat(recovered.recoveredProject().getName()).isEqualTo(corruptNewer ? "Older capture" : "Newer capture");
        assertThat(recovered.appliedRecordCount()).isEqualTo(corruptNewer ? 2 : 1);
    }

    @Test void sealWithoutTrailingMutationsIsCleanButSealOverUncheckpointedEditsStillRecovers() throws Exception {
        ProjectContext context = context("Head");
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
        writer.start();
        writer.onCheckpointSucceeded(writer.captureSequence(), checkpoint("Head"), NOW);
        writer.sealAndClose(NOW.plusSeconds(1));
        assertThat(JournalState.needsRecovery(context)).isFalse();
        JournalState.clear(context);
        writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
        writer.start();
        writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW);
        writer.sealAndClose(NOW.plusSeconds(1));
        assertThat(JournalState.needsRecovery(context)).isTrue();
    }

    @Test void disposalDrainDoesNotSealADirtyCheckpointWhoseStateIsAbsentFromHead() throws Exception {
        ProjectContext context = context("Old saved head");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            writer.onCheckpointSucceeded(writer.captureSequence(), checkpoint("Dirty checkpoint"), NOW);
            writer.forceFlush();
        }
        assertThat(JournalState.needsRecovery(context)).isTrue();
        assertThat(new JournalReplayer().recover(context).recoveredProject().getName()).isEqualTo("Dirty checkpoint");
        assertThat(Files.readString(context.projectFile())).contains("Old saved head");
    }

    @Test void aNewWriterCannotAppendToUnresolvedPriorSessionEvidence() throws Exception {
        ProjectContext context = context("Head");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW);
        }
        Path segment = context.journalDirectory().resolve("segment-000.bin");
        Path base = context.journalDirectory().resolve(JournalState.BASE_FILE);
        byte[] prior = Files.readAllBytes(segment);
        byte[] priorBase = Files.readAllBytes(base);
        JournalWriter refused = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
        assertThatThrownBy(refused::start).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(segment)).isEqualTo(prior);
        assertThat(Files.readAllBytes(base)).as("a refused start never touches the prior session's base").isEqualTo(priorBase);
        assertThatThrownBy(refused::start).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> refused.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void aSessionBaseLeftWriteOnlyIsReplacedByOneTheReplayCanReadAndVerify() throws Exception {
        ProjectContext context = context("Head");
        Path base = Files.createDirectories(context.journalDirectory()).resolve(JournalState.BASE_FILE);
        Files.writeString(base, "stale");
        assumeTrue(Files.getFileStore(folder).supportsFileAttributeView(PosixFileAttributeView.class),
                "the file system has POSIX permission bits (Linux CI)");
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("-w-------"));
        assumeFalse(Files.isReadable(base), "a superuser may read a write-only file");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            assertThat(Files.getPosixFilePermissions(base)).contains(PosixFilePermission.OWNER_READ);
            assertThat(Files.readAllBytes(base)).isEqualTo(Files.readAllBytes(context.projectFile()));
            assertThat(Files.readString(base)).doesNotContain("stale");
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW);
        }
        assertThat(sessionStartPayload(context)).isEqualTo(JournalState.hash(base));
        assertThat(JournalState.needsRecovery(context)).isTrue();
        assertThat(recoveryPoint(context).file()).isEqualTo(base);
        assertThat(new JournalReplayer().recover(context).recoveredProject().getName()).isEqualTo("Head");
    }

    @Test void aStartWhoseSessionStartCannotBeMadeDurableReleasesItsSegmentAndLeavesNoJournalEvidence() throws Exception {
        ProjectContext context = context("Head");
        AtomicReference<FileChannel> forced = new AtomicReference<>();
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null,
                channel -> { forced.set(channel); throw new IOException("disk unavailable"); });
        assertThatThrownBy(writer::start).isInstanceOf(IOException.class)
                .hasMessageContaining("disk unavailable").hasMessageContaining("segment-000.bin");
        assertThat(forced.get()).as("the fsync was injected").isNotNull();
        assertThat(forced.get().isOpen()).as("the segment's channel was released").isFalse();
        assertThat(writer.writerThread()).as("no writer thread was started").isEmpty();
        assertThat(writer.segmentFileNames()).isEmpty();
        assertThatThrownBy(() -> writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(writer::start).isInstanceOf(IllegalStateException.class);
        writer.close();
        writer.sealAndClose(NOW);
        Path journal = context.journalDirectory();
        assertThat(journal.resolve("segment-000.bin")).doesNotExist();
        assertThat(journal.resolve(JournalState.BASE_FILE)).doesNotExist();
        assertThat(JournalState.records(context)).isEmpty();
        assertThat(JournalState.needsRecovery(context)).isFalse();

        JournalWriter next = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
        next.start();
        assertThat(sessionStartPayload(context)).isEqualTo(JournalState.hash(journal.resolve(JournalState.BASE_FILE)));
        next.sealAndClose(NOW);
        assertThat(JournalState.needsRecovery(context)).isFalse();
    }

    @Test void aStartThatFailsBeforeItsSegmentOpensLeavesAClosedWriterThatRefusesToStartAgain() throws Exception {
        ProjectContext context = context("Head");
        Path base = context.journalDirectory().resolve(JournalState.BASE_FILE);
        Path child = Files.writeString(Files.createDirectories(base).resolve("child"), "kept");
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
        assertThatThrownBy(writer::start).isInstanceOf(IOException.class);
        assertThat(context.journalDirectory().resolve("segment-000.bin")).doesNotExist();
        assertThat(base).as("the leftover base is never deleted recursively").isDirectory();
        assertThat(child).hasContent("kept");
        assertThatThrownBy(() -> writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(writer::start).isInstanceOf(IllegalStateException.class);
    }

    @Test void aClosedWriterCannotBeStarted() throws Exception {
        ProjectContext context = context("Head");
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
        writer.close();
        assertThatThrownBy(writer::start).isInstanceOf(IllegalStateException.class).hasMessage("Journal writer is closed");
        assertThat(context.journalDirectory()).doesNotExist();
        assertThat(writer.writerThread()).isEmpty();
    }

    @Test void aFailedStartWhoseSegmentCannotBeDeletedKeepsTheBaseItWrote() throws Exception {
        ProjectContext context = context("Head");
        Path journal = context.journalDirectory();
        Path segment = journal.resolve("segment-000.bin");
        Path base = journal.resolve(JournalState.BASE_FILE);
        Set<String> views = FileSystems.getDefault().supportedFileAttributeViews();
        // POSIX first: Linux also offers a "dos" view, whose read-only bit does not stop a delete there.
        boolean posix = views.contains("posix");
        assumeTrue(posix || views.contains("dos"), "the file system can make the segment undeletable");
        AtomicReference<Set<PosixFilePermission>> journalPermissions = new AtomicReference<>();
        AtomicBoolean journalStillWritable = new AtomicBoolean();
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null, channel -> {
            if (posix) {
                PosixFileAttributeView view = Files.getFileAttributeView(journal, PosixFileAttributeView.class);
                Set<PosixFilePermission> permissions = view.readAttributes().permissions();
                journalPermissions.set(permissions);
                Set<PosixFilePermission> readOnly = EnumSet.noneOf(PosixFilePermission.class);
                readOnly.addAll(permissions);
                readOnly.removeAll(EnumSet.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.OTHERS_WRITE));
                view.setPermissions(readOnly);
                journalStillWritable.set(Files.isWritable(journal));
            } else {
                Files.setAttribute(segment, "dos:readonly", true);
            }
            throw new IOException("disk unavailable");
        });
        try {
            Throwable thrown = catchThrowable(writer::start);
            assertThat(thrown).isInstanceOf(IOException.class).hasMessageContaining("disk unavailable");
            assumeFalse(journalStillWritable.get(), "a superuser may delete from a read-only directory");
            assertThat(thrown.getSuppressed()).as("only the segment's delete failed; the base delete was never tried")
                    .singleElement().isInstanceOfSatisfying(FileSystemException.class, notDeleted ->
                            assertThat(Path.of(notDeleted.getFile()).getFileName()).hasToString("segment-000.bin"));
            assertThat(segment).as("the segment could not be deleted").exists();
            assertThat(base).as("the base is kept while its segment exists").exists();
            assertThat(writer.writerThread()).isEmpty();
            assertThatThrownBy(() -> writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            if (journalPermissions.get() != null) Files.setPosixFilePermissions(journal, journalPermissions.get());
            if (!posix && Files.exists(segment)) Files.setAttribute(segment, "dos:readonly", false);
        }
    }

    @Test void aCloseThatRunsWhileAStartIsParkedAfterItsFsyncMakesTheStartFailAndLeavesNoWriterThread()
            throws Exception {
        ProjectContext context = context("Head");
        CountDownLatch synced = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null, channel -> {
            channel.force(true);
            synced.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("the start was never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        });
        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        Thread starter = Thread.ofVirtual().start(() -> {
            try { writer.start(); } catch (Throwable failure) { startFailure.set(failure); }
        });
        assertThat(synced.await(10, TimeUnit.SECONDS)).as("start reached its $session-start fsync").isTrue();
        Thread closer = Thread.ofVirtual().start(writer::close);
        boolean closeReturnedBeforeRelease = closer.join(Duration.ofSeconds(5));
        release.countDown();
        assertThat(starter.join(Duration.ofSeconds(10))).as("start returned").isTrue();
        assertThat(closer.join(Duration.ofSeconds(10))).as("close returned").isTrue();

        assertThat(closeReturnedBeforeRelease).as("close did not wait for the parked start").isTrue();
        assertThat(startFailure.get()).isInstanceOf(IllegalStateException.class).hasMessage("Journal writer is closed");
        assertThat(writer.writerThread()).isEmpty();
        Path journal = context.journalDirectory();
        assertThat(journal.resolve("segment-000.bin")).doesNotExist();
        assertThat(journal.resolve(JournalState.BASE_FILE)).doesNotExist();
        assertThat(JournalState.needsRecovery(context)).isFalse();
    }

    @Test void aSealThatRunsWhileAStartIsParkedAfterItsFsyncSealsNothingAndMakesTheStartFail() throws Exception {
        ProjectContext context = context("Head");
        CountDownLatch synced = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null, channel -> {
            channel.force(true);
            synced.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("the start was never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        });
        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        Thread starter = Thread.ofVirtual().start(() -> {
            try { writer.start(); } catch (Throwable failure) { startFailure.set(failure); }
        });
        assertThat(synced.await(10, TimeUnit.SECONDS)).as("start reached its $session-start fsync").isTrue();
        AtomicReference<Throwable> sealFailure = new AtomicReference<>();
        Thread sealer = Thread.ofVirtual().start(() -> {
            try { writer.sealAndClose(NOW); } catch (Throwable failure) { sealFailure.set(failure); }
        });
        boolean sealReturnedBeforeRelease = sealer.join(Duration.ofSeconds(5));
        release.countDown();
        assertThat(starter.join(Duration.ofSeconds(10))).as("start returned").isTrue();
        assertThat(sealer.join(Duration.ofSeconds(10))).as("seal returned").isTrue();

        assertThat(sealReturnedBeforeRelease).as("the seal did not wait for the parked start's segment").isTrue();
        assertThat(sealFailure.get()).as("a seal with no writer thread seals nothing and does not throw").isNull();
        assertThat(startFailure.get()).isInstanceOf(IllegalStateException.class).hasMessage("Journal writer is closed");
        assertThat(writer.writerThread()).isEmpty();
        Path journal = context.journalDirectory();
        assertThat(journal.resolve("segment-000.bin")).doesNotExist();
        assertThat(journal.resolve(JournalState.BASE_FILE)).doesNotExist();
        assertThat(JournalState.needsRecovery(context)).isFalse();
    }

    @Test void aSealOfAWriterThatNeverStartedWritesNoRecordAndClosesIt() throws Exception {
        ProjectContext context = context("Head");
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
        writer.sealAndClose(NOW);
        assertThatThrownBy(writer::start).isInstanceOf(IllegalStateException.class).hasMessage("Journal writer is closed");
        assertThat(context.journalDirectory()).doesNotExist();
        assertThat(writer.writerThread()).isEmpty();
    }

    @Test void anEnabledWriterThatNeverStartedRefusesRecordsAndClosesWithoutTouchingTheDisk() throws Exception {
        ProjectContext context = context("Head");
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
        assertThatThrownBy(() -> writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW))
                .isInstanceOf(IllegalStateException.class).hasMessage("Journal writer has not started");
        writer.close();
        assertThat(context.journalDirectory()).doesNotExist();
        assertThat(writer.writerThread()).isEmpty();
        assertThatThrownBy(writer::start).isInstanceOf(IllegalStateException.class).hasMessage("Journal writer is closed");
    }

    @Test void aDisabledWriterReportsARecordOfferedDirectlyAsDisabledWithoutStarting() throws Exception {
        ProjectContext context = context("Head");
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, false, null);
        assertThat(writer.offer(JournalRecord.ofMutation(0L, NOW, "MixerEvent.MuteChanged", UUID.randomUUID(), null)))
                .isEqualTo(OfferResult.DISABLED);
        assertThat(context.journalDirectory()).doesNotExist();
    }

    @Test void anOfferWhileAStartIsParkedAfterItsFsyncIsRefusedAndOnlyLaterRecordsReachTheSession() throws Exception {
        ProjectContext context = context("Head");
        CountDownLatch synced = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null, channel -> {
            channel.force(true);
            synced.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("the start was never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        });
        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        Thread starter = Thread.ofVirtual().start(() -> {
            try { writer.start(); } catch (Throwable failure) { startFailure.set(failure); }
        });
        assertThat(synced.await(10, TimeUnit.SECONDS)).as("start reached its $session-start fsync").isTrue();
        Throwable early = catchThrowable(() -> writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW));
        release.countDown();   // release before asserting, so a red assertion never leaves the start parked
        assertThat(starter.join(Duration.ofSeconds(10))).as("start returned").isTrue();
        assertThat(early).isInstanceOf(IllegalStateException.class).hasMessage("Journal writer has not started");
        assertThat(startFailure.get()).isNull();
        assertThat(writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW)).isEqualTo(OfferResult.QUEUED);
        writer.sealAndClose(NOW);
        assertThat(JournalState.records(context)).filteredOn(record -> !record.isControl())
                .extracting(JournalRecord::type).containsExactly("MixerEvent.GainChanged");
    }

    @Test void aCheckpointReportedToAnEnabledWriterThatNeverStartedIsRefusedAndChangesNothing() throws Exception {
        ProjectContext context = context("Head");
        Path checkpoint = checkpoint("Captured");
        List<Integer> publishedCounts = new CopyOnWriteArrayList<>();
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, new JournalListener() {
            @Override public void onQueuedCountChanged(int queuedSinceCheckpoint) {
                publishedCounts.add(queuedSinceCheckpoint);
            }
        });
        long captured = writer.captureSequence();
        assertThatThrownBy(() -> writer.onCheckpointSucceeded())
                .isInstanceOf(IllegalStateException.class).hasMessage("Journal writer has not started");
        assertThatThrownBy(() -> writer.onCheckpointSucceeded(captured, checkpoint, NOW))
                .isInstanceOf(IllegalStateException.class).hasMessage("Journal writer has not started");
        assertThat(publishedCounts).as("a refused checkpoint publishes no queued count").isEmpty();
        assertThat(writer.queuedSinceCheckpoint()).isZero();
        assertThat(writer.captureSequence()).as("a refused checkpoint takes no sequence number").isEqualTo(captured);
        assertThat(context.journalDirectory()).doesNotExist();
    }

    @Test void aStageReasonIsTheFileSystemReasonOrTheMessageOrTheTypeAndNeverThePath() {
        assertThat(JournalState.reason(new AccessDeniedException("D:/x/session-base.daw")))
                .isEqualTo("AccessDeniedException");
        assertThat(JournalState.reason(new FileSystemException("D:/x", null, "in use by another process")))
                .isEqualTo("in use by another process");
        assertThat(JournalState.reason(new IOException("disk unavailable"))).isEqualTo("disk unavailable");
        assertThat(JournalState.reason(new IOException())).isEqualTo("IOException");
    }

    private static RecoveryPlan.Available recoveryPoint(ProjectContext context) throws IOException {
        RecoveryPlan.ReplayJournal journal = (RecoveryPlan.ReplayJournal) new RecoveryScanner().replayPlan(context).recovery();
        return (RecoveryPlan.Available) journal.base();
    }

    private static String sessionStartPayload(ProjectContext context) throws IOException {
        return JournalState.records(context).stream()
                .filter(record -> JournalRecord.SESSION_START_TYPE.equals(record.type()))
                .map(record -> new String(record.payload(), StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
    }

    @Test void autosaveOffUsesImmutableSessionStartHeadUntilAnExplicitCheckpointSucceeds() throws Exception {
        ProjectContext context = context("Recovery-written head");
        checkpoint("Unrelated stale checkpoint");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            Files.writeString(context.projectFile(), xml("Manual save without checkpoint"));
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW);
        }
        RecoveryResult result = new JournalReplayer().recover(context);
        assertThat(result.recoveredProject().getName()).isEqualTo("Recovery-written head");
        assertThat(result.appliedRecordCount()).isEqualTo(2);
        assertThat(new RecoveryScanner().replayPlan(context).summary().segmentFileNames()).containsExactly("segment-000.bin");
    }

    @Test void failedFsyncCannotCreateACleanSeal() throws Exception {
        ProjectContext context = context("Head");
        AtomicBoolean failing = new AtomicBoolean();
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null,
                channel -> { if (failing.get()) throw new IOException("disk unavailable"); channel.force(true); });
        writer.start();
        writer.forceFlush();
        failing.set(true);
        writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW);
        assertThatThrownBy(() -> writer.sealAndClose(NOW)).isInstanceOf(IOException.class);
        assertThat(JournalState.records(context)).noneMatch(JournalRecord::isSessionSeal);
        assertThat(JournalState.needsRecovery(context)).isTrue();
    }

    @Test void tornBytesAfterASealAreRecoveryEvidence() throws Exception {
        ProjectContext context = context("Head");
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
        writer.start();
        writer.sealAndClose(NOW);
        Files.write(context.journalDirectory().resolve("segment-000.bin"), new byte[] {1, 2, 3}, StandardOpenOption.APPEND);
        assertThat(JournalState.needsRecovery(context)).isTrue();
    }

    @Test void aSealWhoseOwnFsyncFailsIsRolledBackWithoutChangingPriorDurableRecords() throws Exception {
        ProjectContext context = context("Head");
        AtomicBoolean failSeal = new AtomicBoolean();
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null,
                channel -> { if (failSeal.get()) throw new IOException("seal fsync failed"); channel.force(true); });
        writer.start(); writer.forceFlush();
        Path segment = context.journalDirectory().resolve("segment-000.bin");
        byte[] durable = Files.readAllBytes(segment);
        failSeal.set(true);
        assertThatThrownBy(() -> writer.sealAndClose(NOW)).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(segment)).isEqualTo(durable);
        assertThat(JournalState.needsRecovery(context)).isTrue();
    }

    @Test void changedCheckpointBytesFallBackToSessionStartAndACorruptSessionStartFailsSafely() throws Exception {
        ProjectContext context = context("Session start");
        Path checkpoint = checkpoint("Captured");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            writer.onCheckpointSucceeded(writer.captureSequence(), checkpoint, NOW);
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW);
        }
        Files.writeString(checkpoint, xml("Unrelated replacement"));
        RecoveryResult replay = new JournalReplayer().recover(context);
        assertThat(replay.recoveredProject().getName()).isEqualTo("Session start");
        assertThat(replay.appliedRecordCount()).isEqualTo(2);
        Files.writeString(context.journalDirectory().resolve(JournalState.BASE_FILE), xml("Changed baseline"));
        assertThatThrownBy(() -> new JournalReplayer().recover(context)).isInstanceOf(JournalRecoveryException.class);
        assertThat(JournalState.records(context)).isNotEmpty();
    }

    @Test void publicationFenceCannotBeOvertakenByAStalledOrOverflowingAsyncSubscriber() throws Exception {
        ProjectContext context = context("Head");
        DefaultEventBus bus = DefaultEventBus.builder().bufferCapacity(1).uiExecutor(_ -> { }).build();
        bus.on(MixerEvent.class, DispatchMode.ON_UI_THREAD, _ -> { });
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
             JournalEventRecorder recorder = new JournalEventRecorder(bus, writer, InstantSource.fixed(NOW)).start()) {
            writer.start();
            for (int i = 0; i < 50; i++) bus.publish(new MixerEvent.GainChanged(UUID.randomUUID(), NOW));
            long captured = writer.captureSequence();
            for (int i = 0; i < 25; i++) bus.publish(new MixerEvent.GainChanged(UUID.randomUUID(), NOW));
            writer.onCheckpointSucceeded(captured, checkpoint("Captured"), NOW);
            recorder.close();
            writer.forceFlush();
        } finally { bus.close(); }
        assertThat(new JournalReplayer().recover(context).appliedRecordCount()).isEqualTo(25);
        assertThat(JournalState.records(context).stream().filter(record -> !record.isControl())).hasSize(75);
    }

    @ParameterizedTest @ValueSource(strings = {"ar-SA", "th-TH-u-nu-thai"})
    void persistentNamesAndPolicyNumbersAreAsciiUnderEveryFormatLocale(String tag) throws Exception {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(tag));
            ProjectContext context = context("Head");
            try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
                writer.start();
                writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            }
            assertThat(new RecoveryScanner().replayPlan(context).summary().segmentFileNames()).containsExactly("segment-000.bin");
            String json = BackupRetentionPolicyStore.toJson(BackupRetentionPolicy.DEFAULT);
            assertThat(json.chars().filter(Character::isDigit).allMatch(value -> value >= '0' && value <= '9')).isTrue();
        } finally { Locale.setDefault(before); }
    }
}
