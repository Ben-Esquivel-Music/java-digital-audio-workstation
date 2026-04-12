package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.NoiseGateProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for sidechain input routing (user story 091).
 *
 * <p>Verifies:
 * <ol>
 *   <li>Sidechain compression reduces gain based on the sidechain signal</li>
 *   <li>No sidechain falls back to internal detection</li>
 *   <li>Sidechain source changes take effect on the next block</li>
 *   <li>Sidechain gate triggering from an external signal</li>
 *   <li>Mixer routes sidechain buffers during mixDown</li>
 * </ol>
 */
class SidechainRoutingTest {

    private static final double SAMPLE_RATE = 44100.0;
    private static final int BLOCK_SIZE = 512;

    // ── Interface contract ──────────────────────────────────────────────────

    @Test
    void compressorShouldImplementSidechainAwareProcessor() {
        CompressorProcessor comp = new CompressorProcessor(2, SAMPLE_RATE);
        assertThat(comp).isInstanceOf(SidechainAwareProcessor.class);
        assertThat(comp).isInstanceOf(AudioProcessor.class);
    }

    @Test
    void noiseGateShouldImplementSidechainAwareProcessor() {
        NoiseGateProcessor gate = new NoiseGateProcessor(2, SAMPLE_RATE);
        assertThat(gate).isInstanceOf(SidechainAwareProcessor.class);
        assertThat(gate).isInstanceOf(AudioProcessor.class);
    }

    // ── Sidechain compression ──────────────────────────────────────────────

    @Test
    void sidechainCompressionShouldReduceGainBasedOnSidechainSignal() {
        // Set up compressor: low threshold, high ratio, very fast attack
        CompressorProcessor comp = new CompressorProcessor(1, SAMPLE_RATE);
        comp.setThresholdDb(-20.0);
        comp.setRatio(10.0);
        comp.setAttackMs(0.01);
        comp.setKneeDb(0.0);

        // Main input: quiet signal (well below threshold)
        float[][] input = new float[1][4096];
        Arrays.fill(input[0], 0.05f); // ~-26 dB, below -20 dB threshold

        // Sidechain: loud signal (well above threshold) — drives compression
        float[][] sidechain = new float[1][4096];
        Arrays.fill(sidechain[0], 0.9f); // ~-1 dB, well above -20 dB threshold

        float[][] output = new float[1][4096];

        comp.processSidechain(input, sidechain, output, 4096);

        // The loud sidechain drives gain reduction, so the quiet main signal
        // should be attenuated further. After settling, output should be
        // significantly less than input.
        double inputRms = rms(input[0], 2048, 4096);
        double outputRms = rms(output[0], 2048, 4096);
        assertThat(outputRms).isLessThan(inputRms * 0.5);
    }

    @Test
    void noSidechainShouldFallBackToInternalDetection() {
        // Without sidechain, the compressor should use main input for detection.
        // A quiet signal below threshold should pass through uncompressed.
        CompressorProcessor comp = new CompressorProcessor(1, SAMPLE_RATE);
        comp.setThresholdDb(-10.0);
        comp.setRatio(10.0);
        comp.setAttackMs(0.01);
        comp.setKneeDb(0.0);

        float[][] input = new float[1][4096];
        Arrays.fill(input[0], 0.1f); // ~-20 dB, below -10 dB threshold

        float[][] output = new float[1][4096];

        // Use the standard process() method — no sidechain
        comp.process(input, output, 4096);

        // Signal is below threshold — should pass with minimal compression
        double inputRms = rms(input[0], 2048, 4096);
        double outputRms = rms(output[0], 2048, 4096);
        assertThat(outputRms).isCloseTo(inputRms, org.assertj.core.data.Offset.offset(0.02));
    }

