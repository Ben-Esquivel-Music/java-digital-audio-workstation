package com.benesquivelmusic.daw.core.dsp.regression;

import com.benesquivelmusic.daw.core.dsp.dynamics.NoiseGateProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-file regression test for {@link NoiseGateProcessor}. Exercises the
 * processor against the canonical sine-sweep test signal with each of
 * the three canonical presets (Default / Aggressive / Subtle) registered
 * in {@link CanonicalRegressionPresets}.
 *
 * <p>The {@link DspRegressionTarget} annotation disambiguates which
 * {@code NoiseGateProcessor} class is being covered — there is both a
 * legacy {@code com.benesquivelmusic.daw.core.dsp.NoiseGateProcessor}
 * and the current {@code com.benesquivelmusic.daw.core.dsp.dynamics}
 * implementation, so the coverage scanner cannot infer the target from
 * the simple class name alone.</p>
 *
 * <p>Generated as part of the story-114 expansion that brings every
 * built-in EFFECT/MASTERING plugin under {@code @DspRegression}
 * coverage (see {@link DspRegressionCoverageTest}). Rebaseline the
 * goldens with {@code mvn -Pdsp-rebaseline test -Dtest=NoiseGateProcessorRegressionTest}.</p>
 */
@DspRegressionTarget(processor = NoiseGateProcessor.class)
class NoiseGateProcessorRegressionTest {

    static {
        CanonicalRegressionPresets.registerAll();
    }

    @Test
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.DEFAULT)
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.AGGRESSIVE)
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.SUBTLE)
    void noiseGate_sineSweep_allPresets() throws Exception {
        for (DspRegression spec : getClass()
                .getDeclaredMethod("noiseGate_sineSweep_allPresets")
                .getAnnotationsByType(DspRegression.class)) {
            NoiseGateProcessor proc = new NoiseGateProcessor(1, TestSignals.SAMPLE_RATE);
            DspRegressionHarness.Report report = DspRegressionHarness.run(proc, spec);
            assertThat(report.passed())
                    .as("regression case %s", report.summary())
                    .isTrue();
        }
    }
}
