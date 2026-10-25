package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, the doors (rulings G1, G2, G13 #8): a door that would replace
 * the open project while a save this application asked for is still being
 * written waits for the save writer to go idle — without blocking the FX
 * thread — and then judges the project as it is now: no edit, it proceeds
 * with no prompt; an edit during the write, the prompt asks; a recording
 * started meanwhile, it is refused. A failed save at the prompt cancels the
 * door. A second door while one waits is refused with one INFO. A Ctrl+S
 * that lands after the waiting door's idle wait completed but before the
 * door resumed makes it wait again, so the project is not switched while
 * that save writes.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DoorWaitsForTheSaveTest {

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;

    @BeforeEach
    void openSongA() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        runOnFx(() -> fixture.project.get().markDirty());
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void aDoorAskedWhileASaveIsWrittenWaitsThenProceedsWithoutAPromptWhenNothingWasEdited() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save();
        head.awaitHeld();

        CountDownLatch proceeded = onFx(() -> DoorGate.whenItProceeds(fixture.lifecycle()));
        assertThat(proceeded.getCount()).as("the door waits for the save").isEqualTo(1L);
        assertThat(fixture.prompts).as("and asks nothing yet").hasValue(0);

        head.release();
        assertThat(proceeded.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("once the save is written, the door goes ahead").isTrue();
        assertThat(fixture.prompts).as("the save left the project clean: no prompt").hasValue(0);
    }

    @Test
    void anEditDuringTheWriteMakesTheWaitingDoorAsk() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save();
        head.awaitHeld();
        CountDownLatch proceeded = onFx(() -> DoorGate.whenItProceeds(fixture.lifecycle()));

        runOnFx(() -> {
            // An edit, as the undo history marks one: a new edit revision.
            fixture.project.get().createAudioTrack("Edited during the write");
            fixture.project.get().markDirty();
        });
        fixture.promptAnswer = null; // the prompt, hidden, cancels the door
        head.release();
        SaveOutcomes.awaitIdle(fixture.projectManager);
        awaitPrompts(1);

        assertThat(proceeded.getCount()).as("cancelled at the prompt, the door does not go ahead").isEqualTo(1L);
        assertThat(onFx(() -> fixture.project.get().isDirty())).as("the edit is still unsaved").isTrue();
    }

    @Test
    void aRecordingStartedDuringTheWaitRefusesTheDoor() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save();
        head.awaitHeld();
        CountDownLatch proceeded = onFx(() -> DoorGate.whenItProceeds(fixture.lifecycle()));

        runOnFx(() -> fixture.lifecycle().setRecordingInFlightCheck(() -> true));
        head.release();
        fixture.settle();
        runOnFx(() -> { });

        assertThat(proceeded.getCount()).as("the door is refused after its wait").isEqualTo(1L);
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .containsExactly(ProjectLifecycleController.PROJECT_CHANGE_WHILE_RECORDING_MESSAGE);
        assertThat(fixture.prompts).as("refused before any prompt").hasValue(0);
    }

    @Test
    void aFailedSaveAtThePromptCancelsTheDoor() throws Exception {
        fixture.publishes.failNext(SaveFlowFixture::isHead, new IOException("injected head write failure"));
        fixture.promptAnswer = "Save";

        CountDownLatch proceeded = onFx(() -> DoorGate.whenItProceeds(fixture.lifecycle()));
        fixture.settle();
        runOnFx(() -> { });

        assertThat(fixture.prompts).as("a dirty project is asked about").hasValue(1);
        assertThat(proceeded.getCount()).as("the save failed: the door is cancelled").isEqualTo(1L);
        assertThat(fixture.notifications()).as("the save's own failure is reported").singleElement()
                .satisfies(entry -> assertThat(entry.message()).startsWith("Save failed: injected head write failure."));

        // The cancelled door waits for nothing: the next door is judged at once (and asks again).
        fixture.promptAnswer = "Discard";
        assertThat(onFx(() -> DoorGate.proceedsAtOnce(fixture.lifecycle()))).isTrue();
        assertThat(fixture.prompts).hasValue(2);
    }

    @Test
    void aSecondDoorWhileOneWaitsShowsOneInfoAndDoesNotRun() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save();
        head.awaitHeld();
        CountDownLatch first = onFx(() -> DoorGate.whenItProceeds(fixture.lifecycle()));

        AtomicBoolean secondRan = new AtomicBoolean();
        runOnFx(() -> {
            for (int click = 0; click < 3; click++) {
                fixture.lifecycle().whenProjectMayClose(ProjectLifecycleController.OPEN_PROJECT_DOOR,
                        () -> secondRan.set(true));
            }
        });

        assertThat(fixture.notifications()).as("one INFO for three clicks").singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.INFO);
            assertThat(entry.message())
                    .isEqualTo("Still saving — " + DoorGate.TEST_DOOR + " continues when the save finishes.");
        });
        head.release();
        assertThat(first.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("the first door goes on").isTrue();
        fixture.settle();
        assertThat(secondRan).as("the refused door never runs").isFalse();
    }

    @Test
    void aSaveThatLandsBetweenTheIdleWaitAndTheDoorMakesTheDoorWaitAgainAndNothingSwitchesMeanwhile()
            throws Exception {
        Path songB = fixture.anotherProjectOnDisk("Song B");
        SaveFlowFixture.Publishes.Hold firstHead = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save();
        firstHead.awaitHeld();
        // On the FX thread, in the door's own step, after its idle wait completed and before it
        // judges the project: the user presses Ctrl+S again (memory "Pin ordering on the acting thread").
        AtomicReference<SaveFlowFixture.Publishes.Hold> secondHead = new AtomicReference<>();
        AtomicBoolean pressed = new AtomicBoolean();
        runOnFx(() -> fixture.lifecycle().setBeforeWaitingDoorResumes(() -> {
            if (pressed.compareAndSet(false, true)) {
                secondHead.set(fixture.publishes.holdNext(SaveFlowFixture::isHead));
                fixture.lifecycle().onSaveProject();
            }
        }));
        AtomicReference<Path> openWhenTheDoorRan = new AtomicReference<>();
        CountDownLatch switched = new CountDownLatch(1);
        runOnFx(() -> fixture.lifecycle().whenProjectMayClose(DoorGate.TEST_DOOR, () -> {
            openWhenTheDoorRan.set(fixture.projectManager.getCurrentProject().projectPath());
            fixture.lifecycle().loadProjectFromPath(songB);
            switched.countDown();
        }));

        firstHead.release();
        long deadline = System.nanoTime() + GUARD.toNanos();
        while (secondHead.get() == null) {
            assertThat(System.nanoTime() - deadline < 0).as("the door resumed within %s", GUARD).isTrue();
            runOnFx(() -> { });
        }
        Thread writer = secondHead.get().awaitHeld();

        assertThat(writer.getName()).as("fixture: the second save is being written").isEqualTo("daw-save-writer");
        assertThat(switched.getCount()).as("the door waits again while that save writes").isEqualTo(1L);
        assertThat(onFx(() -> fixture.projectManager.getCurrentProject().projectPath()))
                .as("Song A is still open").isEqualTo(fixture.songA);

        secondHead.get().release();
        assertThat(switched.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("then it goes ahead").isTrue();
        assertThat(openWhenTheDoorRan.get()).isEqualTo(fixture.songA);
        assertThat(fixture.projectManager.getCurrentProject().projectPath()).isEqualTo(songB);
        List<SaveFlowFixture.Publish> heads = fixture.publishes.done(SaveFlowFixture::isHead);
        assertThat(heads).as("both saves of Song A were written before the switch").hasSize(2)
                .allSatisfy(publish -> assertThat(publish.target().getParent()).isEqualTo(fixture.songA));
        assertThat(Files.readString(fixture.songA.resolve("project.daw"))).contains("Song A");
    }

    /** Waits, bounded, for {@code count} unsaved-changes prompts. */
    private void awaitPrompts(int count) throws Exception {
        long deadline = System.nanoTime() + GUARD.toNanos();
        while (fixture.prompts.get() < count) {
            assertThat(System.nanoTime() - deadline < 0).as("%s prompts within %s", count, GUARD).isTrue();
            runOnFx(() -> { });
        }
        runOnFx(() -> { });
    }
}
