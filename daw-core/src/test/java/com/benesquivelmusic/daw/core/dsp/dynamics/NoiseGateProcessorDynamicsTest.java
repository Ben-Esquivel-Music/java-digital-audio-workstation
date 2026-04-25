package com.benesquivelmusic.daw.core.dsp.dynamics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the feature-complete {@link NoiseGateProcessor} in the
 * {@code dynamics} package: hysteresis, attack/hold/release timing,
 * lookahead, and sidechain bandpass filtering.
 */
class NoiseGateProcessorDynamicsTest {

    private static final double SR = 48_000.0;

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new NoiseGateProcessor(0, SR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NoiseGateProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExposeChannelCounts() {
        var gate = new NoiseGateProcessor(2, SR);
        assertThat(gate.getInputChannelCount()).isEqualTo(2);
        assertThat(gate.getOutputChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldOpenWithinAttackTimeAfterRisingAboveThreshold() {
        var gate = new NoiseGateProcessor(1, SR);
        gate.setThresholdDb(-20.0);
        gate.setHysteresisDb(3.0);
        gate.setAttackMs(2.0);
        gate.setHoldMs(0.0);
        gate.setReleaseMs(50.0);
        gate.setRangeDb(-80.0);

        int frames = 1024;
        float[][] in = new float[1][frames];
        float[][] out = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            in[0][i] = 0.5f; // ≈ −6 dBFS, well above −20 dB threshold
        }
        gate.process(in, out, frames);

        // After attack, gate should be fully open and pass the signal.
        assertThat(gate.getGateState()).isEqualTo(NoiseGateProcessor.GateState.OPEN);
        assertThat((double) out[0][frames - 1])
                .isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));
        // Within attack samples (2 ms ≈ 96 samples) envelope should already
        // be substantially open.
        int attackSamples = (int) (2.0 * 0.001 * SR);
        double openSample = Math.abs(out[0][attackSamples * 2]);
        assertThat(openSample).isGreaterThan(0.4);
    }

    @Test
    void hysteresisShouldKeepGateOpenBetweenOpenAndCloseThresholds() {
        var gate = new NoiseGateProcessor(1, SR);
        gate.setThresholdDb(-20.0);   // open at −20 dB
        gate.setHysteresisDb(10.0);   // close at −30 dB
        gate.setAttackMs(0.1);
        gate.setHoldMs(0.0);
        gate.setReleaseMs(5.0);
        gate.setRangeDb(-80.0);

        // Phase 1: drive signal well above threshold to open the gate.
        int n = 2048;
        float[][] high = new float[1][n];
        float[][] outHigh = new float[1][n];
        for (int i = 0; i < n; i++) high[0][i] = 0.5f;       // ≈ −6 dB
        gate.process(high, outHigh, n);
        assertThat(gate.getGateState()).isEqualTo(NoiseGateProcessor.GateState.OPEN);

        // Phase 2: drop signal between close (−30 dB) and open (−20 dB) — gate
        // must stay open thanks to hysteresis.
        float[][] mid = new float[1][n];
        float[][] outMid = new float[1][n];
        // 0.05 ≈ −26 dB: below open threshold but above close threshold
        for (int i = 0; i < n; i++) mid[0][i] = 0.05f;
        gate.process(mid, outMid, n);
        // The close threshold is −30 dB, so signal at −26 dB is above it —
        // gate must remain in OPEN (hysteresis prevents chatter).
        assertThat(gate.getGateState()).isEqualTo(NoiseGateProcessor.GateState.OPEN);
    }

    @Test
    void shouldCloseAfterHoldPlusReleaseWhenSignalFallsBelowCloseThreshold() {
        var gate = new NoiseGateProcessor(1, SR);
        gate.setThresholdDb(-20.0);
        gate.setHysteresisDb(3.0);
        gate.setAttackMs(0.1);
        gate.setHoldMs(5.0);
        gate.setReleaseMs(5.0);
        gate.setRangeDb(-80.0);

        // Open the gate.
        int n = 1024;
        float[][] in = new float[1][n];
        float[][] out = new float[1][n];
        for (int i = 0; i < n; i++) in[0][i] = 0.5f;
        gate.process(in, out, n);
        assertThat(gate.getGateState()).isEqualTo(NoiseGateProcessor.GateState.OPEN);

        // Drop signal to silence — must traverse HOLD → RELEASE → CLOSED
        // within hold + release samples (5 ms + 5 ms = 480 samples).
        int silentFrames = 4096;
        float[][] silent = new float[1][silentFrames];
        float[][] silentOut = new float[1][silentFrames];
        gate.process(silent, silentOut, silentFrames);

        assertThat(gate.getGateState()).isEqualTo(NoiseGateProcessor.GateState.CLOSED);
        // Last samples should be attenuated by ≈ rangeLinear (−80 dB ≈ 1e-4).
        assertThat((double) Math.abs(silentOut[0][silentFrames - 1]))
                .isLessThan(1e-3);
    }

