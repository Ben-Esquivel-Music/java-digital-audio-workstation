package com.benesquivelmusic.daw.core.dsp.regression;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example {@code @DspRegression} integration test: drives a
 * {@link CompressorProcessor} through every canonical preset against a
 * single test signal and compares the output to a committed golden file.
 *
 * <p>This serves as both a working regression and the on-ramp for
 * adding a new processor to the framework — copy the pattern, register
 * the three presets in {@link CanonicalRegressionPresets}, run
 * {@code mvn test -Pdsp-rebaseline} to commit golden files, then commit.</p>
 */
class CompressorProcessorRegressionTest {

    static {
        CanonicalRegressionPresets.registerAll();
    }

    @Test
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.DEFAULT)
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.AGGRESSIVE)
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.SUBTLE)
    void compressor_sineSweep_allPresets() throws Exception {
        for (DspRegression spec : getClass()
                .getDeclaredMethod("compressor_sineSweep_allPresets")
                .getAnnotationsByType(DspRegression.class)) {
            CompressorProcessor proc = new CompressorProcessor(1, TestSignals.SAMPLE_RATE);
            DspRegressionHarness.Report report = DspRegressionHarness.run(proc, spec);
            assertThat(report.passed())
                    .as("regression case %s", report.summary())
                    .isTrue();
        }
    }
}
