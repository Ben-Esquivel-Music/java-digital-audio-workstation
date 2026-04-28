package com.benesquivelmusic.daw.core.dsp.regression;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation declaring a single golden-file DSP regression case.
 *
 * <p>Apply to a JUnit 5 test method together with
 * {@link DspRegressionHarness#runAll(Class, Object)} (the harness picks up
 * every {@code @DspRegression} on the calling test method via reflection)
 * <em>or</em> use the values directly with
 * {@link DspRegressionHarness#run(Object, DspRegression)}.</p>
 *
 * <p>Each case feeds the named test signal through the processor at the
 * named preset and compares the output to the committed golden file.
 * The default tolerance is {@code -80 dB} peak — typical of "inaudible
 * numerical noise" between refactors that don't change the algorithm.</p>
 *
 * <p>Multiple cases can be declared on a single test method via the
 * {@link DspRegressionCases} container (Java's repeating-annotation
 * mechanism).</p>
 *
 * @see DspRegressionHarness
 * @see TestSignals
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(DspRegressionCases.class)
public @interface DspRegression {

    /**
     * Name of the test signal to feed through the processor — resolved against
     * {@code daw-core/src/test/resources/test-signals/}. May be a bare name
     * (e.g. {@code "sine-sweep"}) which the harness expands to
     * {@code test-signals/sine-sweep.wav}, or an explicit classpath path.
     */
    String testSignal();

    /**
     * Name of the canonical preset to apply to the processor before
     * processing. Conventionally {@link DspRegressionPreset#DEFAULT},
     * {@link DspRegressionPreset#AGGRESSIVE} or
     * {@link DspRegressionPreset#SUBTLE}; see {@link DspRegressionPreset}
     * for the per-processor parameter mappings.
     */
    String preset();

    /**
     * Path of the golden file relative to
     * {@code daw-core/src/test/resources/dsp-goldens/}. If left empty the
     * harness derives it as
     * {@code dsp-goldens/<ProcessorSimpleName>/<preset>-<testSignal>.wav}.
     */
    String goldenFile() default "";

    /**
     * Per-sample peak tolerance in dB. Default {@code -80 dB} is the
     * threshold typically used for numerical-noise-only changes (well
     * below the ~ -60 dB threshold of audibility for transient differences).
     */
    double peakToleranceDb() default -80.0;
}