    @Test
    void rangeShouldDefineFloorAttenuationWhenClosed() {
        var gate = new NoiseGateProcessor(1, SR);
        gate.setThresholdDb(-10.0);
        gate.setHysteresisDb(0.0);
        gate.setRangeDb(-6.0);             // floor ≈ 0.501
        gate.setAttackMs(0.01);
        gate.setReleaseMs(1.0);
        gate.setHoldMs(0.0);

        float amp = 0.001f;                // far below threshold
        int n = 8192;
        float[][] in = new float[1][n];
        float[][] out = new float[1][n];
        for (int i = 0; i < n; i++) in[0][i] = amp;
        gate.process(in, out, n);

        double rangeLinear = Math.pow(10.0, -6.0 / 20.0);
        float expected = (float) (amp * rangeLinear);
        for (int i = n - 100; i < n; i++) {
            assertThat((double) out[0][i]).isCloseTo(expected,
                    org.assertj.core.data.Offset.offset(expected * 0.05));
        }
    }

    @Test
    void sidechainShouldGateMainSignalFromExternalTrigger() {
        var gate = new NoiseGateProcessor(1, SR);
        gate.setThresholdDb(-20.0);
        gate.setHysteresisDb(3.0);
        gate.setAttackMs(0.1);
        gate.setReleaseMs(5.0);
        gate.setHoldMs(0.0);
        gate.setRangeDb(-80.0);
        gate.setSidechainEnabled(true);
        gate.setSidechainFilterEnabled(false);

        int n = 2048;
        // Main = quiet sine — would normally fail to open the gate.
        float[][] main = new float[1][n];
        float[][] sc   = new float[1][n];
        float[][] out  = new float[1][n];
        for (int i = 0; i < n; i++) {
            main[0][i] = (float) (0.4 * Math.sin(2.0 * Math.PI * 440.0 * i / SR));
            sc  [0][i] = 0.6f; // loud sidechain trigger
        }
        gate.processSidechain(main, sc, out, n);
        // Gate must open from the sidechain.
        assertThat(gate.getGateState()).isEqualTo(NoiseGateProcessor.GateState.OPEN);
        // Output mirrors main input (gain ≈ 1).
        assertThat((double) out[0][n - 1])
                .isCloseTo(main[0][n - 1], org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void sidechainBandpassShouldRejectOutOfBandTrigger() {
        var gate = new NoiseGateProcessor(1, SR);
        gate.setThresholdDb(-20.0);
        gate.setHysteresisDb(3.0);
        gate.setAttackMs(0.1);
        gate.setReleaseMs(2.0);
        gate.setHoldMs(0.0);
        gate.setRangeDb(-80.0);
        gate.setSidechainEnabled(true);
        gate.setSidechainFilterEnabled(true);
        gate.setSidechainFilterFreqHz(80.0);   // kick-band centre
        gate.setSidechainFilterQ(0.7);

        int n = 4096;
        float[][] main = new float[1][n];
        float[][] sc   = new float[1][n];
        float[][] out  = new float[1][n];
        // Main: full-amplitude — we expect the gate to *gate it out*.
        // Sidechain: 5 kHz tone — well outside the 80 Hz bandpass.
        for (int i = 0; i < n; i++) {
            main[0][i] = 0.5f;
            sc  [0][i] = (float) (0.9 * Math.sin(2.0 * Math.PI * 5000.0 * i / SR));
        }
        gate.processSidechain(main, sc, out, n);
        assertThat(gate.getGateState()).isEqualTo(NoiseGateProcessor.GateState.CLOSED);
        // Output strongly attenuated.
        assertThat((double) Math.abs(out[0][n - 1])).isLessThan(0.01);
    }

    @Test
    void sidechainBandpassShouldOpenOnInBandTrigger() {
        var gate = new NoiseGateProcessor(1, SR);
        gate.setThresholdDb(-20.0);
        gate.setHysteresisDb(3.0);
        gate.setAttackMs(0.1);
        gate.setReleaseMs(2.0);
        gate.setHoldMs(0.0);
        gate.setRangeDb(-80.0);
        gate.setSidechainEnabled(true);
        gate.setSidechainFilterEnabled(true);
        gate.setSidechainFilterFreqHz(80.0);
        gate.setSidechainFilterQ(0.7);

        int n = 4096;
        float[][] main = new float[1][n];
        float[][] sc   = new float[1][n];
        float[][] out  = new float[1][n];
        // Sidechain tone at the bandpass centre frequency → passes through.
        for (int i = 0; i < n; i++) {
            main[0][i] = 0.5f;
            sc  [0][i] = (float) (0.9 * Math.sin(2.0 * Math.PI * 80.0 * i / SR));
        }
        gate.processSidechain(main, sc, out, n);
        assertThat(gate.getGateState()).isEqualTo(NoiseGateProcessor.GateState.OPEN);
    }

    @Test
    void lookaheadShouldDelayMainOutput() {
        var gate = new NoiseGateProcessor(1, SR);
        gate.setThresholdDb(-60.0);
        gate.setRangeDb(0.0);    // pass-through floor: gate fully transparent
        gate.setLookaheadMs(1.0); // 48 samples
        gate.setAttackMs(0.01);

        int n = 256;
        float[][] in = new float[1][n];
        float[][] out = new float[1][n];
        in[0][0] = 1.0f;          // unit impulse at sample 0
        gate.process(in, out, n);

        int expectedDelay = (int) Math.round(1.0 * 0.001 * SR);
        // Output sample at expectedDelay should carry the impulse (×1.0).
        assertThat((double) out[0][expectedDelay]).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(0.001));
        // Earlier samples: zero.
        for (int i = 0; i < expectedDelay; i++) {
            assertThat(out[0][i]).isEqualTo(0f);
        }
    }

    @Test
    void resetShouldClearStateAndDelayLine() {
        var gate = new NoiseGateProcessor(1, SR);
        gate.setLookaheadMs(1.0);
        float[][] in = new float[1][256];
        float[][] out = new float[1][256];
        for (int i = 0; i < in[0].length; i++) in[0][i] = 0.9f;
        gate.process(in, out, 256);

        gate.reset();
        assertThat(gate.getGateState()).isEqualTo(NoiseGateProcessor.GateState.CLOSED);
        assertThat(gate.getMeterSnapshot()).isEqualTo(NoiseGateProcessor.MeterSnapshot.SILENT);
    }

    @Test
    void meterSnapshotShouldExposeStateAndLevels() {
        var gate = new NoiseGateProcessor(1, SR);
        gate.setThresholdDb(-20.0);
        int n = 1024;
        float[][] in = new float[1][n];
        float[][] out = new float[1][n];
        for (int i = 0; i < n; i++) in[0][i] = 0.5f;
        gate.process(in, out, n);

        var snap = gate.getMeterSnapshot();
        assertThat(snap.state()).isEqualTo(NoiseGateProcessor.GateState.OPEN);
        assertThat(snap.isOpen()).isTrue();
        assertThat(snap.inputLevelDb()).isGreaterThan(-20.0);
        assertThat(snap.outputLevelDb()).isGreaterThan(-20.0);
        // toPluginMeterSnapshot should report ≈ 0 dB GR when gate is fully open.
        var generic = snap.toPluginMeterSnapshot();
        assertThat(generic.gainReductionDb()).isCloseTo(0.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void parameterSettersShouldClampOutOfRangeValues() {
        var gate = new NoiseGateProcessor(1, SR);
        gate.setHysteresisDb(-5.0);
        assertThat(gate.getHysteresisDb()).isEqualTo(0.0);

        gate.setLookaheadMs(999.0);
        assertThat(gate.getLookaheadMs()).isEqualTo(10.0);

        gate.setSidechainFilterFreqHz(1.0);
        assertThat(gate.getSidechainFilterFreqHz()).isEqualTo(20.0);
        gate.setSidechainFilterFreqHz(99_999.0);
        assertThat(gate.getSidechainFilterFreqHz()).isEqualTo(20_000.0);

        gate.setSidechainFilterQ(0.0);
        assertThat(gate.getSidechainFilterQ()).isEqualTo(0.1);
        gate.setSidechainFilterQ(99.0);
        assertThat(gate.getSidechainFilterQ()).isEqualTo(10.0);
    }
}
