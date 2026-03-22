package com.benesquivelmusic.daw.core.midi.javasound;

import com.benesquivelmusic.daw.sdk.midi.MidiEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaSoundRendererTest {

    @Test
    void shouldReportJavaSoundRendererName() {
        var renderer = new JavaSoundRenderer();
        assertThat(renderer.getRendererName()).isEqualTo("Java Sound");
    }

    @Test
    void shouldAlwaysBeAvailable() {
        var renderer = new JavaSoundRenderer();
        assertThat(renderer.isAvailable()).isTrue();
    }

    @Test
    void shouldThrowOnSendEventWhenNotInitialized() {
        var renderer = new JavaSoundRenderer();
        assertThatThrownBy(() -> renderer.sendEvent(MidiEvent.noteOn(0, 60, 100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void shouldThrowOnSelectPresetWhenNotInitialized() {
        var renderer = new JavaSoundRenderer();
        assertThatThrownBy(() -> renderer.selectPreset(0, 0, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void shouldThrowOnRenderWhenNotInitialized() {
        var renderer = new JavaSoundRenderer();
        assertThatThrownBy(() -> renderer.render(new float[2][512], 512))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void shouldThrowOnAllNotesOffWhenNotInitialized() {
        var renderer = new JavaSoundRenderer();
        assertThatThrownBy(() -> renderer.allNotesOff())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void shouldNotThrowOnCloseWhenNotInitialized() {
        var renderer = new JavaSoundRenderer();
        // close() should be safe even when not initialized
        renderer.close();
    }

    @Test
    void shouldRejectNegativeSampleRateOnInitialize() {
        var renderer = new JavaSoundRenderer();
        assertThatThrownBy(() -> renderer.initialize(-1, 512))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void shouldRejectNegativeBufferSizeOnInitialize() {
        var renderer = new JavaSoundRenderer();
        assertThatThrownBy(() -> renderer.initialize(44100, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bufferSize");
    }

    @Test
    void shouldRejectNegativeGainWhenInitialized() {
        // This test requires a MIDI synthesizer to be available in the environment
        // which may not be available in headless CI. The test guards against this.
        var renderer = new JavaSoundRenderer();
        try {
            renderer.initialize(44100, 512);
            assertThatThrownBy(() -> renderer.setGain(-1.0f))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("gain");
        } catch (Exception e) {
            // MIDI synthesizer unavailable in headless CI — skip
        } finally {
            renderer.close();
        }
    }

    @Test
    void shouldReturnEmptyLoadedSoundFontsBeforeInit() {
        var renderer = new JavaSoundRenderer();
        assertThat(renderer.getLoadedSoundFonts()).isEmpty();
    }
}
