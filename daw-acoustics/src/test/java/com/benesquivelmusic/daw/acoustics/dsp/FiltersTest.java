package com.benesquivelmusic.daw.acoustics.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FiltersTest {

    private static final int FS = 48000;

    @Test
    void lowPass1PassesDC() {
        LowPass1 filter = new LowPass1(1000.0, FS);
        // DC input should pass through (gain ~1.0 at DC)
        double output = 0;
        for (int i = 0; i < 10000; i++)
            output = filter.getOutput(1.0, 1.0);
        assertThat(output).isCloseTo(1.0, within(0.01));
    }

    @Test
    void highShelfInitialises() {
        HighShelf filter = new HighShelf(1000.0, 0.5, FS);
        double output = filter.getOutput(1.0, 1.0);
        assertThat(Double.isNaN(output)).isFalse();
    }

    @Test
    void lowPassPassesDC() {
        LowPass filter = new LowPass(1000.0, FS);
        double output = 0;
        for (int i = 0; i < 1000; i++)
            output = filter.getOutput(1.0, 1.0);
        assertThat(output).isCloseTo(1.0, within(0.01));
    }

    @Test
    void highPassBlocksDC() {
        HighPass filter = new HighPass(1000.0, FS);
        double output = 0;
        for (int i = 0; i < 1000; i++)
            output = filter.getOutput(1.0, 1.0);
        assertThat(output).isCloseTo(0.0, within(0.01));
    }

    @Test
    void peakingFilterUnityGainPassesThrough() {
        PeakingFilter filter = new PeakingFilter(1000.0, 1.0, 2.0, FS);
        double output = 0;
        for (int i = 0; i < 1000; i++)
            output = filter.getOutput(1.0, 1.0);
        assertThat(output).isCloseTo(1.0, within(0.01));
    }

    @Test
    void parameterInterpolates() {
        Parameter p = new Parameter(0.0);
        p.setTarget(1.0);
        assertThat(p.isZero()).isFalse();
        double value = p.use(1.0);
        assertThat(value).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void parameterIsZeroWhenTargetIsZero() {
        Parameter p = new Parameter(0.0);
        p.use(1.0);
        assertThat(p.isZero()).isTrue();
    }
}