    @Test
    void sidechainSourceChangeShouldTakeEffectOnNextBlock() {
        CompressorProcessor comp = new CompressorProcessor(1, SAMPLE_RATE);
        comp.setThresholdDb(-20.0);
        comp.setRatio(10.0);
        comp.setAttackMs(0.01);
        comp.setKneeDb(0.0);

        float[][] input = new float[1][BLOCK_SIZE];
        Arrays.fill(input[0], 0.05f);

        float[][] loudSidechain = new float[1][BLOCK_SIZE];
        Arrays.fill(loudSidechain[0], 0.9f);

        float[][] quietSidechain = new float[1][BLOCK_SIZE];
        Arrays.fill(quietSidechain[0], 0.01f); // Very quiet — below threshold

        float[][] output = new float[1][BLOCK_SIZE];

        // Block 1: loud sidechain — should compress
        comp.processSidechain(input, loudSidechain, output, BLOCK_SIZE);
        double compressedRms = rms(output[0], BLOCK_SIZE / 2, BLOCK_SIZE);

        // Reset for clean comparison
        comp.reset();

        // Block 2: quiet sidechain — should NOT compress
        comp.processSidechain(input, quietSidechain, output, BLOCK_SIZE);
        double uncompressedRms = rms(output[0], BLOCK_SIZE / 2, BLOCK_SIZE);

        // With quiet sidechain, output should be louder (less compression)
        assertThat(uncompressedRms).isGreaterThan(compressedRms);
    }

    // ── Sidechain gating ───────────────────────────────────────────────────

    @Test
    void sidechainGateShouldOpenBasedOnSidechainSignal() {
        NoiseGateProcessor gate = new NoiseGateProcessor(1, SAMPLE_RATE);
        gate.setThresholdDb(-20.0);
        gate.setAttackMs(0.01);
        gate.setHoldMs(0.0);
        gate.setReleaseMs(1.0);
        gate.setRangeDb(-80.0);

        // Main input: constant signal (should pass when gate opens)
        float[][] input = new float[1][4096];
        Arrays.fill(input[0], 0.5f);

        // Sidechain: silent — gate should stay closed
        float[][] silentSidechain = new float[1][4096];
        Arrays.fill(silentSidechain[0], 0.0f);

        float[][] output = new float[1][4096];
        gate.processSidechain(input, silentSidechain, output, 4096);

        // Gate closed — output should be heavily attenuated
        double closedRms = rms(output[0], 2048, 4096);
        assertThat(closedRms).isLessThan(0.01);

        // Now process with a loud sidechain — gate should open
        gate.reset();
        float[][] loudSidechain = new float[1][4096];
        Arrays.fill(loudSidechain[0], 0.5f); // Above -20 dB threshold

        gate.processSidechain(input, loudSidechain, output, 4096);

        double openRms = rms(output[0], 2048, 4096);
        assertThat(openRms).isGreaterThan(0.3);
    }

    // ── InsertSlot sidechain source ────────────────────────────────────────

    @Test
    void insertSlotShouldStoreSidechainSource() {
        CompressorProcessor comp = new CompressorProcessor(2, SAMPLE_RATE);
        InsertSlot slot = new InsertSlot("Compressor", comp, InsertEffectType.COMPRESSOR);

        // Initially null
        assertThat(slot.getSidechainSource()).isNull();

        MixerChannel source = new MixerChannel("Kick");
        slot.setSidechainSource(source);
        assertThat(slot.getSidechainSource()).isSameAs(source);

        // Clear sidechain
        slot.setSidechainSource(null);
        assertThat(slot.getSidechainSource()).isNull();
    }

    // ── Mixer sidechain routing ────────────────────────────────────────────

