package com.benesquivelmusic.daw.core.undo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompoundUndoableActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        UndoableAction a = simple("a");
        CompoundUndoableAction compound = new CompoundUndoableAction("Group Op", List.of(a));
        assertThat(compound.description()).isEqualTo("Group Op");
    }

    @Test
    void shouldExecuteAllChildActionsInOrder() {
        List<String> log = new ArrayList<>();
        UndoableAction a = new UndoableAction() {
            @Override public String description() { return "a"; }
            @Override public void execute() { log.add("exec-a"); }
            @Override public void undo() { log.add("undo-a"); }
        };
        UndoableAction b = new UndoableAction() {
            @Override public String description() { return "b"; }
            @Override public void execute() { log.add("exec-b"); }
            @Override public void undo() { log.add("undo-b"); }
        };

        CompoundUndoableAction compound = new CompoundUndoableAction("Group", List.of(a, b));
        compound.execute();

        assertThat(log).containsExactly("exec-a", "exec-b");
    }

    @Test
    void shouldUndoAllChildActionsInReverseOrder() {
        List<String> log = new ArrayList<>();
        UndoableAction a = new UndoableAction() {
            @Override public String description() { return "a"; }
            @Override public void execute() { log.add("exec-a"); }
            @Override public void undo() { log.add("undo-a"); }
        };
        UndoableAction b = new UndoableAction() {
            @Override public String description() { return "b"; }
            @Override public void execute() { log.add("exec-b"); }
            @Override public void undo() { log.add("undo-b"); }
        };

        CompoundUndoableAction compound = new CompoundUndoableAction("Group", List.of(a, b));
        compound.execute();
        log.clear();
        compound.undo();

        assertThat(log).containsExactly("undo-b", "undo-a");
    }

    @Test
    void shouldWorkWithUndoManager() {
        AtomicInteger counter = new AtomicInteger(0);
        UndoableAction inc = new UndoableAction() {
            @Override public String description() { return "inc"; }
            @Override public void execute() { counter.incrementAndGet(); }
            @Override public void undo() { counter.decrementAndGet(); }
        };
        UndoableAction incAgain = new UndoableAction() {
            @Override public String description() { return "inc2"; }
            @Override public void execute() { counter.incrementAndGet(); }
            @Override public void undo() { counter.decrementAndGet(); }
        };

        UndoManager undoManager = new UndoManager();
        undoManager.execute(new CompoundUndoableAction("Group Inc", List.of(inc, incAgain)));

        assertThat(counter.get()).isEqualTo(2);

        undoManager.undo();
        assertThat(counter.get()).isEqualTo(0);

        undoManager.redo();
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void shouldRejectNullDescription() {
        assertThatThrownBy(() -> new CompoundUndoableAction(null, List.of(simple("a"))))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullActions() {
        assertThatThrownBy(() -> new CompoundUndoableAction("Group", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyActions() {
        assertThatThrownBy(() -> new CompoundUndoableAction("Group", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static UndoableAction simple(String name) {
        return new UndoableAction() {
            @Override public String description() { return name; }
            @Override public void execute() { }
            @Override public void undo() { }
        };
    }
}
