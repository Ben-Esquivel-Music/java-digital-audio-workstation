package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, Restore from Archive's late load (ruling K3): the door's answer
 * covered the project as it was when the extraction started — the same
 * instance at the same edit revision. With no edit since, the restore opens
 * with no prompt; an edit made while the archive was extracted is asked
 * about again, after the extraction, in the prompt's own words. Cancel keeps
 * the project open and the extracted folder in place, with one INFO whose
 * Open passes the project-close gate and opens it with the same
 * announcement — and so do a failed save at the prompt and a cancelled
 * location prompt of a project never saved; Save saves first, then opens.
 * While the archive is extracted, every other door is refused with one INFO.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RestoredArchiveAsksAgainTest {

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;
    private Path archive;
    private Path destination;

    @BeforeEach
    void openSongAWithUnsavedChanges() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        archive = fixture.archiveOnDisk("Song R");
        destination = fixture.restoreDestination("Song R");
        // Unsaved at the door: its prompt was answered Discard.
        runOnFx(() -> fixture.project.get().markDirty());
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void theStringsAreTheRulingsOwn() {
        assertThat(ProjectLifecycleController.restoredArchivePromptText(destination))
                .isEqualTo("The archive was restored to " + destination + ". Do you want to save before opening it?");
        assertThat(ProjectLifecycleController.restoredArchiveNotOpenedMessage(destination))
                .isEqualTo("The archive was restored to " + destination + "; the project was not opened. Open it now,"
                        + " or later with File ▸ Open Project…");
        assertThat(ProjectLifecycleController.OPEN_RESTORED_ARCHIVE_ACTION).isEqualTo("Open");
        assertThat(ProjectLifecycleController.STILL_EXTRACTING_MESSAGE)
                .isEqualTo("Still extracting the archive — Restore from Archive continues when the extraction"
                        + " finishes.");
    }

    @Test
    void withNoEditSinceTheDoorTheRestoreOpensWithoutAskingAgain() throws Exception {
        runOnFx(() -> fixture.lifecycle().startArchiveRestore(archive, destination));

        awaitTheRestoreOpened();
        assertThat(fixture.prompts).as("the door's answer still covers the project").hasValue(0);
    }

    @Test
    void anEditDuringTheExtractionIsAskedAboutAndCancelLeavesSongAOpenWithAnOpenThatOpensItLater()
            throws Exception {
        fixture.promptAnswer = null; // hidden: a cancel
        runOnFx(() -> {
            fixture.lifecycle().startArchiveRestore(archive, destination);
            fixture.project.get().createAudioTrack("Edited during the extraction");
            fixture.project.get().markDirty();
        });
        NotificationEntry info = awaitTheInfo();

        assertThat(fixture.promptQuestions).as("asked again, in the ruling's words")
                .containsExactly(ProjectLifecycleController.restoredArchivePromptText(destination));
        assertThat(info.level()).isEqualTo(NotificationLevel.INFO);
        assertThat(info.message()).isEqualTo(ProjectLifecycleController.restoredArchiveNotOpenedMessage(destination));
        assertThat(info.actionLabel()).contains("Open");
        assertThat(openProject()).as("Song A stays open").isEqualTo(fixture.songA);
        assertThat(Files.isDirectory(destination)).as("the extracted folder is kept").isTrue();

        fixture.promptAnswer = "Discard";
        runOnFx(() -> info.action().orElseThrow().run());

        assertThat(fixture.prompts).as("Open passed the gate, whose prompt is the one asked").hasValue(2);
        assertThat(openProject()).isEqualTo(destination);
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .as("with the restore's own announcement")
                .anyMatch(message -> message.startsWith("Restored archive: Song R"));
    }

    @Test
    void saveAtThePromptSavesTheEditThenOpensTheRestore() throws Exception {
        fixture.promptAnswer = "Save";
        runOnFx(() -> {
            fixture.lifecycle().startArchiveRestore(archive, destination);
            fixture.project.get().createAudioTrack("Edited during the extraction");
            fixture.project.get().markDirty();
        });

        awaitTheRestoreOpened();
        assertThat(fixture.prompts).hasValue(1);
        assertThat(Files.readString(fixture.songA.resolve("project.daw"))).as("the edit was saved first")
                .contains("Edited during the extraction");
    }

    @Test
    void aFailedSaveAtThePromptLeavesSongAOpenWithTheInfoAndItsOpen() throws Exception {
        fixture.promptAnswer = "Save";
        fixture.publishes.failNext(SaveFlowFixture::isHead, new IOException("injected head write failure"));
        DawProject songAModel = fixture.project.get();
        runOnFx(() -> {
            fixture.lifecycle().startArchiveRestore(archive, destination);
            fixture.project.get().createAudioTrack("Edited during the extraction");
            fixture.project.get().markDirty();
        });

        NotificationEntry info = awaitTheInfo();
        assertThat(fixture.prompts).as("asked once, then the save failed").hasValue(1);
        assertThat(info.message()).isEqualTo(ProjectLifecycleController.restoredArchiveNotOpenedMessage(destination));
        assertThat(info.actionLabel()).contains(ProjectLifecycleController.OPEN_RESTORED_ARCHIVE_ACTION);
        assertThat(fixture.notifications()).extracting(NotificationEntry::level)
                .as("the save's own ERROR, and the INFO").contains(NotificationLevel.ERROR, NotificationLevel.INFO);
        assertThat(onFx(fixture.project::get)).as("Song A stays the open project").isSameAs(songAModel);
        assertThat(openProject()).isEqualTo(fixture.songA);
        assertThat(Files.isDirectory(destination)).as("the extracted folder is kept").isTrue();
    }

    @Test
    void aCancelledLocationPromptAtThePromptLeavesTheNeverSavedProjectOpenWithTheInfoAndItsOpen()
            throws Exception {
        // Reachable: a project that was never saved asks for its folder at the
        // prompt's Save, as its Ctrl+S would.
        fixture.projectManager.abandonProject();
        AtomicInteger locationPrompts = new AtomicInteger();
        fixture.promptAnswer = "Save";
        DawProject untitled = fixture.project.get();
        runOnFx(() -> {
            fixture.lifecycle().setNewProjectLocationPrompt(() -> {
                locationPrompts.incrementAndGet();
                return null;
            });
            fixture.lifecycle().startArchiveRestore(archive, destination);
            fixture.project.get().createAudioTrack("Edited during the extraction");
            fixture.project.get().markDirty();
        });

        NotificationEntry info = awaitTheInfo();
        assertThat(fixture.prompts).hasValue(1);
        assertThat(locationPrompts).as("the prompt's Save asked for a folder, which was cancelled").hasValue(1);
        assertThat(info.message()).isEqualTo(ProjectLifecycleController.restoredArchiveNotOpenedMessage(destination));
        assertThat(info.actionLabel()).contains(ProjectLifecycleController.OPEN_RESTORED_ARCHIVE_ACTION);
        assertThat(onFx(fixture.project::get)).as("the never-saved project stays open").isSameAs(untitled);
        assertThat(onFx(fixture.projectManager::getCurrentProject)).as("nothing was opened or created").isNull();
        assertThat(Files.isDirectory(destination)).as("the extracted folder is kept").isTrue();
    }

    @Test
    void aDoorAskedWhileTheArchiveIsExtractedIsRefusedWithOneInfo() throws Exception {
        AtomicInteger doorsRun = new AtomicInteger();
        runOnFx(() -> {
            fixture.lifecycle().startArchiveRestore(archive, destination);
            // In the extraction's FX step: its outcome cannot reach the FX thread before these clicks.
            for (int click = 0; click < 3; click++) {
                fixture.lifecycle().whenProjectMayClose(ProjectLifecycleController.OPEN_PROJECT_DOOR,
                        doorsRun::incrementAndGet);
            }
        });

        assertThat(fixture.notifications()).as("one INFO for three clicks").singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.INFO);
            assertThat(entry.message()).isEqualTo(ProjectLifecycleController.STILL_EXTRACTING_MESSAGE);
        });
        awaitTheRestoreOpened();
        assertThat(doorsRun).as("no refused door ran").hasValue(0);
    }

    private Path openProject() throws Exception {
        return onFx(() -> fixture.projectManager.getCurrentProject().projectPath());
    }

    private void awaitTheRestoreOpened() throws Exception {
        fixture.awaitOnFx(() -> destination.equals(fixture.projectManager.getCurrentProject().projectPath()),
                "the restored project opened");
    }

    private NotificationEntry awaitTheInfo() throws Exception {
        fixture.awaitOnFx(() -> fixture.shown.getEntries().stream()
                .anyMatch(entry -> entry.level() == NotificationLevel.INFO), "the INFO");
        return fixture.notifications().stream().filter(entry -> entry.level() == NotificationLevel.INFO)
                .findFirst().orElseThrow();
    }
}
