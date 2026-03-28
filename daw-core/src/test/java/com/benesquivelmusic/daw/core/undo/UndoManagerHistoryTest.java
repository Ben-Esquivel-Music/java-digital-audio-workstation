package com.benesquivelmusic.daw.core.undo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UndoManagerHistoryTest {

    private UndoManager undoManager;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
    }

    @Test
    void getHistoryReturnsEmptyListWhenEmpty() {
        assertThat(undoManager.getHistory()).isEmpty();
    }

    @Test
    void getHistoryReturnsActionsInOldestFirstOrder() {
        ArrayList<String> log = new ArrayList<>();
        undoManager.execute(action("A", log));
        undoManager.execute(action("B", log));
        undoManager.execute(action("C", log));

        List<UndoableAction> history = undoManager.getHistory();

        assertThat(history).hasSize(3);
        assertThat(history.get(0).description()).isEqualTo("A");
        assertThat(history.get(1).description()).isEqualTo("B");
        assertThat(history.get(2).description()).isEqualTo("C");
    }

    @Test
    void getHistoryIncludesRedoActions() {
        ArrayList<String> log = new ArrayList<>();
        undoManager.execute(action("A", log));
        undoManager.execute(action("B", log));
        undoManager.execute(action("C", log));
        undoManager.undo(); // undo C
        undoManager.undo(); // undo B

        List<UndoableAction> history = undoManager.getHistory();

        assertThat(history).hasSize(3);
        assertThat(history.get(0).description()).isEqualTo("A");
        assertThat(history.get(1).description()).isEqualTo("B");
        assertThat(history.get(2).description()).isEqualTo("C");
    }

    @Test
    void getCurrentHistoryIndexReturnsNegativeOneWhenEmpty() {
        assertThat(undoManager.getCurrentHistoryIndex()).isEqualTo(-1);
    }

    @Test
    void getCurrentHistoryIndexReturnsLastUndoIndex() {
        ArrayList<String> log = new ArrayList<>();
        undoManager.execute(action("A", log));
        undoManager.execute(action("B", log));
        undoManager.execute(action("C", log));

        assertThat(undoManager.getCurrentHistoryIndex()).isEqualTo(2);
    }

    @Test
    void getCurrentHistoryIndexReflectsUndoState() {
        ArrayList<String> log = new ArrayList<>();
        undoManager.execute(action("A", log));
        undoManager.execute(action("B", log));
        undoManager.execute(action("C", log));

        undoManager.undo(); // undo C
        assertThat(undoManager.getCurrentHistoryIndex()).isEqualTo(1);

        undoManager.undo(); // undo B
        assertThat(undoManager.getCurrentHistoryIndex()).isEqualTo(0);

        undoManager.undo(); // undo A
        assertThat(undoManager.getCurrentHistoryIndex()).isEqualTo(-1);
    }

    @Test
    void goToHistoryIndexUndoesForwardToTarget() {
        ArrayList<String> log = new ArrayList<>();
        undoManager.execute(action("A", log));
        undoManager.execute(action("B", log));
        undoManager.execute(action("C", log));

        undoManager.goToHistoryIndex(0); // undo C and B

        assertThat(undoManager.getCurrentHistoryIndex()).isEqualTo(0);
        assertThat(undoManager.undoSize()).isEqualTo(1);
        assertThat(undoManager.redoSize()).isEqualTo(2);
        assertThat(log).contains("undo:C", "undo:B");
    }

    @Test
    void goToHistoryIndexRedoesBackToTarget() {
        ArrayList<String> log = new ArrayList<>();
        undoManager.execute(action("A", log));
        undoManager.execute(action("B", log));
        undoManager.execute(action("C", log));
        undoManager.undo();
        undoManager.undo();
        undoManager.undo();
        log.clear();

        undoManager.goToHistoryIndex(2); // redo A, B, C

        assertThat(undoManager.getCurrentHistoryIndex()).isEqualTo(2);
        assertThat(undoManager.undoSize()).isEqualTo(3);
        assertThat(undoManager.redoSize()).isZero();
        assertThat(log).containsExactly("execute:A", "execute:B", "execute:C");
    }

    @Test
    void goToHistoryIndexIsNoOpForCurrentIndex() {
        ArrayList<String> log = new ArrayList<>();
        undoManager.execute(action("A", log));
        undoManager.execute(action("B", log));
        log.clear();

        undoManager.goToHistoryIndex(1);

        assertThat(log).isEmpty();
        assertThat(undoManager.getCurrentHistoryIndex()).isEqualTo(1);
    }

    @Test
    void goToHistoryIndexRejectsOutOfRangeIndex() {
        ArrayList<String> log = new ArrayList<>();
        undoManager.execute(action("A", log));

        assertThatThrownBy(() -> undoManager.goToHistoryIndex(5))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> undoManager.goToHistoryIndex(-2))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void goToHistoryIndexNegativeOneUndoesAll() {
        ArrayList<String> log = new ArrayList<>();
        undoManager.execute(action("A", log));
        undoManager.execute(action("B", log));

        undoManager.goToHistoryIndex(-1);

        assertThat(undoManager.getCurrentHistoryIndex()).isEqualTo(-1);
        assertThat(undoManager.undoSize()).isZero();
        assertThat(undoManager.redoSize()).isEqualTo(2);
    }

    @Test
    void listenerIsNotifiedOnExecute() {
        AtomicInteger callCount = new AtomicInteger();
        undoManager.addHistoryListener(manager -> callCount.incrementAndGet());

        undoManager.execute(action("A", new ArrayList<>()));

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void listenerIsNotifiedOnUndo() {
        undoManager.execute(action("A", new ArrayList<>()));

        AtomicInteger callCount = new AtomicInteger();
        undoManager.addHistoryListener(manager -> callCount.incrementAndGet());

        undoManager.undo();

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void listenerIsNotifiedOnRedo() {
        undoManager.execute(action("A", new ArrayList<>()));
        undoManager.undo();

        AtomicInteger callCount = new AtomicInteger();
        undoManager.addHistoryListener(manager -> callCount.incrementAndGet());

        undoManager.redo();

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void listenerIsNotifiedOnClear() {
        undoManager.execute(action("A", new ArrayList<>()));

        AtomicInteger callCount = new AtomicInteger();
        undoManager.addHistoryListener(manager -> callCount.incrementAndGet());

        undoManager.clear();

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void listenerIsNotifiedOnGoToHistoryIndex() {
        undoManager.execute(action("A", new ArrayList<>()));
        undoManager.execute(action("B", new ArrayList<>()));

        AtomicInteger callCount = new AtomicInteger();
        undoManager.addHistoryListener(manager -> callCount.incrementAndGet());

        undoManager.goToHistoryIndex(0);

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void removedListenerIsNoLongerCalled() {
        AtomicInteger callCount = new AtomicInteger();
        UndoHistoryListener listener = manager -> callCount.incrementAndGet();
        undoManager.addHistoryListener(listener);

        undoManager.execute(action("A", new ArrayList<>()));
        assertThat(callCount.get()).isEqualTo(1);

        undoManager.removeHistoryListener(listener);
        undoManager.execute(action("B", new ArrayList<>()));
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void multipleListenersAreAllNotified() {
        AtomicInteger count1 = new AtomicInteger();
        AtomicInteger count2 = new AtomicInteger();
        undoManager.addHistoryListener(manager -> count1.incrementAndGet());
        undoManager.addHistoryListener(manager -> count2.incrementAndGet());

        undoManager.execute(action("A", new ArrayList<>()));

        assertThat(count1.get()).isEqualTo(1);
        assertThat(count2.get()).isEqualTo(1);
    }

    @Test
    void getHistoryReturnsUnmodifiableList() {
        undoManager.execute(action("A", new ArrayList<>()));

        List<UndoableAction> history = undoManager.getHistory();

        assertThatThrownBy(() -> history.add(action("X", new ArrayList<>())))
                .isInstanceOf(UnsupportedOperationException.class);
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
