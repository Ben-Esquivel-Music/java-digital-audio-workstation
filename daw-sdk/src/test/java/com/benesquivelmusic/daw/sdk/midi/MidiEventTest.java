package com.benesquivelmusic.daw.sdk.midi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MidiEventTest {

    @Test
    void shouldCreateNoteOnEvent() {
        var event = MidiEvent.noteOn(0, 60, 100);
        assertThat(event.type()).isEqualTo(MidiEvent.Type.NOTE_ON);
        assertThat(event.channel()).isZero();
        assertThat(event.data1()).isEqualTo(60);
        assertThat(event.data2()).isEqualTo(100);
    }

    @Test
    void shouldCreateNoteOffEvent() {
        var event = MidiEvent.noteOff(1, 72);
        assertThat(event.type()).isEqualTo(MidiEvent.Type.NOTE_OFF);
        assertThat(event.channel()).isEqualTo(1);
        assertThat(event.data1()).isEqualTo(72);
        assertThat(event.data2()).isZero();
    }

    @Test
    void shouldCreateControlChangeEvent() {
        var event = MidiEvent.controlChange(5, 1, 64);
        assertThat(event.type()).isEqualTo(MidiEvent.Type.CONTROL_CHANGE);
        assertThat(event.channel()).isEqualTo(5);
        assertThat(event.data1()).isEqualTo(1);
        assertThat(event.data2()).isEqualTo(64);
    }

    @Test
    void shouldCreateProgramChangeEvent() {
        var event = MidiEvent.programChange(9, 25);
        assertThat(event.type()).isEqualTo(MidiEvent.Type.PROGRAM_CHANGE);
        assertThat(event.channel()).isEqualTo(9);
        assertThat(event.data1()).isEqualTo(25);
        assertThat(event.data2()).isZero();
    }

    @Test
    void shouldCreatePitchBendEvent() {
        var event = MidiEvent.pitchBend(0, 8192);
        assertThat(event.type()).isEqualTo(MidiEvent.Type.PITCH_BEND);
        assertThat(event.channel()).isZero();
        assertThat(event.data1()).isEqualTo(8192);
    }

    @Test
    void shouldAcceptBoundaryChannelValues() {
        assertThat(MidiEvent.noteOn(0, 60, 100).channel()).isZero();
        assertThat(MidiEvent.noteOn(15, 60, 100).channel()).isEqualTo(15);
    }

    @Test
    void shouldAcceptBoundaryNoteValues() {
        assertThat(MidiEvent.noteOn(0, 0, 100).data1()).isZero();
        assertThat(MidiEvent.noteOn(0, 127, 100).data1()).isEqualTo(127);
    }

    @Test
    void shouldAcceptBoundaryVelocityValues() {
        assertThat(MidiEvent.noteOn(0, 60, 0).data2()).isZero();
        assertThat(MidiEvent.noteOn(0, 60, 127).data2()).isEqualTo(127);
    }

    @Test
    void shouldAcceptBoundaryPitchBendValues() {
        assertThat(MidiEvent.pitchBend(0, 0).data1()).isZero();
        assertThat(MidiEvent.pitchBend(0, 16383).data1()).isEqualTo(16383);
    }

    @Test
    void shouldRejectNullType() {
        assertThatThrownBy(() -> new MidiEvent(null, 0, 60, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    void shouldRejectNegativeChannel() {
        assertThatThrownBy(() -> MidiEvent.noteOn(-1, 60, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void shouldRejectChannelAboveMax() {
        assertThatThrownBy(() -> MidiEvent.noteOn(16, 60, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void shouldRejectNegativeNote() {
        assertThatThrownBy(() -> MidiEvent.noteOn(0, -1, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data1");
    }

    @Test
    void shouldRejectNoteAboveMax() {
        assertThatThrownBy(() -> MidiEvent.noteOn(0, 128, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data1");
    }

    @Test
    void shouldRejectNegativeVelocity() {
        assertThatThrownBy(() -> MidiEvent.noteOn(0, 60, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data2");
    }

    @Test
    void shouldRejectVelocityAboveMax() {
        assertThatThrownBy(() -> MidiEvent.noteOn(0, 60, 128))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data2");
    }

    @Test
    void shouldRejectNegativePitchBend() {
        assertThatThrownBy(() -> MidiEvent.pitchBend(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pitch bend");
    }

    @Test
    void shouldRejectPitchBendAboveMax() {
        assertThatThrownBy(() -> MidiEvent.pitchBend(0, 16384))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pitch bend");
    }

    @Test
    void shouldDefineConstants() {
        assertThat(MidiEvent.MAX_CHANNELS).isEqualTo(16);
        assertThat(MidiEvent.MAX_DATA_VALUE).isEqualTo(127);
        assertThat(MidiEvent.MAX_PITCH_BEND).isEqualTo(16383);
        assertThat(MidiEvent.PITCH_BEND_CENTER).isEqualTo(8192);
    }

    @ParameterizedTest
    @EnumSource(MidiEvent.Type.class)
    void shouldEnumerateAllEventTypes(MidiEvent.Type type) {
        assertThat(type).isNotNull();
    }

    @Test
    void shouldSupportRecordEquality() {
        var a = MidiEvent.noteOn(0, 60, 100);
        var b = MidiEvent.noteOn(0, 60, 100);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldSupportRecordToString() {
        var event = MidiEvent.noteOn(0, 60, 100);
        assertThat(event.toString()).contains("NOTE_ON");
    }
}
