package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class MixerPlaybackPreparationTest {

    @ParameterizedTest
    @MethodSource("channelPathsAndPrecisions")
    void hotAddedChannelCanProcessInsertsAddedDuringPlayback(ChannelPath path, MixPrecision precision) {
        var mixer = new Mixer();
        mixer.setMixPrecision(precision);
        mixer.prepareForPlayback(2, 4);

        MixerChannel channel = addChannel(mixer, path);
        addInserts(channel);

        assertStereoMix(mixer, channel);
    }

    @ParameterizedTest
    @MethodSource("channelPathsAndPrecisions")
    void hotAddedChannelUsesMostRecentlyPreparedDimensions(ChannelPath path, MixPrecision precision) {
        var mixer = new Mixer();
        mixer.setMixPrecision(precision);
        mixer.prepareForPlayback(1, 2);
        mixer.prepareForPlayback(2, 4);

        MixerChannel channel = addChannel(mixer, path);
        addInserts(channel);

        assertStereoMix(mixer, channel);
    }

    @ParameterizedTest
    @MethodSource("channelPathsAndPrecisions")
    void channelAddedBeforePlaybackIsPreparedAtStartup(ChannelPath path, MixPrecision precision) {
        var mixer = new Mixer();
        mixer.setMixPrecision(precision);
        MixerChannel channel = addChannel(mixer, path);
        addInserts(channel);

        mixer.prepareForPlayback(2, 4);

        assertStereoMix(mixer, channel);
    }

    @ParameterizedTest
    @MethodSource("existingChannelPathsAndPrecisions")
    void populatedChannelIsPreparedWhenAdded(ChannelPath path, MixPrecision precision) {
        var mixer = new Mixer();
        mixer.setMixPrecision(precision);
        mixer.prepareForPlayback(2, 4);
        var channel = new MixerChannel("Populated channel");
        addInserts(channel);

        addExistingChannel(mixer, channel, path);

        assertStereoMix(mixer, channel);
    }

    @ParameterizedTest
    @MethodSource("existingChannelPathsAndPrecisions")
    void restoredChannelUsesCurrentPlaybackDimensions(ChannelPath path, MixPrecision precision) {
        var mixer = new Mixer();
        mixer.setMixPrecision(precision);
        mixer.prepareForPlayback(1, 2);
        MixerChannel channel = addChannel(mixer, path);
        addInserts(channel);
        if (path == ChannelPath.TRACK) {
            mixer.removeChannel(channel);
        } else {
            mixer.removeReturnBus(channel);
        }
        mixer.prepareForPlayback(2, 4);

        addExistingChannel(mixer, channel, path);

        assertStereoMix(mixer, channel);
    }

    @ParameterizedTest
    @CsvSource({"0, 4", "-1, 4", "1, 0", "1, -1"})
    void invalidPreparationDoesNotReplacePlaybackDimensions(int audioChannels, int blockSize) {
        var mixer = new Mixer();
        mixer.prepareForPlayback(2, 4);

        assertThatThrownBy(() -> mixer.prepareForPlayback(audioChannels, blockSize))
                .isInstanceOf(IllegalArgumentException.class);

        MixerChannel channel = mixer.addReturnBus("Return after rejected format");
        addInserts(channel);
        assertStereoMix(mixer, channel);
    }

    private static Stream<Arguments> channelPathsAndPrecisions() {
        return Arrays.stream(ChannelPath.values())
                .flatMap(path -> Arrays.stream(MixPrecision.values())
                        .map(precision -> Arguments.of(path, precision)));
    }

    private static Stream<Arguments> existingChannelPathsAndPrecisions() {
        return channelPathsAndPrecisions()
                .filter(arguments -> arguments.get()[0] != ChannelPath.NAMED_RETURN);
    }

    private static MixerChannel addChannel(Mixer mixer, ChannelPath path) {
        if (path == ChannelPath.NAMED_RETURN) {
            return mixer.addReturnBus("New return");
        }
        var channel = new MixerChannel("New channel");
        addExistingChannel(mixer, channel, path);
        return channel;
    }

    private static void addExistingChannel(Mixer mixer, MixerChannel channel, ChannelPath path) {
        if (path == ChannelPath.TRACK) {
            mixer.addChannel(channel);
        } else {
            mixer.addReturnBus(channel);
        }
    }

    private static void addInserts(MixerChannel channel) {
        channel.addInsert(new InsertSlot("First gain", new GainProcessor(0.5f)));
        channel.addInsert(new InsertSlot("Second gain", new GainProcessor(0.5f)));
    }

    private static void assertStereoMix(Mixer mixer, MixerChannel channel) {
        boolean isTrack = mixer.getChannels().contains(channel);
        if (!isTrack) {
            var source = new MixerChannel("Send source");
            source.setVolume(0.0);
            source.addSend(new Send(channel, 1.0, SendTap.PRE_FADER));
            mixer.addChannel(source);
        }
        float[][][] input = {{{1.0f, 1.0f, 1.0f, 1.0f}, {1.0f, 1.0f, 1.0f, 1.0f}}};
        var output = new float[2][4];
        var returns = new float[mixer.getReturnBusCount()][2][4];

        mixer.mixDown(input, output, returns, 4);

        float expected = isTrack ? (float) (0.25 * Math.cos(Math.PI / 4.0)) : 0.25f;
        for (float[] lane : output) {
            assertThat(lane).containsExactly(new float[]{expected, expected, expected, expected}, offset(1e-6f));
        }
    }

    private enum ChannelPath {
        TRACK, NAMED_RETURN, EXISTING_RETURN
    }

    private record GainProcessor(float gain) implements AudioProcessor {
        @Override
        public void process(float[][] input, float[][] output, int numFrames) {
            for (int channel = 0; channel < input.length; channel++) {
                for (int frame = 0; frame < numFrames; frame++) {
                    output[channel][frame] = input[channel][frame] * gain;
                }
            }
        }

        @Override
        public void processDouble(double[][] input, double[][] output, int numFrames) {
            for (int channel = 0; channel < input.length; channel++) {
                for (int frame = 0; frame < numFrames; frame++) {
                    output[channel][frame] = input[channel][frame] * gain;
                }
            }
        }

        @Override
        public boolean supportsDouble() {
            return true;
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }
}
