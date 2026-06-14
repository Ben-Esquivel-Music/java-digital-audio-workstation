package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 292 — verifies {@link HistoryVM}: the read-only projection of the
 * {@link UndoManager} the undo/redo controls bind to (Control Synchronization
 * Design Book §3.3, §5.6, §6.9). The VM seeds synchronously at construction but
 * marshals every later history change through {@link FxDispatcher#onFx(Runnable)},
 * so a mutation is observed only after {@link #flushFx()} drains the FX queue;
 * property values are then read through a {@link #callOnFx(FxCallable)} capture,
 * never asserted on the FX thread.
 *
 * <p>{@link HistoryVM#dispose()} removes the manager's listener so a stale VM does
 * not leak on the abandoned (per-load) manager.</p>
 */
class HistoryVMTest {

    private static final long TIMEOUT_SECONDS = 5;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void seedsFromEmptyManager() {
        UndoManager manager = new UndoManager();
        FxDispatcher dispatcher = new FxDispatcher();
        HistoryVM vm = new HistoryVM(manager, dispatcher);
        try {
            // Seeded synchronously in the constructor — no flush needed.
            assertThat(vm.canUndo()).as("empty manager: nothing to undo").isFalse();
            assertThat(vm.canRedo()).as("empty manager: nothing to redo").isFalse();
            assertThat(vm.getUndoLabel()).as("empty manager: blank undo label").isEmpty();
            assertThat(vm.getRedoLabel()).as("empty manager: blank redo label").isEmpty();
        } finally {
            vm.dispose();
        }
    }

    @Test
    void canUndoAndLabelFollowExecute() throws InterruptedException {
        UndoManager manager = new UndoManager();
        FxDispatcher dispatcher = new FxDispatcher();
        HistoryVM vm = new HistoryVM(manager, dispatcher);
        try {
            runOnFx(() -> manager.execute(new FakeAction("Add Track")));
            flushFx(); // drain the dispatcher.onFx the history listener posted

            HistorySnapshot snapshot = snapshotOf(vm);
            assertThat(snapshot.canUndo())
                    .as("an executed action becomes undoable").isTrue();
            assertThat(snapshot.undoLabel())
                    .as("the undo label follows the action description")
                    .isEqualTo("Add Track");
        } finally {
            vm.dispose();
        }
    }

    @Test
    void redoFollowsUndo() throws InterruptedException {
        UndoManager manager = new UndoManager();
        FxDispatcher dispatcher = new FxDispatcher();
        HistoryVM vm = new HistoryVM(manager, dispatcher);
        try {
            runOnFx(() -> manager.execute(new FakeAction("Add Track")));
            flushFx();
            runOnFx(manager::undo);
            flushFx();

            HistorySnapshot snapshot = snapshotOf(vm);
            assertThat(snapshot.canUndo())
                    .as("after undoing the only action, nothing is left to undo").isFalse();
            assertThat(snapshot.canRedo())
                    .as("the undone action becomes redoable").isTrue();
            assertThat(snapshot.redoLabel())
                    .as("the redo label follows the undone action's description")
                    .isEqualTo("Add Track");
        } finally {
            vm.dispose();
        }
    }

    @Test
    void disposeStopsUpdates() throws InterruptedException {
        UndoManager manager = new UndoManager();
        FxDispatcher dispatcher = new FxDispatcher();
        HistoryVM vm = new HistoryVM(manager, dispatcher);

        // Seeded empty, then disposed BEFORE any mutation — the listener is gone.
        vm.dispose();

        runOnFx(() -> manager.execute(new FakeAction("Add Track")));
        flushFx();

        assertThat(snapshotOf(vm).canUndo())
                .as("after dispose() the VM no longer tracks the manager")
                .isFalse();
    }

    @Test
    void canUndoPropertyIsReadOnly() {
        UndoManager manager = new UndoManager();
        FxDispatcher dispatcher = new FxDispatcher();
        HistoryVM vm = new HistoryVM(manager, dispatcher);
        try {
            assertThat(vm.canUndoProperty()).isInstanceOf(ReadOnlyBooleanProperty.class);
        } finally {
            vm.dispose();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Reads the four projected facts off the FX thread into an off-thread holder. */
    private static HistorySnapshot snapshotOf(HistoryVM vm) throws InterruptedException {
        return callOnFx(() -> new HistorySnapshot(
                vm.canUndo(), vm.canRedo(), vm.getUndoLabel(), vm.getRedoLabel()));
    }

    private record HistorySnapshot(boolean canUndo, boolean canRedo,
                                   String undoLabel, String redoLabel) {
    }

    /** A minimal undoable action whose {@link #description()} drives the VM labels. */
    private static final class FakeAction implements UndoableAction {
        private final String description;

        FakeAction(String description) {
            this.description = description;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public void execute() {
            // no model mutation needed for a projection test
        }

        @Override
        public void undo() {
            // no model mutation needed for a projection test
        }
    }

    // ── FX-thread helpers ──────────────────────────────────────────────────────

    /** Posts a barrier onto the FX thread and blocks until it (and every earlier {@code onFx}) has run. */
    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("FX queue must flush within %ds", TIMEOUT_SECONDS).isTrue();
    }

    private static void runOnFx(Runnable work) throws InterruptedException {
        callOnFx(() -> {
            work.run();
            return null;
        });
    }

    private static <T> T callOnFx(FxCallable<T> work) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        Throwable t = thrown.get();
        if (t instanceof RuntimeException re) {
            throw re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        if (t != null) {
            throw new AssertionError("FX work threw a checked exception", t);
        }
        return result.get();
    }

    @FunctionalInterface
    private interface FxCallable<T> {
        T call();
    }
}
