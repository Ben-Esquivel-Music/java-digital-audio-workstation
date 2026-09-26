package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.dsp.LimiterProcessor;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.plugin.PluginInvocationSupervisor;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.DynamicLatencyProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MasteringLatencyCompensationTest {
    private static final int FRAMES = 512;
    private static final int BLOCK = 64;
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, BLOCK);

    static Stream<Arguments> mixPaths() {
        return Arrays.stream(MixPrecision.values()).flatMap(precision ->
                Stream.of(Arguments.of(precision, false), Arguments.of(precision, true)));
    }

    @ParameterizedTest
    @MethodSource("mixPaths")
    void directOutputsAlignWithSerialInsertsAndLiveMasteringEdits(MixPrecision precision,
                                                                  boolean instrumented) throws Exception {
        var mixer = new Mixer();
        var chain = limiterChain();
        var limiter = (LimiterProcessor) chain.getStages().getFirst().getProcessor();
        var channel = new MixerChannel("Master source");
        channel.setPan(-1);
        channel.addInsert(new InsertSlot("Channel latency", new DelayProcessor(3)));
        var direct = new MixerChannel("Direct source");
        direct.setPan(-1);
        direct.setOutputRouting(new OutputRouting(2, 2));
        mixer.addChannel(channel);
        mixer.addChannel(direct);
        mixer.getMasterChannel().addInsert(new InsertSlot("Master latency", new DelayProcessor(5)));
        mixer.setMasteringChain(chain);
        mixer.setMixPrecision(precision);
        mixer.prepareForPlayback(4, FRAMES);
        chain.allocateIntermediateBuffers(2, FRAMES);
        try (var compensation = mixer.getDelayCompensation();
             var enforcer = new TrackCpuBudgetEnforcer(48_000, FRAMES)) {
            assertThat(compensation.getMaxLatencySamples()).isEqualTo(3);
            assertThat(compensation.getMasterLatencySamples()).isEqualTo(245);
            assertAlignedImpulse(mixer, chain, enforcer, instrumented, 248);

            limiter.setLookAheadMs(1);
            await(() -> mixer.getSystemLatencySamples() == 56);
            assertAlignedImpulse(mixer, chain, enforcer, instrumented, 56);

            var stage = chain.getStages().getFirst();
            stage.setBypassed(true);
            await(() -> mixer.getSystemLatencySamples() == 8);
            assertAlignedImpulse(mixer, chain, enforcer, instrumented, 8);
            stage.setSolo(true);
            await(() -> mixer.getSystemLatencySamples() == 56);
            assertAlignedImpulse(mixer, chain, enforcer, instrumented, 56);

            stage.setProcessor(new LimiterProcessor(2, 48_000));
            await(() -> mixer.getSystemLatencySamples() == 248);
            assertAlignedImpulse(mixer, chain, enforcer, instrumented, 248);
            chain.setChainBypassed(true);
            await(() -> mixer.getSystemLatencySamples() == 8);
            assertAlignedImpulse(mixer, chain, enforcer, instrumented, 8);
            chain.setChainBypassed(false);
            chain.replaceStages(List.of(new MasteringChain.Stage(MasteringStageType.LIMITING,
                    "Replacement preset", new DelayProcessor(16))));
            await(() -> mixer.getSystemLatencySamples() == 24);
            assertAlignedImpulse(mixer, chain, enforcer, instrumented, 24);
            chain.removeStage(0);
            await(() -> mixer.getSystemLatencySamples() == 8);
            assertAlignedImpulse(mixer, chain, enforcer, instrumented, 8);
        }
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void engineAndOfflineRenderPreserveLastFrameThroughRealLimiter(MixPrecision precision) {
        int totalFrames = 1021;
        var liveMixer = mixer(precision);
        var liveTransport = new Transport();
        var engine = new AudioEngine(FORMAT);
        try (var compensation = liveMixer.getDelayCompensation()) {
            engine.getMasteringChain().addStage(MasteringStageType.LIMITING,
                    "Limiter", new LimiterProcessor(2, 48_000));
            engine.setGraph(liveTransport, liveMixer, List.of(impulseTrack(totalFrames)));
            liveTransport.play();
            engine.start();
            assertThat(engine.getSystemLatencySamples()).isEqualTo(240);
            var liveOutput = new float[2][totalFrames];
            for (int offset = 0; offset < totalFrames; offset += BLOCK) {
                int frames = Math.min(BLOCK, totalFrames - offset);
                var block = new float[2][BLOCK];
                engine.processBlock(null, block, frames);
                for (int channel = 0; channel < 2; channel++) {
                    System.arraycopy(block[channel], 0, liveOutput[channel], offset, frames);
                }
            }

            var offlineMixer = mixer(precision);
            var originalChain = new MasteringChain();
            offlineMixer.setMasteringChain(originalChain);
            try (var offlineCompensation = offlineMixer.getDelayCompensation()) {
                var transport = new Transport();
                transport.play();
                var output = new float[2][totalFrames];
                var pipeline = new RenderPipeline(FORMAT, 1, BLOCK);
                pipeline.renderOffline(transport, offlineMixer, List.of(impulseTrack(totalFrames)),
                        null, limiterChain(), output, totalFrames, BLOCK);

                assertThat(output[0]).containsExactly(liveOutput[0]);
                assertThat(output[0][512]).isCloseTo(0.25f, within(1e-7f));
                assertThat(output[0][totalFrames - 1]).isCloseTo(0.5f, within(1e-7f));
                assertThat(output[1]).containsOnly(0f);
                assertThat(offlineMixer.getMasteringChain()).isSameAs(originalChain);
            }
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void offlineQueuedLatencyEditAppliesBeforeTheRenderOffsetAndStemBypassExcludesMasterDelay() {
        int totalFrames = 1021;
        for (boolean masterEnabled : new boolean[]{true, false}) {
            var mixer = mixer(MixPrecision.FLOAT_32);
            mixer.getMasterChannel().addInsert(new InsertSlot("Master delay", new DelayProcessor(17)));
            var original = limiterChain();
            mixer.setMasteringChain(original);
            var renderedChain = limiterChain();
            var limiter = (LimiterProcessor) renderedChain.getStages().getFirst().getProcessor();
            assertThat(renderedChain.enqueueParameterUpdate(() -> limiter.setLookAheadMs(1))).isTrue();
            var transport = new Transport();
            transport.play();
            var output = new float[2][totalFrames];
            try (var compensation = mixer.getDelayCompensation()) {
                new RenderPipeline(FORMAT, 1, BLOCK).renderOffline(transport, mixer,
                        List.of(impulseTrack(totalFrames)), null, renderedChain,
                        output, totalFrames, BLOCK, masterEnabled);
                assertThat(output[0][512]).isCloseTo(0.25f, within(1e-7f));
                assertThat(output[0][totalFrames - 1]).isCloseTo(0.5f, within(1e-7f));
                assertThat(mixer.getMasteringChain()).isSameAs(original);
                assertThat(mixer.getSystemLatencySamples()).isEqualTo(257);
            }
        }
    }

    @Test
    void failedOfflineMasteringRegistrationRestoresTheOriginalChain() {
        var mixer = mixer(MixPrecision.FLOAT_32);
        var failNextRead = new AtomicBoolean();
        AudioProcessor processor = new AudioProcessor() {
            @Override public int getLatencySamples() {
                if (failNextRead.getAndSet(false)) throw new IllegalStateException("latency read failed");
                return 17;
            }
            @Override public void process(float[][] input, float[][] output, int frames) { }
            @Override public void reset() { }
            @Override public int getInputChannelCount() { return 2; }
            @Override public int getOutputChannelCount() { return 2; }
        };
        mixer.getMasterChannel().addInsert(new InsertSlot("Master latency", processor));
        var original = limiterChain();
        mixer.setMasteringChain(original);
        try (var compensation = mixer.getDelayCompensation()) {
            failNextRead.set(true);
            var pipeline = new RenderPipeline(FORMAT, 1, BLOCK);

            assertThatThrownBy(() -> pipeline.renderOffline(new Transport(), mixer, List.of(), null,
                    new MasteringChain(), new float[2][BLOCK], BLOCK, BLOCK))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("latency read failed");

            assertThat(mixer.getMasteringChain()).isSameAs(original);
            assertThat(mixer.getSystemLatencySamples()).isEqualTo(257);
        }
    }

    @Test
    void resettingCompensationClearsBufferedDirectOutputAudio() {
        var mixer = mixer(MixPrecision.FLOAT_32);
        mixer.setMasteringChain(limiterChain());
        try (var compensation = mixer.getDelayCompensation()) {
            float[][] impulse = {{0.25f}, {0f}};
            compensation.applyToDirectOutput(0, impulse, 1);
            compensation.reset();
            var silence = new float[2][241];
            compensation.applyToDirectOutput(0, silence, 241);
            assertThat(silence[0]).containsOnly(0f);
        }
    }

    @ParameterizedTest
    @MethodSource("mixPaths")
    void faultedMasterInsertRefreshesLatencyBeforeItsOwningChainCanRebuild(
            MixPrecision precision, boolean dynamic, @TempDir Path tempDir) throws Exception {
        var mixer = new Mixer();
        var channel = new MixerChannel("Master source");
        channel.setPan(-1);
        channel.addInsert(new InsertSlot("Channel latency", new DelayProcessor(3)));
        var direct = new MixerChannel("Direct source");
        direct.setPan(-1);
        direct.setOutputRouting(new OutputRouting(2, 2));
        mixer.addChannel(channel);
        mixer.addChannel(direct);
        var processor = dynamic ? new DynamicFaultingDelayProcessor() : new FaultingDelayProcessor();
        var slot = new InsertSlot("Faulting master delay", processor);
        var master = mixer.getMasterChannel();
        var supervisor = new PluginInvocationSupervisor(tempDir.resolve("faults.log"));
        try (var compensation = mixer.getDelayCompensation();
             var enforcer = new TrackCpuBudgetEnforcer(48_000, FRAMES)) {
            mixer.setPluginSupervisor(supervisor);
            master.addInsert(slot);
            master.addInsert(new InsertSlot("Retained master delay", new DelayProcessor(2)));
            mixer.setMixPrecision(precision);
            mixer.prepareForPlayback(4, FRAMES);
            assertAlignedImpulse(mixer, null, enforcer, false, 10);
            int capturedReads = processor.latencyReads.get();

            // The fault drain must wait for this control-thread monitor; rendering and
            // compensation refresh must continue without rebuilding the owning channel.
            synchronized (master) {
                processor.failNext.set(true);
                var failedOutput = renderImpulse(mixer, null, enforcer, false);
                assertThat(failedOutput[0]).containsOnly(0f);
                assertThat(slot.isBypassed()).isTrue();
                assertThat(master.getEffectsChain().size()).isEqualTo(2);
                await(() -> mixer.getSystemLatencySamples() == 5);
                assertThat(compensation.getMasterLatencySamples()).isEqualTo(2);
                assertAlignedImpulse(mixer, null, enforcer, false, 5);
                if (!dynamic) {
                    assertThat(processor.latencyReads).as("static latency is captured only off RT")
                            .hasValue(capturedReads);
                }
            }

            supervisor.reenable(slot);
            assertThat(slot.isBypassed()).isFalse();
            assertAlignedImpulse(mixer, null, enforcer, false, 10);
        } finally {
            supervisor.close();
        }
    }
    private static Mixer mixer(MixPrecision precision) {
        var mixer = new Mixer();
        var channel = new MixerChannel("Programme");
        channel.setPan(-1);
        mixer.addChannel(channel);
        mixer.setMixPrecision(precision);
        mixer.prepareForPlayback(2, BLOCK);
        return mixer;
    }

    private static MasteringChain limiterChain() {
        var chain = new MasteringChain();
        chain.addStage(MasteringStageType.LIMITING, "Limiter", new LimiterProcessor(2, 48_000));
        return chain;
    }

    private static Track impulseTrack(int totalFrames) {
        var track = new Track("Programme", TrackType.AUDIO);
        var clip = new AudioClip("Impulses", 0, totalFrames / 24_000.0, null);
        var data = new float[2][totalFrames];
        data[0][512] = 0.25f;
        data[0][totalFrames - 1] = 0.5f;
        clip.setAudioData(data);
        track.addClip(clip);
        return track;
    }

    private static void assertAlignedImpulse(Mixer mixer, MasteringChain chain,
                                             TrackCpuBudgetEnforcer enforcer, boolean instrumented,
                                             int expectedFrame) {
        var output = renderImpulse(mixer, chain, enforcer, instrumented);
        assertThat(mixer.getSystemLatencySamples()).isEqualTo(expectedFrame);
        for (int frame = 0; frame < FRAMES; frame++) {
            float expected = frame == expectedFrame ? 0.25f : 0f;
            assertThat(output[0][frame]).as("master sample %d", frame).isCloseTo(expected, within(1e-6f));
            assertThat(output[2][frame]).as("direct sample %d", frame).isCloseTo(expected, within(1e-6f));
        }
    }

    private static float[][] renderImpulse(Mixer mixer, MasteringChain chain,
                                           TrackCpuBudgetEnforcer enforcer, boolean instrumented) {
        var sources = new float[2][2][FRAMES];
        sources[0][0][0] = 0.25f;
        sources[1][0][0] = 0.25f;
        var output = new float[4][FRAMES];
        var returns = new float[Mixer.MAX_RETURN_BUSES][4][FRAMES];
        if (instrumented) {
            mixer.mixDownInstrumented(sources, output, returns, FRAMES, List.of(), enforcer,
                    null, chain, null, false, true);
        } else {
            mixer.mixDown(sources, output, returns, FRAMES, null, chain, null, false, true);
        }
        mixer.renderDirectOutputs(sources, output, FRAMES);
        return output;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static class FaultingDelayProcessor implements AudioProcessor {
        private final DelayProcessor delegate = new DelayProcessor(5);
        final AtomicBoolean failNext = new AtomicBoolean();
        final AtomicInteger latencyReads = new AtomicInteger();
        @Override public int getLatencySamples() { latencyReads.incrementAndGet(); return 5; }
        @Override public void process(float[][] input, float[][] output, int frames) {
            if (failNext.getAndSet(false)) throw new IllegalStateException("master insert fault");
            delegate.process(input, output, frames);
        }
        @Override public void reset() { delegate.reset(); }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }

    private static final class DynamicFaultingDelayProcessor extends FaultingDelayProcessor
            implements DynamicLatencyProcessor { }

    private static final class DelayProcessor implements AudioProcessor {
        private final CompensationDelay delay;
        private DelayProcessor(int samples) { delay = new CompensationDelay(2, samples); }
        @Override public int getLatencySamples() { return delay.getDelaySamples(); }
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < Math.min(input.length, output.length); channel++) {
                System.arraycopy(input[channel], 0, output[channel], 0, frames);
            }
            delay.process(output, frames);
        }
        @Override public void reset() { delay.reset(); }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }
}
