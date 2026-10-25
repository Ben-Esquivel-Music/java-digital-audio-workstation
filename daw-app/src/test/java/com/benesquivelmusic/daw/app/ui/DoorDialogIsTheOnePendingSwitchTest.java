package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 331, ruling L4: a door's file dialog — New Project's location prompt,
 * Open Project's and Import DAWproject Exchange's choosers, Restore from
 * Archive's two choosers — blocks only its owner window, so the snapshot
 * browser, the Project Hub and the Welcome window stay live while it is open.
 * The door is the one switch pending from its judgment until the dialog
 * returns: a switch asked meanwhile is refused with one INFO, "&lt;door&gt;'s
 * file dialog is still open — finish or cancel it first.", and nothing is
 * replaced underneath the dialog. The door's answer is the one it gave at
 * its judgment: an edit made while the dialog was open — from a floating
 * window or a control surface — is asked about again before the switch,
 * with the door's own prompt; with no edit nothing more is asked. A dialog
 * that returns nothing, an import read that fails and an exception end the
 * pending state.
 *
 * <p>The choosers are the controller's prompt seams; a test acts inside one
 * as the user would while the native dialog is open. The unsaved-changes
 * prompt is answered by {@link SaveFlowFixture}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DoorDialogIsTheOnePendingSwitchTest {

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;
    private Path songB;

    @BeforeEach
    void openSongA() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        songB = fixture.anotherProjectOnDisk("Song B");
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void theStringIsTheRulingsOwn() {
        assertThat(ProjectLifecycleController.doorDialogStillOpenMessage(ProjectLifecycleController.OPEN_PROJECT_DOOR))
                .isEqualTo("Open Project's file dialog is still open — finish or cancel it first.");
    }

    @Test
    void aSnapshotRestoreWhileOpenProjectsChooserIsOpenIsRefusedAndOpenGoesOnAsAnswered() throws Exception {
        SnapshotEntry entry = onFx(() -> fixture.snapshots().createCheckpointWithLabel("Before the open"));
        DawProject songAModel = fixture.project.get();
        AtomicReference<DawProject> openWhileTheChooserWasOpen = new AtomicReference<>();

        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> {
                // The snapshot browser's Restore, three times, while Open's chooser is open.
                for (int click = 0; click < 3; click++) {
                    fixture.snapshots().restoreEntry(entry);
                }
                openWhileTheChooserWasOpen.set(fixture.project.get());
                return songB;
            });
            fixture.lifecycle().onOpenProject();
        });

        assertThat(openWhileTheChooserWasOpen.get()).as("nothing replaced Song A underneath the chooser")
                .isSameAs(songAModel);
        assertThat(fixture.notifications())
                .filteredOn(notification -> notification.level() == NotificationLevel.INFO)
                .as("one INFO for three clicks").singleElement()
                .extracting(NotificationEntry::message)
                .isEqualTo("Open Project's file dialog is still open — finish or cancel it first.");
        fixture.awaitOnFx(() -> songB.equals(fixture.projectManager.getCurrentProject().projectPath()),
                "Open opens Song B");
        assertThat(fixture.prompts).as("no edit since Open's judgment: nothing more is asked").hasValue(0);
    }

    @Test
    void anEditWhileOpenProjectsChooserIsOpenIsAskedAboutAgainAndCancelEndsTheDoorWithNoMessage()
            throws Exception {
        DawProject songAModel = fixture.project.get();
        fixture.promptAnswer = null; // hidden: a cancel

        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> {
                // An edit from a floating window while the chooser is open.
                fixture.project.get().createAudioTrack("Edited while choosing");
                fixture.project.get().markDirty();
                return songB;
            });
            fixture.lifecycle().onOpenProject();
        });
        fixture.settle();

        assertThat(fixture.promptQuestions).as("asked again, in the door's own words")
                .containsExactly("Do you want to save before continuing?");
        assertThat(fixture.promptHeaders).as("under the unsaved-changes prompt's own header")
                .containsExactly("You have unsaved changes.");
        assertThat(onFx(fixture.project::get)).as("Cancel ends the door: nothing is replaced").isSameAs(songAModel);
        assertThat(onFx(() -> fixture.projectManager.getCurrentProject().projectPath())).isEqualTo(fixture.songA);
        assertThat(fixture.notifications()).as("and no message is shown").isEmpty();
        fixture.promptAnswer = "Discard";
        assertThat(onFx(() -> DoorGate.proceedsAtOnce(fixture.lifecycle())))
                .as("no switch is left pending: the next door asks, and proceeds on Discard").isTrue();
    }

    @Test
    void saveAtTheSecondPromptSavesTheEditMadeWhileChoosingThenOpens() throws Exception {
        fixture.promptAnswer = "Save";

        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> {
                fixture.project.get().createAudioTrack("Edited while choosing");
                fixture.project.get().markDirty();
                return songB;
            });
            fixture.lifecycle().onOpenProject();
        });

        fixture.awaitOnFx(() -> songB.equals(fixture.projectManager.getCurrentProject().projectPath()),
                "Open opens Song B once the edit is saved");
        assertThat(fixture.prompts).hasValue(1);
        assertThat(Files.readString(fixture.songA.resolve("project.daw"))).as("the edit was saved into Song A first")
                .contains("Edited while choosing");
    }

    @Test
    void withNoEditWhileTheChooserIsOpenADiscardAnsweredAtTheDoorIsNotAskedAgain() throws Exception {
        runOnFx(() -> fixture.project.get().markDirty());
        fixture.promptAnswer = "Discard";
        AtomicInteger promptsWhileChoosing = new AtomicInteger(-1);

        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> {
                promptsWhileChoosing.set(fixture.prompts.get());
                return songB;
            });
            fixture.lifecycle().onOpenProject();
        });

        fixture.awaitOnFx(() -> songB.equals(fixture.projectManager.getCurrentProject().projectPath()),
                "Open opens Song B");
        assertThat(promptsWhileChoosing).as("fixture: the door asked, and was answered Discard").hasValue(1);
        assertThat(fixture.prompts).as("the same project at the same revision: not asked again").hasValue(1);
    }

    @Test
    void aDoorAskedWhileNewProjectsLocationPromptIsOpenIsRefusedAndNewGoesOn() throws Exception {
        Path parent = Files.createDirectories(workspace.resolve("new"));
        AtomicBoolean hubOpenRan = new AtomicBoolean();

        runOnFx(() -> {
            fixture.lifecycle().setNewProjectLocationPrompt(() -> {
                // A Project Hub open while New's location prompt is open.
                fixture.lifecycle().whenProjectMayClose(ProjectLifecycleController.OPEN_PROJECT_DOOR,
                        () -> hubOpenRan.set(true));
                return parent;
            });
            fixture.lifecycle().onNewProject();
        });
        fixture.settle();

        assertThat(hubOpenRan).as("the refused open never ran").isFalse();
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .contains("New Project's file dialog is still open — finish or cancel it first.");
        assertThat(onFx(() -> fixture.projectManager.getCurrentProject().projectPath().getParent()))
                .as("New created its project where the prompt said").isEqualTo(parent);
    }

    @Test
    void aCancelledChooserAFailedImportReadAndAnExceptionEachEndTheDoorsPendingState() throws Exception {
        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> null);
            fixture.lifecycle().onOpenProject();
        });
        assertThat(onFx(() -> DoorGate.proceedsAtOnce(fixture.lifecycle())))
                .as("after a cancelled chooser no switch is pending").isTrue();

        Path notASession = Files.writeString(workspace.resolve("not-a-session.dawproject"), "not a DAWproject");
        runOnFx(() -> {
            fixture.lifecycle().setImportSessionPrompt(() -> notASession);
            fixture.lifecycle().onImportSession();
        });
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .anyMatch(message -> message.startsWith("Import failed: "));
        assertThat(onFx(() -> DoorGate.proceedsAtOnce(fixture.lifecycle())))
                .as("after a failed import read no switch is pending").isTrue();

        assertThatThrownBy(() -> runOnFx(() -> {
            fixture.lifecycle().setArchiveToRestorePrompt(() -> {
                throw new IllegalStateException("injected chooser failure");
            });
            fixture.lifecycle().onRestoreFromArchive();
        })).hasRootCauseMessage("injected chooser failure");
        assertThat(onFx(() -> DoorGate.proceedsAtOnce(fixture.lifecycle())))
                .as("after an exception out of the dialog no switch is pending").isTrue();
        assertThat(onFx(fixture.projectManager::getCurrentProject).projectPath()).isEqualTo(fixture.songA);
    }

    @Test
    void aSnapshotRestoreWhileRestoreFromArchivesChooserIsOpenIsRefusedAndTheRestoreGoesOn() throws Exception {
        Path archive = fixture.archiveOnDisk("Song R");
        Path parent = Files.createDirectories(workspace.resolve("restored"));
        SnapshotEntry entry = onFx(() -> fixture.snapshots().createCheckpointWithLabel("Before the restore"));
        DawProject songAModel = fixture.project.get();
        AtomicReference<DawProject> openWhileTheChooserWasOpen = new AtomicReference<>();

        runOnFx(() -> {
            fixture.lifecycle().setArchiveToRestorePrompt(() -> archive);
            fixture.lifecycle().setRestoreParentPrompt(() -> {
                fixture.snapshots().restoreEntry(entry);
                openWhileTheChooserWasOpen.set(fixture.project.get());
                return parent;
            });
            fixture.lifecycle().onRestoreFromArchive();
        });

        assertThat(openWhileTheChooserWasOpen.get()).as("nothing replaced Song A underneath the chooser")
                .isSameAs(songAModel);
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .contains("Restore from Archive's file dialog is still open — finish or cancel it first.");
        fixture.awaitOnFx(() -> parent.equals(fixture.projectManager.getCurrentProject().projectPath().getParent()),
                "the restored project opened");
        assertThat(fixture.prompts).as("no edit since the door's judgment: nothing more is asked").hasValue(0);
    }

    @Test
    void anEditWhileRestoreFromArchivesChooserIsOpenIsAskedAboutAfterTheExtraction() throws Exception {
        Path archive = fixture.archiveOnDisk("Song R");
        Path parent = Files.createDirectories(workspace.resolve("restored"));
        DawProject songAModel = fixture.project.get();
        fixture.promptAnswer = null; // hidden: a cancel

        runOnFx(() -> {
            fixture.lifecycle().setArchiveToRestorePrompt(() -> archive);
            fixture.lifecycle().setRestoreParentPrompt(() -> {
                // The answer was recorded at the door's judgment, before this edit.
                fixture.project.get().createAudioTrack("Edited while choosing");
                fixture.project.get().markDirty();
                return parent;
            });
            fixture.lifecycle().onRestoreFromArchive();
        });
        fixture.awaitOnFx(() -> fixture.shown.getEntries().stream()
                .anyMatch(entry -> entry.level() == NotificationLevel.INFO), "the restore's INFO");

        Path destination = theOnlyFolderIn(parent);
        assertThat(fixture.promptQuestions).as("K3 asks again, in its own words")
                .containsExactly(ProjectLifecycleController.restoredArchivePromptText(destination));
        assertThat(fixture.notifications()).filteredOn(entry -> entry.level() == NotificationLevel.INFO)
                .singleElement().satisfies(info -> {
                    assertThat(info.message())
                            .isEqualTo(ProjectLifecycleController.restoredArchiveNotOpenedMessage(destination));
                    assertThat(info.actionLabel()).contains(ProjectLifecycleController.OPEN_RESTORED_ARCHIVE_ACTION);
                });
        assertThat(onFx(fixture.project::get)).as("Song A stays open").isSameAs(songAModel);
    }

    private static Path theOnlyFolderIn(Path parent) throws Exception {
        try (Stream<Path> folders = Files.list(parent)) {
            List<Path> found = folders.filter(Files::isDirectory).toList();
            assertThat(found).as("one restored folder").hasSize(1);
            return found.getFirst();
        }
    }
}
