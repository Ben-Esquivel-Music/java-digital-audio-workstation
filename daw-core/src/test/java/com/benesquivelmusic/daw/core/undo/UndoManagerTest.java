package com.benesquivelmusic.daw.core.undo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UndoManagerTest {

    private UndoManager undoManager;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
    }

    @Test
    void newManagerHasEmptyStacks() {
        assertThat(undoManager.canUndo()).isFalse();
        assertThat(undoManager.canRedo()).isFalse();
        assertThat(undoManager.undoSize()).isZero();
        assertThat(undoManager.redoSize()).isZero();
        assertThat(undoManager.undoDescription()).isEmpty();
        assertThat(undoManager.redoDescription()).isEmpty();
    }

    @Test
    void executeRunsActionAndPushesToUndoStack() {
        ArrayList<String> log = new ArrayList<String>();
        undoManager.execute(action("Add Track", log));

        assertThat(log).containsExactly("execute:Add Track");
        assertThat(undoManager.canUndo()).isTrue();
        assertThat(undoManager.undoSize()).isEqualTo(1);
        assertThat(undoManager.undoDescription()).isEqualTo("Add Track");
    }

    @Test
    void undoReversesLastAction() {
        ArrayList<String> log = new ArrayList<String>();
        undoManager.execute(action("A", log));
        undoManager.execute(action("B", log));

        boolean result = undoManager.undo();

        assertThat(result).isTrue();
        assertThat(log).containsExactly("execute:A", "execute:B", "undo:B");
        assertThat(undoManager.undoSize()).isEqualTo(1);
        assertThat(undoManager.canRedo()).isTrue();
        assertThat(undoManager.redoDescription()).isEqualTo("B");
    }

    @Test
    void redoReappliesUndoneAction() {
        ArrayList<String> log = new ArrayList<String>();
        undoManager.execute(action("A", log));
        undoManager.undo();
        log.clear();

        boolean result = undoManager.redo();

        assertThat(result).isTrue();
        assertThat(log).containsExactly("execute:A");
        assertThat(undoManager.canUndo()).isTrue();
        assertThat(undoManager.canRedo()).isFalse();
    }

    @Test
    void undoOnEmptyStackReturnsFalse() {
        assertThat(undoManager.undo()).isFalse();
    }

    @Test
    void redoOnEmptyStackReturnsFalse() {
        assertThat(undoManager.redo()).isFalse();
    }

    @Test
    void newActionClearsRedoStack() {
        ArrayList<String> log = new ArrayList<String>();
        undoManager.execute(action("A", log));
        undoManager.execute(action("B", log));
        undoManager.undo(); // undo B, B goes to redo

        undoManager.execute(action("C", log)); // new action clears redo

        assertThat(undoManager.canRedo()).isFalse();
        assertThat(undoManager.redoSize()).isZero();
        assertThat(undoManager.undoSize()).isEqualTo(2); // A, C
    }

    @Test
    void historyIsTrimmedToMaxDepth() {
        UndoManager manager = new UndoManager(3);
        ArrayList<String> log = new ArrayList<String>();

        manager.execute(action("A", log));
        manager.execute(action("B", log));
        manager.execute(action("C", log));
        manager.execute(action("D", log));

        assertThat(manager.undoSize()).isEqualTo(3);
        // The oldest action (A) should have been discarded
        manager.undo(); // undoes D
        manager.undo(); // undoes C
        manager.undo(); // undoes B
        assertThat(manager.undo()).isFalse(); // A was trimmed
    }

    @Test
    void clearRemovesBothStacks() {
        ArrayList<String> log = new ArrayList<String>();
        undoManager.execute(action("A", log));
        undoManager.execute(action("B", log));
        undoManager.undo();

        undoManager.clear();

        assertThat(undoManager.canUndo()).isFalse();
        assertThat(undoManager.canRedo()).isFalse();
        assertThat(undoManager.undoSize()).isZero();
        assertThat(undoManager.redoSize()).isZero();
    }

    @Test
    void defaultMaxHistoryIs100() {
        assertThat(undoManager.getMaxHistory()).isEqualTo(100);
    }

    @Test
    void constructorRejectsNonPositiveMaxHistory() {
        assertThatThrownBy(() -> new UndoManager(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UndoManager(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executeRejectsNullAction() {
        assertThatThrownBy(() -> undoManager.execute(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void multipleUndoRedoCyclesWorkCorrectly() {
        ArrayList<String> log = new ArrayList<String>();
        undoManager.execute(action("A", log));
        undoManager.execute(action("B", log));
        undoManager.execute(action("C", log));

        undoManager.undo(); // undo C
        undoManager.undo(); // undo B
        undoManager.redo(); // redo B
        undoManager.redo(); // redo C

        assertThat(undoManager.undoSize()).isEqualTo(3);
        assertThat(undoManager.redoSize()).isZero();
    }

    private static UndoableAction action(String name, List<String> log) {
        return new UndoableAction() {
            @Override
            public String description() {
                return name;
            }

            @Override
            public void execute() {
                log.add("execute:" + name);
            }

            @Override
            public void undo() {
                log.add("undo:" + name);
            }
        };
    }
}
