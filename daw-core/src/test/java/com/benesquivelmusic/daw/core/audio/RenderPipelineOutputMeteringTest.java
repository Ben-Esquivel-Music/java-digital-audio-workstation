package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.metering.MeterFrame;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.core.track.TrackType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RenderPipelineOutputMeteringTest {

    private static final int BLOCK = 64;
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, BLOCK);

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void finalMeterAndAnalysisIncludeMasterEffectsAndOverlappingDirectRouteCancellation(MixPrecision precision)
            throws InterruptedException {
        var mixer = new Mixer();
        mixer.setMixPrecision(precision);
        var positive = directChannel("Positive");
        var negative = directChannel("Negative");
        mixer.addChannel(positive);
        mixer.addChannel(negative);
        mixer.prepareForPlayback(2, BLOCK);
        var transport = new Transport();
        transport.play();
        var tracks = List.of(constantTrack("Positive", 0.75f), constantTrack("Negative", -0.75f));
        var chain = new EffectsChain();
        chain.addProcessor(new InterfaceSignal());
        chain.allocateIntermediateBuffers(2, BLOCK);
        var pipeline = new RenderPipeline(FORMAT, 4, BLOCK);
        var output = new float[4][BLOCK];
        var interleaved = new float[2 * BLOCK];
        var bus = new MeteringTapBus();
        try {
            bus.rebind(mixer, FORMAT, 1L);
            var level = bus.attachLevel(MeterTapPoint.MASTER_OUT);
            var captured = new AtomicReference<float[][]>();
            var delivered = new CountDownLatch(1);
            bus.attachAnalysis(MeterTapPoint.MASTER_OUT, 4, (samples, channels, frames, rate) -> {
                var copy = new float[channels][];
                for (int lane = 0; lane < channels; lane++) {
                    copy[lane] = Arrays.copyOf(samples[lane], frames);
                }
                captured.set(copy);
                delivered.countDown();
            });
            var taps = bus.snapshot();
            long stamp = taps.blockIndex();
            pipeline.renderBlock(null, output, BLOCK, transport, mixer, tracks, null,
                    chain, null, null, null, null, null, null, null, taps, interleaved);
            var frame = new MeterFrame();
            assertThat(level.readInto(frame)).isTrue();
            assertThat(frame.blockIndex()).isEqualTo(stamp);
            assertThat(frame.epoch()).isEqualTo(bus.epoch());
            assertThat(frame.clipped()).as("intermediate direct sums clip, but their final sum does not").isFalse();
            assertThat(frame.channelCount()).isEqualTo(2);
            for (int sample = 0; sample < BLOCK; sample++) {
                assertThat(output[0][sample]).isCloseTo(InterfaceSignal.sample(0, sample), within(0.000001f));
                assertThat(output[1][sample]).isEqualTo(InterfaceSignal.sample(1, sample));
            }
            assertFinalSamples(frame, output, interleaved, 2);
            bus.blockCompleted(taps);
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(captured.get().length).isEqualTo(2);
            assertThat(captured.get()[0]).containsExactly(output[0]);
            assertThat(captured.get()[1]).containsExactly(output[1]);
        } finally {
            bus.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void interfaceWriterMetersPassthroughAndIdleSilenceWithNoUntappedAudioDifference(int channels) {
        var format = new AudioFormat(48_000, channels, 24, BLOCK);
        var pipeline = new RenderPipeline(format, 4, BLOCK);
        var chain = new EffectsChain();
        var input = new float[channels][BLOCK];
        var output = new float[channels + 2][BLOCK];
        var interleaved = new float[channels * BLOCK];
        for (int lane = 0; lane < channels; lane++) {
            for (int sample = 0; sample < BLOCK; sample++) {
                input[lane][sample] = (lane + 1) * (sample % 4 - 2) * 0.125f;
            }
        }
        var bus = new MeteringTapBus();
        try {
            bus.rebind(new Mixer(), format, 1L);
            var level = bus.attachLevel(MeterTapPoint.MASTER_OUT);
            var taps = bus.snapshot();
            pipeline.renderBlock(input, output, BLOCK, null, null, null, null,
                    chain, null, null, null, null, null, null, null, taps, interleaved);
            var frame = new MeterFrame();
            assertThat(level.readInto(frame)).isTrue();
            assertFinalSamples(frame, output, interleaved, channels);
            var tappedSamples = interleaved.clone();
            level.dispose();
            pipeline.renderBlock(input, output, BLOCK, null, null, null, null,
                    chain, null, null, null, null, null, null, null, bus.snapshot(), interleaved);
            assertThat(interleaved).containsExactly(tappedSamples);
            level = bus.attachLevel(MeterTapPoint.MASTER_OUT);
            pipeline.renderBlock(null, output, BLOCK, null, null, null, null,
                    chain, null, null, null, null, null, null, null, bus.snapshot(), interleaved);
            assertThat(level.readInto(frame)).isTrue();
            assertThat(frame.isSilent()).isTrue();
            assertThat(frame.channelCount()).isEqualTo(channels);
            assertThat(interleaved).containsOnly(0f);
        } finally {
            bus.close();
        }
    }

    @Test
    void planarOnlyRenderingNeverScansOrPublishesTheInterfaceMeter() {
        var pipeline = new RenderPipeline(FORMAT, 4, BLOCK);
        var bus = new MeteringTapBus();
        try {
            bus.rebind(new Mixer(), FORMAT, 1L);
            var level = bus.attachLevel(MeterTapPoint.MASTER_OUT);
            var output = new float[2][BLOCK];
            pipeline.renderBlock(null, output, BLOCK, null, null, null, null,
                    new EffectsChain(), null, null, null, null, null, null, null, bus.snapshot());
            assertThat(level.readInto(new MeterFrame())).isFalse();
        } finally {
            bus.close();
        }
    }

    @Test
    void outputMeterHasOnlyScalarAccumulationInsideTheInterfaceWrite() throws Exception {
        // Class-File API (JEP 484, final in Java 24) enforces the no-extra-pass RT contract.
        try (var stream = RenderPipeline.class.getResourceAsStream("RenderPipeline.class")) {
            assertThat(stream).isNotNull();
            var model = ClassFile.of().parse(stream.readAllBytes());
            int accumulationSites = 0;
            for (var method : model.methods()) {
                var code = method.findAttribute(Attributes.code());
                if (code.isEmpty()) {
                    continue;
                }
                for (var element : code.get()) {
                    if (element instanceof InvokeInstruction invoke
                            && invoke.owner().asInternalName().endsWith("/LevelTapSlot")
                            && invoke.name().stringValue().startsWith("accumulate")) {
                        accumulationSites++;
                        assertThat(method.methodName().stringValue()).isEqualTo("writeInterleavedOutput");
                        assertThat(invoke.type().stringValue()).isEqualTo("(IF)V");
                    }
                }
            }
            assertThat(accumulationSites).isEqualTo(1);
        }
    }

    private static void assertFinalSamples(MeterFrame meter, float[][] output, float[] interleaved, int channels) {
        assertThat(meter.channelCount()).isEqualTo(channels);
        for (int lane = 0; lane < channels; lane++) {
            double squares = 0;
            float peak = 0;
            for (int sample = 0; sample < BLOCK; sample++) {
                float value = interleaved[sample * channels + lane];
                assertThat(value).isEqualTo(output[lane][sample]);
                peak = Math.max(peak, Math.abs(value));
                squares += (double) value * value;
            }
            assertThat(meter.peak(lane)).isEqualTo(peak);
            assertThat(meter.rms(lane)).isEqualTo((float) Math.sqrt(squares / BLOCK));
        }
    }

    private static MixerChannel directChannel(String name) {
        var channel = new MixerChannel(name);
        channel.setOutputRouting(new OutputRouting(0, 1));
        channel.setVolume(1.0);
        return channel;
    }

    private static Track constantTrack(String name, float value) {
        var track = new Track(name, TrackType.AUDIO);
        var clip = new AudioClip(name, 0, 4, null);
        var samples = new float[2][BLOCK * 8];
        for (float[] lane : samples) {
            Arrays.fill(lane, value);
        }
        clip.setAudioData(samples);
        track.addClip(clip);
        return track;
    }

    private static final class InterfaceSignal implements AudioProcessor {
        static float sample(int channel, int frame) {
            return channel == 0 ? (frame % 2 == 0 ? 0.5f : -0.125f) : 0.25f;
        }

        @Override
        public void process(float[][] input, float[][] output, int frames) {
            for (int lane = 0; lane < 2; lane++) {
                for (int frame = 0; frame < frames; frame++) {
                    output[lane][frame] = sample(lane, frame);
                }
            }
        }

        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }
}
