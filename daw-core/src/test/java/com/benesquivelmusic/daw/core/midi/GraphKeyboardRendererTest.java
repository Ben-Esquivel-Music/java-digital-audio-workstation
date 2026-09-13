package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.sdk.midi.MidiEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphKeyboardRendererTest {
    @Test
    void programSelectionChangesTheGraphWaveform() {
        var piano = renderer();
        var organ = renderer();
        piano.selectPreset(0, 0, 0);
        organ.selectPreset(0, 0, 19);
        piano.sendEvent(MidiEvent.noteOn(0, 69, 100));
        organ.sendEvent(MidiEvent.noteOn(0, 69, 100));
        float[][] first = new float[2][256];
        float[][] second = new float[2][256];
        piano.render(first, 256);
        organ.render(second, 256);
        assertThat(first[0]).isNotEqualTo(second[0]);
        assertThat(peak(first)).isGreaterThan(0.01f);
        assertThat(peak(second)).isGreaterThan(0.01f);
    }

    @Test
    void sustainHoldsReleasedNotesAndPanicClearsEveryVoice() {
        var renderer = renderer();
        renderer.sendEvent(MidiEvent.noteOn(0, 60, 100));
        renderer.render(new float[2][256], 256);
        renderer.sendEvent(MidiEvent.controlChange(0, 64, 127));
        renderer.sendEvent(MidiEvent.noteOff(0, 60));
        var held = new float[2][256];
        renderer.render(held, 256);
        assertThat(peak(held)).isGreaterThan(0.01f);
        renderer.allNotesOff();
        var silenced = new float[2][256];
        renderer.render(silenced, 256);
        assertThat(peak(silenced)).isZero();
    }

    @Test
    void allNotesOffDoesNotDropANewNotePublishedBeforeTheNextBlock() {
        var renderer = renderer();
        renderer.sendEvent(MidiEvent.noteOn(0, 60, 100));
        renderer.allNotesOff();
        renderer.sendEvent(MidiEvent.noteOn(0, 72, 100));
        var output = new float[2][256];
        renderer.render(output, 256);
        assertThat(peak(output)).isGreaterThan(0.01f);
    }

    @Test
    void noteOffReleasesWithoutLeavingAStuckVoice() {
        var renderer = renderer();
        renderer.sendEvent(MidiEvent.noteOn(0, 69, 100));
        renderer.render(new float[2][256], 256);
        renderer.sendEvent(MidiEvent.noteOff(0, 69));
        var tail = new float[2][48_000];
        renderer.render(tail, 48_000);
        assertThat(Math.abs(tail[0][47_999])).isLessThan(0.00001f);
    }

    private static GraphKeyboardRenderer renderer() {
        var renderer = new GraphKeyboardRenderer();
        renderer.initialize(48_000, 256);
        return renderer;
    }
    private static float peak(float[][] output) {
        float result = 0;
        for (float value : output[0]) { result = Math.max(result, Math.abs(value)); }
        return result;
    }
}
