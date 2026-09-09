package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MeteringAnalysisSilenceTest {

    private static final int RATE = 48_000;
    private static final int FRAMES = 32;
    private static final float SOURCE_LEVEL = 0.25f;

    private enum Subject { MASTER_CHANNEL, DIRECT_CHANNEL, RETURN }

    static Stream<Arguments> renderPaths() {
        return Arrays.stream(MixPrecision.values()).flatMap(precision ->
                Stream.of(false, true).flatMap(instrumented ->
                        Stream.of(1, 2, 6).flatMap(lanes ->
                                Arrays.stream(Subject.values()).map(subject ->
                                        Arguments.of(precision, instrumented, lanes, subject)))));
    }

    @ParameterizedTest(name = "{0}, instrumented={1}, lanes={2}, subject={3}")
    @MethodSource("renderPaths")
    void mutedAndSoloExcludedTapsKeepEveryAnalysisConsumerAdvancing(
            MixPrecision precision, boolean instrumented, int lanes, Subject subject) throws Exception {
        var mixer = new Mixer();
        mixer.setMixPrecision(precision);
        var channel = new MixerChannel("Signal");
        var otherChannel = new MixerChannel("Other");
        mixer.addChannel(channel);
        mixer.addChannel(otherChannel);
        var returnBus = mixer.getReturnBuses().get(0);
        channel.addSend(new Send(returnBus, 0.5, SendMode.POST_FADER));
        if (subject == Subject.DIRECT_CHANNEL) {
            channel.setOutputRouting(new OutputRouting(1, lanes));
        }
        mixer.prepareForPlayback(lanes, FRAMES);

        MixerChannel target = subject == Subject.RETURN ? returnBus : channel;
        MixerChannel soloChannel = subject == Subject.RETURN ? channel : otherChannel;
        target.setSoloSafe(false);
        MeterTapPoint point = subject == Subject.RETURN
                ? new MeterTapPoint.ReturnPost(target.getId())
                : new MeterTapPoint.ChannelPost(target.getId());
        float[][] source = new float[lanes][FRAMES];
        for (float[] lane : source) {
            Arrays.fill(lane, SOURCE_LEVEL);
        }
        float[][][] sources = {source, new float[lanes][FRAMES]};
        float[][] master = new float[lanes][FRAMES];
        float[][] hardware = new float[lanes + 1][FRAMES];
        float[][][] returns = new float[Mixer.MAX_RETURN_BUSES][lanes][FRAMES];
        var tracks = List.of(new Track("Signal", TrackType.AUDIO), new Track("Other", TrackType.AUDIO));
        int[] blockLengths = {FRAMES, 16, 24, 8, 20, 12, FRAMES};

        var bus = new MeteringTapBus();
        try (var enforcer = new TrackCpuBudgetEnforcer(RATE, FRAMES)) {
            bus.rebind(mixer, new AudioFormat(RATE, lanes, 24, FRAMES), 17L);
            var level = bus.attachLevel(point);
            var first = new Capture();
            var second = new Capture();
            var firstSubscription = bus.attachAnalysis(point, 1, first::accept);
            var secondSubscription = bus.attachAnalysis(point, 2, second::accept);
            var frame = new MeterFrame();
            float[][] initial = null;
            int analyzedFrames = 0;

            for (int block = 0; block < blockLengths.length; block++) {
                target.setMuted(block >= 1 && block <= 3);
                soloChannel.setSolo(block >= 4 && block <= 5);
                boolean silent = block >= 1 && block <= 5;
                int numFrames = blockLengths[block];
                var taps = bus.snapshot();
                long stamp = taps.blockIndex();
                if (instrumented) {
                    mixer.mixDownInstrumented(sources, master, returns, numFrames, tracks, enforcer, taps);
                } else {
                    mixer.mixDown(sources, master, returns, numFrames, taps);
                }
                if (subject == Subject.DIRECT_CHANNEL) {
                    for (float[] lane : hardware) {
                        Arrays.fill(lane, 0f);
                    }
                    mixer.renderDirectOutputs(sources, hardware, numFrames, taps);
                }
                bus.blockCompleted(taps);

                assertThat(level.readInto(frame)).as("level block %d", block).isTrue();
                assertThat(frame.epoch()).isEqualTo(taps.epoch());
                assertThat(frame.blockIndex()).isEqualTo(stamp);
                assertThat(frame.channelCount()).isEqualTo(lanes);
                assertThat(frame.isSilent()).isEqualTo(silent);

                float[][] captured = first.nextBlock();
                float[][] duplicate = second.nextBlock();
                assertThat(captured).hasDimensions(lanes, numFrames);
                assertThat(duplicate).hasDimensions(lanes, numFrames);
                for (int lane = 0; lane < lanes; lane++) {
                    assertThat(duplicate[lane]).containsExactly(captured[lane]);
                    assertThat(source[lane]).as("source remains intact").containsOnly(SOURCE_LEVEL);
                    if (silent) {
                        assertThat(captured[lane]).as("silent block %d lane %d", block, lane).containsOnly(0f);
                    } else {
                        assertThat(captured[lane][0]).isPositive();
                        if (initial != null) {
                            assertThat(captured[lane]).containsExactly(initial[lane]);
                        }
                    }
                }
                if (block == 0) {
                    initial = captured;
                }
                analyzedFrames += captured[0].length;
            }

            assertThat(analyzedFrames).isEqualTo(Arrays.stream(blockLengths).sum());
            assertThat(first.blocks).isEmpty();
            assertThat(second.blocks).isEmpty();
            assertThat(firstSubscription.droppedBlocks()).isZero();
            assertThat(secondSubscription.droppedBlocks()).isZero();
        } finally {
            bus.close();
        }
    }

    private static final class Capture {
        private final BlockingQueue<float[][]> blocks = new LinkedBlockingQueue<>();

        private void accept(float[][] samples, int lanes, int frames, double sampleRate) {
            float[][] copy = new float[lanes][];
            for (int lane = 0; lane < lanes; lane++) {
                copy[lane] = Arrays.copyOf(samples[lane], frames);
            }
            blocks.add(copy);
        }

        private float[][] nextBlock() throws InterruptedException {
            float[][] samples = blocks.poll(5, TimeUnit.SECONDS);
            assertThat(samples).as("analysis block delivered").isNotNull();
            return samples;
        }
    }
}
