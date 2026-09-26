package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import com.benesquivelmusic.daw.core.metering.MeterFrame;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MixerMasteringCompatibilityTest {

    @ParameterizedTest
    @ValueSource(strings = {"simple", "aux", "returns", "tapped returns", "instrumented", "tapped instrumented"})
    void registeredMasteringRunsOnceAfterInsertsAndBeforeMonitorGain(String path) {
        for (var precision : MixPrecision.values()) {
            var mixer = new Mixer();
            mixer.setMixPrecision(precision);
            mixer.addChannel(new MixerChannel("Programme"));
            mixer.getMasterChannel().addInsert(new InsertSlot("Offset", new OffsetProcessor()));
            mixer.getMasterChannel().setVolume(0.25);
            var mastering = new MasteringChain();
            var delay = new DelayedGain();
            mastering.addStage(MasteringStageType.GAIN_STAGING, "Delayed gain", delay);
            mastering.allocateIntermediateBuffers(2, 4);
            mixer.setMasteringChain(mastering);
            mixer.prepareForPlayback(2, 4);
            assertThat(mixer.getSystemLatencySamples()).isEqualTo(2);
            var input = new float[1][2][4];
            for (var lane : input[0]) Arrays.fill(lane, 0.25f);
            var output = new float[2][4];
            var returns = new float[mixer.getReturnBusCount()][2][4];
            var bus = new MeteringTapBus();
            try (var enforcer = new TrackCpuBudgetEnforcer(48_000, 4)) {
                bus.rebind(mixer, new AudioFormat(48_000, 2, 24, 4), 1L);
                var masterTap = bus.attachLevel(MeterTapPoint.MASTER_CHAIN);
                var stageTap = bus.attachLevel(new MeterTapPoint.MasteringStage(0));
                var taps = bus.snapshot();
                switch (path) {
                    case "simple" -> mixer.mixDown(input, output, 4);
                    case "aux" -> mixer.mixDown(input, output, new float[2][4], 4);
                    case "returns" -> mixer.mixDown(input, output, returns, 4);
                    case "tapped returns" -> mixer.mixDown(input, output, returns, 4, taps);
                    case "instrumented" -> mixer.mixDownInstrumented(input, output, returns, 4, List.of(), enforcer);
                    case "tapped instrumented" -> mixer.mixDownInstrumented(input, output, returns, 4, List.of(), enforcer, taps);
                    default -> throw new AssertionError(path);
                }
                assertThat(delay.calls).as("%s, %s", path, precision).isEqualTo(1);
                float mastered = (float) ((0.25 * Math.sqrt(0.5) + 0.1) * 2);
                for (var lane : output) {
                    assertThat(lane[0]).isZero();
                    assertThat(lane[1]).isZero();
                    assertThat(lane[2]).isCloseTo(mastered * 0.25f, within(1e-7f));
                    assertThat(lane[3]).isCloseTo(mastered * 0.25f, within(1e-7f));
                }
                if (path.startsWith("tapped")) {
                    var frame = new MeterFrame();
                    assertThat(masterTap.readInto(frame)).isTrue();
                    assertThat(frame.peak(0)).isCloseTo(mastered, within(1e-7f));
                    assertThat(stageTap.readInto(frame)).isTrue();
                    assertThat(frame.peak(0)).isCloseTo(mastered, within(1e-7f));
                }
            } finally {
                bus.close();
                mixer.getDelayCompensation().close();
            }
        }
    }

    private static final class DelayedGain implements AudioProcessor {
        private final float[][] history = new float[2][2];
        private int position;
        private int calls;

        @Override public void process(float[][] input, float[][] output, int frames) {
            calls++;
            for (int frame = 0; frame < frames; frame++) {
                for (int channel = 0; channel < 2; channel++) {
                    float sample = input[channel][frame];
                    output[channel][frame] = history[channel][position] * 2;
                    history[channel][position] = sample;
                }
                position = (position + 1) % 2;
            }
        }
        @Override public void reset() { for (var lane : history) Arrays.fill(lane, 0f); position = 0; }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
        @Override public int getLatencySamples() { return 2; }
    }

    private static final class OffsetProcessor implements AudioProcessor {
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < 2; channel++) {
                for (int frame = 0; frame < frames; frame++) output[channel][frame] = input[channel][frame] + 0.1f;
            }
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }
}
