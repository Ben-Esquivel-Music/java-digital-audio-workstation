package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.dsp.dynamics.TruePeakLimiterProcessor;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.preset.ReflectivePresetSerializer;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.DynamicLatencyProcessor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

public class LivePluginDelayCompensationTest {
    @Test
    void insertionAndQueuedLookaheadEditsAlignDryAndLimitedImpulses() throws Exception {
        var mixer = new Mixer();
        try (var compensation = mixer.getDelayCompensation()) {
            var dry = new MixerChannel("Dry");
            var wet = new MixerChannel("Limited");
            var limiter = new TruePeakLimiterProcessor(1, 48_000);
            var slot = new InsertSlot("Limiter", limiter);
            mixer.addChannel(dry);
            mixer.addChannel(wet);
            wet.addInsert(slot);

            assertThat(compensation.getChannelCompensationSamples(0)).isEqualTo(240);
            assertAlignedImpulse(limiter, compensation, 240);

            slot.getParameterStore().writeFromUiById(2, 10);
            assertThat(limiter.getLatencySamples()).isEqualTo(240);
            mixer.drainInsertParameters();
            await(() -> compensation.getChannelCompensationSamples(0) == 480);
            assertAlignedImpulse(limiter, compensation, 480);

            slot.getParameterStore().writeFromUiById(2, 1);
            mixer.drainInsertParameters();
            await(() -> compensation.getChannelCompensationSamples(0) == 48);
            assertAlignedImpulse(limiter, compensation, 48);
        }
    }

    @Test
    void automationAndPresetRecallRefreshLatencyWithoutAnOpenEditor() throws Exception {
        var mixer = new Mixer();
        try (var compensation = mixer.getDelayCompensation()) {
            var channel = new MixerChannel("Automated");
            var limiter = new TruePeakLimiterProcessor(1, 48_000);
            channel.addInsert(new InsertSlot("Limiter", limiter));
            mixer.addChannel(channel);
            var binder = mixer.getReflectiveParameterBinder();
            var target = binder.getAutomatablePluginParameters(channel).stream()
                    .filter(parameter -> parameter.parameterId() == 2).findFirst().orElseThrow();
            var automation = new AutomationData();
            automation.getOrCreatePluginLane(target).addPoint(new AutomationPoint(0, 7));
            binder.apply(channel, automation, 0);
            await(() -> compensation.getChannelLatencySamples(0) == 336);
            assertThat(mixer.getSystemLatencySamples()).isEqualTo(336);
            ReflectivePresetSerializer.restore(limiter, Map.of("Lookahead", 3.0));
            await(() -> compensation.getChannelLatencySamples(0) == 144);
        }
    }

    @Test
    void returnBusBypassRemovalAndChannelReorderRetainCurrentOwnership() throws Exception {
        var mixer = new Mixer();
        try (var compensation = mixer.getDelayCompensation()) {
            var dry = new MixerChannel("Dry");
            var wet = new MixerChannel("Limited");
            var limiter = new TruePeakLimiterProcessor(1, 48_000);
            wet.addInsert(new InsertSlot("Limiter", limiter));
            mixer.addChannel(dry);
            mixer.addChannel(wet);
            mixer.moveChannel(1, 0);
            assertThat(compensation.getChannelLatencySamples(0)).isEqualTo(240);
            assertThat(compensation.getChannelCompensationSamples(1)).isEqualTo(240);

            limiter.setBypass(true);
            await(() -> compensation.getMaxLatencySamples() == 0);
            limiter.setBypass(false);
            await(() -> compensation.getMaxLatencySamples() == 240);
            wet.setInsertBypassed(0, true);
            assertThat(compensation.getMaxLatencySamples()).isZero();

            var returnLimiter = new TruePeakLimiterProcessor(1, 48_000);
            mixer.getAuxBus().addInsert(new InsertSlot("Return limiter", returnLimiter));
            returnLimiter.setLookaheadMs(8);
            await(() -> compensation.getReturnBusLatencySamples(0) == 384);
            assertThat(compensation.getChannelCompensationSamples(1)).isEqualTo(384);
            mixer.getAuxBus().removeInsert(0);
            wet.removeInsert(0);
            returnLimiter.setLookaheadMs(10);
            limiter.setLookaheadMs(10);
            compensation.refreshLatencies();
            assertThat(compensation.getMaxLatencySamples()).isZero();
        }
    }

    @Test
    void unchangedLatencyPreservesBufferedAudioAcrossWatcherPolls() throws Exception {
        var mixer = new Mixer();
        try (var compensation = mixer.getDelayCompensation()) {
            var dry = new MixerChannel("Dry");
            var wet = new MixerChannel("Limited");
            wet.addInsert(new InsertSlot("Limiter", new TruePeakLimiterProcessor(1, 48_000)));
            mixer.addChannel(dry);
            mixer.addChannel(wet);
            float[][] impulse = {new float[240]};
            impulse[0][0] = 0.1f;
            compensation.applyToChannel(0, impulse, 240);
            Thread.sleep(30);
            float[][] next = {new float[1]};
            compensation.applyToChannel(0, next, 1);
            assertThat(next[0][0]).isEqualTo(0.1f);
        }
    }

    @Test
    void preparingPlaybackClearsBufferedAudioEvenWhenLatencyAndDimensionsAreUnchanged() {
        var mixer = new Mixer();
        try (var compensation = mixer.getDelayCompensation()) {
            var dry = new MixerChannel("Dry");
            var wet = new MixerChannel("Limited");
            wet.addInsert(new InsertSlot("Limiter", new TruePeakLimiterProcessor(1, 48_000)));
            mixer.addChannel(dry);
            mixer.addChannel(wet);
            mixer.prepareForPlayback(1, 64);
            float[][] impulse = {new float[240]};
            impulse[0][0] = 0.1f;
            compensation.applyToChannel(0, impulse, 240);
            mixer.prepareForPlayback(1, 64);
            float[][] next = {{0}};
            compensation.applyToChannel(0, next, 1);
            assertThat(next[0][0]).isZero();
        }
    }