    @Test
    void mixerShouldRouteSidechainDuringMixDown() {
        Mixer mixer = new Mixer();

        // Channel 0: Kick drum (sidechain source) — loud signal
        MixerChannel kickChannel = new MixerChannel("Kick");
        mixer.addChannel(kickChannel);

        // Channel 1: Bass (target) — has compressor with sidechain from kick
        MixerChannel bassChannel = new MixerChannel("Bass");
        mixer.addChannel(bassChannel);

        CompressorProcessor comp = new CompressorProcessor(1, SAMPLE_RATE);
        comp.setThresholdDb(-20.0);
        comp.setRatio(10.0);
        comp.setAttackMs(0.01);
        comp.setKneeDb(0.0);
        InsertSlot slot = new InsertSlot("Compressor", comp, InsertEffectType.COMPRESSOR);
        slot.setSidechainSource(kickChannel);
        bassChannel.addInsert(slot);

        mixer.prepareForPlayback(1, 4096);

        // Kick: loud signal
        float[][][] channelBuffers = new float[2][1][4096];
        Arrays.fill(channelBuffers[0][0], 0.9f); // kick — loud

        // Bass: quiet signal that should be compressed by kick sidechain
        Arrays.fill(channelBuffers[1][0], 0.05f); // bass — quiet

        float[][] output = new float[1][4096];

        mixer.mixDown(channelBuffers, output, 4096);

        // The bass signal should be compressed (reduced) because the kick
        // signal is loud and drives the compressor's sidechain.
        // Without sidechain, the bass signal (0.05) is below the -20 dB threshold
        // and would pass uncompressed. With sidechain from kick (0.9), the
        // compressor sees above-threshold levels and applies gain reduction.

        // Sum in output = kick + compressed bass.
        // We can verify by running a second mix without sidechain and comparing.
        InsertSlot slot2 = bassChannel.getInsertSlot(0);
        slot2.setSidechainSource(null); // Remove sidechain

        CompressorProcessor comp2 = (CompressorProcessor) slot2.getProcessor();
        comp2.reset();

        float[][][] channelBuffers2 = new float[2][1][4096];
        Arrays.fill(channelBuffers2[0][0], 0.9f);
        Arrays.fill(channelBuffers2[1][0], 0.05f);
        float[][] output2 = new float[1][4096];

        mixer.mixDown(channelBuffers2, output2, 4096);

        // With sidechain: bass is compressed more (lower contribution to mix)
        // Without sidechain: bass passes through (higher contribution)
        // So output with sidechain should have LESS total energy than without
        double withSidechainRms = rms(output[0], 2048, 4096);
        double withoutSidechainRms = rms(output2[0], 2048, 4096);
        assertThat(withSidechainRms).isLessThan(withoutSidechainRms);
    }

