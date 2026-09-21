package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.eq.MatchEqProcessor;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(10)
class PreparedPluginSignalPathTest {
    @Test
    void offlinePreparationInstallsMatchStateAndRefreshesTheExistingLatencySnapshot() {
        var plugin = new MatchEqPlugin();
        plugin.initialize(context());
        var mixer = new Mixer();
        try (var compensation = mixer.getDelayCompensation()) {
            var dry = new MixerChannel("Dry");
            var wet = new MixerChannel("Match");
            var processor = plugin.getProcessor();
            double[] source = new double[processor.getFftSize().value() / 2 + 1];
            double[] reference = new double[source.length];
            Arrays.fill(reference, 6);
            processor.setSourceSpectrum(source);
            processor.setReferenceSpectrum(reference);
            processor.updateMatch();
            var slot = new InsertSlot("Match", plugin.asAudioProcessor().orElseThrow(), null, plugin);
            wet.addInsert(slot);
            mixer.addChannel(dry);
            mixer.addChannel(wet);
            assertThat(compensation.getChannelCompensationSamples(0)).isZero();

            slot.getParameterStore().writeFromUiById(2, 0.25);
            slot.getParameterStore().writeFromUiById(3, MatchEqProcessor.PhaseMode.LINEAR_PHASE.ordinal());
            slot.prepareParametersForOfflineRendering();

            assertThat(plugin.getProcessor().getAmount()).isEqualTo(0.25);
            assertThat(plugin.getProcessor().getPhaseMode()).isEqualTo(MatchEqProcessor.PhaseMode.LINEAR_PHASE);
            compensation.refreshLatencies();
            assertThat(compensation.getChannelCompensationSamples(0)).isEqualTo(1023);

            slot.getParameterStore().writeFromUiById(3, MatchEqProcessor.PhaseMode.MINIMUM_PHASE.ordinal());
            wet.getEffectsChain().processOffline(new float[2][512], new float[2][512], 512);
            compensation.refreshLatencies();
            assertThat(compensation.getChannelCompensationSamples(0)).isZero();
        } finally {
            plugin.dispose();
        }
    }

    @Test
    void acousticReverbPreparesBeforeOfflineProcessingAndRepeatedAutomationKeepsProgressing() throws Exception {
        var plugin = new AcousticReverbPlugin();
        plugin.initialize(context());
        try {
            var original = plugin.getProcessor();
            var signalPath = plugin.asAudioProcessor().orElseThrow();
            var channel = new MixerChannel("Room");
            var slot = new InsertSlot("Room", signalPath, null, plugin);
            channel.addInsert(slot);
            slot.getParameterStore().writeFromUiById(1, 2.0);
            slot.getParameterStore().writeFromUiById(2, 0.75);
            channel.getEffectsChain().processOffline(new float[2][512], new float[2][512], 512);
            assertThat(plugin.getProcessor()).isNotSameAs(original);
            assertThat(plugin.getProcessor().getMix()).isEqualTo(0.75);

            var previous = plugin.getProcessor();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                plugin.setAutomatableParameter(1, 3.0);
                slot.drainParametersToAudio();
                if (plugin.getProcessor() != previous) break;
                Thread.sleep(5);
            } while (System.nanoTime() < deadline);
            assertThat(plugin.getProcessor()).isNotSameAs(previous);
            assertThat(plugin.getProcessor().getMix()).isEqualTo(0.75);
            assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(signalPath);
        } finally {
            plugin.dispose();
        }
    }

    private static PluginContext context() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 48_000; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) { }
        };
    }
}
