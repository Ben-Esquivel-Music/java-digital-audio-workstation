package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.audio.EffectsChain;
import com.benesquivelmusic.daw.core.audio.PluginDelayCompensation;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.plugin.PluginInvocationSupervisor;
import com.benesquivelmusic.daw.core.plugin.PluginSourceKind;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An offline insert is a unity pass-through at its chain position, whatever
 * its bypass flag, reporting no latency and never an instrument (story 334,
 * studio ruling E1) — in both mix precisions, allocating nothing on the
 * 64-bit bus.
 */
class OfflineInsertAudioTest {

    private static final int FRAMES = 256;
    private static final int MASTER_BLOCKS = 16;
    private static final int BATCH_BLOCKS = 10_000;
    private static final int QUIET_BATCH_ATTEMPTS = 5;

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
    void anOfflineInsertOnTheMasterLeavesTheDoubleMixBusBitForBit() {
        float[][] expected = renderThroughTheMaster(List.of());

        assertThat(renderThroughTheMaster(List.of(offline(false)))).as("saved bypassed=\"false\"")
                .isDeepEqualTo(expected);
        assertThat(renderThroughTheMaster(List.of(offline(true)))).as("saved bypassed=\"true\"")
                .isDeepEqualTo(expected);
    }

    @Test
    void anOfflineInsertAllocatesNothingOnTheDoubleMixBus(@TempDir Path directory) {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        PluginInvocationSupervisor supervisor = new PluginInvocationSupervisor(directory.resolve("faults.log"));
        try {
            MixerChannel master = new MixerChannel("Master");
            master.setPluginSupervisor(supervisor);
            master.addInsert(offline(false));
            master.prepareEffectsChain(2, FRAMES);
            EffectsChain chain = master.getEffectsChain();
            assertThat(chain.isEmpty()).as("fixture: the unbypassed slot runs, supervised").isFalse();
            double[][] bus = new double[2][FRAMES];
            for (int frame = 0; frame < FRAMES; frame++) {
                bus[0][frame] = Math.sin(frame * 0.1) / 3.0;
                bus[1][frame] = (frame - FRAMES / 2) / (FRAMES * Math.PI);
            }
            // Warm the counter calls and the exact measured loop.
            for (int batch = 0; batch < 5; batch++) {
                allocatedByABatch(bean, chain, bus);
            }
            // The slot must allocate nothing per block, so a steady allocation fails every batch (the
            // SDK's narrowing adapter allocates about 4 KB per block here), while a one-off JVM event
            // lands in one batch, not every batch: this thread can see one even with the slot
            // bypassed, when no slot code runs. The exact-zero bound is kept, not widened.
            long[] deltas = new long[QUIET_BATCH_ATTEMPTS];
            Arrays.fill(deltas, -1L);
            for (int attempt = 0; attempt < QUIET_BATCH_ATTEMPTS; attempt++) {
                deltas[attempt] = allocatedByABatch(bean, chain, bus);
                if (deltas[attempt] == 0L) {
                    break;
                }
            }

            assertThat(deltas)
                    .as("bytes allocated per %,d-block batch of the 64-bit bus after warm-up (-1 = not measured)",
                            BATCH_BLOCKS)
                    .contains(0L);
        } finally {
            supervisor.close();
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

    /**
     * Renders {@value #MASTER_BLOCKS} blocks of two strips — a distinct
     * program on each audio channel of each, panned off centre at a
     * non-unity fader, so their sum is not a {@code float} — through
     * {@link Mixer#mixDown} on the default 64-bit bus, with
     * {@code masterInserts} on a master whose fader is 0.8: a bus rounded
     * to {@code float} before the fader would narrow differently.
     */
    private static float[][] renderThroughTheMaster(List<InsertSlot> masterInserts) {
        Mixer mixer = new Mixer();
        try (PluginDelayCompensation ignored = mixer.getDelayCompensation()) {
            assertThat(mixer.getMixPrecision()).as("fixture: the default mix bus").isEqualTo(MixPrecision.DOUBLE_64);
            MixerChannel first = new MixerChannel("First");
            first.setVolume(0.9);
            first.setPan(-0.3);
            MixerChannel second = new MixerChannel("Second");
            second.setVolume(0.7);
            second.setPan(0.45);
            mixer.addChannel(first);
            mixer.addChannel(second);
            mixer.getMasterChannel().setVolume(0.8);
            for (InsertSlot insert : masterInserts) {
                mixer.getMasterChannel().addInsert(insert);
            }
            mixer.prepareForPlayback(2, FRAMES);
            long running = masterInserts.stream().filter(insert -> !insert.isBypassed()).count();
            assertThat(mixer.getMasterChannel().getEffectsChain().getProcessors())
                    .as("fixture: every unbypassed master insert runs").hasSize((int) running);
            float[][] rendered = new float[2][MASTER_BLOCKS * FRAMES];
            float[][] output = new float[2][FRAMES];
            for (int block = 0; block < MASTER_BLOCKS; block++) {
                float[][][] strips = {new float[2][FRAMES], new float[2][FRAMES]};
                for (int frame = 0; frame < FRAMES; frame++) {
                    int n = block * FRAMES + frame;
                    strips[0][0][frame] = ((n % 512) - 256) / 512f;
                    strips[0][1][frame] = (float) Math.sin(n * 0.1);
                    strips[1][0][frame] = (float) (0.6 * Math.sin(n * 0.037 + 1.0));
                    strips[1][1][frame] = (float) (0.5 * Math.cos(n * 0.013));
                }
                mixer.mixDown(strips, output, FRAMES);
                for (int channel = 0; channel < 2; channel++) {
                    System.arraycopy(output[channel], 0, rendered[channel], block * FRAMES, FRAMES);
                }
            }
            return rendered;
        }
    }

    /** The bytes this thread allocates while {@code chain} renders {@value #BATCH_BLOCKS} blocks of {@code bus}. */
    private static long allocatedByABatch(com.sun.management.ThreadMXBean bean, EffectsChain chain,
                                          double[][] bus) {
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int block = 0; block < BATCH_BLOCKS; block++) {
            chain.processDouble(bus, bus, FRAMES);
        }
        return bean.getThreadAllocatedBytes(thread) - before;
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
