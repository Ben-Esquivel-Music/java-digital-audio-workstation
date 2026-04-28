package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SetCcValueActionTest {

    private MidiCcLane lane;
    private UndoManager undo;

    @BeforeEach
    void setUp() {
        lane = MidiCcLane.preset(MidiCcLaneType.MOD_WHEEL, false);
        undo = new UndoManager();
    }

    @Test
    void shouldHaveDescriptiveName() {
        assertThat(new SetCcValueAction(lane, new MidiCcEvent(0, 64))
                .description()).isEqualTo("Set CC Value");
    }

    @Test
    void insertsBreakpointWhenColumnIsNew() {
        undo.execute(new SetCcValueAction(lane, new MidiCcEvent(4, 80)));
        assertThat(lane.getEvents()).hasSize(1);
        assertThat(lane.getEvents().get(0).value()).isEqualTo(80);
    }

    @Test
    void replacesBreakpointWhenColumnAlreadyExists() {
        lane.addEvent(new MidiCcEvent(4, 30));
        undo.execute(new SetCcValueAction(lane, new MidiCcEvent(4, 90)));
        assertThat(lane.getEvents()).hasSize(1);
        assertThat(lane.getEvents().get(0).value()).isEqualTo(90);
    }

    @Test
    void undoRemovesInsertedBreakpoint() {
        undo.execute(new SetCcValueAction(lane, new MidiCcEvent(4, 80)));
        undo.undo();
        assertThat(lane.getEvents()).isEmpty();
    }

    @Test
    void undoRestoresReplacedBreakpoint() {
        lane.addEvent(new MidiCcEvent(4, 30));
        undo.execute(new SetCcValueAction(lane, new MidiCcEvent(4, 90)));
        undo.undo();
        assertThat(lane.getEvents()).hasSize(1);
        assertThat(lane.getEvents().get(0).value()).isEqualTo(30);
    }

    @Test
    void redoReappliesChange() {
        lane.addEvent(new MidiCcEvent(4, 30));
        undo.execute(new SetCcValueAction(lane, new MidiCcEvent(4, 90)));
        undo.undo();
        undo.redo();
        assertThat(lane.getEvents().get(0).value()).isEqualTo(90);
    }
}
