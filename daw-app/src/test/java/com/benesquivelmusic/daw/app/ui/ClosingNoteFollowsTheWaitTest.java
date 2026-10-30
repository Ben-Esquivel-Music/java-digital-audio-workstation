package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, the studio engineer's rulings R8 and S2: the closing surface's
 * ten-second note follows the current wait, recomputed on every change of
 * it — a take published after ten seconds while a save is still writing
 * turns R1's take note, which offers no Quit anyway, into R8's "save not
 * finished" note with Quit anyway; and once Save and close's FLUSH landed,
 * a closing step still writing says the project is saved and its checkpoint
 * and journal are still being written.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ClosingNoteFollowsTheWaitTest {

    @TempDir
    Path workspace;

    private ExitFixture fixture;

    @BeforeEach
    void songAEdited() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.edit("Bass");
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void aTakePublishedAfterTenSecondsWhileASaveWritesTurnsTheTakeNoteIntoTheSaveNote() throws Exception {
        ExitFixture.ScriptedTakes takes = new ExitFixture.ScriptedTakes();
        fixture.scriptedTakes = takes;
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.save();
        head.awaitHeld();
        runOnFx(() -> takes.beingWritten(true));

        fixture.closeBox();
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(surface::stepText)).isEqualTo("Finishing take…");
        assertThat(onFx(surface::noteText)).startsWith("The take is still being written after 10 seconds.");
        assertThat(onFx(() -> surface.offerButton().isVisible())).isFalse();

        runOnFx(takes::published);
        fixture.await(() -> surface.stepText().equals("Saving project…"), "the next wait");

        assertThat(onFx(surface::noteText)).as("the note follows the wait, recomputed")
                .startsWith("The save hasn't finished after 10 seconds.");
        assertThat(onFx(() -> surface.offerButton().isVisible())).as("and now offers Quit anyway").isTrue();
        head.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
    }

    @Test
    void onceFlushLandedAClosingStepStillWritingSaysTheProjectIsSaved() throws Exception {
        SaveFlowFixture.Publishes.Hold afterSave = fixture.save.publishes.holdNext(SaveFlowFixture::isCheckpoint);
        fixture.save.promptAnswer = "Save and close";

        fixture.closeBox();
        afterSave.awaitHeld();
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        fixture.await(() -> fixture.reached(ExitCoordinator.ExitStep.FLUSH), "FLUSH landed");

        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(surface::stepText)).isEqualTo("Writing checkpoint…");
        assertThat(onFx(surface::noteText)).isEqualTo("The project is saved, but its checkpoint and journal are still"
                + " being written after 10 seconds. The disk may not be responding. If you quit anyway, the next"
                + " open may offer to recover this session.");
        assertThat(onFx(() -> surface.offerButton().getText())).isEqualTo("Quit anyway");
        afterSave.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
    }
}
