package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.audio.PluginDelayCompensation;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.plugin.PluginSourceKind;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An offline insert is a unity pass-through at its chain position, whatever
 * its bypass flag, reporting no latency and never an instrument (story 334,
 * studio ruling E1).
 */
class OfflineInsertAudioTest {

    private static final int FRAMES = 256;

    @Test
    void anOfflineInsertPassesAudioThroughBitForBit() {
        MixerChannel reference = new MixerChannel("Gain, Gain");
        reference.addInsert(gain());
        reference.addInsert(gain());
        float[][] expected = render(reference);

        MixerChannel withOffline = new MixerChannel("Gain, offline, Gain");
        withOffline.addInsert(gain());
        withOffline.addInsert(offline(false));
        withOffline.addInsert(gain());
        assertThat(render(withOffline)).as("saved bypassed=\"false\"").isDeepEqualTo(expected);

        withOffline.setInsertBypassed(1, true);
        assertThat(render(withOffline)).as("saved bypassed=\"true\"").isDeepEqualTo(expected);

        // A snapshot captured while the insert was not bypassed, recalled onto it bypassed.
        withOffline.setInsertBypassed(1, false);
        Mixer mixer = new Mixer();
        try (PluginDelayCompensation ignored = mixer.getDelayCompensation()) {
            mixer.addChannel(withOffline);
            MixerSnapshot snapshot = MixerSnapshot.capture(mixer, "Unbypassed");
            withOffline.setInsertBypassed(1, true);
            snapshot.applyTo(mixer);
            assertThat(withOffline.getInsertSlots().get(1).isBypassed()).as("fixture: the recall cleared the bypass")
                    .isFalse();
            assertThat(render(withOffline)).as("after a snapshot recall").isDeepEqualTo(expected);
        }
    }

    @Test
    void anOfflineStripReportsNoLatency() {
        int latency = 64;
        Mixer mixer = new Mixer();
        try (PluginDelayCompensation compensation = mixer.getDelayCompensation()) {
            MixerChannel offlineStrip = new MixerChannel("Offline");
            MixerChannel delayed = new MixerChannel("Lookahead");
            mixer.addChannel(offlineStrip);
            mixer.addChannel(delayed);
            offlineStrip.addInsert(offline(false));
            delayed.addInsert(new InsertSlot("Lookahead", new LatentProcessor(latency)));

            assertThat(offlineStrip.getEffectsChain().getTotalLatencySamples()).isZero();
            assertThat(compensation.getChannelLatencySamples(0)).isZero();
            assertThat(compensation.getChannelCompensationSamples(0))
                    .as("the offline strip is delayed by the other strip's latency").isEqualTo(latency);
            assertThat(compensation.getChannelCompensationSamples(1)).isZero();
        }
    }

    @Test
    void anInstrumentOfflineInsertLeavesTheStripWithoutAnInstrument() {
        MixerChannel strip = new MixerChannel("Synth");
        strip.addInsert(InsertSlot.ofOffline("Big Synth", 2, new OfflineInsert(
                new OfflineCause.NotInstalled(PluginSourceKind.JAR, "com.example.synth", "3.1"),
                new SavedInsert(attributes("name", "Big Synth", "plugin-id", "com.example.synth"), List.of()),
                false)));

        assertThat(strip.getInsertSlots().getFirst().isInstrument()).isFalse();
        assertThat(strip.hasInstrumentInsert()).as("nothing sounds in place of the missing instrument").isFalse();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Renders one block of a stereo ramp through {@code channel}'s effects chain. */
    private static float[][] render(MixerChannel channel) {
        channel.prepareEffectsChain(2, FRAMES);
        float[][] input = new float[2][FRAMES];
        for (int frame = 0; frame < FRAMES; frame++) {
            input[0][frame] = (frame - FRAMES / 2) / (float) FRAMES;
            input[1][frame] = (float) Math.sin(frame * 0.1);
        }
        float[][] output = new float[2][FRAMES];
        channel.getEffectsChain().process(input, output, FRAMES);
        return output;
    }

    private static InsertSlot gain() {
        return new InsertSlot("Gain", new GainProcessor());
    }

    private static InsertSlot offline(boolean bypassed) {
        InsertSlot slot = InsertSlot.ofOffline("Old FX", 2, new OfflineInsert(
                new OfflineCause.UnknownEffectType("NOT_A_TYPE"),
                new SavedInsert(attributes("bypassed", String.valueOf(bypassed), "effect-type", "NOT_A_TYPE",
                        "name", "Old FX"), List.of()),
                false));
        slot.setBypassed(bypassed);
        return slot;
    }

    private static SequencedMap<String, String> attributes(String... namesAndValues) {
        SequencedMap<String, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            attributes.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return attributes;
    }

    /** A deterministic insert: three quarters of its input. */
    private static final class GainProcessor implements AudioProcessor {
        @Override
        public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < output.length; channel++) {
                for (int frame = 0; frame < frames; frame++) {
                    output[channel][frame] = input[channel][frame] * 0.75f;
                }
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }

    /** A pass-through that reports {@code latency} samples, as a look-ahead plugin does. */
    private static final class LatentProcessor implements AudioProcessor {
        private final int latency;

        LatentProcessor(int latency) {
            this.latency = latency;
        }

        @Override
        public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < output.length; channel++) {
                System.arraycopy(input[channel], 0, output[channel], 0, frames);
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }

        @Override
        public int getLatencySamples() {
            return latency;
        }
    }
}
