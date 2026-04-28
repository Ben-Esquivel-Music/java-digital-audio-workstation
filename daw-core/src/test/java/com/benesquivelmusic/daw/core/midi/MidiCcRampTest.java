package com.benesquivelmusic.daw.core.midi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MidiCcRampTest {

    @Test
    void producesEvenlySpacedBreakpointsWithCorrectValues() {
        MidiCcEvent left = new MidiCcEvent(0, 0);
        MidiCcEvent right = new MidiCcEvent(8, 80);

        // Default density (1 column per step) — 7 intermediate points
        // at columns 1..7 with values 10, 20, 30, 40, 50, 60, 70.
        List<MidiCcEvent> ramp = MidiCcRamp.generate(left, right, 1);

        assertThat(ramp).hasSize(7);
        for (int i = 0; i < 7; i++) {
            int expectedColumn = i + 1;
            int expectedValue = (i + 1) * 10;
            assertThat(ramp.get(i).column()).isEqualTo(expectedColumn);
            assertThat(ramp.get(i).value()).isEqualTo(expectedValue);
        }
    }

    @Test
    void honoursStepDensity() {
        MidiCcEvent left = new MidiCcEvent(0, 0);
        MidiCcEvent right = new MidiCcEvent(16, 64);

        // step=4 → columns 4, 8, 12 with values 16, 32, 48.
        List<MidiCcEvent> ramp = MidiCcRamp.generate(left, right, 4);

        assertThat(ramp).extracting(MidiCcEvent::column).containsExactly(4, 8, 12);
        assertThat(ramp).extracting(MidiCcEvent::value).containsExactly(16, 32, 48);
    }

    @Test
    void roundsValuesToNearestInteger() {
        MidiCcEvent left = new MidiCcEvent(0, 0);
        MidiCcEvent right = new MidiCcEvent(3, 10);

        List<MidiCcEvent> ramp = MidiCcRamp.generate(left, right, 1);

        // t=1/3 → 3.33 → 3; t=2/3 → 6.66 → 7
        assertThat(ramp).extracting(MidiCcEvent::value).containsExactly(3, 7);
    }

    @Test
    void emptyWhenStepEqualsOrExceedsSpan() {
        MidiCcEvent left = new MidiCcEvent(0, 0);
        MidiCcEvent right = new MidiCcEvent(4, 100);

        assertThat(MidiCcRamp.generate(left, right, 4)).isEmpty();
        assertThat(MidiCcRamp.generate(left, right, 8)).isEmpty();
    }

    @Test
    void rejectsInvalidArguments() {
        MidiCcEvent left = new MidiCcEvent(2, 0);
        MidiCcEvent right = new MidiCcEvent(10, 100);

        assertThatThrownBy(() -> MidiCcRamp.generate(left, right, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MidiCcRamp.generate(right, left, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MidiCcRamp.generate(null, right, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
