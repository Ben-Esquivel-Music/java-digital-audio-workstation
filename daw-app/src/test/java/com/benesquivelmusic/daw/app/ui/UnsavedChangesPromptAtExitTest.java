package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.LockConflictResolution;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, the studio engineer's ruling R2 (and N4, N9, R6): what the
 * close's unsaved-changes prompt says for the project it closes — its lines
 * in order, the failure of the last save first, then the take just recorded,
 * then what Discard leaves — and what Save and close does then: a project
 * never saved asks for its folder ("Save and close…"), whose chooser's cancel
 * cancels the exit before any step, and whose folder is then created off the
 * FX thread and saved once; a project open read-only asks to save a copy and
 * saves it through the exit's Save As, pre-filled {@code <folder> copy},
 * never into its folder, then closes; no unsaved changes, no prompt.
 * ({@code ProjectClosePromptsTest} pins each content variant's words.)
 */
@ExtendWith(JavaFxToolkitExtension.class)
class UnsavedChangesPromptAtExitTest {

    @TempDir
    Path workspace;

    private ExitFixture fixture;

    @AfterEach
    void close() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void theLastSavesFailureComesFirstThenTheTakeThenWhatDiscardLeaves() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.edit("Bass");
        fixture.save.publishes.failNext(SaveFlowFixture::isHead, new IOException("the volume went away"));
        fixture.save.save();
        fixture.save.settle();
        ExitFixture.ScriptedTakes takes = new ExitFixture.ScriptedTakes();
        fixture.scriptedTakes = takes;
        runOnFx(takes::record);
        fixture.stopTakeAnswer = "Stop the take and close";
        fixture.save.promptAnswer = "Cancel";

        fixture.closeBox();
        fixture.await(takes::takeBeingWritten, "the take wait");
        runOnFx(takes::published);
        fixture.await(() -> fixture.unsavedPrompts.size() == 1 && fixture.steps.size() == 2, "the prompt");

        assertThat(fixture.unsavedPrompts.getFirst().content()).isEqualTo("The last save failed (the volume went"
                + " away).\nYour changes include the take you just recorded.\nIf you discard them, “Song A” is left"
                + " empty, as it was created.");
    }

    @Test
    void noUnsavedChangesAsksNothing() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.unsavedPrompts).isEmpty();
        assertThat(fixture.stepNames()).startsWith("INTERCEPT DONE", "GUARD DONE", "FLUSH SKIPPED");
    }

    @Test
    void aProjectNeverSavedAsksForItsFolderAndCancellingTheChooserCancelsTheExit() throws Exception {
        fixture = aProjectNeverSaved();
        runOnFx(() -> fixture.lifecycle().setNewProjectLocationPrompt(() -> null));
        fixture.save.promptAnswer = "Save and close…";

        fixture.closeBox();
        fixture.await(() -> fixture.steps.size() == 3, "the exit's end");

        ExitFixture.Shown prompt = fixture.unsavedPrompts.getFirst();
        assertThat(prompt.content()).isEqualTo("This project has never been saved, so its changes are gone if you"
                + " discard them.");
        assertThat(prompt.buttons()).containsExactly("Save and close…", "Discard and close", "Cancel");
        assertThat(fixture.stepNames()).as("the window stays and no step after the decision ran")
                .containsExactly("INTERCEPT DONE", "GUARD DONE", "FLUSH CANCELLED");
        assertThat(fixture.transportStops).as("the chooser came first: the transport was not stopped (N9)")
                .hasValue(0);
        assertThat(onFx(() -> fixture.stage().isShowing())).isTrue();
    }

    @Test
    void aProjectNeverSavedIsSavedOnceIntoTheFolderTheChooserGaveThenTheWindowHides() throws Exception {
        fixture = aProjectNeverSaved();
        Path parent = Files.createDirectories(workspace.resolve("chosen"));
        runOnFx(() -> fixture.lifecycle().setNewProjectLocationPrompt(() -> parent));
        fixture.save.promptAnswer = "Save and close…";
        fixture.save.publishes.clear();

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        Path created = parent.resolve("Untitled Project");
        assertThat(Files.readString(created.resolve("project.daw"), StandardCharsets.UTF_8)).contains("Keys");
        assertThat(fixture.save.publishes.done(path -> SaveFlowFixture.isHead(path) && path.startsWith(created)))
                .as("the create's metadata head, then the one canonical save").hasSize(2);
        assertThat(fixture.transportStops).as("stopped after the chooser, before the capture").hasPositiveValue();
    }

    @Test
    void aReadOnlyProjectSavesACopyThroughTheExitsSaveAsAndNeverWritesItsFolder() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT, false, UnaryOperator.identity());
        Path songA = fixture.songA;
        fixture.save.projectManager.closeProject().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
        new ProjectLockManager(InstantSource.system(), "bob", "Mix-Room", 7L).forceAcquire(songA);
        fixture.save.projectManager.setLockConflictHandler((lock, stale) -> LockConflictResolution.OPEN_READ_ONLY);
        java.util.concurrent.CompletableFuture<Object> opened = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                opened.complete(fixture.save.projectManager.openProject(songA));
            } catch (Throwable notOpened) {
                opened.completeExceptionally(notOpened);
            }
        });
        opened.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(fixture.save.projectManager.isReadOnly()).as("fixture: Song A is open read-only").isTrue();
        fixture.edit("Bass");
        byte[] head = Files.readAllBytes(songA.resolve("project.daw"));
        fixture.save.promptAnswer = "Save and close…";
        fixture.save.saveAsTargets.answer(call -> java.util.Optional.of(call.directory().resolve(call.name())));
        fixture.save.publishes.clear();

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        ExitFixture.Shown prompt = fixture.unsavedPrompts.getFirst();
        assertThat(prompt.header()).isEqualTo("“Song A” is open read-only. Save your changes as a new project before"
                + " closing?");
        assertThat(prompt.buttons().getFirst()).isEqualTo("Save and close…");
        SaveFlowFixture.SaveAsTargets.Call call = fixture.save.saveAsTargets.calls().getFirst();
        assertThat(call.name()).as("R6: the plain copy pre-fill").isEqualTo("Song A copy");
        assertThat(Files.readString(workspace.resolve("Song A copy").resolve("project.daw"), StandardCharsets.UTF_8))
                .contains("Bass");
        assertThat(fixture.save.publishes.done(path -> path.startsWith(songA))).as("never a save into its folder")
                .isEmpty();
        assertThat(Files.readAllBytes(songA.resolve("project.daw"))).isEqualTo(head);
    }

    /** Song A closed with Close Project (nothing to save), then an edit of the Untitled project: no folder. */
    private ExitFixture aProjectNeverSaved() throws Exception {
        ExitFixture made = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        made.closeProject().toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(made.save.projectManager.getCurrentProject()).as("fixture: no folder is open").isNull();
        made.edit("Keys");
        made.steps.clear();
        made.transportStops.set(0);
        return made;
    }
}
