package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every {@link Track} has a per-track MIDI-effect chain that
 * is initialized empty (the legacy-project migration contract), is
 * mutable, and preserves insertion order.
 */
class TrackMidiEffectChainTest {

    @Test
    void newTrackHasEmptyMidiEffectChain() {
        Track track = new Track("Lead", TrackType.MIDI);
        assertThat(track.getMidiEffectChain()).isEmpty();
    }

    @Test
    void midiEffectChainPreservesInsertionOrder() {
        Track track = new Track("Lead", TrackType.MIDI);
        ArpeggiatorPlugin a = new ArpeggiatorPlugin();
        ArpeggiatorPlugin b = new ArpeggiatorPlugin();
        track.getMidiEffectChain().add(a);
        track.getMidiEffectChain().add(b);
        assertThat(track.getMidiEffectChain()).containsExactly(a, b);
    }
}
