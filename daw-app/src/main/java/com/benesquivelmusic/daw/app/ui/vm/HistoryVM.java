package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.undo.UndoHistoryListener;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.util.Objects;

/**
 * The observable view-model mirror of the {@link UndoManager} — Stage 4 of the
 * §8 migration path (Control Synchronization Design Book §3.3, §5.6, §6.9;
 * story 292). {@code HistoryVM} projects the four facts the undo/redo UI binds
 * to: {@link #canUndoProperty() canUndo}, {@link #canRedoProperty() canRedo},
 * and the next {@link #undoLabelProperty() undo} / {@link #redoLabelProperty()
 * redo} labels. It replaces the hand-pushed
 * {@code MainController.updateUndoRedoState()} (§1.2): the Edit menu and the
 * toolbar buttons bind these properties instead of being poked from nine call
 * sites.
 *
 * <p>It is the <strong>single writer</strong> of its properties (§2.4): the
 * wrappers are private and only the exposed {@code ReadOnly*Property} views are
 * handed to controls.</p>
 *
 * <h2>The seam it observes</h2>
 *
 * <p>{@link UndoManager} predates the {@code Consumer<ChangeKind>} seam used by
 * {@code Track}/{@code Transport}/{@code DawProject}; it exposes the older
 * {@link UndoHistoryListener} (a {@code void historyChanged(UndoManager)} callback
 * removed via {@link UndoManager#removeHistoryListener(UndoHistoryListener)}).
 * {@code HistoryVM} registers one and, on every history change (execute / undo /
 * redo / clear), re-reads {@code canUndo}/{@code canRedo}/{@code undoDescription}/
 * {@code redoDescription} and republishes — the same projection discipline as the
 * other VMs. It does <em>not</em> publish the {@code UiEvent.UndoStateChanged}
 * bus event; that is the command handler's ANNOUNCE step
 * ({@code CoreHistoryIntentHandler}), mirroring stories 290/291 where the VM is a
 * pure projection and the handler announces.</p>
 *
 * <h2>Per-load lifetime</h2>
 *
 * <p>The application installs a fresh {@link UndoManager} on every project load,
 * so a {@code HistoryVM} is bound to a single manager captured at construction
 * (the "capture the swappable reference once" discipline) and is disposed +
 * rebuilt by the full-load cascade (§5.7). {@link #dispose()} removes the
 * listener so a stale {@code HistoryVM} does not leak on the abandoned manager.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>A history change may originate off the FX thread; every property write is
 * marshalled onto the FX thread through {@link FxDispatcher#onFx(Runnable)}
 * (§4.5). The initial seed in the constructor runs on the constructing thread
 * (no control is bound yet).</p>
 */
public final class HistoryVM {

    private final UndoManager undoManager;
    private final FxDispatcher dispatcher;

    private final ReadOnlyBooleanWrapper canUndo =
            new ReadOnlyBooleanWrapper(this, "canUndo");
    private final ReadOnlyBooleanWrapper canRedo =
            new ReadOnlyBooleanWrapper(this, "canRedo");
    private final ReadOnlyStringWrapper undoLabel =
            new ReadOnlyStringWrapper(this, "undoLabel");
    private final ReadOnlyStringWrapper redoLabel =
            new ReadOnlyStringWrapper(this, "redoLabel");

    /** Held so {@link #dispose()} can unregister it from the manager. */
    private final UndoHistoryListener listener;

    private boolean disposed;

    /**
     * Creates a view-model bound to {@code undoManager}, marshalling all property
     * writes through {@code dispatcher}.
     *
     * @param undoManager the authoritative undo manager to mirror; must not be {@code null}
     * @param dispatcher  the marshalling seam (story 289); must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public HistoryVM(UndoManager undoManager, FxDispatcher dispatcher) {
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");

        // Seed directly (nothing is bound yet) so a control binding shows the
        // correct state before the first history change arrives.
        republish();

        this.listener = _ -> dispatcher.onFx(this::republish);
        undoManager.addHistoryListener(listener);
    }

    /** Re-reads every fact from the manager and writes the properties (sole writer). */
    private void republish() {
        canUndo.set(undoManager.canUndo());
        canRedo.set(undoManager.canRedo());
        undoLabel.set(undoManager.undoDescription());
        redoLabel.set(undoManager.redoDescription());
    }

    // ── Read-only property views (the VM is the sole writer) ──────────────────

    /** Whether an undo is available. Read-only; bound by the undo button's {@code disable} and the Edit menu. */
    public ReadOnlyBooleanProperty canUndoProperty() {
        return canUndo.getReadOnlyProperty();
    }

    /** Returns whether an undo is available. */
    public boolean canUndo() {
        return canUndo.get();
    }

    /** Whether a redo is available. Read-only; bound by the redo button's {@code disable} and the Edit menu. */
    public ReadOnlyBooleanProperty canRedoProperty() {
        return canRedo.getReadOnlyProperty();
    }

    /** Returns whether a redo is available. */
    public boolean canRedo() {
        return canRedo.get();
    }

    /** The description of the next undo (empty when none). Read-only; bound by the menu label / tooltip. */
    public ReadOnlyStringProperty undoLabelProperty() {
        return undoLabel.getReadOnlyProperty();
    }

    /** Returns the description of the next undo, or {@code ""} when none. */
    public String getUndoLabel() {
        return undoLabel.get();
    }

    /** The description of the next redo (empty when none). Read-only; bound by the menu label / tooltip. */
    public ReadOnlyStringProperty redoLabelProperty() {
        return redoLabel.getReadOnlyProperty();
    }

    /** Returns the description of the next redo, or {@code ""} when none. */
    public String getRedoLabel() {
        return redoLabel.get();
    }

    /**
     * Removes the history listener so nothing leaks on the (soon-to-be-abandoned)
     * undo manager. Idempotent — a second call is a no-op.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        undoManager.removeHistoryListener(listener);
    }
}
