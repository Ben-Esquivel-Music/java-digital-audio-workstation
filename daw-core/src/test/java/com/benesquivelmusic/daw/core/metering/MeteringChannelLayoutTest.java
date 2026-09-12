package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MeteringChannelLayoutTest {

    private static final int RATE = 48_000;
    private static final int FRAMES = 32;
    private static final double VOLUME = 0.73;
    private static final double PAN = 0.4;

    static Stream<Arguments> layouts() {
        return Arrays.stream(MixPrecision.values()).flatMap(precision ->
                Stream.of(false, true).flatMap(instrumented ->
                        Stream.of(false, true).flatMap(direct ->
                                Stream.of(new int[]{1, 1, 1}, new int[]{1, 2, 2},
                                                new int[]{2, 1, 1}, new int[]{6, 2, 2},
                                                new int[]{6, 6, 6}, new int[]{8, 8, 8},
                                                new int[]{1, 6, 6}, new int[]{2, 6, 6},
                                                new int[]{6, 8, 8}, new int[]{6, 6, 3},
                                                new int[]{2, 2, 1}, new int[]{2, 2, 0})
                                        .filter(layout -> direct || layout[1] == layout[2])
                                        .map(layout -> Arguments.of(precision, instrumented, direct,
                                                layout[0], layout[1], layout[2])))));
    }

    @ParameterizedTest(name = "{0}, instrumented={1}, direct={2}, source={3}, route={4}, available={5}")
    @MethodSource("layouts")
    void analysisSamplesMatchRenderedAudioAndLevelLanes(MixPrecision precision, boolean instrumented,
                                                        boolean direct, int sourceLanes,
                                                        int outputLanes, int availableLanes) throws Exception {
        var mixer = new Mixer();
        mixer.setMixPrecision(precision);
        var channel = new MixerChannel("Layout");
        channel.setVolume(VOLUME);
        channel.setPan(PAN);
        mixer.addChannel(channel);
        mixer.prepareForPlayback(outputLanes, FRAMES);
        int firstOutput = direct ? 1 : 0;
        if (direct) {
            channel.setOutputRouting(new OutputRouting(firstOutput, outputLanes));
        }

        var bus = new MeteringTapBus();
        try (var enforcer = new TrackCpuBudgetEnforcer(RATE, FRAMES)) {
            bus.rebind(mixer, new AudioFormat(RATE, outputLanes, 24, FRAMES), 1L);
            var point = new MeterTapPoint.ChannelPost(channel.getId());
            var level = bus.attachLevel(point);
            var captured = new AtomicReference<float[][]>();
            var delivered = new CountDownLatch(1);
            bus.attachAnalysis(point, 2, (samples, lanes, frames, _) -> {
                float[][] copy = new float[lanes][];
                for (int lane = 0; lane < lanes; lane++) {
                    copy[lane] = Arrays.copyOf(samples[lane], frames);
                }
                captured.set(copy);
                delivered.countDown();
            });

            float[][] source = new float[sourceLanes][FRAMES];
            for (int lane = 0; lane < sourceLanes; lane++) {
                for (int frame = 0; frame < FRAMES; frame++) {
                    source[lane][frame] = (lane + 1) * 0.07f * (frame % 2 == 0 ? 1 : -1);
                }
            }
            float[][] master = new float[outputLanes][FRAMES];
            float[][][] returns = new float[Mixer.MAX_RETURN_BUSES][outputLanes][FRAMES];
            var taps = bus.snapshot();
            if (instrumented) {
                mixer.mixDownInstrumented(new float[][][]{source}, master, returns, FRAMES,
                        List.of(new Track("Layout", TrackType.AUDIO)), enforcer, taps);
            } else {
                mixer.mixDown(new float[][][]{source}, master, returns, FRAMES, taps);
            }
            float[][] rendered = master;
            if (direct) {
                rendered = new float[firstOutput + availableLanes][FRAMES];
                mixer.renderDirectOutputs(new float[][][]{source}, rendered, FRAMES, taps);
            }
            bus.blockCompleted(taps);

            assertThat(delivered.await(5, TimeUnit.SECONDS)).as("analysis block delivered").isTrue();
            var frame = new MeterFrame();
            assertThat(level.readInto(frame)).isTrue();
            int expectedLanes = Math.min(availableLanes,
                    outputLanes >= 2 ? Math.max(2, sourceLanes) : sourceLanes);
            assertThat(frame.channelCount()).isEqualTo(expectedLanes);
            assertThat(captured.get().length).isEqualTo(expectedLanes);
            for (int lane = 0; lane < expectedLanes; lane++) {
                float[] samples = captured.get()[lane];
                double gain = outputLanes < 2 || lane >= 2 ? VOLUME
                        : lane == 0 ? Math.cos((PAN + 1) * Math.PI / 4) * VOLUME
                        : Math.sin((PAN + 1) * Math.PI / 4) * VOLUME;
                float expectedPeak = (float) (source[lane == 1 && sourceLanes == 1 ? 0 : lane][0] * gain);
                assertThat(samples).containsExactly(rendered[firstOutput + lane]);
                assertThat(samples[0]).isCloseTo(expectedPeak, within(1e-7f));
                assertThat(frame.peak(lane)).isCloseTo(expectedPeak, within(1e-7f));
                assertThat(frame.rms(lane)).isCloseTo(expectedPeak, within(1e-7f));
            }
        } finally {
            bus.close();
        }
    }
}
