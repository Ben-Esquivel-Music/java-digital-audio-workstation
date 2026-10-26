package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.LockConflictResolution;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class ActiveProjectAliasTest {
    enum Action { OPEN, DISCARD }

    @TempDir
    Path workspace;

    @ParameterizedTest
    @EnumSource(Action.class)
    void symbolicAliasCannotAcquireOrDiscardTheActiveProject(Action action) throws Exception {
        exerciseAlias(action, false);
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    @EnabledOnOs(OS.WINDOWS)
    void junctionAliasCannotAcquireOrDiscardTheActiveProject(Action action) throws Exception {
        exerciseAlias(action, true);
    }

    private void exerciseAlias(Action action, boolean junction) throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            Path alias = workspace.resolve("alias");
            createAlias(alias, fixture.songA, junction);
            assertThat(Files.isSameFile(alias, fixture.songA)).isTrue();
            Path segment = LockedRecoveryDiscardTest.recoveryEvidence(fixture.songA);
            ProjectLock original = fixture.projectManager.getLockManager().currentLock().orElseThrow();
            DawProject model = fixture.project.get();
            AtomicInteger conflicts = new AtomicInteger();
            AtomicInteger journalCloses = new AtomicInteger();
            AtomicInteger journalOpens = new AtomicInteger();
            fixture.projectManager.setLockConflictHandler((_, _) -> {
                conflicts.incrementAndGet();
                return LockConflictResolution.TAKE_OVER;
            });

            runOnFx(() -> {
                fixture.lifecycle().enableRecoveryGate(() -> true,
                        () -> () -> journalCloses.incrementAndGet(), _ -> journalOpens.incrementAndGet());
                switch (action) {
                    case OPEN -> fixture.lifecycle().loadProjectFromPath(alias);
                    case DISCARD -> fixture.lifecycle().discardConfirmedRecovery(alias);
                }
            });
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "alias action refused");

            assertThat(conflicts).hasValue(0);
            assertThat(journalCloses).hasValue(0);
            assertThat(journalOpens).hasValue(0);
            assertThat(fixture.project.get()).isSameAs(model);
            assertThat(fixture.projectManager.getCurrentProject().projectPath()).isEqualTo(fixture.songA);
            assertThat(ProjectLockManager.peekLock(fixture.songA).orElseThrow().lockId()).isEqualTo(original.lockId());
            fixture.projectManager.getLockManager().verifyHeld();
            LockedRecoveryDiscardTest.assertEvidenceUnchanged(segment);
        }
    }

    private void createAlias(Path alias, Path target, boolean junction) throws Exception {
        if (junction) {
            Process command = new ProcessBuilder("cmd", "/c", "mklink", "/J", alias.toString(), target.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(workspace.resolve("mklink.log").toFile())
                    .start();
            assertThat(command.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assumeTrue(command.exitValue() == 0, "junction creation requires filesystem support");
        } else {
            try {
                Files.createSymbolicLink(alias, target);
            } catch (IOException | UnsupportedOperationException unsupported) {
                assumeTrue(false, "symbolic links are unavailable: " + unsupported);
            }
        }
    }
}
