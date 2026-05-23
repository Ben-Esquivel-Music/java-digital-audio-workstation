package com.benesquivelmusic.daw.core.dsp.regression;

import com.benesquivelmusic.daw.core.spatial.binaural.BinauralMonitoringProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-file regression test for {@link BinauralMonitoringProcessor}. Exercises the
 * processor against the canonical sine-sweep test signal with each of
 * the three canonical presets (Default / Aggressive / Subtle) registered
 * in {@link CanonicalRegressionPresets}.
 *
 * <p>The {@link DspRegressionTarget} annotation declares the covered
 * processor explicitly because {@code BinauralMonitoringProcessor} lives
 * outside the {@code com.benesquivelmusic.daw.core.dsp.*} packages that
 * the coverage scanner walks by class-name inference.</p>
 *
 * <p>Generated as part of the story-114 expansion that brings every
 * built-in EFFECT/MASTERING plugin under {@code @DspRegression}
 * coverage (see {@link DspRegressionCoverageTest}). Rebaseline the
 * goldens with {@code mvn -Pdsp-rebaseline test -Dtest=BinauralMonitoringProcessorRegressionTest}.</p>
 */
@DspRegressionTarget(processor = BinauralMonitoringProcessor.class)
class BinauralMonitoringProcessorRegressionTest {

    static {
        CanonicalRegressionPresets.registerAll();
    }

    @Test
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.DEFAULT)
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.AGGRESSIVE)
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.SUBTLE)
    void binauralMonitoring_sineSweep_allPresets() throws Exception {
        for (DspRegression spec : getClass()
                .getDeclaredMethod("binauralMonitoring_sineSweep_allPresets")
                .getAnnotationsByType(DspRegression.class)) {
            BinauralMonitoringProcessor proc = new BinauralMonitoringProcessor(2, TestSignals.SAMPLE_RATE);
            DspRegressionHarness.Report report = DspRegressionHarness.run(proc, spec);
            assertThat(report.passed())
                    .as("regression case %s", report.summary())
                    .isTrue();
        }
    }
}
