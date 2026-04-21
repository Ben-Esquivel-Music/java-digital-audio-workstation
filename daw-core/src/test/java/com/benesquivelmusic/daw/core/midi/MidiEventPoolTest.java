package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.midi.MidiEventPool.MutableMidiEvent;
import com.benesquivelmusic.daw.sdk.midi.MidiEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MidiEventPoolTest {

    @Test
    void shouldPreAllocateAndReuseHolders() {
        MidiEventPool pool = new MidiEventPool(2);
        assertThat(pool.capacity()).isEqualTo(2);
        assertThat(pool.available()).isEqualTo(2);

        MutableMidiEvent a = pool.acquire();
        MutableMidiEvent b = pool.acquire();
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(pool.available()).isZero();
        assertThat(pool.acquire()).isNull(); // exhausted

        assertThat(pool.release(a)).isTrue();
        assertThat(pool.available()).isEqualTo(1);

        MutableMidiEvent reacquired = pool.acquire();
        assertThat(reacquired).isSameAs(a);
    }

    @Test
    void shouldFillHolderInPlaceAndConvertToImmutable() {
        MidiEventPool pool = new MidiEventPool(1);
        MutableMidiEvent ev = pool.acquire();
        ev.set(MidiEvent.Type.NOTE_ON, 3, 60, 100);

        assertThat(ev.type()).isEqualTo(MidiEvent.Type.NOTE_ON);
        assertThat(ev.channel()).isEqualTo(3);
        assertThat(ev.data1()).isEqualTo(60);
        assertThat(ev.data2()).isEqualTo(100);

        MidiEvent snap = ev.toImmutable();
        assertThat(snap).isEqualTo(MidiEvent.noteOn(3, 60, 100));
    }

    @Test
    void shouldReturnFalseWhenReleasingToFullPool() {
        MidiEventPool pool = new MidiEventPool(1);
        assertThat(pool.release(new MutableMidiEvent())).isFalse();
    }

    @Test
    void shouldRejectNullRelease() {
        MidiEventPool pool = new MidiEventPool(1);
        assertThatThrownBy(() -> pool.release(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidPoolSize() {
        assertThatThrownBy(() -> new MidiEventPool(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
