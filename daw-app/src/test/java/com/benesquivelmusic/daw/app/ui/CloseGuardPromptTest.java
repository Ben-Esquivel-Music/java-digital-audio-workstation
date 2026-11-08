package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrigin;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;

import javafx.scene.control.ButtonBar;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "Close-guard" (the studio engineer's rulings R2,
 * R3, R9; Persistence Integrity book §5.4): closing through the window's
 * close box with unsaved changes asks the prompt, the close request
 * consumed so the window stays; Cancel aborts with the project untouched;
 * Discard and close exits with no head write — its closing checkpoint, the
 * misclick copy, is expected (R3) — and Save and close makes exactly one
 * atomic head write, the canonical save of story 331, then exits.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CloseGuardPromptTest {

    @TempDir
    Path workspace;

    private ExitFixture fixture;
    private byte[] savedHead;

    @BeforeEach
    void songASavedThenEdited() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.save.save();
        fixture.save.settle();
        savedHead = Files.readAllBytes(fixture.songA.resolve("project.daw"));
        fixture.edit("Bass");
        fixture.save.publishes.clear();
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void theCloseBoxAsksThePromptAndCancelLeavesTheSessionAsItWas() throws Exception {
        fixture.save.promptAnswer = "Cancel";

        fixture.closeBox();
        fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

        assertThat(onFx(() -> fixture.stage().isShowing())).as("the request was consumed: the window stays")
                .isTrue();
        ExitFixture.Shown prompt = fixture.unsavedPrompts.getFirst();
        assertThat(prompt.header()).isEqualTo("Save the changes to “Song A” before closing?");
        // The stage-6 review N5: the time is the saved head's own, HH:mm today in Locale.ROOT and the strip's zone.
        java.time.Instant savedAt = onFx(() -> fixture.lifecycle().closeReadiness().headSavedAt().orElseThrow());
        String time = savedAt.atZone(ZoneId.systemDefault()).toLocalTime()
                .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
        assertThat(prompt.content()).matches("If you discard them, “Song A” keeps its last save from \\d{2}:\\d{2}\\.");
        assertThat(prompt.content()).as("the head's own save time, %s", time)
                .isEqualTo("If you discard them, “Song A” keeps its last save from " + time + ".");
        assertThat(prompt.buttons()).containsExactly("Save and close", "Discard and close", "Cancel");
        assertThat(prompt.data()).containsExactly(ButtonBar.ButtonData.OK_DONE, ButtonBar.ButtonData.LEFT,
                ButtonBar.ButtonData.CANCEL_CLOSE);
        assertThat(prompt.defaultButton()).isEqualTo("Save and close");
        assertThat(prompt.owner()).as("owned by the main window").isSameAs(fixture.stage());
        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
        assertThat(onFx(() -> fixture.save.project.get().isDirty())).isTrue();
        assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw"))).isEqualTo(savedHead);
        assertThat(onFx(() -> fixture.coordinator().isWindowInert())).as("the window is live again").isFalse();
        assertThat(fixture.terminalExits).hasValue(0);
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).exists();
    }

    @Test
    void discardAndCloseExitsWithNoHeadWriteAndKeepsTheDiscardedWorkInAClosingCheckpoint() throws Exception {
        fixture.save.promptAnswer = "Discard and close";

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw")))
                .as("no head write: project.daw is byte-identical (ruling R3)").isEqualTo(savedHead);
        assertThat(fixture.save.publishes.done(SaveFlowFixture::isHead)).isEmpty();
        assertThat(fixture.save.checkpointEvents).as("the closing checkpoint is expected (ruling R3): one CLOSING")
                .filteredOn(event -> event instanceof CheckpointEvent.Written written
                        && written.origin() == CheckpointOrigin.CLOSING).hasSize(1);
        assertThat(fixture.terminalExits).hasValue(1);
        assertThat(onFx(() -> fixture.stage().isShowing())).isFalse();
        fixture.save.projectManager.whenRetiredLeasesReleased().get(ExitFixture.GUARD.toMillis(),
                TimeUnit.MILLISECONDS);
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the lock is gone").doesNotExist();
    }

    @Test
    void saveAndCloseWritesTheHeadExactlyOnceThenExits() throws Exception {
        fixture.save.promptAnswer = "Save and close";

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.save.publishes.done(SaveFlowFixture::isHead)).as("exactly one head write").hasSize(1);
        assertThat(Files.readString(fixture.songA.resolve("project.daw"), StandardCharsets.UTF_8))
                .as("the head is the capture").contains("Bass");
        assertThat(fixture.terminalExits).hasValue(1);
        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD DONE", "FLUSH DONE",
                fixture.stepNames().get(3), "SEAL DONE", "RELEASE DONE", "DISPOSE DONE", "HIDE DONE");
        assertThat(fixture.stepNames().get(3)).startsWith("CLOSING_CHECKPOINT");
        assertThat(onFx(() -> fixture.save.project.get().isDirty())).isFalse();
    }

    @Test
    void aSecondCloseRequestWhileThePromptAsksIsMergedAndAddsNoStep() throws Exception {
        fixture.save.promptAnswer = "Cancel";
        fixture.beforeTheUnsavedPrompt = () -> fixture.stage().fireEvent(new javafx.stage.WindowEvent(
                fixture.stage(), javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));

        fixture.closeBox();
        fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
        assertThat(fixture.unsavedPrompts).hasSize(1);
        assertThat(List.copyOf(fixture.save.notifications())).isEmpty();
    }
}
