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
        }, (head, content) -> {
            if (original != null) assertThat(Files.readAllBytes(segment)).isEqualTo(original);
            new AtomicFileWriter().writeString(head, content,
                    AtomicFileWriter.ReplacePermission.TARGET, AtomicFileWriter.ReplaceOption.FOLLOW_LINK_TO_DAW_FILE);
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
        OpenRecoveryGate gate = new OpenRecoveryGate(manager, () -> true, _ -> RecoveryChoice.CANCEL, (_, _) -> { });
        manager.setRecoveryGate((path, readOnly, lease) -> gate.run(path, readOnly, lease));
        try {
            assertThatThrownBy(() -> worker(() -> manager.prepareOpen(incoming))).hasCauseInstanceOf(OpenRecoveryGate.Cancelled.class);
            assertThat(manager.getCurrentProject()).isSameAs(current);
            assertThat(Files.readAllBytes(outgoing.resolve(ProjectLockManager.LOCK_FILE_NAME))).isEqualTo(oldLock);
            assertThat(incoming.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
            assertThat(JournalState.records(ProjectContext.forProject(incoming))).isNotEmpty();
        } finally { manager.abandonProject(); }
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
        }, (_, _) -> { });
        manager.setRecoveryGate((path, readOnly, lease) -> { order.add("scan"); gate.run(path, readOnly, lease); });
        worker(() -> { try (ProjectManager.PreparedOpen ignored = manager.prepareOpen(folder)) { return null; } });
        assertThat(order).containsExactly("conflict", "scan", "recovery");
    }
    @Test void readOnlyLeaseDoesNotTouchJournalTempsOrAnActiveManifestOlderThanFourHours() throws Exception {
        Path folder = project("Song");
        crash(folder);
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
        OpenRecoveryGate gate = new OpenRecoveryGate(manager, () -> true, _ -> { throw new AssertionError("read-only choice"); }, (_, _) -> { });
        manager.setRecoveryGate((path, readOnly, lease) -> gate.run(path, readOnly, lease));
        try {
            worker(() -> { try (ProjectManager.PreparedOpen lease = manager.prepareOpen(folder)) {
                assertThat(lease.readOnly()).isTrue(); return null;
            } });
            assertThat(temp).exists();
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
        OpenRecoveryGate gate = new OpenRecoveryGate(manager, () -> true, _ -> RecoveryChoice.REPLAY_ALL, (_, _) -> {
            assertThat(Files.readAllBytes(folder.resolve("journal/segment-000.bin"))).isEqualTo(segment);
            throw new IOException("atomic replacement refused");
        });
        manager.setRecoveryGate((path, readOnly, lock) -> gate.run(path, readOnly, lock));
        assertThatThrownBy(() -> worker(() -> manager.prepareOpen(folder))).hasCauseInstanceOf(IOException.class);
        assertThat(Files.readString(folder.resolve("project.daw"))).isEqualTo(head);
        assertThat(Files.readAllBytes(folder.resolve("journal/segment-000.bin"))).isEqualTo(segment);
        assertThat(temp).exists();
        assertThat(folder.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
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
