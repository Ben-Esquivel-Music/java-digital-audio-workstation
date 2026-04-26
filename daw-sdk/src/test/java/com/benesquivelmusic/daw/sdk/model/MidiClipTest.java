package com.benesquivelmusic.daw.sdk.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MidiClipTest {

    @Test
    void of_createsEmptyClip() {
        MidiClip c = MidiClip.of("pad", 0.0, 4.0);
        assertThat(c.notes()).isEmpty();
        assertThat(c.endBeat()).isEqualTo(4.0);
    }

    @Test
    void notesList_isImmutable() {
        MidiClip c = MidiClip.of("pad", 0.0, 4.0)
                .withNotes(List.of(new MidiNote(0.0, 1.0, 60, 100)));
        assertThatThrownBy(() -> c.notes().add(new MidiNote(1.0, 1.0, 64, 100)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void noteValidation() {
        assertThatThrownBy(() -> new MidiNote(0.0, 1.0, 200, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MidiNote(0.0, 1.0, 60, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MidiNote(0.0, 0.0, 60, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void structuralEquality() {
        MidiNote n = new MidiNote(0.0, 1.0, 60, 100);
        MidiClip a = MidiClip.of("p", 0.0, 1.0).withNotes(List.of(n));
        MidiClip b = a.withName("p"); // identical
        assertThat(a).isEqualTo(b);
    }
}
