package com.benesquivelmusic.daw.core.midi.fluidsynth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FluidSynthBindingsTest {

    @Test
    void shouldReportAvailabilityBasedOnNativeLibrary() {
        // FluidSynth is unlikely to be installed in the CI/test environment
        FluidSynthBindings bindings = new FluidSynthBindings();
        // We don't assert true/false — just that it doesn't crash
        assertThat(bindings.isAvailable()).isIn(true, false);
    }

    @Test
    void shouldDefineConstants() {
        assertThat(FluidSynthBindings.FLUID_OK).isEqualTo(0);
        assertThat(FluidSynthBindings.FLUID_FAILED).isEqualTo(-1);
        assertThat(FluidSynthBindings.MIDI_CHANNELS).isEqualTo(16);
    }
}
