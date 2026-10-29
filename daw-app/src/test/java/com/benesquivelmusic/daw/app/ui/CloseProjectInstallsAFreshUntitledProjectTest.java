package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, the studio engineer's ruling R10 — the close protocol's half
 * (the window's own resets are stage 7's {@code CloseProjectResetsToUntitledTest}):
 * File ▸ Close Project runs the protocol without HIDE and then the window is
 * as at launch — a fresh in-memory Untitled project, New's construction
 * without a folder (its tempo the new project's default, loop and punch
 * off), a new undo history, the Welcome window over it — and the closed
 * folder's lock is released once its work ended. Close Project is available
 * while a folder is open or the in-memory project has unsaved changes, and
 * not for the pristine Untitled.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CloseProjectInstallsAFreshUntitledProjectTest {

    @TempDir
    Path workspace;

    @Test
    void closeProjectInstallsUntitledWithTheWelcomeWindowAndReleasesTheFolder() throws Exception {
        try (ExitFixture fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            assertThat(onFx(() -> fixture.coordinator().closeProjectAvailable())).as("a folder is open").isTrue();
            DawProject closed = fixture.save.project.get();
            closed.setLayoutJson("{\"active\":\"Song A's mixing layout\"}");
            int closedChannels = onFx(() -> closed.getMixer().getChannels().size());
            var undo = fixture.save.undoManager.get();

            ExitCoordinator.CloseOutcome outcome = fixture.closeProject().toCompletableFuture()
                    .get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(outcome).isEqualTo(new ExitCoordinator.CloseOutcome.Closed());
            // Ruling 4 Q3, the stage-7 review N9: the Welcome window comes once the run has ended, posted.
            fixture.await(() -> fixture.welcomes.get() == 1, "the Welcome window after the run ended");
            DawProject untitled = fixture.save.project.get();
            // The studio engineer's stage-6 S4 / stage-7 S3: the layouts reset to the built-in Default, as New's.
            assertThat(fixture.save.appliedLayouts).as("the closed project's named layouts never carry over")
                    .isNotEmpty().last().isNull();
            assertThat(untitled.getLayoutJson()).isNull();
            DawProject reference = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
            assertThat(untitled).isNotSameAs(closed);
            assertThat(untitled.getName()).isEqualTo("Untitled Project");
            assertThat(untitled.getTransport().getTempo()).isEqualTo(reference.getTransport().getTempo());
            assertThat(untitled.getTransport().isLoopEnabled()).isFalse();
            assertThat(untitled.getTransport().isPunchEnabled()).isFalse();
            assertThat(untitled.isDirty()).isFalse();
            assertThat(untitled.getMixer().getChannels()).as("ruling R10: the default mixer channel count, not the"
                    + " closed project's %d", closedChannels).hasSameSizeAs(reference.getMixer().getChannels());
            assertThat(closedChannels).as("fixture: the closed project had more channels (its Vox track's)")
                    .isGreaterThan(reference.getMixer().getChannels().size());
            assertThat(fixture.save.undoManager.get()).as("a new undo history").isNotSameAs(undo);
            assertThat(fixture.save.undoManager.get().canUndo()).as("an empty one").isFalse();
            assertThat(fixture.welcomes).as("the Welcome window over it").hasValue(1);
            assertThat(fixture.save.projectManager.getCurrentProject()).as("no folder is open").isNull();
            assertThat(fixture.stepNames()).doesNotContain("DISPOSE DONE", "HIDE DONE");
            assertThat(fixture.terminalExits).hasValue(0);
            assertThat(onFx(() -> fixture.coordinator().closeProjectAvailable()))
                    .as("nothing to close in the pristine Untitled").isFalse();
            fixture.edit("Sketch");
            assertThat(onFx(() -> fixture.coordinator().closeProjectAvailable()))
                    .as("its unsaved changes are something to close").isTrue();
            fixture.save.projectManager.whenRetiredLeasesReleased().get(ExitFixture.GUARD.toMillis(),
                    TimeUnit.MILLISECONDS);
            assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
        }
    }
}
