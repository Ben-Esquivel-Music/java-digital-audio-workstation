package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.PluginParameterTarget;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

@Timeout(10)
public class AutomationRetirementTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retirementWaitsForAutomationAndRejectsCachedBindingsAfterDisposal(boolean pluginAutomation) throws Exception {
        var format = new AudioFormat(48_000, 2, 24, 64);
        var engine = new AudioEngine(format);
        var mixer = new Mixer();
        var channel = new MixerChannel("Automated");
        var processor = new BlockingProcessor();
        var slot = new InsertSlot("Blocking", processor);
        channel.addInsert(slot);
        mixer.addChannel(channel);
        var track = new Track("Automated", TrackType.AUDIO);
        var transport = new Transport();
        engine.setGraph(transport, mixer, List.of(track));
        var binder = mixer.getReflectiveParameterBinder();
        var reflectedTarget = binder.getAutomatablePluginParameters(channel).getFirst();
        var target = pluginAutomation ? new PluginParameterTarget(
                "test.blocking", 0, "Gain", 0, 1, 1, "") : reflectedTarget;
        track.getAutomationData().getOrCreatePluginLane(target).addPoint(new AutomationPoint(0, 0.25));
        engine.start();
        transport.play();
        CompletableFuture<Void> rendering = CompletableFuture.runAsync(
                () -> engine.processBlock(null, new float[2][64], 64));
        try {
            assertThat(processor.entered.await(5, TimeUnit.SECONDS)).isTrue();
            var retirement = channel.disposeInsertsWhenQuiescent();
            assertThatThrownBy(() -> retirement.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(processor.closed).isFalse();
            processor.release.countDown();
            rendering.get(5, TimeUnit.SECONDS);
            retirement.get(5, TimeUnit.SECONDS);
            assertThat(processor.closedDuringSetter).isFalse();
            assertThat(processor.closed).isTrue();

            int completedSetters = processor.setterCalls;
            var staleAutomation = new AutomationData();
            staleAutomation.getOrCreatePluginLane(reflectedTarget).addPoint(new AutomationPoint(0, 0.5));
            binder.apply(channel, staleAutomation, 0);
            engine.processBlock(null, new float[2][64], 64);
            assertThat(processor.setterCalls).isEqualTo(completedSetters);
        } finally {
            processor.release.countDown();
            rendering.get(5, TimeUnit.SECONDS);
            engine.stop();
            mixer.getDelayCompensation().close();
        }
    }

    public static final class BlockingProcessor implements AudioProcessor, DawPlugin {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean closed;
        volatile boolean closedDuringSetter;
        int setterCalls;
        @ProcessorParam(id = 0, name = "Gain", min = 0, max = 1, defaultValue = 1)
        public double getGain() { return 1; }
        public void setGain(double value) {
            setterCalls++;
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("setter timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            closedDuringSetter = closed;
        }
        @Override public void setAutomatableParameter(int id, double value) { setGain(value); }
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < output.length; channel++) {
                System.arraycopy(input[channel], 0, output[channel], 0, frames);
            }
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("test.blocking", "Blocking", "1", "Test", PluginType.EFFECT);
        }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { closed = true; }
    }
}
