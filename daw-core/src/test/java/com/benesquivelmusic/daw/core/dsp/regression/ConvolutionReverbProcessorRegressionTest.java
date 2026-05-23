package com.benesquivelmusic.daw.core.dsp.regression;

import com.benesquivelmusic.daw.core.dsp.reverb.ConvolutionReverbProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-file regression test for {@link ConvolutionReverbProcessor}. Exercises the
 * processor against the canonical sine-sweep test signal with each of
 * the three canonical presets (Default / Aggressive / Subtle) registered
 * in {@link CanonicalRegressionPresets}.
 *
 * <p>Generated as part of the story-114 expansion that brings every
 * built-in EFFECT/MASTERING plugin under {@code @DspRegression}
 * coverage (see {@link DspRegressionCoverageTest}). Rebaseline the
 * goldens with {@code mvn -Pdsp-rebaseline test -Dtest=ConvolutionReverbProcessorRegressionTest}.</p>
 */
class ConvolutionReverbProcessorRegressionTest {

    static {
        CanonicalRegressionPresets.registerAll();
    }

    @Test
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.DEFAULT)
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.AGGRESSIVE)
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.SUBTLE)
    void convolutionReverb_sineSweep_allPresets() throws Exception {
        for (DspRegression spec : getClass()
                .getDeclaredMethod("convolutionReverb_sineSweep_allPresets")
                .getAnnotationsByType(DspRegression.class)) {
            ConvolutionReverbProcessor proc = new ConvolutionReverbProcessor(1, TestSignals.SAMPLE_RATE);
            DspRegressionHarness.Report report = DspRegressionHarness.run(proc, spec);
            assertThat(report.passed())
                    .as("regression case %s", report.summary())
                    .isTrue();
        }
    }
}
