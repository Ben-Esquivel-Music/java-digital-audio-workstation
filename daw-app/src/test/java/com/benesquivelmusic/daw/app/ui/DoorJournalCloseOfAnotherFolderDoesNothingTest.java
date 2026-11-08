package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, the studio engineer's ruling 2 OQ-1, constraint 2, as the
 * application's doors apply it (the app fix pass's hand-off to stage 7):
 * {@code MainController.prepareDoorJournalClose()} — the journal close a
 * door runs before it replaces the open project — ends the journal
 * coordinator's journal only when it is the journal of the project
 * manager's open folder, the folder the door closes. When it is the open
 * folder's, the door's close seals it; when the coordinator's journal is
 * another folder's, the close does nothing: that journal stays open, unsealed
 * by this door. The controller is partial — its journal coordinator, project
 * manager and active session are the exit fixture's, set on non-final
 * fields.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DoorJournalCloseOfAnotherFolderDoesNothingTest {

    @TempDir
    Path workspace;

    @Test
    void theDoorsJournalCloseSealsTheOpenFoldersJournal() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            MainController main = partial(exit);

            Runnable close = onFx(main::prepareDoorJournalClose);
            runOnAWorker(close);

            assertThat(exit.journal.isOpen()).as("the open folder's journal was ended").isFalse();
            assertThat(exit.journal.closedUnsealed(exit.songA)).as("sealed, not drained").isFalse();
            assertThat(JournalState.records(ProjectContext.forProject(exit.songA, InstantSource.system())).getLast()
                    .isSessionSeal()).as("its last record is the seal").isTrue();
        }
    }

    @Test
    void aJournalOfAnotherFolderThanTheOpenOneIsLeftAlone() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            Path songB = exit.save.anotherProjectOnDisk("Song B");
            // The project manager moves to Song B behind the journal's back: the coordinator still holds Song A's.
            exit.save.projectManager.closeProject().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
            CompletableFuture<Void> opened = new CompletableFuture<>();
            Thread.ofVirtual().name("test-open-song-b").start(() -> {   // the open's recovery gate runs off FX
                try {
                    exit.save.projectManager.openProject(songB);
                    opened.complete(null);
                } catch (Throwable failed) {
                    opened.completeExceptionally(failed);
                }
            });
            opened.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(exit.save.currentProjectIs(songB)).isTrue();
            assertThat(exit.journal.currentProjectDirectory()).isEqualTo(exit.songA);
            MainController main = partial(exit);

            Runnable close = onFx(main::prepareDoorJournalClose);
            runOnAWorker(close);

            assertThat(exit.journal.isOpen()).as("another folder's journal is not this door's to end").isTrue();
            assertThat(exit.journal.currentProjectDirectory()).isEqualTo(exit.songA);
            assertThat(JournalState.records(ProjectContext.forProject(exit.songA, InstantSource.system())))
                    .as("Song A's journal was not sealed by this door").noneMatch(record -> record.isSessionSeal());
        }
    }

    private static MainController partial(ExitFixture exit) throws Exception {
        return onFx(() -> {
            MainController controller = new MainController();
            setField(controller, "projectJournalCoordinator", exit.journal);
            setField(controller, "projectManager", exit.save.projectManager);
            setField(controller, "activeSession", exit.activeSession.get());
            setField(controller, "activeSessionProjectDir", exit.activeSessionDirectory.get());
            return controller;
        });
    }

    /** Runs the door's close where the door runs it, off the FX thread, bounded. */
    private static void runOnAWorker(Runnable close) throws Exception {
        CompletableFuture.runAsync(close, runnable -> Thread.ofVirtual().name("test-door-journal-close").start(runnable))
                .get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Sets a non-final field of the partial controller (never a final one: JEP 500). */
    private static void setField(MainController target, String name, Object value) {
        try {
            Field field = MainController.class.getDeclaredField(name);
            assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers())).as("%s is not final", name).isFalse();
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
