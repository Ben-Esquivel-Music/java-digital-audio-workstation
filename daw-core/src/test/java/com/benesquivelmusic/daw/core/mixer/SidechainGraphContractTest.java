package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.metering.MeterFrame;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.plugin.PluginFault;
import com.benesquivelmusic.daw.core.plugin.PluginInvocationSupervisor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SidechainGraphContractTest {
    private static final int FRAMES = 32;
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 1, 24, FRAMES);
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sidechainAndAdjacentProcessorsPublishTheirOwnInputOutputMeters(boolean instrumented) {
        var graph = graph(new DetectionGain());
        var tail = new InsertSlot("Tail", new Gain(2));
        graph.target().addInsert(tail);
        graph.mixer().prepareForPlayback(1, FRAMES);
        var meters = new MeteringTapBus();
        meters.rebind(graph.mixer(), FORMAT, 1);
        var sidechainMeter = meters.attachInsertIo(new MeterTapPoint.InsertIo(graph.slot().getPluginInstanceId()));
        var tailMeter = meters.attachInsertIo(new MeterTapPoint.InsertIo(tail.getPluginInstanceId()));
        try (var enforcer = new TrackCpuBudgetEnforcer(48_000, FRAMES)) {
            var buffers = buffers();
            var taps = meters.snapshot();
            if (instrumented) {
                graph.mixer().mixDownInstrumented(buffers, new float[1][FRAMES], returns(graph.mixer()),
                        FRAMES, List.of(), enforcer, taps);
            } else {
                graph.mixer().mixDown(buffers, new float[1][FRAMES], returns(graph.mixer()), FRAMES, taps);
            }
            assertSamples(buffers[1], 0.2f);
            var frame = new MeterFrame();
            assertThat(sidechainMeter.readInputInto(frame)).isTrue();
            assertThat(frame.peak(0)).isEqualTo(0.5f);
            assertThat(sidechainMeter.readOutputInto(frame)).isTrue();
            assertThat(frame.peak(0)).isCloseTo(0.1f, within(0.00001f));
            assertThat(tailMeter.readInputInto(frame)).isTrue();
            assertThat(frame.peak(0)).isCloseTo(0.1f, within(0.00001f));
            assertThat(tailMeter.readOutputInto(frame)).isTrue();
            assertThat(frame.peak(0)).isCloseTo(0.2f, within(0.00001f));
        } finally { meters.close(); }
    }

    @Test
    void sidechainFaultSilencesOnlyTheFaultedChainAndCanReenableItsExactSlot() throws Exception {
        var processor = new DetectionGain();
        processor.fail = true;
        var graph = graph(processor);
        graph.target().addInsert(new InsertSlot("Tail", new Gain(2)));
        var supervisor = new PluginInvocationSupervisor(directory.resolve("sidechain-faults.log"));
        var reported = new CompletableFuture<PluginFault>();
        supervisor.publisher().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(PluginFault fault) { reported.complete(fault); }
            @Override public void onError(Throwable failure) { reported.completeExceptionally(failure); }
            @Override public void onComplete() { }
        });
        graph.mixer().setPluginSupervisor(supervisor);
        graph.mixer().prepareForPlayback(1, FRAMES);
        try {
            var buffers = buffers();
            var output = new float[1][FRAMES];
            graph.mixer().mixDown(buffers, output, FRAMES);
            assertSamples(buffers[1], 0);
            assertThat(output[0][0]).as("the independent detection channel remains audible").isGreaterThan(0);
            assertThat(graph.slot().isBypassed()).isTrue();
            assertThat(reported.get(3, TimeUnit.SECONDS).slot()).isSameAs(graph.slot());
            assertThat(graph.target().getEffectsChain().size()).isEqualTo(1);
            processor.fail = false;
            supervisor.reenable(graph.slot());
            buffers = buffers();
            graph.mixer().mixDown(buffers, output, FRAMES);
            assertSamples(buffers[1], 0.2f);
            assertThat(graph.slot().isBypassed()).isFalse();
        } finally { supervisor.close(); }
    }

    @Test
    void bypassDuringAnInFlightSidechainBlockStillCopiesTheFinalProcessedSignal() throws Exception {
        var gate = new BlockingDetectionGain();
        var graph = graph(gate);
        var tailProcessor = new Gain(2);
        graph.target().addInsert(new InsertSlot("Tail", tailProcessor));
        graph.mixer().prepareForPlayback(1, FRAMES);
        var buffers = buffers();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var render = workers.submit(() -> graph.mixer().mixDown(buffers, new float[1][FRAMES], FRAMES));
            try {
                assertThat(gate.entered.await(3, TimeUnit.SECONDS)).isTrue();
                graph.target().setInsertBypassed(1, true);
            } finally { gate.release.countDown(); }
            render.get(3, TimeUnit.SECONDS);
        }
        assertSamples(buffers[1], 0.1f);
        assertThat(tailProcessor.calls.get()).isZero();
    }

    @Test
    void channelRetirementWaitsForItsInFlightSidechainCallback() throws Exception {
        var gate = new BlockingDetectionGain();
        var graph = graph(gate);
        var disposals = new AtomicInteger();
        graph.slot().setDisposal(disposals::incrementAndGet);
        graph.mixer().prepareForPlayback(1, FRAMES);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var render = workers.submit(() -> graph.mixer().mixDown(buffers(), new float[1][FRAMES], FRAMES));
            CompletableFuture<Void> retired;
            try {
                assertThat(gate.entered.await(3, TimeUnit.SECONDS)).isTrue();
                retired = graph.target().disposeInsertsWhenQuiescent();
                assertThat(retired).isNotDone();
                assertThat(disposals.get()).isZero();
            } finally { gate.release.countDown(); }
            render.get(3, TimeUnit.SECONDS);
            retired.get(3, TimeUnit.SECONDS);
            assertThat(disposals.get()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void returnBusSidechainUsesDetectionInputAndPublishesItsMeters(MixPrecision precision) {
        var graph = graph(new DetectionGain());
        graph.target().removeInsert(graph.slot());
        var bus = graph.mixer().getReturnBuses().getFirst();
        bus.addInsert(graph.slot());
        graph.target().addSend(new Send(bus, 1, SendTap.PRE_FADER));
        graph.mixer().setMixPrecision(precision);
        graph.mixer().prepareForPlayback(1, FRAMES);
        var meters = new MeteringTapBus();
        meters.rebind(graph.mixer(), FORMAT, 1);
        var subscription = meters.attachInsertIo(new MeterTapPoint.InsertIo(graph.slot().getPluginInstanceId()));
        try {
            var returnBuffers = returns(graph.mixer());
            graph.mixer().mixDown(buffers(), new float[1][FRAMES], returnBuffers, FRAMES, meters.snapshot());
            assertSamples(returnBuffers[0], 0.1f);
            var frame = new MeterFrame();
            assertThat(subscription.readInputInto(frame)).isTrue();
            assertThat(frame.peak(0)).isEqualTo(0.5f);
            assertThat(subscription.readOutputInto(frame)).isTrue();
            assertThat(frame.peak(0)).isCloseTo(0.1f, within(0.00001f));
        } finally { meters.close(); }
    }

    private static Graph graph(DetectionGain processor) {
        var mixer = new Mixer();
        var source = new MixerChannel("Detection");
        var target = new MixerChannel("Target");
        mixer.addChannel(source);
        mixer.addChannel(target);
        var slot = new InsertSlot("Sidechain", processor);
        slot.setSidechainSource(source);
        target.addInsert(slot);
        return new Graph(mixer, target, slot);
    }

    private static float[][][] buffers() {
        var buffers = new float[2][1][FRAMES];
        Arrays.fill(buffers[0][0], 0.2f);
        Arrays.fill(buffers[1][0], 0.5f);
        return buffers;
    }

    private static float[][][] returns(Mixer mixer) { return new float[mixer.getReturnBusCount()][1][FRAMES]; }
    private static void assertSamples(float[][] buffer, float expected) {
        for (float sample : buffer[0]) assertThat(sample).isCloseTo(expected, within(0.00001f));
    }
    private record Graph(Mixer mixer, MixerChannel target, InsertSlot slot) { }

    private static class Gain implements AudioProcessor {
        final AtomicInteger calls = new AtomicInteger();
        private final float gain;
        Gain(float gain) { this.gain = gain; }
        @Override public void process(float[][] input, float[][] output, int frames) {
            calls.incrementAndGet();
            for (int lane = 0; lane < output.length; lane++) {
                for (int frame = 0; frame < frames; frame++) output[lane][frame] = input[lane][frame] * gain;
            }
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
    }

    private static class DetectionGain extends Gain implements SidechainAwareProcessor {
        volatile boolean fail;
        DetectionGain() { super(1); }
        @Override public void processSidechain(float[][] input, float[][] sidechain, float[][] output, int frames) {
            if (fail) throw new LinkageError("fault in sidechain callback");
            for (int lane = 0; lane < output.length; lane++) {
                for (int frame = 0; frame < frames; frame++) {
                    output[lane][frame] = input[lane][frame] * sidechain[lane][frame];
                }
            }
        }
    }

    private static final class BlockingDetectionGain extends DetectionGain {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override public void processSidechain(float[][] input, float[][] sidechain, float[][] output, int frames) {
            entered.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("render was never released");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            super.processSidechain(input, sidechain, output, frames);
        }
    }
}
