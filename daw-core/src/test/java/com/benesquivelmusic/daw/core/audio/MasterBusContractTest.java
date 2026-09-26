package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.dsp.GainStagingProcessor;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.plugin.PluginFault;
import com.benesquivelmusic.daw.core.plugin.PluginInvocationSupervisor;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.DynamicLatencyProcessor;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MasterBusContractTest {

    private static final int FRAMES = 32;

    @TempDir
    Path temporaryDirectory;

    private enum MixPath { SIMPLE, RETURNS, INSTRUMENTED }

    static Stream<Arguments> mixPaths() {
        return Arrays.stream(MixPrecision.values()).flatMap(precision ->
                Arrays.stream(MixPath.values()).map(path -> Arguments.of(precision, path)));
    }

    @ParameterizedTest
    @MethodSource("mixPaths")
    void masterInsertAffectsEveryMixPathAndRemovingItRestoresDryOutput(MixPrecision precision, MixPath path) {
        try (var rig = new Rig(precision, path)) {
            rig.channel.setVolume(0.8);
            rig.master.setVolume(0.5);
            var insert = new InsertSlot("Master gain", new GainProcessor(0.25f));
            rig.master.addInsert(insert);

            assertThat(rig.renderConstant()).isCloseTo(0.1f, within(1e-6f));

            rig.master.setInsertBypassed(0, true);
            assertThat(rig.renderConstant()).isCloseTo(0.4f, within(1e-6f));
            rig.master.setInsertBypassed(0, false);
            assertThat(rig.renderConstant()).isCloseTo(0.1f, within(1e-6f));
            assertThat(rig.master.removeInsert(insert)).isTrue();
            assertThat(rig.renderConstant()).isCloseTo(0.4f, within(1e-6f));
        }
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void serialMasterLatencyAddsToParallelAlignmentWithoutDelayingTheSumTwice(MixPrecision precision) {
        try (var rig = new Rig(precision, MixPath.RETURNS)) {
            var delayed = new MixerChannel("Delayed source");
            delayed.setPan(-1.0);
            delayed.addInsert(new InsertSlot("Channel delay", new DelayProcessor(3)));
            rig.mixer.addChannel(delayed);
            rig.master.addInsert(new InsertSlot("Master delay", new DelayProcessor(5)));
            rig.master.addInsert(new InsertSlot("Second master delay", new DelayProcessor(2)));
            rig.mixer.prepareForPlayback(2, FRAMES);
            var compensation = rig.mixer.getDelayCompensation();

            assertThat(compensation.getMaxLatencySamples()).isEqualTo(3);
            assertThat(compensation.getMasterLatencySamples()).isEqualTo(7);
            assertThat(compensation.getChannelCompensationSamples(0)).isEqualTo(3);
            assertThat(compensation.getChannelCompensationSamples(1)).isZero();
            assertThat(rig.mixer.getSystemLatencySamples()).isEqualTo(10);

            var sources = new float[2][2][FRAMES];
            sources[0][0][0] = 0.5f;
            sources[1][0][0] = 0.5f;
            var output = new float[2][FRAMES];
            rig.mixer.mixDown(sources, output, new float[Mixer.MAX_RETURN_BUSES][2][FRAMES], FRAMES);

            for (int frame = 0; frame < FRAMES; frame++) {
                assertThat(output[0][frame]).as("sample %d", frame)
                        .isCloseTo(frame == 10 ? 1.0f : 0.0f, within(1e-6f));
            }
            assertThat(output[1]).containsOnly(0.0f);
        }
    }

    @Test
    void dynamicMasterLatencyAndBypassUpdateSystemLatencyWithoutChangingParallelCompensation() throws Exception {
        try (var rig = new Rig(MixPrecision.FLOAT_32, MixPath.RETURNS)) {
            var wet = new MixerChannel("Wet");
            wet.addInsert(new InsertSlot("Channel latency", new DelayProcessor(3)));
            rig.mixer.addChannel(wet);
            var delay = new DelayProcessor(5);
            var slot = new InsertSlot("Dynamic master", delay);
            rig.master.addInsert(slot);
            var compensation = rig.mixer.getDelayCompensation();
            assertThat(rig.mixer.getSystemLatencySamples()).isEqualTo(8);

            delay.setDelaySamples(11);
            await(() -> rig.mixer.getSystemLatencySamples() == 14);

            assertThat(compensation.getMasterLatencySamples()).isEqualTo(11);
            assertThat(compensation.getChannelCompensationSamples(0)).isEqualTo(3);
            assertThat(compensation.getChannelCompensationSamples(1)).isZero();
            rig.master.setInsertBypassed(0, true);
            assertThat(rig.mixer.getSystemLatencySamples()).isEqualTo(3);
            assertThat(compensation.getMasterLatencySamples()).isZero();
            rig.master.setInsertBypassed(0, false);
            assertThat(rig.mixer.getSystemLatencySamples()).isEqualTo(14);
            rig.master.removeInsert(slot);
            assertThat(rig.mixer.getSystemLatencySamples()).isEqualTo(3);
            assertThat(compensation.getChannelCompensationSamples(0)).isEqualTo(3);
        }
    }

    @ParameterizedTest
    @MethodSource("mixPaths")
    void directOutputsStayAlignedWithMasterAfterLatencyEditsAndBypass(MixPrecision precision, MixPath path)
            throws Exception {
        try (var rig = new Rig(precision, path)) {
            rig.channel.addInsert(new InsertSlot("Channel delay", new DelayProcessor(3)));
            var direct = new MixerChannel("Direct source");
            direct.setPan(-1.0);
            direct.setOutputRouting(new OutputRouting(2, 2));
            rig.mixer.addChannel(direct);
            var masterDelay = new DelayProcessor(5);
            rig.master.addInsert(new InsertSlot("Master delay", masterDelay));
            rig.mixer.prepareForPlayback(4, FRAMES);

            assertAlignedRoutingImpulse(rig, 8);

            masterDelay.setDelaySamples(7);
            await(() -> rig.mixer.getSystemLatencySamples() == 10);
            assertAlignedRoutingImpulse(rig, 10);

            rig.master.setInsertBypassed(0, true);
            assertThat(rig.mixer.getSystemLatencySamples()).isEqualTo(3);
            assertAlignedRoutingImpulse(rig, 3);
        }
    }

    @ParameterizedTest
    @MethodSource("mixPaths")
    void masterInsertUsesItsConfiguredExternalSidechainAcrossMixPaths(MixPrecision precision, MixPath path) {
        try (var rig = new Rig(precision, path)) {
            var detector = new MixerChannel("Detector");
            detector.setOutputRouting(new OutputRouting(2, 2));
            rig.mixer.addChannel(detector);
            var processor = new SidechainGainProcessor();
            var insert = new InsertSlot("Master sidechain", processor);
            insert.setSidechainSource(detector);
            rig.master.addInsert(insert);
            var sources = new float[2][2][FRAMES];
            Arrays.fill(sources[0][0], 1.0f);
            Arrays.fill(sources[0][1], 1.0f);
            Arrays.fill(sources[1][0], 0.25f);
            Arrays.fill(sources[1][1], 0.25f);
            var output = new float[2][FRAMES];

            rig.render(sources, output);

            assertThat(output[0]).containsOnly(0.25f);
            assertThat(processor.sidechainCalls).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void recallingMixerSnapshotRestoresAudibleMasterInsertParametersAndBypass(MixPrecision precision) {
        try (var rig = new Rig(precision, MixPath.RETURNS)) {
            var gain = new GainStagingProcessor(2, -6.0);
            var slot = new InsertSlot("Master trim", gain, InsertEffectType.GAIN_STAGING);
            rig.master.addInsert(slot);
            rig.master.setVolume(0.6);
            var snapshot = MixerSnapshot.capture(rig.mixer, "Master trim");
            float before = rig.renderConstant();
            assertThat(before).isCloseTo((float) (0.6 * Math.pow(10.0, -6.0 / 20.0)), within(1e-6f));
            assertThat(snapshot.master().inserts()).hasSize(1);
            gain.setGainDb(-18.0);
            rig.master.setInsertBypassed(0, true);
            rig.master.setVolume(0.2);
            assertThat(rig.renderConstant()).isCloseTo(0.2f, within(1e-6f));

            snapshot.applyTo(rig.mixer);

            assertThat(gain.getGainDb()).isEqualTo(-6.0);
            assertThat(slot.isBypassed()).isFalse();
            assertThat(rig.master.getEffectsChain().size()).isEqualTo(1);
            assertThat(rig.renderConstant()).isCloseTo(before, within(1e-6f));
        }
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void masterInsertFaultIsSupervisedAndFollowingBlocksBypassTheExactFailedSlot(MixPrecision precision)
            throws Exception {
        var supervisor = new PluginInvocationSupervisor(temporaryDirectory.resolve("master-faults.log"));
        try (var rig = new Rig(precision, MixPath.RETURNS)) {
            rig.mixer.setPluginSupervisor(supervisor);
            var calls = new AtomicInteger();
            var failed = new InsertSlot("Failing master", new FaultingProcessor(calls));
            rig.master.addInsert(failed);
            var received = new CountDownLatch(1);
            var fault = new AtomicReference<PluginFault>();
            supervisor.publisher().subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(1); }
                @Override public void onNext(PluginFault value) { fault.set(value); received.countDown(); }
                @Override public void onError(Throwable error) { received.countDown(); }
                @Override public void onComplete() { }
            });

            assertThat(rig.renderConstant()).isZero();
            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(fault.get()).isNotNull();
            assertThat(fault.get().slot()).isSameAs(failed);
            assertThat(failed.isBypassed()).isTrue();
            assertThat(rig.master.getEffectsChain().isEmpty()).isTrue();
            assertThat(rig.renderConstant()).isEqualTo(1.0f);
            assertThat(rig.renderConstant()).isEqualTo(1.0f);
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            supervisor.close();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void assertAlignedRoutingImpulse(Rig rig, int expectedFrame) {
        var sources = new float[2][2][FRAMES];
        sources[0][0][0] = 0.5f;
        sources[1][0][0] = 0.5f;
        var output = new float[4][FRAMES];
        rig.render(sources, output);
        rig.mixer.renderDirectOutputs(sources, output, FRAMES);

        for (int frame = 0; frame < FRAMES; frame++) {
            float expected = frame == expectedFrame ? 0.5f : 0.0f;
            assertThat(output[0][frame]).as("master sample %d", frame).isCloseTo(expected, within(1e-6f));
            assertThat(output[2][frame]).as("direct sample %d", frame).isCloseTo(expected, within(1e-6f));
        }
        assertThat(output[1]).containsOnly(0.0f);
        assertThat(output[3]).containsOnly(0.0f);
    }

    private static final class Rig implements AutoCloseable {
        private final Mixer mixer = new Mixer();
        private final MixerChannel channel = new MixerChannel("Source");
        private final MixerChannel master = mixer.getMasterChannel();
        private final TrackCpuBudgetEnforcer enforcer = new TrackCpuBudgetEnforcer(48_000.0, FRAMES);
        private final List<Track> tracks = List.of(new Track("Source", TrackType.AUDIO));
        private final float[][][] input = new float[1][2][FRAMES];
        private final float[][] output = new float[2][FRAMES];
        private final float[][][] returns = new float[Mixer.MAX_RETURN_BUSES][2][FRAMES];
        private final MixPath path;

        private Rig(MixPrecision precision, MixPath path) {
            this.path = path;
            mixer.setMixPrecision(precision);
            channel.setPan(-1.0);
            mixer.addChannel(channel);
            mixer.prepareForPlayback(2, FRAMES);
        }

        private float renderConstant() {
            Arrays.fill(input[0][0], 1.0f);
            Arrays.fill(input[0][1], 1.0f);
            render(input, output);
            return output[0][0];
        }

        private void render(float[][][] sources, float[][] destination) {
            switch (path) {
                case SIMPLE -> mixer.mixDown(sources, destination, FRAMES);
                case RETURNS -> mixer.mixDown(sources, destination, returns, FRAMES);
                case INSTRUMENTED -> mixer.mixDownInstrumented(sources, destination, returns, FRAMES, tracks, enforcer);
            }
        }

        @Override
        public void close() {
            enforcer.close();
            mixer.getDelayCompensation().close();
        }
    }

    private record GainProcessor(float gain) implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int channel = 0; channel < outputBuffer.length; channel++) {
                for (int frame = 0; frame < numFrames; frame++) {
                    outputBuffer[channel][frame] = inputBuffer[channel][frame] * gain;
                }
            }
        }

        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }

    private static final class DelayProcessor implements DynamicLatencyProcessor {
        private volatile CompensationDelay delay;

        private DelayProcessor(int samples) {
            setDelaySamples(samples);
        }

        private void setDelaySamples(int samples) {
            delay = new CompensationDelay(2, samples);
        }

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int channel = 0; channel < outputBuffer.length; channel++) {
                System.arraycopy(inputBuffer[channel], 0, outputBuffer[channel], 0, numFrames);
            }
            delay.process(outputBuffer, numFrames);
        }

        @Override public void reset() { delay.reset(); }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
        @Override public int getLatencySamples() { return delay.getDelaySamples(); }
    }

    private record FaultingProcessor(AtomicInteger calls) implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            calls.incrementAndGet();
            outputBuffer[0][0] = 99.0f;
            throw new IllegalStateException("Master insert failure");
        }

        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }

    private static final class SidechainGainProcessor implements SidechainAwareProcessor {
        private int sidechainCalls;

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            throw new AssertionError("The configured detector must reach processSidechain");
        }

        @Override
        public void processSidechain(float[][] inputBuffer, float[][] sidechainBuffer,
                                     float[][] outputBuffer, int numFrames) {
            sidechainCalls++;
            for (int channel = 0; channel < outputBuffer.length; channel++) {
                for (int frame = 0; frame < numFrames; frame++) {
                    outputBuffer[channel][frame] = inputBuffer[channel][frame] * sidechainBuffer[0][frame];
                }
            }
        }

        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }
}
