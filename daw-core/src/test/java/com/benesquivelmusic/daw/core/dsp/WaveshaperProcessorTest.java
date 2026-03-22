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
}
