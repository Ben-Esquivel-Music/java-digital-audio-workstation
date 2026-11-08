package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.RecoveryChoice;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.*;
import com.benesquivelmusic.daw.core.persistence.journal.*;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OpenRecoveryGateTest {
    @TempDir Path workspace;
    private final AtomicInteger decisions = new AtomicInteger();
    /** The open's target lease, as {@link #recoverEverything} handed it to the gate. */
    private final AtomicReference<ProjectLockManager> openLease = new AtomicReference<>();

    private Path project(String name) throws IOException {
        Path folder = Files.createDirectories(workspace.resolve(name));
        Files.writeString(folder.resolve("project.daw"), xml(name));
        return folder;
    }
    private static String xml(String name) throws IOException {
        return new ProjectSerializer().serialize(new DawProject(name, AudioFormat.CD_QUALITY));
    }
    private void crash(Path folder) throws IOException {
        try (JournalWriter writer = new JournalWriter(ProjectContext.forProject(folder), JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, Instant.now());
        }
    }
    private static <T> T worker(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try { result.complete(action.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result.get(15, TimeUnit.SECONDS);
    }
    private OpenRecoveryGate.Result gate(Path folder, RecoveryChoice choice, boolean enabled) throws Exception {
        ProjectManager manager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false)));
        AtomicReference<OpenRecoveryGate.Result> result = new AtomicReference<>();
        Path segment = folder.resolve("journal/segment-000.bin");
        byte[] original = Files.exists(segment) ? Files.readAllBytes(segment) : null;
        OpenRecoveryGate gate = new OpenRecoveryGate(manager, () -> enabled, summary -> {
            decisions.incrementAndGet();
            assertThat(Thread.currentThread().isVirtual()).isTrue();
            return choice;
        }, (head, content, beforeReplace) -> {
            if (original != null) assertThat(Files.readAllBytes(segment)).isEqualTo(original);
            beforeReplace.verify();
            replaceHead(head, content);
        });
        manager.setRecoveryGate((directory, readOnly, lock) -> result.set(gate.run(directory, readOnly, lock)));
        worker(() -> { try (ProjectManager.PreparedOpen ignored = manager.prepareOpen(folder)) { return null; } });
        return result.get();
    }

    @Test void absentJournalOpensWithoutAChoice() throws Exception {
        try (Logs logs = new Logs(OpenRecoveryGate.class)) {
            assertThat(gate(project("New"), RecoveryChoice.CANCEL, true).applied()).isEqualTo(-1);
            assertThat(logs.lines).anyMatch(line -> line.contains("degraded lock-heuristic mode"));
        }
        assertThat(decisions).hasValue(0);
    }
    @Test void freshCrashOffersRecoveryWithoutWaitingForLockStaleness() throws Exception {
        Path folder = project("Song");
        crash(folder);
        assertThat(com.benesquivelmusic.daw.app.ui.hub.ProjectHealthScanner.isRecoverable(folder,
                java.time.InstantSource.system())).isTrue();
        gate(folder, RecoveryChoice.CHECKPOINT_ONLY, true);
        assertThat(decisions).hasValue(1);
        assertThat(JournalState.records(ProjectContext.forProject(folder))).isEmpty();
    }
    @Test void sealedCheckpointOpensPlainlyAndOnlyThenClearsStaleSegments() throws Exception {
        Path folder = project("Song");
        Path checkpoint = Files.writeString(Files.createDirectories(folder.resolve("checkpoints"))
                .resolve("checkpoint-001-20261025T090000.daw"), xml("Song"));
        JournalWriter writer = new JournalWriter(ProjectContext.forProject(folder), JournalConfig.DEFAULT, true, null);
        writer.start();
        writer.onCheckpointSucceeded(writer.captureSequence(), checkpoint, Instant.now());
        writer.sealAndClose(Instant.now());
        gate(folder, RecoveryChoice.CANCEL, true);
        assertThat(decisions).hasValue(0);
        assertThat(JournalState.records(ProjectContext.forProject(folder))).isEmpty();
    }
    @Test void replayCommitsBeforePriorJournalIsCleared() throws Exception {
        Path folder = project("Song");
        crash(folder);
        assertThat(gate(folder, RecoveryChoice.REPLAY_ALL, true).applied()).isEqualTo(1);
        assertThat(JournalState.records(ProjectContext.forProject(folder))).isEmpty();
        assertThat(Files.readString(folder.resolve("project.daw"))).contains("Song");
    }
    @Test void optingOutSuppressesJournalRecoveryAndPreservesExistingEvidence() throws Exception {
        Path folder = project("Song");
        crash(folder);
        byte[] before = Files.readAllBytes(folder.resolve("journal/segment-000.bin"));
        try (Logs logs = new Logs(OpenRecoveryGate.class)) {
            gate(folder, RecoveryChoice.CANCEL, false);
            assertThat(logs.lines).anyMatch(line -> line.contains("degraded lock-heuristic mode"));
        }
        assertThat(decisions).hasValue(0);
        assertThat(Files.readAllBytes(folder.resolve("journal/segment-000.bin"))).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = RecoveryChoice.class, names = {"REPLAY_ALL", "CHECKPOINT_ONLY"})
    void recoveringAHeadTempWhileOptedOutPreservesTheUnreplayedJournalAndBase(RecoveryChoice choice) throws Exception {
        Path folder = project("Opted out");
        crash(folder);
        byte[] journal = Files.readAllBytes(folder.resolve("journal/segment-000.bin"));
        byte[] base = Files.readAllBytes(folder.resolve("journal").resolve(JournalState.BASE_FILE));
        Files.setLastModifiedTime(folder.resolve("project.daw"), FileTime.from(Instant.now().minusSeconds(100)));
        Path temp = Files.writeString(folder.resolve(".project.daw.0123456789abcdef.tmp"), xml("Newer complete head"));
        gate(folder, choice, false);
        assertThat(decisions).hasValue(1);
        assertThat(Files.readAllBytes(folder.resolve("journal/segment-000.bin"))).isEqualTo(journal);
        assertThat(Files.readAllBytes(folder.resolve("journal").resolve(JournalState.BASE_FILE))).isEqualTo(base);
        assertThat(temp).doesNotExist();
        assertThat(Files.readString(folder.resolve("project.daw")))
                .contains(choice == RecoveryChoice.REPLAY_ALL ? "Newer complete head" : "Opted out");
    }
    @Test void cancelPreservesTheOutgoingModelLockAndIncomingEvidence() throws Exception {
        Path incoming = project("Incoming");
        crash(incoming);
        ProjectManager manager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false)));
        Path outgoing = manager.createProject("Outgoing", workspace).projectPath();
        ProjectMetadata current = manager.getCurrentProject();
        byte[] oldLock = Files.readAllBytes(outgoing.resolve(ProjectLockManager.LOCK_FILE_NAME));
        OpenRecoveryGate gate = new OpenRecoveryGate(manager, () -> true, _ -> RecoveryChoice.CANCEL, (_, _, _) -> { });
        manager.setRecoveryGate((path, readOnly, lease) -> gate.run(path, readOnly, lease));
        try {
            assertThatThrownBy(() -> worker(() -> manager.prepareOpen(incoming))).hasCauseInstanceOf(OpenRecoveryGate.Cancelled.class);
            assertThat(manager.getCurrentProject()).isSameAs(current);
            assertThat(Files.readAllBytes(outgoing.resolve(ProjectLockManager.LOCK_FILE_NAME))).isEqualTo(oldLock);
            assertThat(incoming.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
            assertThat(JournalState.records(ProjectContext.forProject(incoming))).isNotEmpty();
        } finally { manager.closeProject().join(); }
    }
    @Test void freshForeignLockResolvesBeforeTheGateReevaluatesEvidence() throws Exception {
        Path folder = project("Song");
        crash(folder);
        ProjectLockManager foreign = new ProjectLockManager(java.time.InstantSource.fixed(Instant.now().minusSeconds(30)),
                "crashed engineer", "studio", 99999999L);
        foreign.forceAcquire(folder);
        List<String> order = new ArrayList<>();
        ProjectManager manager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false)));
        manager.setLockConflictHandler((_, stale) -> {
            assertThat(stale).isFalse();
            order.add("conflict");
            try { foreign.release(); } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return LockConflictResolution.TAKE_OVER;
        });
        OpenRecoveryGate gate = new OpenRecoveryGate(manager, () -> true, _ -> {
            order.add("recovery");
            return RecoveryChoice.CHECKPOINT_ONLY;
        }, (_, _, _) -> { });
        manager.setRecoveryGate((path, readOnly, lease) -> { order.add("scan"); gate.run(path, readOnly, lease); });
        worker(() -> { try (ProjectManager.PreparedOpen ignored = manager.prepareOpen(folder)) { return null; } });
        assertThat(order).containsExactly("conflict", "scan", "recovery");
    }
    @Test void readOnlyLeaseDoesNotTouchJournalTempsOrAnActiveManifestOlderThanFourHours() throws Exception {
        Path folder = project("Song");
        crash(folder);
        // A directory named like a segment: reading the journal fails on it, so a read-only open that
        // built a plan would fail, while one that reads nothing opens.
        Path segmentDirectory = Files.createDirectory(folder.resolve("journal/segment-001.bin"));
        byte[] segment = Files.readAllBytes(folder.resolve("journal/segment-000.bin"));
        byte[] sessionBase = Files.readAllBytes(folder.resolve("journal").resolve(JournalState.BASE_FILE));
        Path temp = Files.writeString(folder.resolve(".project.daw.0123456789abcdef.tmp"), xml("Temp"));
        SessionManager sessions = new SessionManager();
        sessions.openSession(folder, Instant.now().minusSeconds(6 * 3600));
        List<Path> manifests;
        try (java.util.stream.Stream<Path> paths = Files.walk(folder.resolve("sessions"))) {
            manifests = paths.filter(Files::isRegularFile).toList();
        }
        List<String> before = manifests.stream().map(path -> {
            try { return Files.readString(path); } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }).toList();
        ProjectLockManager foreign = new ProjectLockManager();
        foreign.forceAcquire(folder);
        ProjectManager manager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false)));
        manager.setLockConflictHandler((_, _) -> LockConflictResolution.OPEN_READ_ONLY);
        OpenRecoveryGate gate = new OpenRecoveryGate(manager, () -> true, _ -> { throw new AssertionError("read-only choice"); }, (_, _, _) -> { });
        manager.setRecoveryGate((path, readOnly, lease) -> gate.run(path, readOnly, lease));
        try {
            worker(() -> { try (ProjectManager.PreparedOpen lease = manager.prepareOpen(folder)) {
                assertThat(lease.readOnly()).isTrue(); return null;
            } });
            assertThat(temp).exists();
            assertThat(segmentDirectory).isDirectory().isEmptyDirectory();
            assertThat(Files.readAllBytes(folder.resolve("journal/segment-000.bin"))).isEqualTo(segment);
            assertThat(Files.readAllBytes(folder.resolve("journal").resolve(JournalState.BASE_FILE))).isEqualTo(sessionBase);
            assertThat(sessions.history(folder)).hasSize(1).allMatch(session -> session.isActive());
            for (int i = 0; i < manifests.size(); i++) assertThat(Files.readString(manifests.get(i))).isEqualTo(before.get(i));
        } finally { foreign.release(); }
    }

    @Test void everyExactTempIsHandledAndNearMissesRemainAfterTempRecovery() throws Exception {
        Path folder = project("Song");
        Path head = folder.resolve("project.daw");
        Files.setLastModifiedTime(head, FileTime.from(Instant.now().minusSeconds(100)));
        Path best = Files.writeString(folder.resolve(".project.daw.0123456789abcdef.tmp"), xml("Complete newer head"));
        Files.writeString(folder.resolve(".project.daw.1111111111111111.tmp"), "<daw-project");
        Files.writeString(folder.resolve(".notes.json.2222222222222222.tmp"), "{}");
        Files.writeString(Files.createDirectories(folder.resolve("snapshots")).resolve(".mix.daw.3333333333333333.tmp"), "partial");
        Path near = Files.writeString(folder.resolve(".project.daw.0123456789ABCDEG.tmp"), "keep");
        try (Logs logs = new Logs(RecoveryTemps.class)) {
            gate(folder, RecoveryChoice.REPLAY_ALL, true);
            assertThat(logs.lines.stream().filter(line -> line.contains("Handled 4 atomic-writer temp"))).hasSize(1);
        }
        assertThat(decisions).hasValue(1);
        assertThat(Files.readString(head)).contains("Complete newer head");
        assertThat(best).doesNotExist();
        assertThat(near).exists();
        try (java.util.stream.Stream<Path> paths = Files.walk(folder)) {
            assertThat(paths.filter(Files::isRegularFile).filter(path -> AtomicFileWriter.publishedNameOf(path.getFileName().toString()).isPresent())).isEmpty();
        }
    }
    @Test void mixedTempAndJournalPreservesBothIndependentStates() throws Exception {
        Path folder = project("Journal base");
        crash(folder);
        Files.setLastModifiedTime(folder.resolve("project.daw"), FileTime.from(Instant.now().minusSeconds(100)));
        String tempXml = xml("Independent newer temp");
        Path temp = Files.writeString(folder.resolve(".project.daw.0123456789abcdef.tmp"), tempXml);
        assertThat(gate(folder, RecoveryChoice.REPLAY_ALL, true).applied()).isEqualTo(1);
        assertThat(Files.readString(folder.resolve("project.daw"))).contains("Journal base");
        try (java.util.stream.Stream<Path> snapshots = Files.list(folder.resolve("snapshots"))) {
            assertThat(snapshots.toList()).singleElement().satisfies(path -> {
                try { assertThat(Files.readString(path)).isEqualTo(tempXml); } catch (IOException failure) { throw new AssertionError(failure); }
            });
        }
        assertThat(temp).doesNotExist();
    }
    @Test void linkedHeadTempsAreDiscoveredBesideTheFinalTarget() throws Exception {
        Path folder = project("Song");
        Path target = Files.writeString(Files.createDirectories(workspace.resolve("shared")).resolve("Song.daw"), xml("Shared"));
        Files.delete(folder.resolve("project.daw"));
        try { Files.createSymbolicLink(folder.resolve("project.daw"), Path.of("..", "shared", "Song.daw")); }
        catch (IOException | UnsupportedOperationException unavailable) { assumeTrue(false, "Symbolic links unavailable"); }
        Files.setLastModifiedTime(target, FileTime.from(Instant.now().minusSeconds(100)));
        Path temp = Files.writeString(target.getParent().resolve(".Song.daw.0123456789abcdef.tmp"), xml("Recovered shared head"));
        gate(folder, RecoveryChoice.REPLAY_ALL, true);
        assertThat(Files.isSymbolicLink(folder.resolve("project.daw"))).isTrue();
        assertThat(Files.readString(target)).contains("Recovered shared head");
        assertThat(temp).doesNotExist();
    }

    @Test void failedReplayCommitPreservesHeadJournalAndTempsAndReleasesTheTargetLock() throws Exception {
        Path folder = project("Original");
        crash(folder);
        Path temp = Files.writeString(folder.resolve(".notes.json.0123456789abcdef.tmp"), "{} ");
        byte[] segment = Files.readAllBytes(folder.resolve("journal/segment-000.bin"));
        String head = Files.readString(folder.resolve("project.daw"));
        ProjectManager manager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false)));
        OpenRecoveryGate gate = new OpenRecoveryGate(manager, () -> true, _ -> RecoveryChoice.REPLAY_ALL, (_, _, beforeReplace) -> {
            assertThat(Files.readAllBytes(folder.resolve("journal/segment-000.bin"))).isEqualTo(segment);
            beforeReplace.verify();
            throw new IOException("atomic replacement refused");
        });
        manager.setRecoveryGate((path, readOnly, lock) -> gate.run(path, readOnly, lock));
        assertThatThrownBy(() -> worker(() -> manager.prepareOpen(folder))).cause()
                .as("a write that fails after the lease check passed is the write's failure")
                .isInstanceOfSatisfying(OpenRecoveryGate.ReplayFailed.class, failed -> assertThat(failed.writing()).isTrue())
                .hasMessage("atomic replacement refused");
        assertThat(Files.readString(folder.resolve("project.daw"))).isEqualTo(head);
        assertThat(Files.readAllBytes(folder.resolve("journal/segment-000.bin"))).isEqualTo(segment);
        assertThat(temp).exists();
        assertThat(folder.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
    }

    @Test void theChooserSeesTheVerifiedRecoveryPointTheReplayUsesNotTheNewestCheckpoint() throws Exception {
        Path folder = project("Saved head");
        capturedJournal(folder);
        AtomicReference<RecoverySummary> seen = new AtomicReference<>();
        OpenRecoveryGate.Result result = gateWith(folder, true, summary -> { seen.set(summary); return RecoveryChoice.REPLAY_ALL; });
        assertThat(seen.get().recovery()).isInstanceOfSatisfying(RecoverySummary.Replay.class, replay -> {
            assertThat(replay.baseKind()).isEqualTo(RecoverySummary.BaseKind.CHECKPOINT_CAPTURE);
            assertThat(replay.checkpointIndex()).hasValue(1);
            assertThat(replay.changes().count()).isEqualTo(1);
            assertThat(replay.changes().types()).containsExactly("ClipEvent.Moved");
        });
        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.promotedInterruptedSave()).isFalse();
        assertThat(result.door(OpenDoor.PLAIN)).as("the change the dialog said cannot be rebuilt is announced as such")
                .isEqualTo(new OpenDoor.JournalReplay(1, 1, List.of()));
        assertThat(Files.readString(folder.resolve("project.daw"))).contains("Captured base");
    }

    @Test void aTempOnlyRecoveryIsDescribedAsPromotingTheInterruptedSave() throws Exception {
        Path folder = project("Clean head");
        Files.writeString(Files.createDirectories(folder.resolve("checkpoints")).resolve("checkpoint-004-20261025T090000.daw"),
                xml("Checkpoint four"));
        Path temp = interruptedSave(folder, "Interrupted save");
        Instant writtenAt = Files.getLastModifiedTime(temp).toInstant();
        AtomicReference<RecoverySummary> seen = new AtomicReference<>();
        OpenRecoveryGate.Result result = gateWith(folder, true, summary -> { seen.set(summary); return RecoveryChoice.REPLAY_ALL; });
        assertThat(seen.get().recovery())
                .isEqualTo(new RecoverySummary.PromoteNewerHead(writtenAt, temp.getFileName().toString()));
        assertThat(result.promotedInterruptedSave()).isTrue();
        assertThat(result.applied()).isZero();
        assertThat(OpenAnnouncement.of(result.door(OpenDoor.PLAIN), folder, MissingAssetsReport.EMPTY, List.of()))
                .as("announced as the interrupted save, never as 0 journal changes")
                .isEqualTo(new OpenAnnouncement(NotificationLevel.SUCCESS, "Recovered an interrupted save", false));
        assertThat(Files.readString(folder.resolve("project.daw"))).contains("Interrupted save");
        assertThat(temp).doesNotExist();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "changed", "damaged"})
    void aReplayThatCannotStartIsDescribedBeforeTheChoiceAndChangesNothing(String damage) throws Exception {
        Path folder = project("Session head");
        ProjectContext context = ProjectContext.forProject(folder);
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, Instant.now());
            writer.offer("TrackEvent.Renamed", UUID.randomUUID(), null, Instant.now());
            writer.forceFlush();
        }
        Path base = context.journalDirectory().resolve(JournalState.BASE_FILE);
        Path segment = context.journalDirectory().resolve("segment-000.bin");
        RecoverySummary.CannotReplay expected = switch (damage) {
            case "missing" -> {
                Files.delete(base);
                yield new RecoverySummary.BaseMissing();
            }
            case "changed" -> {
                Files.writeString(base, xml("Changed baseline"));
                yield new RecoverySummary.BaseChanged();
            }
            default -> {
                flipAByteInsideTheSecondFrame(context, segment);
                yield new RecoverySummary.SegmentDamaged("segment-000.bin");
            }
        };
        Path temp = interruptedSave(folder, "Interrupted save");
        Path notes = Files.writeString(folder.resolve(".notes.json.0123456789abcdef.tmp"), "{} ");
        byte[] head = Files.readAllBytes(folder.resolve("project.daw"));
        Evidence before = Evidence.of(folder, temp, notes);
        AtomicReference<RecoverySummary> seen = new AtomicReference<>();
        Throwable thrown = catchThrowable(() -> gateWith(folder, true, summary -> {
            seen.set(summary);
            return RecoveryChoice.REPLAY_ALL;
        }));
        assertThat(thrown).hasCauseInstanceOf(OpenRecoveryGate.ReplayFailed.class);
        if (damage.equals("damaged")) {
            assertThat(thrown.getCause()).as("the damaged segment is named")
                    .hasMessage("journal segment failed checksum verification: " + segment);
        }
        assertThat(seen.get().recovery()).isInstanceOfSatisfying(RecoverySummary.ReplayFails.class,
                fails -> assertThat(fails.reason()).isEqualTo(expected));
        assertThat(Evidence.of(folder, temp, notes)).isEqualTo(before);
        assertThat(folder.resolve("snapshots")).doesNotExist();
        assertThat(Files.readAllBytes(folder.resolve("project.daw"))).isEqualTo(head);
    }

    /** Flips one byte inside the second frame of {@code segment}, so a complete record mid-segment fails its checksum. */
    private static void flipAByteInsideTheSecondFrame(ProjectContext context, Path segment) throws IOException {
        byte[] bytes = Files.readAllBytes(segment);
        int corruptAt = JournalState.records(context).getFirst().serializedSize() + 6;
        assertThat(bytes.length).isGreaterThan(corruptAt);
        bytes[corruptAt] ^= (byte) 0xFF;
        Files.write(segment, bytes);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "false, true, CHECKPOINT_ONLY",
            "false, false, CHECKPOINT_ONLY",
            "true, true, REPLAY_ALL",
            "true, true, CHECKPOINT_ONLY"})
    void theInterruptedSaveIsKeptAsAnInterruptedSaveSnapshotBeforeAnythingIsDeleted(boolean owedJournal, boolean enabled,
            RecoveryChoice choice) throws Exception {
        Path folder = project("Clean head");
        if (owedJournal || !enabled) crash(folder);
        Path temp = interruptedSave(folder, "Interrupted save");
        byte[] saved = Files.readAllBytes(temp);
        Evidence journal = Evidence.of(folder);
        try (Logs logs = new Logs(RecoveryTemps.class)) {
            gateWith(folder, enabled, _ -> choice);
            assertThat(logs.lines.stream().filter(line -> line.contains("Handled 1 atomic-writer temp"))).hasSize(1);
        }
        assertThat(files(folder.resolve("snapshots"))).singleElement().satisfies(snapshot ->
                assertThat(Files.readAllBytes(snapshot)).isEqualTo(saved));
        assertThat(new com.benesquivelmusic.daw.core.persistence.snapshot.SnapshotStore()
                .listSnapshotDirectory(folder.resolve("snapshots")))
                .singleElement().satisfies(snapshot -> assertThat(snapshot.name()).isEqualTo("interrupted save"));
        assertThat(temp).doesNotExist();
        try (java.util.stream.Stream<Path> paths = Files.walk(folder)) {
            assertThat(paths.filter(Files::isRegularFile)
                    .filter(path -> AtomicFileWriter.publishedNameOf(path.getFileName().toString()).isPresent())).isEmpty();
        }
        if (!enabled) assertThat(Evidence.of(folder)).as("the opted-out journal is kept").isEqualTo(journal);
    }

    @Test void aFailedCopyOfTheInterruptedSaveAbortsRestoreCheckpointOnlyWithNothingDeleted() throws Exception {
        Path folder = project("Clean head");
        crash(folder);
        Path temp = interruptedSave(folder, "Interrupted save");
        Path blocker = Files.writeString(folder.resolve("snapshots"), "a file where the snapshots folder belongs");
        Evidence before = Evidence.of(folder, temp, blocker);
        assertThatThrownBy(() -> gateWith(folder, true, _ -> RecoveryChoice.CHECKPOINT_ONLY))
                .cause().isInstanceOf(IOException.class).isNotInstanceOf(OpenRecoveryGate.ReplayFailed.class)
                .as("it names the interrupted save, where it could not be copied, why (the failure's type, never its path), and that nothing was deleted")
                .hasMessage("The interrupted save .project.daw.0123456789abcdef.tmp could not be copied to "
                        + folder.resolve("snapshots") + " (FileAlreadyExistsException); nothing was deleted")
                .hasCauseInstanceOf(FileAlreadyExistsException.class);
        assertThat(Evidence.of(folder, temp, blocker)).isEqualTo(before);
    }

    @Test void restoreCheckpointOnlyWithoutAProjectFileKeepsTheJournalAndRecoverEverythingStillRecovers() throws Exception {
        Path folder = Files.createDirectories(workspace.resolve("Legacy"));
        Path checkpoints = Files.createDirectories(folder.resolve("checkpoints"));
        Files.writeString(checkpoints.resolve("checkpoint-002-20261025T090000.daw"), xml("Older checkpoint"));
        Files.writeString(checkpoints.resolve("checkpoint-003-20261025T100000.daw"), xml("Newest checkpoint"));
        try (JournalWriter writer = new JournalWriter(ProjectContext.forProject(folder), JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, Instant.now());
            writer.offer("TrackEvent.Renamed", UUID.randomUUID(), null, Instant.now());
            writer.forceFlush();
        }
        Evidence before = Evidence.of(folder);
        assertThatThrownBy(() -> gateWith(folder, true, _ -> RecoveryChoice.CHECKPOINT_ONLY))
                .cause().isInstanceOf(IOException.class)
                .hasMessage("project.daw is missing, so Restore checkpoint only cannot open this project; the journal was kept");
        assertThat(Evidence.of(folder)).isEqualTo(before);
        assertThat(folder.resolve("project.daw")).doesNotExist();

        assertThat(gateWith(folder, true, _ -> RecoveryChoice.REPLAY_ALL).applied()).isEqualTo(2);
        assertThat(Files.readString(folder.resolve("project.daw"))).contains("Newest checkpoint");
        assertThat(JournalState.records(ProjectContext.forProject(folder))).isEmpty();
    }

    @Test void aRecoveryPointOverwrittenAfterTheChoiceFailsTheReplayAndWritesNothing() throws Exception {
        Path folder = project("Saved head");
        Path captured = capturedJournal(folder);
        String head = Files.readString(folder.resolve("project.daw"));
        assertThatThrownBy(() -> gateWith(folder, true, _ -> {
            Files.writeString(captured, xml("Replaced while the dialog was open"));
            return RecoveryChoice.REPLAY_ALL;
        })).hasCauseInstanceOf(OpenRecoveryGate.ReplayFailed.class);
        assertThat(Files.readString(folder.resolve("project.daw"))).isEqualTo(head);
        assertThat(JournalState.records(ProjectContext.forProject(folder))).isNotEmpty();
        assertThat(JournalState.needsRecovery(ProjectContext.forProject(folder))).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void anInterruptedSaveChangedAfterTheChoiceIsNotPromoted(boolean sizeChanged) throws Exception {
        Path folder = project("Clean head");
        Path temp = interruptedSave(folder, "Interrupted save");
        String head = Files.readString(folder.resolve("project.daw"));
        assertThatThrownBy(() -> gateWith(folder, true, _ -> {
            FileTime described = Files.getLastModifiedTime(temp);
            if (sizeChanged) {
                Files.writeString(temp, xml("Interrupted save, rewritten"));
                Files.setLastModifiedTime(temp, described);
            } else {
                Files.setLastModifiedTime(temp, FileTime.from(described.toInstant().plusSeconds(1)));
            }
            return RecoveryChoice.REPLAY_ALL;
        })).hasCauseInstanceOf(OpenRecoveryGate.ReplayFailed.class);
        assertThat(Files.readString(folder.resolve("project.daw"))).isEqualTo(head);
        assertThat(temp).exists();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"journal replay", "interrupted save"})
    void aTakeoverBeforeTheReplaceFailsTheOpenAsTheStolenLockWithNothingReplacedOrCleared(String recovery) throws Exception {
        Path folder = project("Taken over");
        Path temp;
        if (recovery.equals("journal replay")) {
            crash(folder);
            temp = Files.writeString(folder.resolve(".notes.json.0123456789abcdef.tmp"), "{} ");
        } else {
            temp = interruptedSave(folder, "Interrupted save");
        }
        byte[] head = Files.readAllBytes(folder.resolve("project.daw"));
        Evidence before = Evidence.of(folder, temp);
        AtomicInteger replaces = new AtomicInteger();
        ProjectLockManager foreign = anotherSession();
        try {
            Throwable thrown = catchThrowable(() -> recoverEverything(folder, (target, content, beforeReplace) -> {
                foreign.forceAcquire(folder);
                beforeReplace.verify();
                replaceHead(target, content);
                replaces.incrementAndGet();
            }, plan -> new JournalReplayer().recover(plan)));
            assertThat(thrown).cause().as("the lost lease, never a failed write and its read-only fallback")
                    .isInstanceOf(LockStolenException.class)
                    .hasMessage("Lock has been taken over by other engineer@other studio");
            // A replayed head can serialize to the head's own bytes, so the replace itself is counted.
            assertThat(replaces).as("the head was not replaced").hasValue(0);
            assertThat(Files.readAllBytes(folder.resolve("project.daw"))).isEqualTo(head);
            assertThat(Evidence.of(folder, temp)).as("the journal and the temps are not cleared").isEqualTo(before);
        } finally { foreign.release(); }
    }

    @Test void aLeaseNoLongerHeldBeforeTheReplaceFailsAsItselfNotAsAFailedWrite() throws Exception {
        Path folder = project("Released");
        crash(folder);
        byte[] head = Files.readAllBytes(folder.resolve("project.daw"));
        Evidence before = Evidence.of(folder);
        AtomicInteger replaces = new AtomicInteger();
        Throwable thrown = catchThrowable(() -> recoverEverything(folder, (target, content, beforeReplace) -> {
            openLease.get().release();
            beforeReplace.verify();
            replaceHead(target, content);
            replaces.incrementAndGet();
        }, plan -> new JournalReplayer().recover(plan)));
        assertThat(thrown).cause().as("a lease check's plain IOException, not ReplayFailed")
                .isNotInstanceOf(OpenRecoveryGate.ReplayFailed.class)
                .hasMessage("This session holds no project lock (NONE); it does not write the project folder");
        assertThat(replaces).as("the head was not replaced").hasValue(0);
        assertThat(Files.readAllBytes(folder.resolve("project.daw"))).isEqualTo(head);
        assertThat(Evidence.of(folder)).isEqualTo(before);
    }

    @Test void aTakeoverDuringTheReplayFailsTheOpenBeforeTheInterruptedSaveIsCopiedOrTheHeadBackedUp() throws Exception {
        Path folder = project("Journal base");
        crash(folder);
        Path temp = interruptedSave(folder, "Interrupted save");
        byte[] head = Files.readAllBytes(folder.resolve("project.daw"));
        Evidence before = Evidence.of(folder, temp);
        Path backup = folder.resolve("project.daw.recovered.bak");
        AtomicInteger replays = new AtomicInteger();
        ProjectLockManager foreign = anotherSession();
        try {
            Throwable thrown = catchThrowable(() -> recoverEverything(folder, (target, content, beforeReplace) -> {
                Files.copy(target, backup); // as the production commit backs the head up first
                beforeReplace.verify();
                replaceHead(target, content);
            }, plan -> {
                RecoveryResult replayed = new JournalReplayer().recover(plan);
                replays.incrementAndGet();
                try { foreign.forceAcquire(folder); }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                return replayed;
            }));
            assertThat(replays).as("the takeover came after the lease check that follows the choice").hasValue(1);
            assertThat(thrown).cause().isInstanceOf(LockStolenException.class)
                    .hasMessage("Lock has been taken over by other engineer@other studio");
            assertThat(files(folder.resolve("snapshots"))).as("no Interrupted save snapshot").isEmpty();
            assertThat(backup).as("no backup").doesNotExist();
            assertThat(Files.readAllBytes(folder.resolve("project.daw"))).isEqualTo(head);
            assertThat(Evidence.of(folder, temp)).isEqualTo(before);
        } finally { foreign.release(); }
    }

    /** A takeover can be what made the replay fail, so the lock wins over the failed replay's read-only fallback. */
    @Test void aReplayThatFailsAfterATakeoverFailsTheOpenAsTheStolenLockWithTheFailedReplayKept() throws Exception {
        Path folder = project("Journal base");
        crash(folder);
        Path temp = interruptedSave(folder, "Interrupted save");
        byte[] head = Files.readAllBytes(folder.resolve("project.daw"));
        Evidence before = Evidence.of(folder, temp);
        Path backup = folder.resolve("project.daw.recovered.bak");
        JournalRecoveryException replayFailure =
                new JournalRecoveryException("Journal replay rolled back", new IOException("a segment vanished"));
        ProjectLockManager foreign = anotherSession();
        try {
            Throwable thrown = catchThrowable(() -> recoverEverything(folder, (target, content, beforeReplace) -> {
                Files.copy(target, backup); // as the production commit backs the head up first
                beforeReplace.verify();
                replaceHead(target, content);
            }, plan -> {
                try { foreign.forceAcquire(folder); }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                throw replayFailure;
            }));
            assertThat(thrown).cause().as("the lost lease, never the failed replay's read-only fallback")
                    .isInstanceOf(LockStolenException.class)
                    .hasMessage("Lock has been taken over by other engineer@other studio");
            assertThat(List.of(thrown.getCause().getSuppressed())).as("the failed replay, kept for the log")
                    .singleElement().isInstanceOfSatisfying(OpenRecoveryGate.ReplayFailed.class, failed -> {
                        assertThat(failed.writing()).isFalse();
                        assertThat(failed.getCause()).isSameAs(replayFailure);
                    });
            assertThat(files(folder.resolve("snapshots"))).as("no Interrupted save snapshot").isEmpty();
            assertThat(backup).as("no backup").doesNotExist();
            assertThat(Files.readAllBytes(folder.resolve("project.daw"))).isEqualTo(head);
            assertThat(Evidence.of(folder, temp)).isEqualTo(before);
        } finally { foreign.release(); }
    }

    /** A takeover can be what made the write fail before its lease check, so the lock wins over the failed write. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"journal replay", "interrupted save"})
    void aWriteThatFailsAfterATakeoverFailsTheOpenAsTheStolenLockWithTheFailedWriteKept(String recovery) throws Exception {
        Path folder = project("Taken over");
        Path temp;
        if (recovery.equals("journal replay")) {
            crash(folder);
            temp = Files.writeString(folder.resolve(".notes.json.0123456789abcdef.tmp"), "{} ");
        } else {
            temp = interruptedSave(folder, "Interrupted save");
        }
        byte[] head = Files.readAllBytes(folder.resolve("project.daw"));
        Evidence before = Evidence.of(folder, temp);
        IOException writeFailure = new IOException("the backup could not be published");
        ProjectLockManager foreign = anotherSession();
        try {
            Throwable thrown = catchThrowable(() -> recoverEverything(folder, (_, _, _) -> {
                foreign.forceAcquire(folder);
                throw writeFailure; // before the commit's own lease check
            }, plan -> new JournalReplayer().recover(plan)));
            assertThat(thrown).cause().as("the lost lease, never a failed write and its read-only fallback")
                    .isInstanceOf(LockStolenException.class)
                    .hasMessage("Lock has been taken over by other engineer@other studio");
            assertThat(List.of(thrown.getCause().getSuppressed())).as("the failed write, kept for the log")
                    .singleElement().isInstanceOfSatisfying(OpenRecoveryGate.ReplayFailed.class, failed -> {
                        assertThat(failed.writing()).isTrue();
                        assertThat(failed.getCause()).isSameAs(writeFailure);
                    });
            assertThat(Files.readAllBytes(folder.resolve("project.daw"))).isEqualTo(head);
            assertThat(Evidence.of(folder, temp)).as("the journal and the temps are not cleared").isEqualTo(before);
        } finally { foreign.release(); }
    }

    @Test void aFailedCheckTheCommitReplacedWithItsOwnFailureFailsAsTheCheckWithThatFailureKept() throws Exception {
        Path folder = project("Released");
        crash(folder);
        byte[] head = Files.readAllBytes(folder.resolve("project.daw"));
        Evidence before = Evidence.of(folder);
        IOException commitsOwn = new IOException("the commit's own account of the refusal");
        Throwable thrown = catchThrowable(() -> recoverEverything(folder, (_, _, beforeReplace) -> {
            openLease.get().release();
            try { beforeReplace.verify(); }
            catch (IOException refused) { throw commitsOwn; }
        }, plan -> new JournalReplayer().recover(plan)));
        assertThat(thrown).cause().isNotInstanceOf(OpenRecoveryGate.ReplayFailed.class)
                .hasMessage("This session holds no project lock (NONE); it does not write the project folder");
        assertThat(List.of(thrown.getCause().getSuppressed())).as("the commit's failure, kept for the log")
                .containsExactly(commitsOwn);
        assertThat(Files.readAllBytes(folder.resolve("project.daw"))).isEqualTo(head);
        assertThat(Evidence.of(folder)).isEqualTo(before);
    }

    /**
     * A check that fails, its lease held again before anything could check it once more, still fails
     * the open as that check: the step decides. A gate that took it for a failed write would re-check
     * before reporting it, find the lease held, and show the read-only fallback.
     */
    @Test void aCheckThatFailsWhileTheLeaseIsHeldAgainAtTheRecheckFailsAsItselfNotAsAFailedWrite() throws Exception {
        Path folder = project("Held again");
        crash(folder);
        byte[] head = Files.readAllBytes(folder.resolve("project.daw"));
        Evidence before = Evidence.of(folder);
        AtomicReference<IOException> checkFailure = new AtomicReference<>();
        AtomicReference<ProjectLockManager.AcquisitionResult> heldAgain = new AtomicReference<>();
        Throwable thrown = catchThrowable(() -> recoverEverything(folder, (_, _, beforeReplace) -> {
            ProjectLockManager lease = openLease.get();
            lease.release();
            try { beforeReplace.verify(); }
            catch (IOException refused) {
                checkFailure.set(refused);
                heldAgain.set(lease.tryAcquire(folder));
                throw refused;
            }
        }, plan -> new JournalReplayer().recover(plan)));
        assertThat(checkFailure.get()).as("the check failed")
                .hasMessage("This session holds no project lock (NONE); it does not write the project folder");
        assertThat(heldAgain.get().wasAcquired()).as("the lease is held again, so a re-check would pass").isTrue();
        assertThat(thrown).cause().as("the check's own failure, never a failed write and its read-only fallback")
                .isSameAs(checkFailure.get())
                .isNotInstanceOf(OpenRecoveryGate.ReplayFailed.class);
        assertThat(thrown.getCause().getSuppressed()).isEmpty();
        assertThat(Files.readAllBytes(folder.resolve("project.daw"))).isEqualTo(head);
        assertThat(Evidence.of(folder)).isEqualTo(before);
    }

    /** A chooser that may touch the disk; it runs after the plan is built and before the choice acts. */
    @FunctionalInterface private interface Chooser { RecoveryChoice choose(RecoverySummary summary) throws IOException; }

    private OpenRecoveryGate.Result gateWith(Path folder, boolean enabled, Chooser chooser) throws Exception {
        ProjectManager manager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false)));
        AtomicReference<OpenRecoveryGate.Result> result = new AtomicReference<>();
        OpenRecoveryGate gate = new OpenRecoveryGate(manager, () -> enabled, summary -> {
            decisions.incrementAndGet();
            try { return chooser.choose(summary); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }, (head, content, beforeReplace) -> {
            beforeReplace.verify();
            replaceHead(head, content);
        });
        manager.setRecoveryGate((directory, readOnly, lock) -> result.set(gate.run(directory, readOnly, lock)));
        worker(() -> { try (ProjectManager.PreparedOpen ignored = manager.prepareOpen(folder)) { return null; } });
        return result.get();
    }

    /** Recover everything on {@code folder} through a prepared open, with {@code commit} and {@code replay}. */
    private void recoverEverything(Path folder, OpenRecoveryGate.Commit commit, OpenRecoveryGate.Replay replay) throws Exception {
        ProjectManager manager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false)));
        OpenRecoveryGate gate = new OpenRecoveryGate(manager, () -> true, _ -> RecoveryChoice.REPLAY_ALL, commit, replay);
        manager.setRecoveryGate((directory, readOnly, lock) -> {
            openLease.set(lock);
            gate.run(directory, readOnly, lock);
        });
        worker(() -> { try (ProjectManager.PreparedOpen ignored = manager.prepareOpen(folder)) { return null; } });
    }

    /** Replaces {@code head} as the production commit does once its lease check has passed. */
    private static void replaceHead(Path head, String content) throws IOException {
        new AtomicFileWriter().writeString(head, content,
                AtomicFileWriter.ReplacePermission.TARGET, AtomicFileWriter.ReplaceOption.FOLLOW_LINK_TO_DAW_FILE);
    }

    /** A second session on another machine, which takes the folder over. */
    private static ProjectLockManager anotherSession() {
        return new ProjectLockManager(java.time.InstantSource.system(), "other engineer", "other studio", 4242L);
    }

    /**
     * An unsealed journal whose verified capture is checkpoint #1, with two changes before it and
     * one after, beside an unrelated, newer checkpoint #5.
     */
    private Path capturedJournal(Path folder) throws IOException {
        Path checkpoints = Files.createDirectories(folder.resolve("checkpoints"));
        Path captured = Files.writeString(checkpoints.resolve("checkpoint-001-20261025T090000.daw"), xml("Captured base"));
        Files.writeString(checkpoints.resolve("checkpoint-005-20261025T110000.daw"), xml("Stale unrelated five"));
        try (JournalWriter writer = new JournalWriter(ProjectContext.forProject(folder), JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, Instant.now());
            writer.offer("TrackEvent.Renamed", UUID.randomUUID(), null, Instant.now());
            writer.onCheckpointSucceeded(writer.captureSequence(), captured, Instant.now());
            writer.offer("ClipEvent.Moved", UUID.randomUUID(), null, Instant.now());
            writer.forceFlush();
        }
        return captured;
    }

    /** A complete save of project.daw that did not finish: a head temp newer than the head. */
    private static Path interruptedSave(Path folder, String name) throws IOException {
        Files.setLastModifiedTime(folder.resolve("project.daw"), FileTime.from(Instant.now().minusSeconds(100)));
        return Files.writeString(folder.resolve(".project.daw.0123456789abcdef.tmp"), xml(name));
    }

    private static List<Path> files(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile).toList();
        }
    }

    /** The bytes of the journal's segments and session base, and of {@code others}, keyed by path. */
    private record Evidence(java.util.Map<Path, List<Byte>> bytes) {
        static Evidence of(Path folder, Path... others) throws IOException {
            java.util.Map<Path, List<Byte>> bytes = new java.util.TreeMap<>();
            List<Path> paths = new ArrayList<>(files(folder.resolve("journal")));
            paths.addAll(List.of(others));
            for (Path path : paths) {
                byte[] content = Files.readAllBytes(path);
                List<Byte> boxed = new ArrayList<>(content.length);
                for (byte value : content) boxed.add(value);
                bytes.put(path, boxed);
            }
            return new Evidence(bytes);
        }
    }

    private static final class Logs implements AutoCloseable {
        final List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.logging.Logger logger;
        final java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() == java.util.logging.Level.INFO.intValue()) lines.add(record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logs(Class<?> type) { logger = java.util.logging.Logger.getLogger(type.getName()); logger.addHandler(handler); }
        @Override public void close() { logger.removeHandler(handler); }
    }
}
