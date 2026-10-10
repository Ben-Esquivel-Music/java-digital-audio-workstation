package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InputMonitorPreparationTest {
    @Test
    void noneRoutesAndNewGraphTracksArePreparedBeforeTheyReachTheCallback() {
        var format = new AudioFormat(48000, 2, 16, 256);
        var engine = new AudioEngine(format);
        var registry = new InputLevelMonitorRegistry();
        var track = new Track("Initially disconnected", TrackType.AUDIO);
        track.setInputRouting(InputRouting.NONE);
        track.setArmed(true);
        engine.setInputLevelMonitorRegistry(registry);
        var transport = new Transport();
        engine.setGraph(transport, new Mixer(), List.of(track));
        var initial = registry.get(track.getId());
        assertThat(initial).isNotNull();
        engine.start();
        transport.play();
        try {
            track.setInputRouting(new InputRouting(0, 1));
            float[][] input = new float[1][256];
            Arrays.fill(input[0], 0.5f);
            engine.processBlock(input, new float[2][256], 256);
            assertThat(registry.get(track.getId())).isSameAs(initial);
            assertThat(initial.snapshot().peakDbfs()).isCloseTo(-6.0206, org.assertj.core.data.Offset.offset(0.001));
            var added = new Track("New graph track", TrackType.AUDIO);
            added.setArmed(true);
            engine.setTracks(List.of(track, added));
            assertThat(registry.get(added.getId())).isNotNull();
            var replacement = new InputLevelMonitorRegistry();
            engine.setInputLevelMonitorRegistry(replacement);
            assertThat(replacement.size()).isEqualTo(2);
            var replacementInitial = replacement.get(track.getId());
            var replacementAdded = replacement.get(added.getId());
            float[][] replacementInput = new float[2][256];
            for (float[] lane : replacementInput) Arrays.fill(lane, 0.25f);
            engine.processBlock(replacementInput, new float[2][256], 256);
            assertThat(replacement.get(track.getId())).isSameAs(replacementInitial);
            assertThat(replacement.get(added.getId())).isSameAs(replacementAdded);
            assertThat(replacementInitial.snapshot().peakDbfs()).isCloseTo(-12.0412, org.assertj.core.data.Offset.offset(0.001));
            assertThat(replacementAdded.snapshot().peakDbfs()).isCloseTo(-12.0412, org.assertj.core.data.Offset.offset(0.001));
            replacement.clear();
            engine.processBlock(input, new float[2][256], 256);
            assertThat(replacement.size()).as("the callback never recreates removed monitors").isZero();
        } finally { engine.shutdown(); }
    }
}
