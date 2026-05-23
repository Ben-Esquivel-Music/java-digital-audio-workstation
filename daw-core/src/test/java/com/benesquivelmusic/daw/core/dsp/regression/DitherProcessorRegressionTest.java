package com.benesquivelmusic.daw.core.dsp.regression;

import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor;
import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor.DitherType;
import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor.NoiseShape;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-file regression test for {@link DitherProcessor}. Exercises the
 * processor against the canonical sine-sweep test signal with each of
 * the three canonical presets (Default / Aggressive / Subtle) registered
 * in {@link CanonicalRegressionPresets}.
 *
 * <p>Dither uses a PRNG to generate noise; a fixed seed is supplied to the
 * constructor so each preset produces the same bytes on every run.</p>
 *
 * <p>Generated as part of the story-114 expansion that brings every
 * built-in EFFECT/MASTERING plugin under {@code @DspRegression}
 * coverage (see {@link DspRegressionCoverageTest}). Rebaseline the
 * goldens with {@code mvn -Pdsp-rebaseline test -Dtest=DitherProcessorRegressionTest}.</p>
 */
class DitherProcessorRegressionTest {

    /** Fixed seed so dither output is byte-identical across runs. */
    private static final long FIXED_SEED = 0xDA7A1234L;

    static {
        CanonicalRegressionPresets.registerAll();
    }

    @Test
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.DEFAULT)
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.AGGRESSIVE)
    @DspRegression(testSignal = "sine-sweep", preset = DspRegressionPreset.SUBTLE)
    void dither_sineSweep_allPresets() throws Exception {
        for (DspRegression spec : getClass()
                .getDeclaredMethod("dither_sineSweep_allPresets")
                .getAnnotationsByType(DspRegression.class)) {
            DitherProcessor proc = new DitherProcessor(
                    1, 16, DitherType.TPDF, NoiseShape.FLAT, FIXED_SEED);
            DspRegressionHarness.Report report = DspRegressionHarness.run(proc, spec);
            assertThat(report.passed())
                    .as("regression case %s", report.summary())
                    .isTrue();
        }
    }
}
