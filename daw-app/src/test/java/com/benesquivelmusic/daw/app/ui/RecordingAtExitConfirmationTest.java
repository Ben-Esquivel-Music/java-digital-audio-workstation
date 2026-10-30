package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;

import javafx.scene.control.ButtonBar;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, the studio engineer's ruling R1 (and N1, S1): with a recording
 * in flight, every door asks first — "Recording in Progress", the header
 * naming what closes, "Stop the take and close" apart and never the
 * default, "Keep recording" the default with the focus, so Enter, Escape and
 * the close box all keep recording and the close ends before any step. Once
 * stopped, the take is waited out: past 500 ms the strip shows "Finishing
 * take…", at ten seconds the take note — and never Quit anyway; a second exit
 * meanwhile says so once per thirty seconds; Don't quit then keeps the
 * project open and the take publishes into it. The take facts here are
 * scripted on the FX thread (the R1 seam); {@code TakeAtExitTest} records
 * for real.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RecordingAtExitConfirmationTest {

    @TempDir
    Path workspace;

    private ExitFixture fixture;
    private final ExitFixture.ScriptedTakes takes = new ExitFixture.ScriptedTakes();

    @BeforeEach
    void aRecordingInFlight() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.scriptedTakes = takes;
        runOnFx(takes::record);
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void theExitsConfirmationKeepsRecordingByDefaultAndEndsTheCloseBeforeAnyStep() throws Exception {
        fixture.closeBox();
        fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

        ExitFixture.Shown shown = fixture.stopTakePrompts.getFirst();
        assertThat(shown.header()).isEqualTo("Stop the take and close the application?");
        assertThat(shown.content()).isEqualTo("Stopping keeps everything recorded so far. Once the take has been"
                + " written you will be asked about any unsaved changes.");
        assertThat(shown.buttons()).containsExactly("Stop the take and close", "Keep recording");
        assertThat(shown.data()).containsExactly(ButtonBar.ButtonData.LEFT, ButtonBar.ButtonData.CANCEL_CLOSE);
        assertThat(shown.defaultButton()).as("Keep recording is the default (the studio engineer's N1)")
                .isEqualTo("Keep recording");
        assertThat(shown.focused()).as("and has the focus as the window opens").isEqualTo("Keep recording");
        assertThat(shown.owner()).isSameAs(fixture.stage());
        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
        assertThat(takes.recordingInFlight()).as("nothing changed: the take keeps recording").isTrue();
        assertThat(fixture.transportStops).hasValue(0);
        assertThat(onFx(() -> fixture.coordinator().isWindowInert())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {ExitFixture.ENTER, ExitFixture.ESCAPE, ExitFixture.CLOSE_BOX})
    void enterEscapeAndTheCloseBoxAllKeepRecording(String answer) throws Exception {
        fixture.stopTakeAnswer = answer;

        fixture.closeBox();
        fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
        assertThat(takes.recordingInFlight()).isTrue();
        assertThat(fixture.transportStops).hasValue(0);
        assertThat(fixture.stopTakeClosedByItself).as("the key or the close box closed the confirmation itself,"
                + " before the fixture's hang guard (the stage-6 review N3)").containsExactly(true);
    }

    @Test
    void closeProjectsConfirmationNamesTheProject() throws Exception {
        fixture.closeProject();
        fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

        assertThat(fixture.stopTakePrompts.getFirst().header()).isEqualTo("Stop the take and close “Song A”?");
        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
    }

    @Test
    void theStoppedTakeIsWaitedOutWithItsNoteAndNoQuitAnywayAndASecondExitSaysSoOnce() throws Exception {
        fixture.stopTakeAnswer = "Stop the take and close";
        fixture.closeBox();
        fixture.await(() -> takes.takeBeingWritten() && fixture.timers.pending(ExitCoordinator.SURFACE_DELAY)
                .size() == 1, "the take wait");
        assertThat(fixture.transportStops).as("the consent's Stop").hasValue(1);

        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        assertThat(onFx(() -> fixture.surface().stepText())).isEqualTo("Finishing take…");
        assertThat(fixture.save.operationNames()).as("the strip's operation at 500 ms").contains("Finishing take…");
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        assertThat(onFx(() -> fixture.surface().noteText())).isEqualTo("The take is still being written after 10"
                + " seconds. The disk may not be responding. The application closes once the take has been written;"
                + " choose Don't quit to keep working.");
        assertThat(onFx(() -> fixture.surface().offerButton().isVisible()))
                .as("no Quit anyway in the take wait (rulings R1, R12 #9)").isFalse();

        fixture.closeBox();
        fixture.fileExit();
        fixture.nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        fixture.closeBox();
        assertThat(fixture.save.notifications()).extracting(NotificationEntry::message).as("one INFO in 30 s")
                .containsExactly("Still finishing the take — the application closes when it has been written.");
        fixture.nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        fixture.closeBox();
        assertThat(fixture.save.notifications()).as("and again after 30 s").hasSize(2);
        assertThat(fixture.stepNames()).as("the merged requests add no step").containsExactly("INTERCEPT DONE");

        runOnFx(takes::published);
        fixture.await(() -> fixture.reached(ExitCoordinator.ExitStep.HIDE), "the exit once the take published");
        assertThat(fixture.save.operationNames()).doesNotContain("Finishing take…");
    }

    @Test
    void closeProjectsTakeNoteNamesTheProjectAndDontQuitKeepsItOpenWhileTheTakePublishes() throws Exception {
        fixture.stopTakeAnswer = "Stop the take and close";
        fixture.closeProject();
        fixture.await(() -> takes.takeBeingWritten() && !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY)
                .isEmpty(), "the take wait");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        assertThat(onFx(() -> fixture.surface().noteText())).isEqualTo("The take is still being written after 10"
                + " seconds. The disk may not be responding. The project closes once the take has been written;"
                + " choose Keep project open to keep working.");

        runOnFx(() -> fixture.surface().keepButton().fire());
        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
        runOnFx(() -> {
            takes.published();
            fixture.save.project.get().markDirty();
        });
        fixture.save.settle();
        assertThat(fixture.stepNames()).as("no FLUSH, SEAL or RELEASE after the keep").hasSize(2);
        assertThat(fixture.save.currentProjectIs(fixture.songA)).isTrue();
        assertThat(onFx(() -> fixture.save.project.get().isDirty())).isTrue();
    }

    @Test
    void aSecondTakeWrittenRightAfterTheFirstKeepsTheCloseWaitingUntilItToo() throws Exception {
        fixture.stopTakeAnswer = "Stop the take and close";
        fixture.closeBox();
        fixture.await(takes::takeBeingWritten, "the take wait");

        // The take fact toggles true → false → true in one FX turn: the second take is being written.
        runOnFx(() -> {
            takes.published();
            takes.beingWritten(true);
        });
        fixture.save.settle();
        assertThat(fixture.stepNames()).as("still waiting on the second take").containsExactly("INTERCEPT DONE");

        runOnFx(takes::published);
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        assertThat(fixture.unsavedPrompts).as("the project is clean: nothing to ask").isEmpty();
    }
}
