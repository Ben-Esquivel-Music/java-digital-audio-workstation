package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that sets, replaces, or inserts a CC breakpoint
 * inside a {@link MidiCcLane}.
 *
 * <p>Semantics:</p>
 * <ul>
 *   <li>If a breakpoint already exists at {@link MidiCcEvent#column()
 *       newEvent.column()}, that event is replaced and undo restores the
 *       previous one.</li>
 *   <li>Otherwise {@code newEvent} is inserted and undo removes it.</li>
 * </ul>
 *
 * <p>Multi-note (cluster) edits are achieved by composing several
 * {@code SetCcValueAction} (or {@link SetNoteVelocityAction}) instances
 * in a single undo group — the {@code UndoManager} already supports
 * that pattern.</p>
 */
public final class SetCcValueAction implements UndoableAction {

    private final MidiCcLane lane;
    private final MidiCcEvent newEvent;
    private MidiCcEvent replaced; // captured at execute time (may be null)
    private boolean wasInsert;

    /**
     * Creates a new set-CC-value action.
     *
     * @param lane     the lane to modify
     * @param newEvent the breakpoint to add or replace (its column
     *                 determines which existing event, if any, is replaced)
     */
    public SetCcValueAction(MidiCcLane lane, MidiCcEvent newEvent) {
        this.lane = Objects.requireNonNull(lane, "lane must not be null");
        this.newEvent = Objects.requireNonNull(newEvent, "newEvent must not be null");
    }

    @Override
    public String description() {
        return "Set CC Value";
    }

    @Override
    public void execute() {
        // Find any existing event at the same column.
        replaced = null;
        wasInsert = true;
        for (int i = 0; i < lane.getEvents().size(); i++) {
            MidiCcEvent e = lane.getEvents().get(i);
            if (e.column() == newEvent.column()) {
                replaced = e;
                wasInsert = false;
                lane.replaceEvent(i, newEvent);
                return;
            }
        }
        lane.addEvent(newEvent);
    }

    @Override
    public void undo() {
        if (wasInsert) {
            lane.removeEvent(newEvent);
        } else if (replaced != null) {
            int idx = lane.indexOf(newEvent);
            if (idx >= 0) {
                lane.replaceEvent(idx, replaced);
            }
        }
    }
}
