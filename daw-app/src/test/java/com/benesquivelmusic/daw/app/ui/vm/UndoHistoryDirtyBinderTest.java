package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one dirty path through the undo history (PR #984 review): every
 * execute, undo, redo or history jump marks the generation's project dirty;
 * a clear of either stack does not; and the binder lives exactly one
 * UndoManager + project generation. Headless — no FX toolkit.
 */
class UndoHistoryDirtyBinderTest {

    private UndoManager undoManager;
    private DawProject project;
    private UndoHistoryDirtyBinder binder;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
        project = new DawProject("Test", AudioFormat.STUDIO_QUALITY);
        binder = new UndoHistoryDirtyBinder(undoManager, project);
    }

    @Test
    void executeMarksTheProjectDirty() {
        assertThat(project.isDirty()).isFalse();

        undoManager.execute(edit("A"));

        assertThat(project.isDirty()).isTrue();
    }

    @Test
    void undoAfterASaveMarksTheProjectDirty() {
        undoManager.execute(edit("A"));
        project.markClean();

        undoManager.undo();

        assertThat(project.isDirty()).isTrue();
    }

    @Test
    void redoAfterASaveMarksTheProjectDirty() {
        undoManager.execute(edit("A"));
        undoManager.undo();
        project.markClean();

        undoManager.redo();

        assertThat(project.isDirty()).isTrue();
    }

    @Test
    void aHistoryJumpMarksTheProjectDirty() {
        undoManager.execute(edit("A"));
        undoManager.execute(edit("B"));
        project.markClean();

        undoManager.goToHistoryIndex(-1);

        assertThat(project.isDirty()).isTrue();
    }

    @Test
    void aJumpToTheCurrentPositionChangesNothingAndStaysClean() {
        undoManager.execute(edit("A"));
        project.markClean();

        undoManager.goToHistoryIndex(undoManager.getCurrentHistoryIndex());

        assertThat(project.isDirty()).isFalse();
    }

    @Test
    void executeAtTheHistoryCapMarksTheProjectDirty() {
        // At the cap the stack sizes do not move (push one, trim the oldest),
        // so only the reported change kind can tell this was an edit.
        UndoManager capped = new UndoManager(2);
        DawProject cappedProject = new DawProject("Capped", AudioFormat.STUDIO_QUALITY);
        UndoHistoryDirtyBinder cappedBinder = new UndoHistoryDirtyBinder(capped, cappedProject);
        capped.execute(edit("A"));
        capped.execute(edit("B"));
        cappedProject.markClean();
        assertThat(capped.undoSize()).isEqualTo(capped.getMaxHistory());

        capped.execute(edit("C"));

        assertThat(capped.undoSize()).isEqualTo(capped.getMaxHistory());
        assertThat(capped.redoSize()).isZero();
        assertThat(cappedProject.isDirty()).isTrue();
        cappedBinder.dispose();
    }

    @Test
    void clearingTheHistoryLeavesACleanProjectClean() {
        undoManager.execute(edit("A"));
        undoManager.undo();
        project.markClean();

        undoManager.clearRedoStack();
        undoManager.clear();

        assertThat(project.isDirty()).isFalse();
    }

    @Test
    void bindingAFreshGenerationLeavesItsCleanProjectClean() {
        // Binder level only: attaching to a fresh manager and a clean project
        // fires no change, whatever the outgoing generation's state. The real
        // Open sequence (fresh manager, then the rebuild that attaches the
        // binder) is pinned by ProjectLifecycleControllerTest and
        // UndoHistoryWiringSentinelTest.
        undoManager.execute(edit("edit in the outgoing project"));
        assertThat(project.isDirty()).isTrue();

        DawProject next = new DawProject("Next", AudioFormat.STUDIO_QUALITY);
        UndoManager nextHistory = new UndoManager();
        next.markClean();
        binder.dispose();
        UndoHistoryDirtyBinder nextBinder = new UndoHistoryDirtyBinder(nextHistory, next);

        assertThat(next.isDirty()).isFalse();
        nextHistory.clear();
        assertThat(next.isDirty()).as("a clear is never an edit").isFalse();
        nextBinder.dispose();
    }

    @Test
    void afterRebindingOnlyTheNewGenerationMarksItsProject() {
        DawProject oldProject = project;
        UndoManager oldHistory = undoManager;
        binder.dispose();
        DawProject newProject = new DawProject("New", AudioFormat.STUDIO_QUALITY);
        UndoManager newHistory = new UndoManager();
        UndoHistoryDirtyBinder newBinder = new UndoHistoryDirtyBinder(newHistory, newProject);

        oldHistory.execute(edit("late edit on the abandoned manager"));

        assertThat(oldProject.isDirty()).as("the old manager is released").isFalse();
        assertThat(newProject.isDirty()).as("and never marks the new project").isFalse();

        newHistory.execute(edit("edit on the live manager"));

        assertThat(newProject.isDirty()).isTrue();
        assertThat(oldProject.isDirty()).isFalse();
        newBinder.dispose();
    }

    @Test
    void disposeDetaches() {
        binder.dispose();
        binder.dispose();   // idempotent

        undoManager.execute(edit("A"));

        assertThat(project.isDirty()).isFalse();
    }

    private static UndoableAction edit(String description) {
        return new UndoableAction() {
            @Override
            public String description() {
                return description;
            }

            @Override
            public void execute() {
            }

            @Override
            public void undo() {
            }
        };
    }
}
