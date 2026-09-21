package com.benesquivelmusic.daw.core.dsp.regression;

import com.benesquivelmusic.daw.core.dsp.dynamics.TruePeakLimiterProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-file regression test for {@link TruePeakLimiterProcessor}. Exercises the
 * processor against the canonical sine-sweep test signal with each of
 * the three canonical presets (Default / Aggressive / Subtle) registered
 * in {@link CanonicalRegressionPresets}.
 *
 * <p>Generated as part of the story-114 expansion that brings every
 * built-in EFFECT/MASTERING plugin under {@code @DspRegression}
 * coverage (see {@link DspRegressionCoverageTest}). Rebaseline the
 * goldens with {@code mvn -Pdsp-rebaseline test -Dtest=TruePeakLimiterProcessorRegressionTest}.</p>
 */
class TruePeakLimiterProcessorRegressionTest {

    static {
        CanonicalRegressionPresets.registerAll();
    }

    @Test
    // These existing goldens include the leading lookahead delay. Keep testing
    // the same raw DSP samples; LivePluginDelayCompensationTest checks alignment.
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.DEFAULT, alignLatency = false)
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.AGGRESSIVE, alignLatency = false)
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.SUBTLE, alignLatency = false)
    void truePeakLimiter_sineSweep_allPresets() throws Exception {
        for (DspRegression spec : getClass()
                .getDeclaredMethod("truePeakLimiter_sineSweep_allPresets")
                .getAnnotationsByType(DspRegression.class)) {
            TruePeakLimiterProcessor proc = new TruePeakLimiterProcessor(1, TestSignals.SAMPLE_RATE);
            DspRegressionHarness.Report report = DspRegressionHarness.run(proc, spec);
            assertThat(report.passed())
                    .as("regression case %s", report.summary())
                    .isTrue();
        }
    }
}