    @Test
    void changingOneLatencyPreservesUnchangedChannelsAndNeverPollsStaticNativeGetters() throws Exception {
        var mixer = new Mixer();
        var unexpectedReads = new AtomicInteger();
        Thread controlThread = Thread.currentThread();
        AudioProcessor fixed = new AudioProcessor() {
            @Override public int getLatencySamples() {
                if (Thread.currentThread() != controlThread) unexpectedReads.incrementAndGet();
                return 480;
            }
            @Override public void process(float[][] input, float[][] output, int frames) { }
            @Override public void reset() { }
            @Override public int getInputChannelCount() { return 1; }
            @Override public int getOutputChannelCount() { return 1; }
        };
        try (var compensation = mixer.getDelayCompensation()) {
            var changing = new MixerChannel("Changing");
            var largest = new MixerChannel("Largest");
            var dry = new MixerChannel("Dry");
            var limiter = new TruePeakLimiterProcessor(1, 48_000);
            changing.addInsert(new InsertSlot("Limiter", limiter));
            largest.addInsert(new InsertSlot("Native", fixed));
            mixer.addChannel(changing);
            mixer.addChannel(largest);
            mixer.addChannel(dry);
            float[][] impulse = {new float[480]};
            impulse[0][0] = 0.25f;
            compensation.applyToChannel(2, impulse, 480);
            limiter.setLookaheadMs(7);
            await(() -> compensation.getChannelLatencySamples(0) == 336);
            float[][] next = {{0}};
            compensation.applyToChannel(2, next, 1);
            assertThat(next[0][0]).isEqualTo(0.25f);
            assertThat(unexpectedReads).hasValue(0);
        }
    }

    @Test
    void offlineAutomationRunsOncePerBlockAndRefreshesCompensationBeforeProcessing() {
        var mixer = new Mixer();
        try (var compensation = mixer.getDelayCompensation()) {
            var channel = new MixerChannel("Automated");
            var processor = new CountingLatencyProcessor(compensation);
            channel.addInsert(new InsertSlot("Latency", processor));
            mixer.addChannel(channel);
            mixer.prepareForPlayback(1, 64);
            var track = new Track("Audio", TrackType.AUDIO);
            var target = mixer.getReflectiveParameterBinder().getAutomatablePluginParameters(channel).getFirst();
            track.getAutomationData().getOrCreatePluginLane(target).addPoint(new AutomationPoint(0, 48));
            var transport = new Transport();
            transport.play();
            var pipeline = new RenderPipeline(new AudioFormat(48_000, 1, 16, 64), 1, 64);
            var master = new EffectsChain();
            master.allocateIntermediateBuffers(1, 64);
            pipeline.renderOffline(transport, mixer, List.of(track), null, master, new float[1][128], 128, 64);
            assertThat(processor.setterCalls).isEqualTo(2);
            assertThat(processor.processCalls).isEqualTo(2);
            assertThat(compensation.getChannelLatencySamples(0)).isEqualTo(48);

            transport.stop();
            pipeline.renderOffline(transport, mixer, List.of(track), null, master, new float[1][64], 64, 64);
            assertThat(processor.setterCalls).isEqualTo(2);
        }
    }

    @Test
    void closingCompensationStopsReadingDynamicProcessors() throws Exception {
        var mixer = new Mixer();
        var compensation = mixer.getDelayCompensation();
        var processor = new CountingLatencyProcessor(compensation);
        var channel = new MixerChannel("Retired");
        channel.addInsert(new InsertSlot("Latency", processor));
        mixer.addChannel(channel);
        compensation.close();
        int reads = processor.latencyReads.get();
        processor.setDelay(100);
        Thread.sleep(30);
        assertThat(processor.latencyReads).hasValue(reads);
    }

    public static final class CountingLatencyProcessor implements DynamicLatencyProcessor {
        private final PluginDelayCompensation compensation;
        private final AtomicInteger latencyReads = new AtomicInteger();
        private volatile int latency;
        private int setterCalls;
        private int processCalls;

        CountingLatencyProcessor(PluginDelayCompensation compensation) { this.compensation = compensation; }
        @ProcessorParam(id = 0, name = "Delay", min = 0, max = 500, defaultValue = 0)
        public double getDelay() { return latency; }
        public void setDelay(double value) { latency = (int) value; setterCalls++; }
        @Override public int getLatencySamples() { latencyReads.incrementAndGet(); return latency; }
        @Override public void process(float[][] input, float[][] output, int frames) {
            assertThat(compensation.getChannelLatencySamples(0)).isEqualTo(latency);
            processCalls++;
            System.arraycopy(input[0], 0, output[0], 0, frames);
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
    }

    private static void assertAlignedImpulse(TruePeakLimiterProcessor limiter,
                                             PluginDelayCompensation compensation, int latency) {
        limiter.reset();
        compensation.reset();
        float[][] input = {new float[latency + 2]};
        input[0][0] = 0.1f;
        float[][] output = {new float[input[0].length]};
        limiter.process(input, output, input[0].length);
        compensation.applyToChannel(0, input, input[0].length);
        assertThat(limiter.getLatencySamples()).isEqualTo(latency);
        assertThat(output[0]).containsExactly(input[0]);
        assertThat(output[0][latency]).isPositive();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
