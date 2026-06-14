package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.event.UiEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * The production {@link HistoryIntentHandler}: it wraps the {@link UndoManager}
 * mutation path and runs the universal cascade for each history intent (Control
 * Synchronization Design Book §5.1, §5.6; story 292).
 *
 * <p>For every intent the handler runs, in order:</p>
 * <ol>
 *   <li><strong>VALIDATE</strong> — proceed only when an undo/redo is actually
 *       available, so an intent with nothing to do neither mutates nor
 *       announces.</li>
 *   <li><strong>MUTATE</strong> — call {@link UndoManager#undo()} /
 *       {@link UndoManager#redo()}. That fires the manager's history listener, so
 *       {@code HistoryVM} re-reads and republishes its properties (the toolbar
 *       buttons and Edit menu update as bound subscribers; this handler touches
 *       no {@code Property}).</li>
 *   <li><strong>ANNOUNCE</strong> — publish {@link UiEvent.UndoStateChanged} on
 *       the bus via {@link EventBusPublisher} so unrelated surfaces (e.g. the
 *       menu-enablement policy) react. This is "the command-history mutation
 *       publishes {@code UndoStateChanged}" (§6.9); {@code HistoryVM} itself does
 *       not publish (it is a pure projection, mirroring stories 290/291).</li>
 * </ol>
 *
 * <p>The application installs a fresh {@link UndoManager} per project load, so the
 * full-load cascade (§5.7) builds a new handler bound to the new manager.</p>
 */
public final class CoreHistoryIntentHandler implements HistoryIntentHandler {

    private final UndoManager undoManager;

    /**
     * Creates a handler bound to {@code undoManager}.
     *
     * @param undoManager the authoritative undo manager to mutate; must not be {@code null}
     * @throws NullPointerException if {@code undoManager} is {@code null}
     */
    public CoreHistoryIntentHandler(UndoManager undoManager) {
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager must not be null");
    }

    @Override
    public void undo() {
        if (!undoManager.canUndo()) {
            return; // VALIDATE: nothing to undo — no-op
        }
        undoManager.undo();
        EventBusPublisher.publish(new UiEvent.UndoStateChanged(Instant.now()));
    }

    @Override
    public void redo() {
        if (!undoManager.canRedo()) {
            return; // VALIDATE: nothing to redo — no-op
        }
        undoManager.redo();
        EventBusPublisher.publish(new UiEvent.UndoStateChanged(Instant.now()));
    }
}
