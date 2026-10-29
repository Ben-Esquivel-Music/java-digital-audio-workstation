package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.PendingSwitch;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrigin;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "First-save prompt" (ruling L4; design §3.1): the
 * first save of a project never saved is a pending switch — {@code Save
 * Project} — while its location prompt is open and while its folder is
 * created off the FX thread, so nothing replaces the project underneath it.
 * A snapshot restore asked while the prompt is open is refused with
 * "Save Project's file dialog is still open — finish or cancel it first.",
 * and one asked while the folder is created with "Still creating the
 * project folder — Save Project continues when the folder has been
 * created." (the studio engineer's stage-4 N1); the project is captured once
 * the folder exists — an edit made while the prompt was open is in its
 * {@code project.daw}. A Ctrl+S while the folder is created is that first
 * save's: one prompt, one folder, one head, no ERROR (FEAS-S1). A named
 * snapshot asked meanwhile copies that save's head. A folder that cannot be
 * created is a failed save, reported once, and the next Ctrl+S asks again.
 * A first save asked while another switch is pending — an archive being
 * extracted — is refused, with no prompt and no folder (stage-4 review S3).
 * The strip's "Saving project…" covers the create as well as the head write
 * (the studio engineer's stage-4 S4), and the pending fact never reads false
 * between the prompt and the create (stage-4 review N3).
 *
 * <p>The folder's creation is held at the publish of its metadata-only
 * {@code project.daw} — the create's own write, through the project
 * manager's writer — so nothing sleeps. Kills: the capture before the
 * prompt; a create that is not a pending switch; a second prompt on the
 * second Ctrl+S; a named snapshot queued before its save.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FirstSavePromptIsAPendingSwitchTest {

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;
    private SnapshotEntry entry;
    private Path parent;
    private DawProject neverSaved;

    @BeforeEach
    void openANeverSavedProject() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        entry = onFx(() -> fixture.snapshots().createCheckpointWithLabel("Before the first save"));
        // Its file is written before the project is let go, so no failure of it is reported later.
        fixture.awaitOnFx(() -> fixture.checkpointEvents.stream().anyMatch(event ->
                event instanceof CheckpointEvent.Written written && written.origin() == CheckpointOrigin.USER),
                "the entry's checkpoint written");
        // A project never saved: the project manager holds no project for it.
        fixture.projectManager.closeProject().get(SaveFlowFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
        parent = Files.createDirectories(workspace.resolve("first save"));
        neverSaved = fixture.project.get();
        runOnFx(() -> neverSaved.markDirty());
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void aRestoreWhileTheLocationPromptIsOpenIsRefusedAndTheSaveCapturesTheProjectAsItIsOnceTheFolderExists()
            throws Exception {
        runOnFx(() -> {
            fixture.lifecycle().setNewProjectLocationPrompt(() -> {
                fixture.snapshots().restoreEntry(entry);
                // An edit from a window the native dialog does not block, while it is open.
                fixture.project.get().createAudioTrack("Added while choosing");
                return parent;
            });
            fixture.lifecycle().onSaveProject();
        });
        Path folder = awaitTheFirstSave();

        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .contains("Save Project's file dialog is still open — finish or cancel it first.");
        assertThat(onFx(fixture.project::get)).as("nothing replaced the project underneath the prompt")
                .isSameAs(neverSaved);
        assertThat(Files.readString(folder.resolve("project.daw")))
                .as("the project as it was once the folder existed, the edit made while choosing included")
                .contains("Added while choosing").contains("Vox");
        assertThat(onFx(() -> neverSaved.isDirty())).isFalse();
    }

    @Test
    void aRestoreWhileTheFolderIsCreatedIsRefusedAndACtrlSMeanwhileIsTheFirstSavesOwn() throws Exception {
        AtomicInteger prompts = new AtomicInteger();
        List<Boolean> pendingChanges = new java.util.concurrent.CopyOnWriteArrayList<>();
        javafx.beans.value.ChangeListener<Boolean> recording = (observable, was, now) -> pendingChanges.add(now);
        fixture.holdSlowSaveTimers();
        SaveFlowFixture.Publishes.Hold create = fixture.publishes.holdNext(
                target -> SaveFlowFixture.isHead(target) && target.startsWith(parent));
        runOnFx(() -> {
            fixture.lifecycle().switchPendingProperty().addListener(recording);
            fixture.lifecycle().setNewProjectLocationPrompt(() -> {
                prompts.incrementAndGet();
                return parent;
            });
            fixture.lifecycle().onSaveProject();
        });
        Thread creator = create.awaitHeld();
        assertThat(creator.getName()).as("the folder is created off the FX thread")
                .isEqualTo(ProjectLifecycleController.PROJECT_CREATE_WORKER);
        assertThat(pendingChanges).as("stage-4 review N3: true from the prompt on, never false before the create")
                .containsExactly(true);
        fixture.fireSlowSaveTimers();
        assertThat(fixture.operationNames()).as("ENG4-S4: the create shows on the strip as the save")
                .containsExactly("Saving project…");
        // Stage-4 review N6: an edit made while the folder is created is in the first save's head.
        runOnFx(() -> fixture.project.get().createAudioTrack("Added during the create"));

        runOnFx(() -> {
            assertThat(fixture.lifecycle().closeReadiness().pending())
                    .isEqualTo(new PendingSwitch.CreatingFolder("Save Project"));
            assertThat(fixture.lifecycle().switchPendingProperty().get()).isTrue();
            fixture.snapshots().restoreEntry(entry);
            fixture.lifecycle().onSaveProject();
            fixture.lifecycle().onSaveProject();
        });
        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .as("the restore refused with one INFO, the two Ctrl+S silent")
                .filteredOn(message -> message.startsWith("Still"))
                .containsExactly("Still creating the project folder — Save Project continues when the folder has"
                        + " been created.");
        create.release();
        Path folder = awaitTheFirstSave();
        runOnFx(() -> fixture.lifecycle().switchPendingProperty().removeListener(recording));
        assertThat(Files.readString(folder.resolve("project.daw"))).as("captured once the folder existed")
                .contains("Added during the create");
        assertThat(fixture.operationNames()).as("gone once the head was saved").isEmpty();

        assertThat(prompts).as("one location prompt for three Ctrl+S").hasValue(1);
        assertThat(foldersIn(parent)).as("one folder").containsExactly(folder);
        assertThat(fixture.publishes.done(target -> SaveFlowFixture.isHead(target) && target.startsWith(folder)))
                .as("the create's metadata-only head, then one save's head").hasSize(2);
        assertThat(fixture.notifications()).extracting(NotificationEntry::level).as("no ERROR")
                .doesNotContain(NotificationLevel.ERROR);
        assertThat(onFx(fixture.project::get)).isSameAs(neverSaved);
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending())).isEqualTo(new PendingSwitch.None());
    }

    @Test
    void aNamedSnapshotAskedWhileTheFolderIsCreatedCopiesTheFirstSavesHead() throws Exception {
        SaveFlowFixture.Publishes.Hold create = fixture.publishes.holdNext(
                target -> SaveFlowFixture.isHead(target) && target.startsWith(parent));
        runOnFx(() -> {
            fixture.lifecycle().setNewProjectLocationPrompt(() -> parent);
            fixture.lifecycle().onSaveProject();
        });
        create.awaitHeld();
        runOnFx(() -> fixture.lifecycle().createNamedSnapshot("First"));
        create.release();
        Path folder = awaitTheFirstSave();
        fixture.awaitOnFx(() -> fixture.shown.getEntries().stream()
                .anyMatch(shown -> shown.level() == NotificationLevel.SUCCESS), "the snapshot's SUCCESS");

        assertThat(fixture.notifications()).extracting(NotificationEntry::message).contains("Snapshot saved: First");
        Path snapshots = folder.resolve("snapshots");
        try (Stream<Path> copies = Files.list(snapshots)) {
            List<Path> found = copies.toList();
            assertThat(found).as("one snapshot").hasSize(1);
            assertThat(Files.readString(found.getFirst())).as("it holds the first save's head")
                    .isEqualTo(Files.readString(folder.resolve("project.daw")));
        }
    }

    @Test
    void aFirstSaveWhileAnArchiveIsExtractedIsRefusedAndAsksNothing() throws Exception {
        Path archive = fixture.archiveOnDisk("Song R");
        Path restored = Files.createDirectories(workspace.resolve("restored"));
        SaveFlowFixture.Publishes.Hold extraction = fixture.publishes.holdNext(
                target -> SaveFlowFixture.isHead(target) && target.startsWith(restored));
        AtomicInteger prompts = new AtomicInteger();
        fixture.promptAnswer = "Discard";
        runOnFx(() -> {
            fixture.lifecycle().setFileWriter(fixture.writer);
            fixture.lifecycle().setArchiveToRestorePrompt(() -> archive);
            fixture.lifecycle().setRestoreParentPrompt(() -> restored);
            fixture.lifecycle().setNewProjectLocationPrompt(() -> {
                prompts.incrementAndGet();
                return parent;
            });
            fixture.lifecycle().onRestoreFromArchive();
        });
        extraction.awaitHeld();

        runOnFx(() -> fixture.lifecycle().onSaveProject());

        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .as("stage-4 review S3: refused as every switch is while an extraction runs")
                .contains(ProjectLifecycleController.STILL_EXTRACTING_MESSAGE);
        assertThat(prompts).as("no location prompt").hasValue(0);
        assertThat(foldersIn(parent)).as("no folder created").isEmpty();
        extraction.release();
        fixture.awaitOnFx(() -> fixture.projectManager.getCurrentProject() != null
                && fixture.projectManager.getCurrentProject().projectPath().startsWith(restored)
                && fixture.lifecycle().closeReadiness().pending() instanceof PendingSwitch.None,
                "the restored archive opened");
    }

    @Test
    void aFolderThatCannotBeCreatedIsAFailedSaveAndTheNextCtrlSAsksAgain() throws Exception {
        Path notADirectory = Files.writeString(workspace.resolve("a file"), "not a folder");
        AtomicInteger prompts = new AtomicInteger();
        fixture.holdSlowSaveTimers();
        long failedBefore = onFx(() -> fixture.lifecycle().failedSavesOfOpenProject());
        runOnFx(() -> {
            fixture.lifecycle().setNewProjectLocationPrompt(() -> {
                prompts.incrementAndGet();
                return notADirectory;
            });
            fixture.lifecycle().onSaveProject();
        });
        fixture.awaitOnFx(() -> fixture.shown.getEntries().stream()
                .anyMatch(shown -> shown.level() == NotificationLevel.ERROR), "the ERROR");

        NotificationEntry error = fixture.notifications().getLast();
        assertThat(error.message()).startsWith("Save failed: the project folder could not be created: ");
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending())).isEqualTo(new PendingSwitch.None());
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().headFailure())).as("the failure stands")
                .isPresent();
        assertThat(onFx(() -> fixture.lifecycle().failedSavesOfOpenProject())).isEqualTo(failedBefore + 1);
        assertThat(onFx(fixture.projectManager::getCurrentProject)).as("nothing was created").isNull();
        fixture.fireSlowSaveTimers();
        assertThat(fixture.operationNames()).as("ENG4-S4: a failed create no longer counts as a save running")
                .isEmpty();

        AtomicReference<Path> secondChoice = new AtomicReference<>(parent);
        runOnFx(() -> {
            fixture.lifecycle().setNewProjectLocationPrompt(() -> {
                prompts.incrementAndGet();
                return secondChoice.get();
            });
            fixture.lifecycle().onSaveProject();
        });
        awaitTheFirstSave();
        assertThat(prompts).as("the next Ctrl+S asked for a folder again").hasValue(2);
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().headFailure()))
                .as("a head save's success clears the standing failure").isEmpty();
    }

    /** Waits until the first save's folder is the open project's and its save reported; returns the folder. */
    private Path awaitTheFirstSave() throws Exception {
        fixture.awaitOnFx(() -> fixture.projectManager.getCurrentProject() != null
                && parent.equals(fixture.projectManager.getCurrentProject().projectPath().getParent())
                && fixture.lifecycle().closeReadiness().pending() instanceof PendingSwitch.None,
                "the first save's folder");
        fixture.settle();
        return fixture.projectManager.getCurrentProject().projectPath();
    }

    private static List<Path> foldersIn(Path directory) throws Exception {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isDirectory).toList();
        }
    }
}
