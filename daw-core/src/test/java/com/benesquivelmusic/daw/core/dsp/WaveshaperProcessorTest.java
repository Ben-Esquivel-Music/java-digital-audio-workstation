package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaveshaperProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        WaveshaperProcessor ws = new WaveshaperProcessor(2, 44100.0);
        assertThat(ws.getInputChannelCount()).isEqualTo(2);
        assertThat(ws.getOutputChannelCount()).isEqualTo(2);
        assertThat(ws.getTransferFunction()).isEqualTo(WaveshaperProcessor.TransferFunction.SOFT_CLIP);
        assertThat(ws.getOversampleFactor()).isEqualTo(WaveshaperProcessor.OversampleFactor.TWO_X);
        assertThat(ws.getDriveDb()).isEqualTo(0.0);
        assertThat(ws.getMix()).isEqualTo(1.0);
        assertThat(ws.getOutputGainDb()).isEqualTo(0.0);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new WaveshaperProcessor(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaveshaperProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaveshaperProcessor(-1, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaveshaperProcessor(2, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldPassDrySignalWithZeroMix() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setMix(0.0);
        ws.setDriveDb(20.0); // Heavy drive, but mix is zero

        float[][] input = new float[1][512];
        float[][] output = new float[1][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        ws.process(input, output, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldShapeSilenceToSilence() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setDriveDb(20.0);

        float[][] input = new float[1][256];
        float[][] output = new float[1][256];
        ws.process(input, output, 256);

        for (int i = 0; i < 256; i++) {
            assertThat(output[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void softClipShouldProduceOutput() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setTransferFunction(WaveshaperProcessor.TransferFunction.SOFT_CLIP);
        ws.setDriveDb(12.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        ws.process(input, output, 4096);

        // After filter settling, output should have significant energy
        double outputRms = rms(output[0], 2048, 4096);
        assertThat(outputRms).isGreaterThan(0.1);
    }

    @Test
    void hardClipShouldLimitAmplitude() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setTransferFunction(WaveshaperProcessor.TransferFunction.HARD_CLIP);
        ws.setDriveDb(20.0); // Heavy drive
        ws.setOutputGainDb(0.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.9 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        ws.process(input, output, 4096);

        // After settling, all output samples should be bounded
        for (int i = 2048; i < 4096; i++) {
            assertThat(Math.abs(output[0][i])).isLessThanOrEqualTo(1.5f);
        }
    }

    @Test
    void tubeSaturationShouldProduceOutput() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setTransferFunction(WaveshaperProcessor.TransferFunction.TUBE_SATURATION);
        ws.setDriveDb(6.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        ws.process(input, output, 4096);

        double outputRms = rms(output[0], 2048, 4096);
        assertThat(outputRms).isGreaterThan(0.05);
    }

    @Test
    void tapeSaturationShouldProduceOutput() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setTransferFunction(WaveshaperProcessor.TransferFunction.TAPE_SATURATION);
        ws.setDriveDb(6.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        ws.process(input, output, 4096);

        double outputRms = rms(output[0], 2048, 4096);
        assertThat(outputRms).isGreaterThan(0.05);
    }

    @Test
    void higherDriveShouldIncreaseDistortion() {
        WaveshaperProcessor wsLow = new WaveshaperProcessor(1, 44100.0);
        wsLow.setDriveDb(0.0);

        WaveshaperProcessor wsHigh = new WaveshaperProcessor(1, 44100.0);
        wsHigh.setDriveDb(24.0);

        float[][] input = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }

        float[][] outputLow = new float[1][4096];
        float[][] outputHigh = new float[1][4096];
        wsLow.process(input, outputLow, 4096);
        wsHigh.process(input, outputHigh, 4096);

        // Higher drive should produce more harmonic distortion (higher RMS relative to fundamental)
        double rmsLow = rms(outputLow[0], 2048, 4096);
        double rmsHigh = rms(outputHigh[0], 2048, 4096);

        // With tanh soft-clip and heavy drive, the signal gets compressed,
        // so peak amplitude is lower but harmonic content is higher.
        // The absolute RMS levels should differ.
        assertThat(rmsLow).isNotCloseTo(rmsHigh,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void outputGainShouldScaleOutput() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setDriveDb(6.0);
        ws.setOutputGainDb(-6.0); // -6 dB ≈ 0.5 linear

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.3 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        ws.process(input, output, 4096);

        // Compare with 0 dB output gain
        WaveshaperProcessor wsRef = new WaveshaperProcessor(1, 44100.0);
        wsRef.setDriveDb(6.0);
        wsRef.setOutputGainDb(0.0);

        float[][] outputRef = new float[1][4096];
        wsRef.process(input, outputRef, 4096);

        double rmsGain = rms(output[0], 2048, 4096);
        double rmsRef = rms(outputRef[0], 2048, 4096);

        // Output with -6 dB gain should be roughly half the amplitude
        assertThat(rmsGain).isLessThan(rmsRef);
        assertThat(rmsGain).isCloseTo(rmsRef * 0.5,
                org.assertj.core.data.Offset.offset(rmsRef * 0.15));
    }

    @Test
    void shouldSupportAllOversamplingFactors() {
        for (WaveshaperProcessor.OversampleFactor factor :
                WaveshaperProcessor.OversampleFactor.values()) {
            WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
            ws.setOversampleFactor(factor);
            ws.setDriveDb(12.0);

            assertThat(ws.getOversampleFactor()).isEqualTo(factor);

            float[][] input = new float[1][1024];
            float[][] output = new float[1][1024];
            for (int i = 0; i < 1024; i++) {
                input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
            }
            ws.process(input, output, 1024);

            double outputRms = rms(output[0], 512, 1024);
            assertThat(outputRms)
                    .as("OversampleFactor %s should produce output", factor)
                    .isGreaterThan(0.01);
        }
    }

    @Test
    void oversampleFactorEnumShouldReturnCorrectValues() {
        assertThat(WaveshaperProcessor.OversampleFactor.TWO_X.getFactor()).isEqualTo(2);
        assertThat(WaveshaperProcessor.OversampleFactor.TWO_X.getStages()).isEqualTo(1);
        assertThat(WaveshaperProcessor.OversampleFactor.FOUR_X.getFactor()).isEqualTo(4);
        assertThat(WaveshaperProcessor.OversampleFactor.FOUR_X.getStages()).isEqualTo(2);
        assertThat(WaveshaperProcessor.OversampleFactor.EIGHT_X.getFactor()).isEqualTo(8);
        assertThat(WaveshaperProcessor.OversampleFactor.EIGHT_X.getStages()).isEqualTo(3);
    }

    @Test
    void shouldResetState() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setDriveDb(12.0);

        // Process loud signal to fill filter state
        float[][] input = new float[1][1024];
        float[][] output = new float[1][1024];
        for (int i = 0; i < 1024; i++) {
            input[0][i] = 0.9f;
        }
        ws.process(input, output, 1024);

        ws.reset();

        // After reset, processing silence should produce silence
        float[][] silence = new float[1][512];
        float[][] resetOutput = new float[1][512];
        ws.process(silence, resetOutput, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(resetOutput[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldProcessStereoChannelsIndependently() {
        WaveshaperProcessor ws = new WaveshaperProcessor(2, 44100.0);
        ws.setDriveDb(12.0);

        float[][] input = new float[2][2048];
        float[][] output = new float[2][2048];
        for (int i = 0; i < 2048; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
            input[1][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 880.0 * i / 44100.0));
        }
        ws.process(input, output, 2048);

        // Both channels should have signal
        assertThat(rms(output[0], 1024, 2048)).isGreaterThan(0.01);
        assertThat(rms(output[1], 1024, 2048)).isGreaterThan(0.01);
    }

    @Test
    void shouldRejectInvalidMix() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        assertThatThrownBy(() -> ws.setMix(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ws.setMix(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullTransferFunction() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        assertThatThrownBy(() -> ws.setTransferFunction(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullOversampleFactor() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        assertThatThrownBy(() -> ws.setOversampleFactor(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSupportParameterChanges() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setTransferFunction(WaveshaperProcessor.TransferFunction.HARD_CLIP);
        ws.setOversampleFactor(WaveshaperProcessor.OversampleFactor.EIGHT_X);
        ws.setDriveDb(18.0);
        ws.setMix(0.5);
        ws.setOutputGainDb(-3.0);

        assertThat(ws.getTransferFunction()).isEqualTo(WaveshaperProcessor.TransferFunction.HARD_CLIP);
        assertThat(ws.getOversampleFactor()).isEqualTo(WaveshaperProcessor.OversampleFactor.EIGHT_X);
        assertThat(ws.getDriveDb()).isEqualTo(18.0);
        assertThat(ws.getMix()).isEqualTo(0.5);
        assertThat(ws.getOutputGainDb()).isEqualTo(-3.0);
    }

    @Test
    void mixShouldBlendWetAndDry() {
        WaveshaperProcessor wsFull = new WaveshaperProcessor(1, 44100.0);
        wsFull.setDriveDb(12.0);
        wsFull.setMix(1.0);

        WaveshaperProcessor wsHalf = new WaveshaperProcessor(1, 44100.0);
        wsHalf.setDriveDb(12.0);
        wsHalf.setMix(0.5);

        float[][] input = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }

        float[][] outputFull = new float[1][4096];
        float[][] outputHalf = new float[1][4096];
        wsFull.process(input, outputFull, 4096);
        wsHalf.process(input, outputHalf, 4096);

        // Half mix should differ from full wet
        boolean differs = false;
        for (int i = 2048; i < 4096; i++) {
            if (Math.abs(outputFull[0][i] - outputHalf[0][i]) > 0.001f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void allTransferFunctionsShouldMapZeroToZero() {
        // Each transfer function should be continuous through zero
        for (WaveshaperProcessor.TransferFunction tf :
                WaveshaperProcessor.TransferFunction.values()) {
            WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
            ws.setTransferFunction(tf);
            ws.setDriveDb(0.0);

            // DC input of 0 should produce 0 output (after filter settling)
            float[][] input = new float[1][512];
            float[][] output = new float[1][512];
            ws.process(input, output, 512);

            for (int i = 0; i < 512; i++) {
                assertThat(output[0][i])
                        .as("TransferFunction %s should map zero to zero", tf)
                        .isCloseTo(0.0f, org.assertj.core.data.Offset.offset(1e-6f));
            }
        }
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }

    // ── New features: NONE oversampling, CUSTOM curve, latency, @RealTimeSafe ──

    @Test
    void noneOversampleFactorShouldBypassFilters() {
        assertThat(WaveshaperProcessor.OversampleFactor.NONE.getFactor()).isEqualTo(1);
        assertThat(WaveshaperProcessor.OversampleFactor.NONE.getStages()).isEqualTo(0);

        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setOversampleFactor(WaveshaperProcessor.OversampleFactor.NONE);
        ws.setDriveDb(0.0);
        ws.setTransferFunction(WaveshaperProcessor.TransferFunction.HARD_CLIP);

        // With hard-clip at 0 dB drive, |x|<=1 inputs should pass through unchanged
        // and with no oversampling there is no filter settling time.
        float[][] input = new float[1][128];
        float[][] output = new float[1][128];
        for (int i = 0; i < 128; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * i / 64.0));
        }
        ws.process(input, output, 128);

        for (int i = 0; i < 128; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void softClipAtZeroDriveShouldProduceNearUnityOutputForSmallSignals() {
        // For small x, tanh(x) ≈ x, so soft-clip at 0 dB drive is effectively
        // unity gain. The oversampling filters settle to a flat passband, so
        // output should match input after filter settling.
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setTransferFunction(WaveshaperProcessor.TransferFunction.SOFT_CLIP);
        ws.setDriveDb(0.0);
        ws.setMix(1.0);

        int n = 4096;
        float[][] input = new float[1][n];
        float[][] output = new float[1][n];
        for (int i = 0; i < n; i++) {
            input[0][i] = (float) (0.01 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        ws.process(input, output, n);

        int latency = ws.getLatencySamples();
        // After the filter delay, output should closely track the input
        // (small-signal tanh is ~linear: tanh(0.01) ≈ 0.00999967).
        double rmsIn = rms(input[0], n / 2, n);
        double rmsOut = rms(output[0], n / 2, n);
        assertThat(rmsOut).isCloseTo(rmsIn, org.assertj.core.data.Offset.offset(rmsIn * 0.01));

        // And sample-for-sample, after the reported latency (small residual
        // phase/amplitude ripple from the polyphase filter is expected).
        for (int i = n / 2 + latency; i < n - 1; i++) {
            assertThat(output[0][i])
                    .isCloseTo(input[0][i - latency],
                            org.assertj.core.data.Offset.offset(5e-4f));
        }
    }

    @Test
    void oversampledOutputShouldHaveLowerAliasingThanNonOversampled() {
        // Drive a hard-clip waveshaper with a tone near Nyquist/4 so the
        // generated odd harmonics (3f, 5f, …) fold back into the audible band
        // without oversampling. Compare residual energy in the first aliased
        // region for NONE vs. 4× oversampling.
        double sampleRate = 44100.0;
        double f = 6000.0;
        int n = 8192;

        float[][] input = new float[1][n];
        for (int i = 0; i < n; i++) {
            input[0][i] = (float) (0.9 * Math.sin(2.0 * Math.PI * f * i / sampleRate));
        }

        double noneAlias = measureAliasingEnergy(input, WaveshaperProcessor.OversampleFactor.NONE,
                sampleRate, f);
        double fourAlias = measureAliasingEnergy(input, WaveshaperProcessor.OversampleFactor.FOUR_X,
                sampleRate, f);

        // Expect a meaningful (≥ 3 dB) reduction in aliasing energy.
        assertThat(fourAlias)
                .as("4× oversampled aliasing (%.4g) should be lower than 1× (%.4g)",
                        fourAlias, noneAlias)
                .isLessThan(noneAlias * 0.5);
    }

    private static double measureAliasingEnergy(float[][] input,
                                                WaveshaperProcessor.OversampleFactor factor,
                                                double sampleRate, double fundamentalHz) {
        int n = input[0].length;
        WaveshaperProcessor ws = new WaveshaperProcessor(1, sampleRate);
        ws.setOversampleFactor(factor);
        ws.setTransferFunction(WaveshaperProcessor.TransferFunction.HARD_CLIP);
        ws.setDriveDb(12.0);
        float[][] output = new float[1][n];
        ws.process(input, output, n);

        // Harmonics of a hard-clipped fundamental at f are at 3f, 5f, 7f, ...
        // For f = 6000 Hz and Fs = 44100 Hz, the 5th harmonic (30 kHz) folds
        // back to 44100 - 30000 = 14100 Hz in the audible band when not
        // oversampled. We probe around a few aliased-only frequencies by
        // computing the energy at specific DFT bins via Goertzel-style
        // accumulation (no FFT dependency required).
        //
        // The true (non-aliased) harmonic content at 18 kHz (3f) is above
        // Nyquist after folding, so any energy at aliased bins below the
        // fundamental that isn't also present at the correct harmonic must be
        // aliasing. We measure total energy at a couple of frequencies that
        // only appear due to aliasing.
        int warmup = n / 2; // skip filter-settling region
        double total = 0.0;
        double[] aliasFreqs = {14100.0, 8100.0, 2100.0}; // 5f, 7f, 11f aliased images
        for (double af : aliasFreqs) {
            total += goertzelEnergy(output[0], warmup, n, sampleRate, af);
        }
        return total;
    }

    /** Compute energy at frequency {@code freq} in {@code buf[start..end)}. */
    private static double goertzelEnergy(float[] buf, int start, int end,
                                         double sampleRate, double freq) {
        double omega = 2.0 * Math.PI * freq / sampleRate;
        double cosw = Math.cos(omega);
        double coeff = 2.0 * cosw;
        double q0 = 0, q1 = 0, q2 = 0;
        for (int i = start; i < end; i++) {
            q0 = coeff * q1 - q2 + buf[i];
            q2 = q1;
            q1 = q0;
        }
        // Squared magnitude = q1^2 + q2^2 - q1*q2*coeff
        double mag2 = q1 * q1 + q2 * q2 - q1 * q2 * coeff;
        int len = end - start;
        return mag2 / ((double) len * len);
    }

    @Test
    void latencyReportingShouldMatchImpulseResponse() {
        // For each oversampling factor, feed an impulse into a linear
        // (identity) configuration and verify the peak response occurs at
        // approximately the reported latency.
        double sampleRate = 44100.0;
        for (WaveshaperProcessor.OversampleFactor factor :
                WaveshaperProcessor.OversampleFactor.values()) {
            WaveshaperProcessor ws = new WaveshaperProcessor(1, sampleRate);
            ws.setOversampleFactor(factor);
            // Use the identity custom curve for a fully linear path so the
            // impulse is preserved (no nonlinear smearing).
            ws.setTransferFunction(WaveshaperProcessor.TransferFunction.CUSTOM);
            ws.setDriveDb(0.0);
            ws.setMix(1.0);

            int n = 256;
            float[][] input = new float[1][n];
            input[0][0] = 1.0f;
            float[][] output = new float[1][n];
            ws.process(input, output, n);

            int peakIdx = 0;
            float peakAbs = 0.0f;
            for (int i = 0; i < n; i++) {
                if (Math.abs(output[0][i]) > peakAbs) {
                    peakAbs = Math.abs(output[0][i]);
                    peakIdx = i;
                }
            }

            int reported = ws.getLatencySamples();
            assertThat(peakIdx)
                    .as("impulse-response peak for %s (reported latency %d)",
                            factor, reported)
                    .isBetween(Math.max(0, reported - 2), reported + 2);
        }
    }

    @Test
    void latencyShouldBeZeroForNoneOversampling() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setOversampleFactor(WaveshaperProcessor.OversampleFactor.NONE);
        assertThat(ws.getLatencySamples()).isZero();
    }

    @Test
    void latencyShouldBeNonNegativeAndMonotonicNonDecreasing() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        int prev = -1;
        for (WaveshaperProcessor.OversampleFactor factor :
                WaveshaperProcessor.OversampleFactor.values()) {
            ws.setOversampleFactor(factor);
            int lat = ws.getLatencySamples();
            assertThat(lat).isGreaterThanOrEqualTo(0);
            assertThat(lat).isGreaterThanOrEqualTo(prev);
            prev = lat;
        }
    }

    @Test
    void shouldBeAnnotatedRealTimeSafe() {
        // The processor contract requires @RealTimeSafe so real-time hosts can
        // verify safety. Creating instances at multiple oversampling factors
        // must not affect that contract.
        for (WaveshaperProcessor.OversampleFactor factor :
                WaveshaperProcessor.OversampleFactor.values()) {
            WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
            ws.setOversampleFactor(factor);
            assertThat(WaveshaperProcessor.class.isAnnotationPresent(
                    com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe.class))
                    .as("WaveshaperProcessor must be @RealTimeSafe at %s", factor)
                    .isTrue();
        }
    }

    @Test
    void shouldRejectDriveOutOfRange() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        assertThatThrownBy(() -> ws.setDriveDb(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ws.setDriveDb(48.1))
                .isInstanceOf(IllegalArgumentException.class);
        // boundary values should be accepted
        ws.setDriveDb(WaveshaperProcessor.MIN_DRIVE_DB);
        ws.setDriveDb(WaveshaperProcessor.MAX_DRIVE_DB);
    }

    @Test
    void shouldRejectOutputGainOutOfRange() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        assertThatThrownBy(() -> ws.setOutputGainDb(-12.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ws.setOutputGainDb(12.1))
                .isInstanceOf(IllegalArgumentException.class);
        // boundary values should be accepted
        ws.setOutputGainDb(WaveshaperProcessor.MIN_OUTPUT_GAIN_DB);
        ws.setOutputGainDb(WaveshaperProcessor.MAX_OUTPUT_GAIN_DB);
    }

    @Test
    void customTransferFunctionShouldApplyControlPointCurve() {
        // Build a symmetric "fold" curve: x=-1->-1, x=-0.5->0, x=0->0,
        // x=0.5->0, x=1->1 — deadzone in [-0.5, 0.5], linear outside.
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setOversampleFactor(WaveshaperProcessor.OversampleFactor.NONE);
        ws.setTransferFunction(WaveshaperProcessor.TransferFunction.CUSTOM);
        ws.setCustomCurvePoints(
                new double[]{-1.0, -0.5, 0.0, 0.5, 1.0},
                new double[]{-1.0, 0.0, 0.0, 0.0, 1.0});
        ws.setDriveDb(0.0);

        float[][] input = new float[1][7];
        input[0][0] = -1.0f; input[0][1] = -0.75f; input[0][2] = -0.25f;
        input[0][3] = 0.0f;  input[0][4] = 0.25f;  input[0][5] = 0.75f;
        input[0][6] = 1.0f;
        float[][] output = new float[1][7];
        ws.process(input, output, 7);

        var offset = org.assertj.core.data.Offset.offset(1e-6f);
        assertThat(output[0][0]).isCloseTo(-1.0f, offset);
        assertThat(output[0][1]).isCloseTo(-0.5f, offset); // interp between -1 and -0.5
        assertThat(output[0][2]).isCloseTo(0.0f, offset);  // deadzone
        assertThat(output[0][3]).isCloseTo(0.0f, offset);
        assertThat(output[0][4]).isCloseTo(0.0f, offset);
        assertThat(output[0][5]).isCloseTo(0.5f, offset);
        assertThat(output[0][6]).isCloseTo(1.0f, offset);
    }

    @Test
    void customCurveShouldClampInputsOutsideConfiguredRange() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setOversampleFactor(WaveshaperProcessor.OversampleFactor.NONE);
        ws.setTransferFunction(WaveshaperProcessor.TransferFunction.CUSTOM);
        ws.setCustomCurvePoints(
                new double[]{-0.5, 0.5},
                new double[]{-0.25, 0.25});

        float[][] input = {{-1.0f, 1.0f, 0.0f}};
        float[][] output = new float[1][3];
        ws.process(input, output, 3);

        var offset = org.assertj.core.data.Offset.offset(1e-6f);
        assertThat(output[0][0]).isCloseTo(-0.25f, offset); // clamped to first
        assertThat(output[0][1]).isCloseTo(0.25f, offset);  // clamped to last
        assertThat(output[0][2]).isCloseTo(0.0f, offset);   // interpolated midpoint
    }

    @Test
    void setCustomCurvePointsShouldValidateArguments() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        assertThatThrownBy(() -> ws.setCustomCurvePoints(null, new double[]{0, 1}))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ws.setCustomCurvePoints(new double[]{0, 1}, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ws.setCustomCurvePoints(new double[]{0}, new double[]{0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ws.setCustomCurvePoints(new double[]{0, 1},
                new double[]{0, 1, 2})).isInstanceOf(IllegalArgumentException.class);
        // not strictly monotonic
        assertThatThrownBy(() -> ws.setCustomCurvePoints(new double[]{0, 0, 1},
                new double[]{0, 0, 1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ws.setCustomCurvePoints(new double[]{0, 1, 0.5},
                new double[]{0, 1, 0.5})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customCurveAccessorsShouldReturnConfiguredPoints() {
        WaveshaperProcessor ws = new WaveshaperProcessor(1, 44100.0);
        ws.setCustomCurvePoints(new double[]{-1.0, 0.0, 1.0},
                new double[]{-0.9, 0.1, 0.7});
        assertThat(ws.getCustomCurveXs()).containsExactly(-1.0, 0.0, 1.0);
        assertThat(ws.getCustomCurveYs()).containsExactly(-0.9, 0.1, 0.7);
    }
}
