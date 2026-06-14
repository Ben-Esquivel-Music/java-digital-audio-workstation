package com.benesquivelmusic.daw.sdk.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Sealed family of <strong>UI-only discrete facts</strong> — the sibling of
 * {@link DawEvent} for changes that several unrelated <em>surfaces</em> care
 * about but that never need to reach the engine (Control Synchronization Design
 * Book §4.2, §7, Appendix A; story 292).
 *
 * <p>Selection and undo-state are owned by the UI (the {@code SelectionVM} and
 * {@code HistoryVM} view-models in {@code daw-app}), not by {@code daw-core}, so
 * modelling them as {@code DawEvent}s would wrongly couple the engine domain to
 * a UI concern. Instead they ride the <em>same</em> typed {@link EventBus} as a
 * separate sealed family under the shared {@link BusEvent} super-type — reusing
 * the bus, its per-subscription {@link DispatchMode} marshalling, and the
 * {@code EventBusPublisher} seam rather than a second bus (story 292).</p>
 *
 * <h2>Notification, not a state-bearing delta</h2>
 *
 * <p>Like {@link DawEvent}, a {@code UiEvent} carries only the minimal fact that
 * <em>something</em> changed plus a wall-clock {@link #timestamp()}; subscribers
 * re-read the post-change state from the relevant view-model (the menu bar
 * recomputes enablement from {@code SelectionVM}/{@code HistoryVM} via
 * {@code MenuEnablementPolicy}; the toolbar re-reads {@code HistoryVM}). This is
 * why a bare {@code SelectionChanged} (with no "what is now selected" payload) is
 * sufficient — the canonical state lives in the VM, not in the event.</p>
 *
 * <h2>Exhaustiveness</h2>
 *
 * <p>The {@code permits} clause is explicit and exhaustive, so an exhaustive
 * {@code switch} over {@code UiEvent} compiles without a {@code default} and
 * adding a variant forces every consumer to be updated at compile time
 * (story 202's sealing guarantee, mirrored here).</p>
 *
 * @see DawEvent
 * @see BusEvent
 */
public sealed interface UiEvent extends BusEvent
        permits UiEvent.SelectionChanged,
                UiEvent.UndoStateChanged {

    /** Returns the wall-clock instant at which this event was produced. */
    Instant timestamp();

    /**
     * Emitted when the UI selection changes — a different track, clip, or device
     * becomes selected, the selection is cleared, or the edit tool changes
     * (Control Synchronization Design Book §5.5). Subscribers (the inspector,
     * the menu-enablement policy, the canvas highlight, the mixer focus) re-read
     * the current selection from {@code SelectionVM}; the event itself is a bare
     * notification.
     *
     * @param timestamp wall-clock instant of the event
     */
    record SelectionChanged(Instant timestamp) implements UiEvent {
        public SelectionChanged {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when the command-history (undo/redo) state changes — an action was
     * executed, undone, or redone (Control Synchronization Design Book §5.6,
     * §6.9). Subscribers (the Edit menu, the undo/redo toolbar buttons, the
     * history panel) re-read {@code HistoryVM}'s {@code canUndo}/{@code canRedo}
     * and the next undo/redo labels.
     *
     * @param timestamp wall-clock instant of the event
     */
    record UndoStateChanged(Instant timestamp) implements UiEvent {
        public UndoStateChanged {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }
}
