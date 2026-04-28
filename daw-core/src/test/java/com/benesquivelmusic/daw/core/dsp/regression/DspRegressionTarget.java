package com.benesquivelmusic.daw.core.dsp.regression;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

/**
 * Optional explicit binding from a regression-test class to the
 * {@link AudioProcessor} class it covers. Used by
 * {@code DspRegressionCoverageTest} when the test class's name does not
 * follow the {@code <Processor>RegressionTest} convention.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DspRegressionTarget {
    Class<? extends AudioProcessor> processor();
}
