package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.midi.MidiCcEvent;
import com.benesquivelmusic.daw.core.midi.MidiCcLane;
import com.benesquivelmusic.daw.core.midi.MidiCcLaneType;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a {@link MidiClip} including its CC editing lanes (the
 * piano-roll lane configuration) round-trips through
 * {@link ProjectSerializer#serializeMidiClip(MidiClip)} /
 * {@link ProjectSerializer#deserializeMidiClip(String)} unchanged.
 */
class ProjectSerializerMidiClipTest {

    @Test
    void roundTripsNotesAndCcLanes() throws Exception {
        ProjectSerializer ser = new ProjectSerializer();

        MidiClip clip = new MidiClip();
        clip.addNote(MidiNoteData.of(60, 0, 4, 100));
        clip.addNote(MidiNoteData.of(64, 4, 2, 90));

        MidiCcLane vel = MidiCcLane.velocity();
        vel.setHeightRatio(0.6);
        clip.addCcLane(vel);

        MidiCcLane mod = MidiCcLane.preset(MidiCcLaneType.MOD_WHEEL, true);
        mod.addEvent(new MidiCcEvent(0, 0));
        mod.addEvent(new MidiCcEvent(8, 16383));
        mod.addEvent(new MidiCcEvent(16, 8192));
        clip.addCcLane(mod);

        MidiCcLane custom = new MidiCcLane(MidiCcLaneType.ARBITRARY_CC, 7, false, 3);
        custom.addEvent(new MidiCcEvent(2, 100));
        clip.addCcLane(custom);

        String xml = ser.serializeMidiClip(clip);
        MidiClip restored = ser.deserializeMidiClip(xml);

        assertThat(restored.getNotes()).hasSize(2);
        assertThat(restored.getNotes().get(0).noteNumber()).isEqualTo(60);
        assertThat(restored.getNotes().get(1).velocity()).isEqualTo(90);

        assertThat(restored.getCcLanes()).hasSize(3);

        MidiCcLane rVel = restored.getCcLanes().get(0);
        assertThat(rVel.getType()).isEqualTo(MidiCcLaneType.VELOCITY);
        assertThat(rVel.getHeightRatio()).isEqualTo(0.6);

        MidiCcLane rMod = restored.getCcLanes().get(1);
        assertThat(rMod.getType()).isEqualTo(MidiCcLaneType.MOD_WHEEL);
        assertThat(rMod.isHighResolution()).isTrue();
        assertThat(rMod.getEvents()).hasSize(3);
        assertThat(rMod.getEvents().get(0).value()).isEqualTo(0);
        assertThat(rMod.getEvents().get(1).value()).isEqualTo(16383);
        assertThat(rMod.getEvents().get(2).value()).isEqualTo(8192);

        MidiCcLane rCustom = restored.getCcLanes().get(2);
        assertThat(rCustom.getType()).isEqualTo(MidiCcLaneType.ARBITRARY_CC);
        assertThat(rCustom.getCcNumber()).isEqualTo(7);
        assertThat(rCustom.getChannel()).isEqualTo(3);
        assertThat(rCustom.getEvents()).hasSize(1);
    }

    @Test
    void roundTripPreservesEmptyClip() throws Exception {
        ProjectSerializer ser = new ProjectSerializer();
        MidiClip empty = new MidiClip();

        MidiClip restored = ser.deserializeMidiClip(ser.serializeMidiClip(empty));

        assertThat(restored.getNotes()).isEmpty();
        assertThat(restored.getCcLanes()).isEmpty();
        assertThat(restored.isLocked()).isFalse();
    }

    @Test
    void rejectsMalformedRoot() throws Exception {
        ProjectSerializer ser = new ProjectSerializer();
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> ser.deserializeMidiClip("<not-a-clip/>"));
    }
}
