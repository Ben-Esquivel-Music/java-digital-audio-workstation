package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.JournalEnding.JournalEnd;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, the studio engineer's ruling on app fix pass 2a, item 5
 * (SHOULD; ruling 4 Q3, constraint 1, "a defect anywhere in the run must end
 * it"): a step of the shared closer itself that throws — here its journal
 * end's preparation, inside one of its FX turns — still answers the close:
 * its result completes exceptionally, once, logged {@code SEVERE}, and the
 * coordinator ends the run by its phase. The journal was detached and its
 * end had not begun, so the close's skip still drains it unsealed; the
 * window is live, the project open and its lock held, and one ERROR says the
 * journaling is paused — no {@code close.cancelled.*} WARNING. For Close
 * Project, for an exit, and for the close-then-continue primitive, whose
 * continuation receives {@code Failed}, once.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class AFolderCloseThatThrowsStillAnswersTest {

    @TempDir
    Path workspace;

    private ExitFixture fixture;
    private final AtomicBoolean throwTheNextSeal = new AtomicBoolean(true);

    @BeforeEach
    void songAWhoseFirstSealPreparationThrows() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT, true, real -> new JournalEnding() {
            @Override
            public void detach() {
                real.detach();
            }

            @Override
            public Runnable prepare(JournalEnd mode) {
                if (mode == JournalEnd.SEAL && throwTheNextSeal.compareAndSet(true, false)) {
                    throw new IllegalStateException("injected");
                }
                return real.prepare(mode);
            }
        });
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void closeProjectEndsWithThePausedErrorTheJournalDrainedAndTheProjectOpen() throws Exception {
        CompletionStage<ExitCoordinator.CloseOutcome> outcome = fixture.closeProject();

        assertThat(outcome.toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExitCoordinator.CloseOutcome.Failed.class);
        assertThat(errors()).containsExactly("Close Project stopped because of an internal error: injected. “Song A”"
                + " is still open, and journaled persistence is paused until this project is reopened; save your"
                + " changes before you try again.");
        assertTheWindowIsLiveTheJournalDrainedAndNoCancelWarning();
    }

    @Test
    void anExitEndsTheSameWayNamingTheExit() throws Exception {
        fixture.closeBox();
        fixture.await(() -> errorCount() == 1, "the run's ERROR");

        assertThat(errors()).containsExactly("Exit stopped because of an internal error: injected. “Song A” is still"
                + " open, and journaled persistence is paused until this project is reopened; save your changes"
                + " before you try again.");
        assertTheWindowIsLiveTheJournalDrainedAndNoCancelWarning();
        assertThat(fixture.terminalExits).hasValue(0);
    }

    @Test
    void thePrimitivesContinuationReceivesFailedOnce() throws Exception {
        CompletableFuture<ExitCoordinator.CloseOutcome> outcome = fixture.closeThenContinue("Switch Workspace");

        assertThat(outcome.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOfSatisfying(ExitCoordinator.CloseOutcome.Failed.class, failed ->
                        assertThat(failed.cause()).hasMessage("injected"));
        assertThat(errors()).as("ruling 4 Q6.4 item 3: Q3's texts take the switch's name").containsExactly(
                "Switch Workspace stopped because of an internal error: injected. “Song A” is still open, and"
                        + " journaled persistence is paused until this project is reopened; save your changes before"
                        + " you try again.");
        fixture.save.settle();
        assertThat(fixture.continuations).as("one outcome").hasSize(1);
        assertTheWindowIsLiveTheJournalDrainedAndNoCancelWarning();
    }

    private void assertTheWindowIsLiveTheJournalDrainedAndNoCancelWarning() throws Exception {
        assertThat(onFx(() -> fixture.coordinator().isWindowInert())).as("the window is live").isFalse();
        assertThat(onFx(() -> fixture.lifecycle().aSwitchIsPending())).as("the close is no longer pending").isFalse();
        assertThat(fixture.save.currentProjectIs(fixture.songA)).as("the project is open").isTrue();
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("its lock is held").exists();
        fixture.save.projectManager.getLockManager().verifyHeld();
        fixture.awaitTheJournalEnded();
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA))).as("drained, never sealed")
                .isNotEmpty().noneMatch(JournalRecord::isSessionSeal);
        assertThat(fixture.stepNames()).contains("RELEASE SKIPPED").doesNotContain("RELEASE DONE")
                .filteredOn(name -> name.startsWith("SEAL")).as("SEAL told once: it failed")
                .containsExactly("SEAL FAILED");
        assertThat(fixture.save.notifications()).extracting(NotificationEntry::message).as("one fact, one notification")
                .noneMatch(message -> message.contains("cancelled"));
    }

    private long errorCount() {
        return fixture.save.shown.getEntries().stream().filter(entry -> entry.level() == NotificationLevel.ERROR)
                .count();
    }

    private List<String> errors() throws Exception {
        return fixture.save.notifications().stream().filter(entry -> entry.level() == NotificationLevel.ERROR)
                .map(NotificationEntry::message).toList();
    }
}
