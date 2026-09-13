package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.eq.MatchEqProcessor;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

final class MatchEqLiveParameterTest {
    @Test
    void amountRecallRebuildsOffRenderAndPreservesLearnedSpectra() throws Exception {
        var plugin = initializedPlugin();
        var channel = new MixerChannel("Match");
        try {
            MatchEqProcessor initial = plugin.getProcessor();
            double[] source = new double[initial.getFftSize().value() / 2 + 1];
            double[] reference = new double[source.length];
            Arrays.fill(source, 1.0);
            Arrays.fill(reference, 4.0);
            initial.setSourceSpectrum(source);
            initial.setReferenceSpectrum(reference);
            initial.updateMatch();
            var slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
            channel.addInsert(slot);
            var stable = slot.getProcessor();
            float[][] input = tone();
            float[][] output = {new float[512], new float[512]};
            channel.getEffectsChain().process(input, output, 512);
            double matchedEnergy = energy(output[0]);
            slot.getParameterStore().writeFromUiById(2, 0.0);
            awaitApplied(() -> plugin.getProcessor().getAmount() == 0.0,
                    () -> channel.getEffectsChain().process(input, output, 512));
            assertThat(slot.getProcessor()).isSameAs(stable);
            assertThat(plugin.getProcessor()).isNotSameAs(initial);
            assertThat(plugin.getProcessor().getSourceSpectrum()).containsExactly(source);
            assertThat(plugin.getProcessor().getReferenceSpectrum()).containsExactly(reference);
            assertThat(energy(output[0])).isLessThan(matchedEnergy);
            for (int frame = 0; frame < 512; frame++) {
                assertThat(output[0][frame]).isCloseTo(input[0][frame], within(0.00001f));
            }
        } finally {
            channel.disposeInsertsWhenQuiescent().get(5, TimeUnit.SECONDS);
            plugin.dispose();
        }
    }

    @Test
    void fftSmoothingAndPhaseRequestsReplaceTheFilterWithoutLosingCapturedSpectra() throws Exception {
        var plugin = initializedPlugin();
        var channel = new MixerChannel("Match");
        try {
            double[] captured = new double[1025];
            Arrays.fill(captured, 1.0);
            plugin.getProcessor().setSourceSpectrum(captured);
            plugin.getProcessor().setReferenceSpectrum(captured);
            var slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
            channel.addInsert(slot);
            var store = slot.getParameterStore();
            store.writeFromUiById(0, MatchEqProcessor.FftSize.SIZE_4096.ordinal());
            store.writeFromUiById(1, MatchEqProcessor.Smoothing.SIXTH_OCTAVE.ordinal());
            store.writeFromUiById(3, MatchEqProcessor.PhaseMode.LINEAR_PHASE.ordinal());
            float[][] input = tone();
            float[][] output = {new float[512], new float[512]};
            awaitApplied(() -> plugin.getProcessor().getFftSize() == MatchEqProcessor.FftSize.SIZE_4096
                            && plugin.getProcessor().getSmoothing() == MatchEqProcessor.Smoothing.SIXTH_OCTAVE
                            && plugin.getProcessor().getPhaseMode() == MatchEqProcessor.PhaseMode.LINEAR_PHASE,
                    () -> channel.getEffectsChain().process(input, output, 512));
            assertThat(plugin.getProcessor().getSourceSpectrum()).hasSize(2049);
            assertThat(plugin.getProcessor().getReferenceSpectrum()).hasSize(2049);
            assertThat(plugin.getProcessor().isMatchActive()).isTrue();
            assertThat(slot.getProcessor().getLatencySamples()).isPositive()
                    .isEqualTo(plugin.getProcessor().getLatencySamples());
        } finally {
            channel.disposeInsertsWhenQuiescent().get(5, TimeUnit.SECONDS);
            plugin.dispose();
        }
    }

    private static MatchEqPlugin initializedPlugin() {
        var plugin = new MatchEqPlugin();
        plugin.initialize(new PluginContext() {
            @Override public double getSampleRate() { return 48_000; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) { }
        });
        return plugin;
    }

    private static void awaitApplied(BooleanSupplier condition, Runnable render) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            render.run();
            if (condition.getAsBoolean()) return;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        assertThat(condition.getAsBoolean()).as("prepared filter becomes live on a render boundary").isTrue();
    }

    private static float[][] tone() {
        float[][] input = {new float[512], new float[512]};
        for (int frame = 0; frame < 512; frame++) {
            input[0][frame] = input[1][frame] = (float) (0.01 * Math.sin(2 * Math.PI * 750 * frame / 48_000));
        }
        return input;
    }

    private static double energy(float[] audio) {
        double energy = 0;
        for (float sample : audio) energy += sample * sample;
        return energy;
    }
}
