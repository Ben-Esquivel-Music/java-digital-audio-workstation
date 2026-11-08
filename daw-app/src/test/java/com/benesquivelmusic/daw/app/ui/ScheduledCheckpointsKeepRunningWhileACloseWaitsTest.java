package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.ProjectHealthScanner;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrigin;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, the studio engineer's ruling 4 Q4 (the stage-7 review S2) — scheduled checkpoints keep running while a
 * close waits: the autosave's capture gate is not the input-inert window's — it says no only while a project is
 * installed and, for good, once DISPOSE began (the app-fix-2 review S1) — so edits made since the last tick reach a
 * SCHEDULED checkpoint while the window is inert and the close waits — for a take still being written (ruling R1's
 * wait, which offers no Quit anyway) or for a held save. Then the close goes on, Save and close, and the next open is
 * plain.
 *
 * <p>The ticks are the checkpoint manager's own scheduler at a 200 ms cadence (daw-core has no manual tick seam). The
 * close starts with unsaved edits; the edit whose checkpoint the test awaits is made on the FX thread once the window
 * is inert — as a take's publication edits the project during R1's wait — so a capture of the edited revision can
 * only run while the window is inert. A third case presses Quit anyway with an edit no tick has captured: from
 * DISPOSE on, no tick captures the project — in the application DISPOSE empties its inserts without an edit (the
 * app-fix-2 review S1). The exit fixture's gate is the application's: it says no while a project is installed and
 * once DISPOSE began ({@code MainController.autosaveCaptureAllowed}, pinned by
 * {@code CheckpointCaptureFailureReachesTheStripTest} and read on a partial controller by
 * {@code ProjectInstallationInputFenceTest} and {@code WindowLifetimeDisposalTest}).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ScheduledCheckpointsKeepRunningWhileACloseWaitsTest {

    private static final AutoSaveConfig EVERY_200_MS = new AutoSaveConfig(Duration.ofMillis(200), 10, true);

    @TempDir
    Path workspace;

    @Test
    void aTickWritesTheEditsWhileTheCloseWaitsForATakeStillBeingWritten() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, EVERY_200_MS)) {
            ExitFixture.ScriptedTakes takes = new ExitFixture.ScriptedTakes();
            exit.scriptedTakes = takes;
            runOnFx(() -> takes.beingWritten(true));
            exit.edit("Bass");

            exit.closeBox();
            exit.await(() -> exit.fence().isRaised(), "the close waits for the take, the window inert");
            long edited = editOnTheFxThread(exit, "Keys");
            awaitAScheduledCheckpointOf(exit, edited);
            runOnFx(() -> {
                assertThat(exit.coordinator().isWindowInert()).as("written while the close waits").isTrue();
                assertThat(takes.takeBeingWritten()).as("for the take, still being written").isTrue();
            });
            assertThat(exit.stepNames()).as("GUARD is still waiting").containsExactly("INTERCEPT DONE");

            exit.save.promptAnswer = "Save and close";
            runOnFx(takes::published);
            exit.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(exit.stepNames()).contains("FLUSH DONE", "SEAL DONE", "RELEASE DONE", "HIDE DONE");
            assertTheNextOpenIsPlain(exit);
        }
    }

    @Test
    void aTickWritesTheEditsWhileTheCloseWaitsForAHeldSave() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, EVERY_200_MS)) {
            exit.edit("Bass");
            SaveFlowFixture.Publishes.Hold head = exit.save.publishes.holdNext(SaveFlowFixture::isHead);
            try {
                exit.save.save();
                head.awaitHeld();

                exit.closeBox();
                exit.await(() -> exit.fence().isRaised(), "the close waits for the held save, the window inert");
                long edited = editOnTheFxThread(exit, "Keys");
                awaitAScheduledCheckpointOf(exit, edited);
                runOnFx(() -> assertThat(exit.coordinator().isWindowInert()).as("written while the close waits")
                        .isTrue());
                assertThat(head.isHeld()).as("for the save, still held").isTrue();
                assertThat(exit.stepNames()).as("GUARD is still waiting").containsExactly("INTERCEPT DONE");
                exit.save.promptAnswer = "Save and close";
            } finally {
                head.release();
            }
            exit.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(exit.stepNames()).contains("FLUSH DONE", "SEAL DONE", "RELEASE DONE", "HIDE DONE");
            assertTheNextOpenIsPlain(exit);
        }
    }

    /**
     * The app-fix-2 review S1: Quit anyway, with an edit no tick has captured, made in the same FX turn — so no
     * capture can run between them. From DISPOSE on no tick captures it: a tick after it is skipped, the M1 hook's
     * wait finds nothing more to write, and no checkpoint holds the edited revision.
     */
    @Test
    void noTickCapturesTheProjectOnceQuitAnywayDisposedTheWindow() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, EVERY_200_MS)) {
            SaveFlowFixture.Publishes.Hold head = exit.save.publishes.holdNext(SaveFlowFixture::isHead);
            try {
                exit.edit("Bass");
                exit.save.save();
                head.awaitHeld();
                exit.closeBox();
                exit.await(() -> exit.fence().isRaised(), "the close waits for the held save, the window inert");
                exit.fire(ExitCoordinator.SURFACE_DELAY);
                exit.fire(ExitCoordinator.NOTE_DELAY);
                exit.await(() -> exit.surface() != null && exit.surface().offerButton().isVisible(),
                        "Quit anyway is offered");

                AtomicLong late = new AtomicLong();
                AtomicInteger mark = new AtomicInteger();
                runOnFx(() -> {
                    DawProject project = exit.save.project.get();
                    project.createAudioTrack("Late");
                    project.markDirty();
                    late.set(project.getEditRevision());
                    assertThat(exit.save.projectManager.isCheckpointed(project))
                            .as("the validity check: a tick would capture this revision").isFalse();
                    exit.surface().offerButton().fire();
                    mark.set(exit.save.checkpointEvents.size());
                });
                // The held save is a writer the M1 hook waits for too, and one a checkpoint write may queue behind:
                // once DISPOSE began it lands, so nothing but the gate keeps a tick from capturing the edit.
                head.release();
                exit.save.settle();
                // Three ticks skipped after the edit: a skip from a tick that ran before it may be recorded after the
                // mark, but with the gate open the edit is captured before a second tick after it could skip. The
                // wait also ends on a checkpoint of the edit, so the assertion below names that defect (the app-fix-3
                // review N1).
                exit.await(() -> exit.save.checkpointEvents.stream().skip(mark.get()).filter(event ->
                                event instanceof CheckpointEvent.Rearmed rearmed
                                        && rearmed.reason() == CheckpointEvent.RearmReason.SKIPPED).count() >= 3
                                || exit.save.checkpointEvents.stream().anyMatch(event ->
                                        event instanceof CheckpointEvent.Written written
                                                && written.revision() == late.get()),
                        "three ticks skipped once DISPOSE began, or a checkpoint of the edit");
                assertThat(exit.save.projectManager.awaitWritesBeforeExit()).as("the M1 hook's wait").isTrue();
                assertThat(exit.save.checkpointEvents).as("no checkpoint of the project DISPOSE took apart")
                        .noneMatch(event -> event instanceof CheckpointEvent.Written written
                                && written.revision() == late.get());
                assertThat(exit.stepNames()).contains("DISPOSE DONE");
            } finally {
                head.release();
            }
        }
    }

    /** An edit of the open project on the FX thread — a track added, the project dirty. Returns its revision. */
    private static long editOnTheFxThread(ExitFixture exit, String track) throws Exception {
        AtomicLong revision = new AtomicLong();
        runOnFx(() -> {
            DawProject project = exit.save.project.get();
            project.createAudioTrack(track);
            project.markDirty();
            revision.set(project.getEditRevision());
        });
        return revision.get();
    }

    private static void awaitAScheduledCheckpointOf(ExitFixture exit, long revision) throws Exception {
        exit.await(() -> exit.save.checkpointEvents.stream().anyMatch(event ->
                        event instanceof CheckpointEvent.Written written
                                && written.origin() == CheckpointOrigin.SCHEDULED && written.revision() == revision),
                "a scheduled checkpoint of the edited revision");
    }

    /** The next open is plain: the lock released, the journal sealed, no recovery owed. */
    private static void assertTheNextOpenIsPlain(ExitFixture exit) throws Exception {
        exit.save.projectManager.whenRetiredLeasesReleased().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(exit.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the lock file is gone").doesNotExist();
        ProjectContext context = ProjectContext.forProject(exit.songA);
        assertThat(JournalState.records(context).getLast().isSessionSeal()).as("the journal is sealed").isTrue();
        assertThat(JournalState.needsRecovery(context)).as("no recovery decision is owed").isFalse();
        assertThat(ProjectHealthScanner.isRecoverable(exit.songA, InstantSource.system())).isFalse();
        assertThat(onFx(() -> exit.save.project.get().isDirty())).as("Save and close saved the edits").isFalse();
    }
}
