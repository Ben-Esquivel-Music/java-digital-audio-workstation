package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PluginDelayCompensationTest {

    @Test
    void shouldReportZeroLatencyWithNoChannels() {
        var pdc = new PluginDelayCompensation();
        pdc.recalculate(List.of(), List.of(), 2);

        assertThat(pdc.getMaxLatencySamples()).isZero();
    }

    @Test
    void shouldReportZeroLatencyWhenNoProcessorsHaveLatency() {
        var pdc = new PluginDelayCompensation();
        var ch1 = new MixerChannel("Ch1");
        ch1.addInsert(new InsertSlot("Pass", new PassthroughProcessor()));

        pdc.recalculate(List.of(ch1), List.of(), 1);

        assertThat(pdc.getMaxLatencySamples()).isZero();
        assertThat(pdc.getChannelLatencySamples(0)).isZero();
        assertThat(pdc.getChannelCompensationSamples(0)).isZero();
    }

    @Test
    void shouldCalculateMaxLatencyAcrossChannels() {
        var pdc = new PluginDelayCompensation();
        var ch1 = new MixerChannel("Ch1");
        var ch2 = new MixerChannel("Ch2");
        ch1.addInsert(new InsertSlot("Latent", new LatencyProcessor(100)));
        ch2.addInsert(new InsertSlot("Latent", new LatencyProcessor(200)));

        pdc.recalculate(List.of(ch1, ch2), List.of(), 1);

        assertThat(pdc.getMaxLatencySamples()).isEqualTo(200);
    }

    @Test
    void channelWithNoInsertsShouldGetMaximumDelay() {
        var pdc = new PluginDelayCompensation();
        var ch1 = new MixerChannel("NoInserts");
        var ch2 = new MixerChannel("HasInserts");
        ch2.addInsert(new InsertSlot("Latent", new LatencyProcessor(256)));

        pdc.recalculate(List.of(ch1, ch2), List.of(), 1);

        // Channel with no inserts has 0 latency, so it gets maximum compensation
        assertThat(pdc.getChannelLatencySamples(0)).isZero();
        assertThat(pdc.getChannelCompensationSamples(0)).isEqualTo(256);
    }

    @Test
    void channelWithHighestLatencyShouldGetZeroDelay() {
        var pdc = new PluginDelayCompensation();
        var ch1 = new MixerChannel("Low");
        var ch2 = new MixerChannel("High");
        ch1.addInsert(new InsertSlot("Low", new LatencyProcessor(50)));
        ch2.addInsert(new InsertSlot("High", new LatencyProcessor(300)));

        pdc.recalculate(List.of(ch1, ch2), List.of(), 1);

        assertThat(pdc.getChannelLatencySamples(1)).isEqualTo(300);
        assertThat(pdc.getChannelCompensationSamples(1)).isZero();

        // Low latency channel gets compensation
        assertThat(pdc.getChannelLatencySamples(0)).isEqualTo(50);
        assertThat(pdc.getChannelCompensationSamples(0)).isEqualTo(250);
    }

    @Test
    void compensationShouldUpdateWhenInsertsChange() {
        var pdc = new PluginDelayCompensation();
        var ch1 = new MixerChannel("Ch1");
        var ch2 = new MixerChannel("Ch2");
        ch1.addInsert(new InsertSlot("Latent", new LatencyProcessor(100)));

        pdc.recalculate(List.of(ch1, ch2), List.of(), 1);

        assertThat(pdc.getMaxLatencySamples()).isEqualTo(100);
        assertThat(pdc.getChannelCompensationSamples(0)).isZero();
        assertThat(pdc.getChannelCompensationSamples(1)).isEqualTo(100);

        // Add a higher-latency processor to ch2
        ch2.addInsert(new InsertSlot("Higher", new LatencyProcessor(500)));
        pdc.recalculate(List.of(ch1, ch2), List.of(), 1);

        assertThat(pdc.getMaxLatencySamples()).isEqualTo(500);
        assertThat(pdc.getChannelCompensationSamples(0)).isEqualTo(400);
        assertThat(pdc.getChannelCompensationSamples(1)).isZero();
    }

    @Test
    void shouldSumLatencyAcrossMultipleProcessors() {
        var pdc = new PluginDelayCompensation();
        var ch = new MixerChannel("Ch1");
        ch.addInsert(new InsertSlot("A", new LatencyProcessor(100)));
        ch.addInsert(new InsertSlot("B", new LatencyProcessor(50)));

        pdc.recalculate(List.of(ch), List.of(), 1);

        assertThat(pdc.getChannelLatencySamples(0)).isEqualTo(150);
    }

    @Test
    void shouldIncludeReturnBusLatencyInMaxCalculation() {
        var pdc = new PluginDelayCompensation();
        var ch = new MixerChannel("Ch1");
        var returnBus = new MixerChannel("Return");
        returnBus.addInsert(new InsertSlot("Reverb", new LatencyProcessor(512)));

        pdc.recalculate(List.of(ch), List.of(returnBus), 1);

        assertThat(pdc.getMaxLatencySamples()).isEqualTo(512);
        assertThat(pdc.getChannelCompensationSamples(0)).isEqualTo(512);
        assertThat(pdc.getReturnBusLatencySamples(0)).isEqualTo(512);
    }

    @Test
    void shouldApplyCompensationDelayToChannel() {
        var pdc = new PluginDelayCompensation();
        var ch1 = new MixerChannel("NoInserts");
        var ch2 = new MixerChannel("WithInserts");
        ch2.addInsert(new InsertSlot("Latent", new LatencyProcessor(2)));

        pdc.recalculate(List.of(ch1, ch2), List.of(), 1);

        // ch1 should be delayed by 2 samples (compensation)
        float[][] buffer = {{1.0f, 2.0f, 3.0f, 4.0f}};
        pdc.applyToChannel(0, buffer, 4);
        assertThat(buffer[0]).containsExactly(0.0f, 0.0f, 1.0f, 2.0f);

        // ch2 should NOT be delayed (it's the highest latency)
        float[][] buffer2 = {{1.0f, 2.0f, 3.0f, 4.0f}};
        pdc.applyToChannel(1, buffer2, 4);
        assertThat(buffer2[0]).containsExactly(1.0f, 2.0f, 3.0f, 4.0f);
    }

    @Test
    void shouldSafelyHandleOutOfRangeIndices() {
        var pdc = new PluginDelayCompensation();
        pdc.recalculate(List.of(), List.of(), 1);

        assertThat(pdc.getChannelLatencySamples(999)).isZero();
        assertThat(pdc.getChannelCompensationSamples(999)).isZero();
        assertThat(pdc.getReturnBusLatencySamples(999)).isZero();

        // Should not throw when applied to out-of-range index
        float[][] buffer = {{1.0f}};
        pdc.applyToChannel(999, buffer, 1);
        assertThat(buffer[0]).containsExactly(1.0f);
    }

    @Test
    void shouldResetAllDelayBuffers() {
        var pdc = new PluginDelayCompensation();
        var ch = new MixerChannel("Ch");
        var chLatent = new MixerChannel("Latent");
        chLatent.addInsert(new InsertSlot("Latent", new LatencyProcessor(2)));

        pdc.recalculate(List.of(ch, chLatent), List.of(), 1);

        // Prime the delay with data
        float[][] buffer = {{10.0f, 20.0f}};
        pdc.applyToChannel(0, buffer, 2);
        assertThat(buffer[0]).containsExactly(0.0f, 0.0f);

        // Reset should clear the buffered data
        pdc.reset();

        float[][] buffer2 = {{30.0f, 40.0f}};
        pdc.applyToChannel(0, buffer2, 2);
        // After reset, the delay outputs silence again (not the primed data)
        assertThat(buffer2[0]).containsExactly(0.0f, 0.0f);
    }

    // --- Test processors ---

    private static class PassthroughProcessor implements AudioProcessor {
        @Override
        public void process(float[][] in, float[][] out, int frames) {
            for (int ch = 0; ch < in.length; ch++) {
                System.arraycopy(in[ch], 0, out[ch], 0, frames);
            }
        }

        @Override public void reset() {}
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
    }

    private record LatencyProcessor(int latency) implements AudioProcessor {
        @Override
        public void process(float[][] in, float[][] out, int frames) {
            for (int ch = 0; ch < in.length; ch++) {
                System.arraycopy(in[ch], 0, out[ch], 0, frames);
            }
        }

        @Override public void reset() {}
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }

        @Override
        public int getLatencySamples() {
            return latency;
        }
    }
}
