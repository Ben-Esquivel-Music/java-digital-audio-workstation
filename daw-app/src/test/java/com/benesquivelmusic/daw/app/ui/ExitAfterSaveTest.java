package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "Exit-after-save" (story 331's ruling M1; the
 * studio engineer's rulings R1 (the queued saves are waited for), R8, R9):
 * Ctrl+S, then the close box, File ▸ Exit or File ▸ Close Project while the
 * save is still writing. The close waits for it without blocking the FX
 * thread: past 500 ms the still-open window shows "◐ Saving project…" and
 * the closing surface the step "Saving project…"; {@code project.daw} is then
 * that capture, its after-save checkpoint was written, and only after both
 * writers are idle does the window hide. A save that fails shows its ERROR
 * in the live window and the close does not go on by itself; asking again
 * shows the prompt with "The last save failed (…)." first, and Discard and
 * close then exits.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ExitAfterSaveTest {

    /** The three doors. */
    enum Door { CLOSE_BOX, FILE_EXIT, CLOSE_PROJECT }

    @TempDir
    Path workspace;

    private ExitFixture fixture;

    @BeforeEach
    void songAEdited() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.save.holdSlowSaveTimers();
        fixture.edit("Bass");
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @ParameterizedTest
    @EnumSource(Door.class)
    void theCloseWaitsForTheSaveAndItsCheckpointThenClosesWithThatCapture(Door door) throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.save();
        head.awaitHeld();
        ask(door);
        fixture.await(() -> fixture.reached(ExitCoordinator.ExitStep.INTERCEPT)
                && fixture.lifecycle().closeReadiness().pending() instanceof ProjectLifecycleController.PendingSwitch.None
                && !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the close waits");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        fixture.save.fireSlowSaveTimers();

        assertThat(fixture.save.operationNames()).as("the still-open window shows ◐ Saving project…")
                .contains(ProjectLifecycleController.SAVING_OPERATION_NAME);
        assertThat(onFx(() -> fixture.surface().stepText())).isEqualTo("Saving project…");
        assertThat(onFx(() -> fixture.surface().getTitle())).isEqualTo("Closing “Song A”");
        assertThat(fixture.stepNames()).as("nothing past GUARD while the save writes").containsExactly(
                "INTERCEPT DONE");
        assertThat(onFx(() -> fixture.stage().isShowing())).isTrue();

        head.release();
        if (door == Door.CLOSE_PROJECT) {
            fixture.awaitStep(ExitCoordinator.ExitStep.RELEASE);
            fixture.await(() -> fixture.save.project.get().getName().equals("Untitled Project"), "Untitled");
        } else {
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        }

        assertThat(Files.readString(fixture.songA.resolve("project.daw"), StandardCharsets.UTF_8))
                .as("project.daw is the Ctrl+S capture").contains("Bass");
        assertThat(fixture.save.publishes.done(SaveFlowFixture::isHead)).as("one head: the Ctrl+S save").hasSize(1);
        assertThat(fixture.unsavedPrompts).as("the save left nothing unsaved: no prompt").isEmpty();
        List<String> events = List.copyOf(fixture.events);
        int afterSave = events.indexOf("checkpoint Written AFTER_SAVE");
        assertThat(afterSave).as("its after-save checkpoint was written").isNotNegative();
        if (door == Door.CLOSE_PROJECT) {
            assertThat(fixture.reached(ExitCoordinator.ExitStep.HIDE)).isFalse();
            assertThat(fixture.terminalExits).hasValue(0);
        } else {
            assertThat(afterSave).as("before HIDE").isLessThan(events.indexOf("step HIDE DONE"));
            assertThat(fixture.terminalExits).hasValue(1);
            assertThat(fixture.save.projectManager.whenWritersIdle()).as("both writers idle after HIDE")
                    .succeedsWithin(ExitFixture.GUARD);
        }
    }

    @ParameterizedTest
    @EnumSource(Door.class)
    void aSaveThatFailsStopsTheCloseWithItsErrorAndAskingAgainSaysSoFirst(Door door) throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.publishes.failNext(SaveFlowFixture::isHead, new IOException("the volume went away"));
        fixture.save.save();
        head.awaitHeld();
        ask(door);
        fixture.await(() -> fixture.reached(ExitCoordinator.ExitStep.INTERCEPT)
                && !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the close waits");
        head.release();
        fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

        assertThat(fixture.stepNames()).as("ruling R9: the close stops at GUARD").containsExactly(
                "INTERCEPT DONE", "GUARD FAILED");
        assertThat(fixture.save.notifications()).extracting(NotificationEntry::message)
                .anyMatch(message -> message.startsWith("Save failed: the volume went away."));
        assertThat(onFx(() -> fixture.stage().isShowing())).isTrue();
        assertThat(onFx(() -> fixture.save.project.get().isDirty())).isTrue();
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).exists();
        assertThat(onFx(() -> fixture.coordinator().isWindowInert())).isFalse();
        assertThat(fixture.surfaces).allSatisfy(surface -> assertThat(onFx(surface::isOpen)).isFalse());

        fixture.save.promptAnswer = "Discard and close";
        ask(door);
        if (door == Door.CLOSE_PROJECT) {
            fixture.await(() -> fixture.save.project.get().getName().equals("Untitled Project"), "Untitled");
        } else {
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        }
        assertThat(fixture.unsavedPrompts.getFirst().content()).as("the failure first, then the content line")
                .startsWith("The last save failed (the volume went away).\n");
    }

    /**
     * Ruling 4 Q5 (vi), R9 refined (the stage-6 review N8): a queued save
     * that failed, superseded by a later save of the project that landed,
     * no longer stops the close — {@code project.daw} is current, no head
     * failure stands — so there is no GUARD FAILED and no made-up cause: the
     * close goes on, logged, to a clean exit, or to the R2 prompt with no
     * failure line when an edit came after.
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aFailedQueuedSaveThatALaterSaveSupersededDoesNotStopTheClose(boolean editedAfter) throws Exception {
        java.util.List<String> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Logger coordinatorLog = java.util.logging.Logger.getLogger(ExitCoordinator.class.getName());
        java.util.logging.Handler recording = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord logRecord) {
                logged.add(logRecord.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        coordinatorLog.addHandler(recording);
        try {
            SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
            fixture.save.publishes.failNext(SaveFlowFixture::isHead, new IOException("the volume went away"));
            fixture.save.save();
            head.awaitHeld();
            fixture.edit("Keys");
            fixture.save.save();
            if (editedAfter) {
                fixture.edit("Strings");
                fixture.save.promptAnswer = "Discard and close";
            }
            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the close waits");
            head.release();
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(fixture.stepNames()).as("no GUARD FAILED").doesNotContain("GUARD FAILED").contains("GUARD DONE");
            assertThat(logged).contains("A save of “Song A” queued before the close failed, and a later one"
                    + " succeeded; the close goes on.");
            if (editedAfter) {
                assertThat(fixture.unsavedPrompts).singleElement().satisfies(prompt -> assertThat(prompt.content())
                        .as("no failure line: none stands").doesNotContain("The last save failed"));
            } else {
                assertThat(fixture.unsavedPrompts).as("a clean exit").isEmpty();
            }
            assertThat(fixture.terminalExits).hasValue(1);
        } finally {
            coordinatorLog.removeHandler(recording);
        }
    }

    private void ask(Door door) throws Exception {
        switch (door) {
            case CLOSE_BOX -> fixture.closeBox();
            case FILE_EXIT -> fixture.fileExit();
            case CLOSE_PROJECT -> fixture.closeProject();
        }
    }
}
