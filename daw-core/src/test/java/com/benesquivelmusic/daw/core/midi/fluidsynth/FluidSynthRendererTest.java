package com.benesquivelmusic.daw.core.midi.fluidsynth;

import com.benesquivelmusic.daw.sdk.midi.MidiEvent;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRendererException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FluidSynthRendererTest {

    @Test
    void shouldReportFluidSynthRendererName() {
        FluidSynthBindings bindings = new FluidSynthBindings();
        FluidSynthRenderer renderer = new FluidSynthRenderer(bindings);
        assertThat(renderer.getRendererName()).isEqualTo("FluidSynth");
    }

    @Test
    void shouldReportAvailabilityFromBindings() {
        FluidSynthBindings bindings = new FluidSynthBindings();
        FluidSynthRenderer renderer = new FluidSynthRenderer(bindings);
        assertThat(renderer.isAvailable()).isEqualTo(bindings.isAvailable());
    }

    @Test
    void shouldRejectNullBindings() {
        assertThatThrownBy(() -> new FluidSynthRenderer(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("bindings");
    }

    @Test
    void shouldThrowIfInitializedWithoutNativeLibrary() {
        FluidSynthBindings bindings = new FluidSynthBindings();
        FluidSynthRenderer renderer = new FluidSynthRenderer(bindings);
        if (!bindings.isAvailable()) {
            assertThatThrownBy(() -> renderer.initialize(44100, 512))
                    .isInstanceOf(SoundFontRendererException.class)
                    .hasMessageContaining("not available");
        }
    }

    @Test
    void shouldThrowOnSendEventWhenNotInitialized() {
        FluidSynthBindings bindings = new FluidSynthBindings();
        FluidSynthRenderer renderer = new FluidSynthRenderer(bindings);
        assertThatThrownBy(() -> renderer.sendEvent(MidiEvent.noteOn(0, 60, 100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void shouldThrowOnRenderWhenNotInitialized() {
        FluidSynthBindings bindings = new FluidSynthBindings();
        FluidSynthRenderer renderer = new FluidSynthRenderer(bindings);
        assertThatThrownBy(() -> renderer.render(new float[2][512], 512))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void shouldThrowOnSelectPresetWhenNotInitialized() {
        FluidSynthBindings bindings = new FluidSynthBindings();
        FluidSynthRenderer renderer = new FluidSynthRenderer(bindings);
        assertThatThrownBy(() -> renderer.selectPreset(0, 0, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void shouldThrowOnAllNotesOffWhenNotInitialized() {
        FluidSynthBindings bindings = new FluidSynthBindings();
        FluidSynthRenderer renderer = new FluidSynthRenderer(bindings);
        assertThatThrownBy(() -> renderer.allNotesOff())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void shouldNotThrowOnCloseWhenNotInitialized() {
        FluidSynthBindings bindings = new FluidSynthBindings();
        FluidSynthRenderer renderer = new FluidSynthRenderer(bindings);
        // close() should be safe even when not initialized
        renderer.close();
    }

    @Test
    void shouldRejectNegativeSampleRate() {
        FluidSynthBindings bindings = new FluidSynthBindings();
        FluidSynthRenderer renderer = new FluidSynthRenderer(bindings);
        if (bindings.isAvailable()) {
            assertThatThrownBy(() -> renderer.initialize(-1, 512))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sampleRate");
        }
    }

    @Test
    void shouldRejectNegativeBufferSize() {
        FluidSynthBindings bindings = new FluidSynthBindings();
        FluidSynthRenderer renderer = new FluidSynthRenderer(bindings);
        if (bindings.isAvailable()) {
            assertThatThrownBy(() -> renderer.initialize(44100, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bufferSize");
        }
    }

    @Test
    void shouldReturnEmptyLoadedSoundFontsWhenNotInitialized() {
        FluidSynthBindings bindings = new FluidSynthBindings();
        FluidSynthRenderer renderer = new FluidSynthRenderer(bindings);
        // getLoadedSoundFonts should return empty list when not initialized
        assertThat(renderer.getLoadedSoundFonts()).isEmpty();
    }
}
