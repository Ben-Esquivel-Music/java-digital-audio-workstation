package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalogDistortionProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        AnalogDistortionProcessor proc = new AnalogDistortionProcessor(2, 44100.0);
        assertThat(proc.getInputChannelCount()).isEqualTo(2);
        assertThat(proc.getOutputChannelCount()).isEqualTo(2);
        assertThat(proc.getDriveDb()).isEqualTo(0.0);
        assertThat(proc.getTone()).isEqualTo(0.0);
        assertThat(proc.getSlewRate()).isEqualTo(13.0);
        assertThat(proc.getAsymmetry()).isEqualTo(0.0);
        assertThat(proc.getOutputLevelDb()).isEqualTo(0.0);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new AnalogDistortionProcessor(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalogDistortionProcessor(-1, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalogDistortionProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalogDistortionProcessor(2, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldProcessSilenceToSilence() {
        AnalogDistortionProcessor proc = new AnalogDistortionProcessor(1, 44100.0);
        proc.setDriveDb(12.0);

        float[][] input = new float[1][512];
        float[][] output = new float[1][512];
        proc.process(input, output, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(Math.abs(output[0][i])).isLessThan(0.001f);
        }
    }

    @Test
    void shouldProduceOutputForSignal() {
        AnalogDistortionProcessor proc = new AnalogDistortionProcessor(1, 44100.0);
        proc.setDriveDb(12.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        proc.process(input, output, 4096);

        double outputRms = rms(output[0], 2048, 4096);
        assertThat(outputRms).isGreaterThan(0.001);
    }

    @Test
    void higherDriveShouldChangeDistortionCharacter() {
        AnalogDistortionProcessor low = new AnalogDistortionProcessor(1, 44100.0);
        low.setDriveDb(0.0);

        AnalogDistortionProcessor high = new AnalogDistortionProcessor(1, 44100.0);
        high.setDriveDb(24.0);

        float[][] input = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }

        float[][] outputLow = new float[1][4096];
        float[][] outputHigh = new float[1][4096];
        low.process(input, outputLow, 4096);
        high.process(input, outputHigh, 4096);

        double rmsLow = rms(outputLow[0], 2048, 4096);
        double rmsHigh = rms(outputHigh[0], 2048, 4096);

        // With high drive, the signal character should differ
        assertThat(rmsLow).isNotCloseTo(rmsHigh,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void slewRateLimitingShouldSmoothFastTransients() {
        // A low slew rate should produce smoother output than a high slew rate
        AnalogDistortionProcessor slowSlew = new AnalogDistortionProcessor(1, 44100.0);
        slowSlew.setDriveDb(6.0);
        slowSlew.setSlewRate(0.5); // Very low slew rate

        AnalogDistortionProcessor fastSlew = new AnalogDistortionProcessor(1, 44100.0);
        fastSlew.setDriveDb(6.0);
        fastSlew.setSlewRate(100.0); // Very high slew rate

        // Square wave — rich in fast transients
        float[][] input = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0) > 0 ? 0.8 : -0.8);
        }

        float[][] outSlow = new float[1][4096];
        float[][] outFast = new float[1][4096];
        slowSlew.process(input, outSlow, 4096);
        fastSlew.process(input, outFast, 4096);

        // Compute maximum sample-to-sample difference as a proxy for transient sharpness
        double maxDeltaSlow = maxAbsDelta(outSlow[0], 2048, 4096);
        double maxDeltaFast = maxAbsDelta(outFast[0], 2048, 4096);

        // Low slew rate should limit the maximum rate of change
        assertThat(maxDeltaSlow).isLessThan(maxDeltaFast);
    }

    @Test
    void toneControlShouldAffectFrequencyBalance() {
        AnalogDistortionProcessor bright = new AnalogDistortionProcessor(1, 44100.0);
        bright.setDriveDb(6.0);
        bright.setTone(1.0);

        AnalogDistortionProcessor dark = new AnalogDistortionProcessor(1, 44100.0);
        dark.setDriveDb(6.0);
        dark.setTone(-1.0);

        // High-frequency test signal
        float[][] input = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 8000.0 * i / 44100.0));
        }

        float[][] outBright = new float[1][4096];
        float[][] outDark = new float[1][4096];
        bright.process(input, outBright, 4096);
        dark.process(input, outDark, 4096);

        double rmsBright = rms(outBright[0], 2048, 4096);
        double rmsDark = rms(outDark[0], 2048, 4096);

        // Bright tone should pass more high-frequency content
        assertThat(rmsBright).isGreaterThan(rmsDark);
    }

    @Test
    void asymmetryShouldIntroduceEvenHarmonics() {
        AnalogDistortionProcessor symmetric = new AnalogDistortionProcessor(1, 44100.0);
        symmetric.setDriveDb(12.0);
        symmetric.setAsymmetry(0.0);

        AnalogDistortionProcessor asymmetric = new AnalogDistortionProcessor(1, 44100.0);
        asymmetric.setDriveDb(12.0);
        asymmetric.setAsymmetry(0.8);

        float[][] input = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }

        float[][] outSym = new float[1][4096];
        float[][] outAsym = new float[1][4096];
        symmetric.process(input, outSym, 4096);
        asymmetric.process(input, outAsym, 4096);

        // Asymmetric clipping should produce a different waveform (DC offset or shape change)
        boolean differs = false;
        for (int i = 2048; i < 4096; i++) {
            if (Math.abs(outSym[0][i] - outAsym[0][i]) > 0.0001f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void outputLevelShouldScaleOutput() {
        AnalogDistortionProcessor proc = new AnalogDistortionProcessor(1, 44100.0);
        proc.setDriveDb(6.0);
        proc.setOutputLevelDb(-6.0);

        AnalogDistortionProcessor ref = new AnalogDistortionProcessor(1, 44100.0);
        ref.setDriveDb(6.0);
        ref.setOutputLevelDb(0.0);

        float[][] input = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.3 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }

        float[][] output = new float[1][4096];
        float[][] outputRef = new float[1][4096];
        proc.process(input, output, 4096);
        ref.process(input, outputRef, 4096);

        double rmsOut = rms(output[0], 2048, 4096);
        double rmsRef = rms(outputRef[0], 2048, 4096);

        // -6 dB should be roughly half amplitude
        assertThat(rmsOut).isLessThan(rmsRef);
        assertThat(rmsOut).isCloseTo(rmsRef * 0.5,
                org.assertj.core.data.Offset.offset(rmsRef * 0.15));
    }

    @Test
    void shouldResetState() {
        AnalogDistortionProcessor proc = new AnalogDistortionProcessor(1, 44100.0);
        proc.setDriveDb(12.0);

        // Process loud signal to fill internal state
        float[][] input = new float[1][1024];
        float[][] output = new float[1][1024];
        for (int i = 0; i < 1024; i++) {
            input[0][i] = 0.9f;
        }
        proc.process(input, output, 1024);

        proc.reset();

        // After reset, processing silence should produce near-silence
        float[][] silence = new float[1][512];
        float[][] resetOutput = new float[1][512];
        proc.process(silence, resetOutput, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(Math.abs(resetOutput[0][i])).isLessThan(0.001f);
        }
    }

    @Test
    void shouldProcessStereoChannelsIndependently() {
        AnalogDistortionProcessor proc = new AnalogDistortionProcessor(2, 44100.0);
        proc.setDriveDb(12.0);

        float[][] input = new float[2][2048];
        float[][] output = new float[2][2048];
        for (int i = 0; i < 2048; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
            input[1][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 880.0 * i / 44100.0));
        }
        proc.process(input, output, 2048);

        // Both channels should have signal
        assertThat(rms(output[0], 1024, 2048)).isGreaterThan(0.001);
        assertThat(rms(output[1], 1024, 2048)).isGreaterThan(0.001);
    }

    @Test
    void shouldRejectInvalidTone() {
        AnalogDistortionProcessor proc = new AnalogDistortionProcessor(1, 44100.0);
        assertThatThrownBy(() -> proc.setTone(-1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proc.setTone(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSlewRate() {
        AnalogDistortionProcessor proc = new AnalogDistortionProcessor(1, 44100.0);
        assertThatThrownBy(() -> proc.setSlewRate(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proc.setSlewRate(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidAsymmetry() {
        AnalogDistortionProcessor proc = new AnalogDistortionProcessor(1, 44100.0);
        assertThatThrownBy(() -> proc.setAsymmetry(-1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proc.setAsymmetry(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportParameterChanges() {
        AnalogDistortionProcessor proc = new AnalogDistortionProcessor(1, 44100.0);
        proc.setDriveDb(18.0);
        proc.setTone(0.5);
        proc.setSlewRate(5.0);
        proc.setAsymmetry(-0.3);
        proc.setOutputLevelDb(-3.0);

        assertThat(proc.getDriveDb()).isEqualTo(18.0);
        assertThat(proc.getTone()).isEqualTo(0.5);
        assertThat(proc.getSlewRate()).isEqualTo(5.0);
        assertThat(proc.getAsymmetry()).isEqualTo(-0.3);
        assertThat(proc.getOutputLevelDb()).isEqualTo(-3.0);
    }

    @Test
    void diodeClipperShouldBoundOutput() {
        AnalogDistortionProcessor proc = new AnalogDistortionProcessor(1, 44100.0);
        proc.setDriveDb(30.0); // Very heavy drive

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.9 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        proc.process(input, output, 4096);

        // Output should be bounded — the diode clipper limits amplitude
        for (int i = 2048; i < 4096; i++) {
            assertThat(Math.abs(output[0][i])).isLessThan(2.0f);
        }
    }

    @Test
    void neutralToneShouldApproximateUnity() {
        // With 0 dB drive, neutral tone, high slew rate, and 0 asymmetry,
        // the processor should approximate a pass-through (after the nonlinear
        // stages, which are near-linear for small signals)
        AnalogDistortionProcessor proc = new AnalogDistortionProcessor(1, 44100.0);
        proc.setDriveDb(0.0);
        proc.setTone(0.0);
        proc.setSlewRate(100.0); // Effectively no slew limiting
        proc.setAsymmetry(0.0);
        proc.setOutputLevelDb(0.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.05 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        proc.process(input, output, 4096);

        // For a low-amplitude signal, the nonlinearities are nearly transparent.
        // The output should be close to the input (within some tolerance for
        // the first-order feedback filter settling).
        double correlation = 0;
        double inputEnergy = 0;
        double outputEnergy = 0;
        for (int i = 2048; i < 4096; i++) {
            correlation += input[0][i] * output[0][i];
            inputEnergy += input[0][i] * input[0][i];
            outputEnergy += output[0][i] * output[0][i];
        }
        double normalizedCorrelation = correlation / Math.sqrt(inputEnergy * outputEnergy);
        // High correlation (> 0.9) means the shape is preserved
        assertThat(normalizedCorrelation).isGreaterThan(0.9);
    }

    @Test
    void shouldWorkAtDifferentSampleRates() {
        double[] sampleRates = {22050.0, 44100.0, 48000.0, 96000.0};
        for (double sr : sampleRates) {
            AnalogDistortionProcessor proc = new AnalogDistortionProcessor(1, sr);
            proc.setDriveDb(12.0);

            float[][] input = new float[1][1024];
            float[][] output = new float[1][1024];
            for (int i = 0; i < 1024; i++) {
                input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / sr));
            }
            proc.process(input, output, 1024);

            double outputRms = rms(output[0], 512, 1024);
            assertThat(outputRms)
                    .as("Should produce output at sample rate %s", sr)
                    .isGreaterThan(0.001);
        }
    }

    // --- Helpers ---

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }

    private static double maxAbsDelta(float[] buffer, int start, int end) {
        double maxDelta = 0;
        for (int i = start + 1; i < end; i++) {
            double delta = Math.abs(buffer[i] - buffer[i - 1]);
            if (delta > maxDelta) {
                maxDelta = delta;
            }
        }
        return maxDelta;
    }
}