    @Test
    void mixerShouldFallBackWhenSidechainSourceNotFound() {
        Mixer mixer = new Mixer();

        MixerChannel channel = new MixerChannel("Bass");
        mixer.addChannel(channel);

        CompressorProcessor comp = new CompressorProcessor(1, SAMPLE_RATE);
        comp.setThresholdDb(0.0); // Very high threshold — no compression expected
        InsertSlot slot = new InsertSlot("Compressor", comp, InsertEffectType.COMPRESSOR);

        // Set a source that is not in the mixer
        MixerChannel orphan = new MixerChannel("Orphan");
        slot.setSidechainSource(orphan);
        channel.addInsert(slot);

        mixer.prepareForPlayback(1, BLOCK_SIZE);

        float[][][] channelBuffers = {{{0.5f}}};
        float[][] output = new float[1][1];

        // Should not throw — falls back to internal detection
        mixer.mixDown(channelBuffers, output, 1);

        // Signal passes through (threshold=0 dB, input is below)
        assertThat(output[0][0]).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.1f));
    }

    @Test
    void mixerMultiBusMixDownShouldRouteSidechain() {
        Mixer mixer = new Mixer();

        MixerChannel kickChannel = new MixerChannel("Kick");
        mixer.addChannel(kickChannel);

        MixerChannel bassChannel = new MixerChannel("Bass");
        mixer.addChannel(bassChannel);

        CompressorProcessor comp = new CompressorProcessor(1, SAMPLE_RATE);
        comp.setThresholdDb(-20.0);
        comp.setRatio(10.0);
        comp.setAttackMs(0.01);
        comp.setKneeDb(0.0);
        InsertSlot slot = new InsertSlot("Compressor", comp, InsertEffectType.COMPRESSOR);
        slot.setSidechainSource(kickChannel);
        bassChannel.addInsert(slot);

        mixer.prepareForPlayback(1, 4096);

        float[][][] channelBuffers = new float[2][1][4096];
        Arrays.fill(channelBuffers[0][0], 0.9f);
        Arrays.fill(channelBuffers[1][0], 0.05f);

        float[][] output = new float[1][4096];
        float[][][] returnBuffers = new float[1][1][4096];

        mixer.mixDown(channelBuffers, output, returnBuffers, 4096);

        // Verify the bass was compressed (same logic as simple mixDown test)
        // The gain reduction metering on the compressor should show negative
        assertThat(comp.getGainReductionDb()).isLessThan(0.0);
    }

    @Test
    void mixerMultiBusMixDownShouldRouteSidechainFromReturnBus() {
        // Sidechain source is a return bus — the compressor should use the
        // return bus buffer for detection when available in the multi-bus
        // mixDown path. The return bus is populated by a send from an earlier
        // channel before the sidechain-consuming channel is processed.
        Mixer mixer = new Mixer();

        MixerChannel vocalsChannel = new MixerChannel("Vocals");
        mixer.addChannel(vocalsChannel);

        MixerChannel bassChannel = new MixerChannel("Bass");
        mixer.addChannel(bassChannel);

        MixerChannel returnBus = mixer.getReturnBuses().get(0);

        // Vocals sends a loud signal to the return bus (pre-fader, full level)
        vocalsChannel.addSend(new Send(returnBus, 1.0, SendMode.PRE_FADER));

        CompressorProcessor comp = new CompressorProcessor(1, SAMPLE_RATE);
        comp.setThresholdDb(-20.0);
        comp.setRatio(10.0);
        comp.setAttackMs(0.01);
        comp.setKneeDb(0.0);
        InsertSlot slot = new InsertSlot("Compressor", comp, InsertEffectType.COMPRESSOR);
        slot.setSidechainSource(returnBus);
        bassChannel.addInsert(slot);

        mixer.prepareForPlayback(1, 4096);

        // Vocals: loud signal that will be sent to the return bus
        float[][][] channelBuffers = new float[2][1][4096];
        Arrays.fill(channelBuffers[0][0], 0.9f);  // vocals — loud
        Arrays.fill(channelBuffers[1][0], 0.05f);  // bass — quiet

        float[][] output = new float[1][4096];
        float[][][] returnBuffers = new float[1][1][4096];

        mixer.mixDown(channelBuffers, output, returnBuffers, 4096);

        // The vocals' send fills the return bus with a loud signal.
        // The bass compressor sidechains from that return bus, so it should
        // detect above-threshold levels and apply gain reduction.
        assertThat(comp.getGainReductionDb()).isLessThan(0.0);
    }

    @Test
    void mixerShouldHandleThreeActiveInsertsWithSidechain() {
        // Regression test: 3+ active non-bypassed inserts require proper
        // ping-pong between two scratch buffers to avoid aliasing.
        Mixer mixer = new Mixer();

        MixerChannel kickChannel = new MixerChannel("Kick");
        mixer.addChannel(kickChannel);

        MixerChannel bassChannel = new MixerChannel("Bass");
        mixer.addChannel(bassChannel);

        // Three active inserts: EQ (no sidechain) + Compressor (sidechain) + Gate (no sidechain)
        InsertSlot eq = InsertEffectFactory.createSlot(
                InsertEffectType.PARAMETRIC_EQ, 1, SAMPLE_RATE);
        bassChannel.addInsert(eq);

        CompressorProcessor comp = new CompressorProcessor(1, SAMPLE_RATE);
        comp.setThresholdDb(-20.0);
        comp.setRatio(10.0);
        comp.setAttackMs(0.01);
        comp.setKneeDb(0.0);
        InsertSlot compSlot = new InsertSlot("Compressor", comp, InsertEffectType.COMPRESSOR);
        compSlot.setSidechainSource(kickChannel);
        bassChannel.addInsert(compSlot);

        NoiseGateProcessor gate = new NoiseGateProcessor(1, SAMPLE_RATE);
        gate.setThresholdDb(-60.0); // Low threshold so gate stays open
        InsertSlot gateSlot = new InsertSlot("Gate", gate, InsertEffectType.NOISE_GATE);
        bassChannel.addInsert(gateSlot);

        mixer.prepareForPlayback(1, 4096);

        float[][][] channelBuffers = new float[2][1][4096];
        Arrays.fill(channelBuffers[0][0], 0.9f);  // kick — loud
        Arrays.fill(channelBuffers[1][0], 0.05f);  // bass — quiet

        float[][] output = new float[1][4096];

        // Should not corrupt data — three active slots require proper buffering
        mixer.mixDown(channelBuffers, output, 4096);

        // Compressor should have applied gain reduction via sidechain
        assertThat(comp.getGainReductionDb()).isLessThan(0.0);

        // Output should contain both kick and (compressed) bass
        double outRms = rms(output[0], 2048, 4096);
        assertThat(outRms).isGreaterThan(0.0);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static double rms(float[] buffer, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (to - from));
    }
}
