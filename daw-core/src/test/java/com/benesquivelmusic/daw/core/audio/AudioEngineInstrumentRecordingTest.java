package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioEngineInstrumentRecordingTest {
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, 64);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void captureKeepsRenderedTrackIdentityWhenGraphIsReorderedOrReplaced(boolean replace) {
        var engine = new AudioEngine(FORMAT);
        var transport = new Transport();
        var firstTrack = new Track("First", TrackType.AUDIO);
        var secondTrack = new Track("Second", TrackType.AUDIO);
        var replacementTrack = new Track("Replacement", TrackType.AUDIO);
        var mixer = new Mixer();
        mixer.addChannel(instrumentChannel("First", 0.25f));
        mixer.addChannel(instrumentChannel("Second", -0.5f));
        var replacementMixer = new Mixer();
        replacementMixer.addChannel(instrumentChannel("Replacement", 0.75f));
        engine.setGraph(transport, mixer, List.of(firstTrack, secondTrack));
        engine.start();
        transport.record();
        try {
            assertThat(engine.graphInstrumentRecordingBuffer(firstTrack)).isNull();
            engine.setRecordingCallback((input, frames) -> {
                if (replace) {
                    engine.setGraph(transport, replacementMixer, List.of(replacementTrack));
                } else {
                    engine.setTracks(List.of(secondTrack, firstTrack));
                }
                assertThat(engine.graphInstrumentRecordingBuffer(firstTrack)[0]).containsOnly(0.25f);
                assertThat(engine.graphInstrumentRecordingBuffer(secondTrack)[0]).containsOnly(-0.5f);
                assertThat(engine.graphInstrumentRecordingBuffer(replacementTrack)).isNull();
                assertThat(engine.graphInstrumentRecordingBuffer(null)).isNull();
            });
            engine.processBlock(null, new float[2][64], 64);
            assertThat(engine.graphInstrumentRecordingBuffer(firstTrack)).isNull();
            assertThat(engine.graphInstrumentRecordingBuffer(secondTrack)).isNull();
        } finally {
            engine.stop();
        }
    }

    @Test
    void failedCaptureDoesNotExposeStaleBuffersOutsideTheCallback() {
        var engine = new AudioEngine(FORMAT);
        var transport = new Transport();
        var track = new Track("Instrument", TrackType.AUDIO);
        var mixer = new Mixer();
        mixer.addChannel(instrumentChannel("Instrument", 0.25f));
        engine.setGraph(transport, mixer, List.of(track));
        engine.start();
        try {
            engine.setRecordingCallback((input, frames) -> {
                assertThat(engine.graphInstrumentRecordingBuffer(track)[0]).containsOnly(0.25f);
                throw new IllegalStateException("capture failed");
            });
            assertThatThrownBy(() -> engine.processBlock(null, new float[2][64], 64))
                    .isInstanceOf(IllegalStateException.class).hasMessage("capture failed");
            assertThat(engine.graphInstrumentRecordingBuffer(track)).isNull();
            engine.setRecordingCallback(null);
            engine.processBlock(null, new float[2][64], 64);
            assertThat(engine.graphInstrumentRecordingBuffer(track)).isNull();
        } finally {
            engine.stop();
        }
    }

    private static MixerChannel instrumentChannel(String name, float value) {
        var channel = new MixerChannel(name);
        channel.addInsert(new InsertSlot(name, new ConstantInstrument(value)));
        return channel;
    }

    private record ConstantInstrument(float value) implements AudioProcessor, DawPlugin {
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (float[] channel : output) {
                Arrays.fill(channel, 0, frames, value);
            }
        }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
        @Override public void reset() { }
        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("test.instrument", "Test Instrument", "1", "Test", PluginType.INSTRUMENT);
        }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
    }
}
