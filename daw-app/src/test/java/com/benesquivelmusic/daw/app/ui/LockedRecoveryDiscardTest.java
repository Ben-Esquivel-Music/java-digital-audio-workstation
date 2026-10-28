package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.LockConflictResolution;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class LockedRecoveryDiscardTest {
    @TempDir
    Path workspace;

    @Test
    void activeProjectsJournalIsRefusedBeforeLockNegotiation() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            Path segment = recoveryEvidence(fixture.songA);
            ProjectLock original = fixture.projectManager.getLockManager().currentLock().orElseThrow();
            AtomicInteger conflicts = new AtomicInteger();
            fixture.projectManager.setLockConflictHandler((_, _) -> {
                conflicts.incrementAndGet();
                return LockConflictResolution.TAKE_OVER;
            });

            runOnFx(() -> fixture.lifecycle().discardConfirmedRecovery(fixture.songA));
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "active discard refused");

            assertThat(conflicts).hasValue(0);
            assertEvidenceUnchanged(segment);
            assertThat(ProjectLockManager.peekLock(fixture.songA).orElseThrow().lockId()).isEqualTo(original.lockId());
            fixture.projectManager.getLockManager().verifyHeld();
        }
    }

    @ParameterizedTest
    @EnumSource(value = LockConflictResolution.class, names = {"CANCEL", "OPEN_READ_ONLY"})
    void foreignWritableSessionKeepsItsEvidenceWhenDiscardIsNotWritable(LockConflictResolution decision)
            throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
             ProjectLockManager foreign = new ProjectLockManager()) {
            Path target = fixture.anotherProjectOnDisk("foreign");
            Path segment = recoveryEvidence(target);
            ProjectLock original = foreign.tryAcquire(target).acquiredLock();
            AtomicInteger conflicts = new AtomicInteger();
            fixture.projectManager.setLockConflictHandler((holder, stale) -> {
                assertThat(holder.lockId()).isEqualTo(original.lockId());
                assertThat(stale).isFalse();
                conflicts.incrementAndGet();
                return decision;
            });

            runOnFx(() -> fixture.lifecycle().discardConfirmedRecovery(target));
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "foreign discard refused");

            assertThat(conflicts).hasValue(1);
            assertEvidenceUnchanged(segment);
            foreign.verifyHeld();
            fixture.projectManager.getLockManager().verifyHeld();
            assertThat(fixture.projectManager.getCurrentProject().projectPath()).isEqualTo(fixture.songA);
        }
    }

    @Test
    void explicitTakeoverAllowsDiscardOfAFreshCrashedSession() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
             ProjectLockManager crashed = new ProjectLockManager()) {
            Path target = fixture.anotherProjectOnDisk("crashed");
            recoveryEvidence(target);
            ProjectLock original = crashed.tryAcquire(target).acquiredLock();
            assertThat(ProjectLockManager.isStale(original, Instant.now())).isFalse();
            AtomicInteger conflicts = new AtomicInteger();
            fixture.projectManager.setLockConflictHandler((holder, stale) -> {
                assertThat(holder.lockId()).isEqualTo(original.lockId());
                assertThat(stale).isFalse();
                conflicts.incrementAndGet();
                return LockConflictResolution.TAKE_OVER;
            });

            runOnFx(() -> fixture.lifecycle().discardConfirmedRecovery(target));
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "confirmed discard completed");

            assertThat(conflicts).hasValue(1);
            assertThat(target.resolve("journal")).doesNotExist();
            assertThat(ProjectLockManager.peekLock(target)).isEmpty();
            fixture.projectManager.getLockManager().verifyHeld();
            assertThat(fixture.projectManager.getCurrentProject().projectPath()).isEqualTo(fixture.songA);
        }
    }

    static Path recoveryEvidence(Path folder) throws Exception {
        Path journal = Files.createDirectories(folder.resolve("journal"));
        Files.writeString(journal.resolve("session-base.daw"), "immutable session base");
        return Files.writeString(journal.resolve("segment-000.bin"), "accepted journal records");
    }

    static void assertEvidenceUnchanged(Path segment) throws Exception {
        assertThat(Files.readString(segment)).isEqualTo("accepted journal records");
        assertThat(Files.readString(segment.resolveSibling("session-base.daw"))).isEqualTo("immutable session base");
    }
}
