package com.benesquivelmusic.daw.core.spatial;

import com.benesquivelmusic.daw.core.spatial.panner.InverseSquareAttenuation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AirAbsorptionFilterTest {

    private static final double SAMPLE_RATE = 44100.0;

    private InverseSquareAttenuation defaultAttenuation() {
        return new InverseSquareAttenuation(1.0, 100.0);
    }

    // ---- Construction & Defaults ----

    @Test
    void shouldCreateWithDefaults() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(2, SAMPLE_RATE, defaultAttenuation());
        assertThat(filter.getInputChannelCount()).isEqualTo(2);
        assertThat(filter.getOutputChannelCount()).isEqualTo(2);
        assertThat(filter.getDistance()).isEqualTo(1.0);
        assertThat(filter.getTemperature()).isEqualTo(20.0);
        assertThat(filter.getHumidity()).isEqualTo(50.0);
        assertThat(filter.getAttenuationModel()).isNotNull();
    }

    @Test
    void shouldRejectZeroChannels() {
        assertThatThrownBy(() -> new AirAbsorptionFilter(0, SAMPLE_RATE, defaultAttenuation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channels");
    }

    @Test
    void shouldRejectNegativeChannels() {
        assertThatThrownBy(() -> new AirAbsorptionFilter(-1, SAMPLE_RATE, defaultAttenuation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channels");
    }

    @Test
    void shouldRejectZeroSampleRate() {
        assertThatThrownBy(() -> new AirAbsorptionFilter(1, 0, defaultAttenuation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void shouldRejectNegativeSampleRate() {
        assertThatThrownBy(() -> new AirAbsorptionFilter(1, -44100, defaultAttenuation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void shouldRejectNullAttenuationModel() {
        assertThatThrownBy(() -> new AirAbsorptionFilter(1, SAMPLE_RATE, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- Parameter Validation ----

    @Test
    void shouldRejectNegativeDistance() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        assertThatThrownBy(() -> filter.setDistance(-1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distanceMeters");
    }

    @Test
    void shouldAcceptZeroDistance() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        filter.setDistance(0.0);
        assertThat(filter.getDistance()).isEqualTo(0.0);
    }

    @Test
    void shouldRejectTemperatureBelowRange() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        assertThatThrownBy(() -> filter.setTemperature(-21.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temperatureCelsius");
    }

    @Test
    void shouldRejectTemperatureAboveRange() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        assertThatThrownBy(() -> filter.setTemperature(51.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temperatureCelsius");
    }

    @Test
    void shouldAcceptBoundaryTemperatures() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        filter.setTemperature(-20.0);
        assertThat(filter.getTemperature()).isEqualTo(-20.0);
        filter.setTemperature(50.0);
        assertThat(filter.getTemperature()).isEqualTo(50.0);
    }

    @Test
    void shouldRejectHumidityBelowRange() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        assertThatThrownBy(() -> filter.setHumidity(9.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relativeHumidityPercent");
    }

    @Test
    void shouldRejectHumidityAboveRange() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        assertThatThrownBy(() -> filter.setHumidity(101.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relativeHumidityPercent");
    }

    @Test
    void shouldAcceptBoundaryHumidity() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        filter.setHumidity(10.0);
        assertThat(filter.getHumidity()).isEqualTo(10.0);
        filter.setHumidity(100.0);
        assertThat(filter.getHumidity()).isEqualTo(100.0);
    }

    // ---- ISO 9613-1 Coefficient Sanity ----

    @Test
    void shouldProduceHigherAbsorptionAtHigherFrequencies() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        double alpha1k = filter.computeAbsorptionCoefficient(1000.0);
        double alpha4k = filter.computeAbsorptionCoefficient(4000.0);
        double alpha8k = filter.computeAbsorptionCoefficient(8000.0);

        assertThat(alpha1k).isGreaterThan(0.0);
        assertThat(alpha4k).isGreaterThan(alpha1k);
        assertThat(alpha8k).isGreaterThan(alpha4k);
    }

    @Test
    void shouldProduceReasonableAbsorptionAt4kHz() {
        // At 20°C, 50% RH, 4 kHz: α ≈ 0.02 dB/m (typical range 0.01–0.05)
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        double alpha4k = filter.computeAbsorptionCoefficient(4000.0);
        assertThat(alpha4k).isBetween(0.005, 0.1);
    }

    @Test
    void shouldVaryAbsorptionWithTemperature() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());

        filter.setTemperature(0.0);
        double alphaCold = filter.computeAbsorptionCoefficient(4000.0);

        filter.setTemperature(40.0);
        double alphaHot = filter.computeAbsorptionCoefficient(4000.0);

        // Absorption should differ between cold and hot air
        assertThat(alphaCold).isNotCloseTo(alphaHot, within(1e-6));
    }

    @Test
    void shouldVaryAbsorptionWithHumidity() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());

        filter.setHumidity(10.0);
        double alphaDry = filter.computeAbsorptionCoefficient(4000.0);

        filter.setHumidity(90.0);
        double alphaWet = filter.computeAbsorptionCoefficient(4000.0);

        assertThat(alphaDry).isNotCloseTo(alphaWet, within(1e-6));
    }

    // ---- Audio Processing Behavior ----

    @Test
    void shouldPassthroughAtReferenceDistance() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        filter.setDistance(1.0); // reference distance → unity gain, minimal absorption

        int frames = 1024;
        float[][] input = new float[1][frames];
        float[][] output = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE));
        }

        // Settle the filter
        filter.process(input, output, frames);
        filter.process(input, output, frames);

        // At reference distance, gain is unity and absorption is tiny
        double inputRms = rms(input[0], 0, frames);
        double outputRms = rms(output[0], 0, frames);
        assertThat(outputRms).isCloseTo(inputRms, within(inputRms * 0.05));
    }

    @Test
    void shouldAttenuateMoreAtGreaterDistance() {
        AirAbsorptionFilter filterNear = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        AirAbsorptionFilter filterFar = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        filterNear.setDistance(5.0);
        filterFar.setDistance(50.0);

        int frames = 2048;
        float[][] input = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 4000.0 * i / SAMPLE_RATE));
        }

        // Settle filters
        float[][] tempOut = new float[1][frames];
        filterNear.process(input, tempOut, frames);
        filterFar.process(input, tempOut, frames);

        float[][] outputNear = new float[1][frames];
        float[][] outputFar = new float[1][frames];
        filterNear.process(input, outputNear, frames);
        filterFar.process(input, outputFar, frames);

        double rmsNear = rms(outputNear[0], 0, frames);
        double rmsFar = rms(outputFar[0], 0, frames);

        // Far source should be significantly quieter
        assertThat(rmsFar).isLessThan(rmsNear);
    }

    @Test
    void shouldRollOffHighFrequenciesMoreThanLow() {
        AirAbsorptionFilter filterLow = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        AirAbsorptionFilter filterHigh = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        filterLow.setDistance(30.0);
        filterHigh.setDistance(30.0);

        int frames = 4096;

        // Low frequency signal (200 Hz)
        float[][] inputLow = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            inputLow[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 200.0 * i / SAMPLE_RATE));
        }

        // High frequency signal (8000 Hz)
        float[][] inputHigh = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            inputHigh[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 8000.0 * i / SAMPLE_RATE));
        }

        // Settle
        float[][] tempOut = new float[1][frames];
        filterLow.process(inputLow, tempOut, frames);
        filterHigh.process(inputHigh, tempOut, frames);

        float[][] outputLow = new float[1][frames];
        float[][] outputHigh = new float[1][frames];
        filterLow.process(inputLow, outputLow, frames);
        filterHigh.process(inputHigh, outputHigh, frames);

        // Both get the same distance gain, but high frequencies lose more from absorption
        double rmsInputLow = rms(inputLow[0], 0, frames);
        double rmsInputHigh = rms(inputHigh[0], 0, frames);
        double rmsOutLow = rms(outputLow[0], 0, frames);
        double rmsOutHigh = rms(outputHigh[0], 0, frames);

        double ratioLow = rmsOutLow / rmsInputLow;
        double ratioHigh = rmsOutHigh / rmsInputHigh;

        // High frequency should be attenuated more relative to its input
        assertThat(ratioHigh).isLessThan(ratioLow);
    }

    @Test
    void shouldResetFilterState() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        filter.setDistance(20.0);

        int frames = 2048;
        float[][] input = new float[1][frames];
        input[0][0] = 1.0f;
        float[][] output = new float[1][frames];
        filter.process(input, output, frames);

        filter.reset();

        // After reset, processing silence should produce silence
        float[][] silence = new float[1][frames];
        float[][] resetOutput = new float[1][frames];
        filter.process(silence, resetOutput, frames);

        for (int i = 0; i < frames; i++) {
            assertThat(resetOutput[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldProcessStereo() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(2, SAMPLE_RATE, defaultAttenuation());
        filter.setDistance(10.0);

        int frames = 2048;
        float[][] input = new float[2][frames];
        float[][] output = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            float sample = (float) (0.5 * Math.sin(2.0 * Math.PI * 4000.0 * i / SAMPLE_RATE));
            input[0][i] = sample;
            input[1][i] = sample;
        }

        // Settle
        filter.process(input, output, frames);
        filter.process(input, output, frames);

        double rmsL = rms(output[0], 0, frames);
        double rmsR = rms(output[1], 0, frames);

        // Both channels should have signal and be equal (same input)
        assertThat(rmsL).isGreaterThan(0.0);
        assertThat(rmsR).isCloseTo(rmsL, within(rmsL * 0.01));
    }

    @Test
    void shouldApplyDistanceGainFromAttenuationModel() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        // At max distance (100m), gain should be zero
        filter.setDistance(100.0);

        int frames = 512;
        float[][] input = new float[1][frames];
        float[][] output = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            input[0][i] = 0.5f;
        }
        filter.process(input, output, frames);

        for (int i = 0; i < frames; i++) {
            assertThat(output[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldUpdateParametersAndRecalculate() {
        AirAbsorptionFilter filter = new AirAbsorptionFilter(1, SAMPLE_RATE, defaultAttenuation());
        filter.setDistance(25.0);
        assertThat(filter.getDistance()).isEqualTo(25.0);
        filter.setTemperature(30.0);
        assertThat(filter.getTemperature()).isEqualTo(30.0);
        filter.setHumidity(80.0);
        assertThat(filter.getHumidity()).isEqualTo(80.0);
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
