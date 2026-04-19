package com.benesquivelmusic.daw.core.dsp.harness;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Reflection-powered baseline test battery for every
 * {@link AudioProcessor} implementation in
 * {@code com.benesquivelmusic.daw.core.dsp}.
 *
 * <p>For every concrete {@code AudioProcessor} in the {@code dsp} package
 * with a public {@code (int channels, double sampleRate)} constructor, this
 * harness runs a standard battery of structural and behavioral tests:</p>
 * <ul>
 *   <li><b>Construction</b> — instantiates with {@code (2, 44100.0)} and
 *       verifies the resulting processor reports sensible channel counts.</li>
 *   <li><b>Invalid construction</b> — verifies the {@code (0, 44100.0)} and
 *       {@code (2, -1.0)} forms throw {@link IllegalArgumentException}.
 *       Negative sample rate is used (instead of zero) so the test applies
 *       uniformly to processors whose second argument semantically tolerates
 *       zero, e.g. {@code GainStagingProcessor(channels, gainDb)} where
 *       {@code 0 dB} is unity gain.</li>
 *   <li><b>Process produces output</b> — feeds a 512-frame buffer of 0.5f
 *       through {@code process()}, verifies output dimensions.</li>
 *   <li><b>Silence in, silence out</b> — feeds zeros through {@code process()}
 *       on a freshly-constructed processor, verifies output is all zeros.
 *       A small tolerance accommodates numerical noise from biquad filter
 *       coefficients and similar.</li>
 *   <li><b>Reset clears state</b> — calls {@code process()} with signal,
 *       then {@code reset()}, then {@code process()} with silence and
 *       verifies output decays to zero within the processor's own latency
 *       window.</li>
 *   <li><b>Non-negative latency</b> — verifies
 *       {@code getLatencySamples() >= 0}.</li>
 *   <li><b>@ProcessorParam range testing</b> — for each
 *       {@link ProcessorParam}-annotated getter, sets the value to
 *       {@code min}, {@code max}, and {@code defaultValue} via the matching
 *       setter; verifies no exception.</li>
 *   <li><b>Getter/setter round-trip</b> — for each
 *       {@link ProcessorParam}-annotated getter, sets a value via the setter
 *       and verifies the getter returns the same value.</li>
 * </ul>
 *
 * <p>When a new processor is added to the {@code dsp} package it is tested
 * automatically with zero additional code, provided it exposes the standard
 * {@code (int, double)} constructor. {@link ProcessorDiscoverabilityTest}
 * guards against silently skipping a processor with a non-standard ctor.</p>
 *
 * <p>This harness intentionally does <em>not</em> replace the per-processor
 * tests (e.g., {@code CompressorProcessorTest}) which exercise domain-specific
 * behavior (compression curves, gate state machines, etc.) the harness cannot
 * cover.</p>
 */
final class ProcessorTestHarness {

    /** Package scanned for {@link AudioProcessor} implementations. */
    static final String DSP_PACKAGE = "com.benesquivelmusic.daw.core.dsp";

    /** Standard test parameters. */
    private static final int CHANNELS = 2;
    private static final double SAMPLE_RATE = 44100.0;
    private static final int FRAMES = 512;

    /**
     * Processors whose {@code (int, double)} constructor's second argument is
     * <em>not</em> a sample rate — {@code 0.0} is a valid value so the
     * "invalid sample rate" leg of the construction test does not apply.
     * The processor is still exercised by all other tests in the battery.
     */
    private static final Set<String> ACCEPTS_ZERO_SECOND_ARG = Set.of(
            "GainStagingProcessor" // second arg is gainDb; 0 dB = unity gain
    );

    // ─── @MethodSource: one argument row per discovered processor ──────────

    static Stream<Arguments> discoveredProcessors() {
        List<Class<? extends AudioProcessor>> processors =
                ProcessorDiscovery.findProcessorsWithStandardConstructor(DSP_PACKAGE);
        if (processors.isEmpty()) {
            fail("No AudioProcessor implementations discovered in " + DSP_PACKAGE
                    + " — the classpath scanner may be misconfigured.");
        }
        return processors.stream()
                .map(cls -> Arguments.of(named(cls.getSimpleName(), cls)));
    }

    /** JUnit 5 {@code Named} wrapper so reports show the processor's simple name. */
    private static org.junit.jupiter.api.Named<Class<? extends AudioProcessor>> named(
            String name, Class<? extends AudioProcessor> cls) {
        return org.junit.jupiter.api.Named.of(name, cls);
    }

    // ─── Test battery ───────────────────────────────────────────────────────

    @ParameterizedTest(name = "construction: {0}")
    @MethodSource("discoveredProcessors")
    void construction(Class<? extends AudioProcessor> cls) throws Exception {
        AudioProcessor p = instantiate(cls, CHANNELS, SAMPLE_RATE);
        assertThat(p).as("instance of %s", cls.getSimpleName()).isNotNull();
        assertThat(p.getInputChannelCount())
                .as("%s.getInputChannelCount()", cls.getSimpleName())
                .isGreaterThan(0);
        assertThat(p.getOutputChannelCount())
                .as("%s.getOutputChannelCount()", cls.getSimpleName())
                .isGreaterThan(0);
    }

    @ParameterizedTest(name = "invalid construction: {0}")
    @MethodSource("discoveredProcessors")
    void invalidConstruction(Class<? extends AudioProcessor> cls) {
        String simple = cls.getSimpleName();
        assertThatThrownBy(() -> instantiate(cls, 0, SAMPLE_RATE))
                .as("%s should reject channels=0", simple)
                .isInstanceOf(IllegalArgumentException.class);

        if (!ACCEPTS_ZERO_SECOND_ARG.contains(simple)) {
            // Use a negative value: clearly invalid for a sample rate, and
            // also clearly invalid for any other numeric parameter meaning.
            assertThatThrownBy(() -> instantiate(cls, CHANNELS, -1.0))
                    .as("%s should reject second arg = -1.0 (invalid sampleRate)", simple)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest(name = "process produces output: {0}")
    @MethodSource("discoveredProcessors")
    void processProducesOutput(Class<? extends AudioProcessor> cls) throws Exception {
        AudioProcessor p = instantiate(cls, CHANNELS, SAMPLE_RATE);
        int in = p.getInputChannelCount();
        int out = p.getOutputChannelCount();
        float[][] input = fill(new float[in][FRAMES], 0.5f);
        float[][] output = new float[out][FRAMES];
        p.process(input, output, FRAMES);
        assertThat(output).as("%s output buffer", cls.getSimpleName()).isNotNull();
        assertThat(output.length).isEqualTo(out);
        for (int c = 0; c < out; c++) {
            assertThat(output[c]).as("%s output channel %d", cls.getSimpleName(), c)
                    .hasSize(FRAMES);
        }
    }

    @ParameterizedTest(name = "silence in -> silence out: {0}")
    @MethodSource("discoveredProcessors")
    void silenceInSilenceOut(Class<? extends AudioProcessor> cls) throws Exception {
        AudioProcessor p = instantiate(cls, CHANNELS, SAMPLE_RATE);
        int in = p.getInputChannelCount();
        int out = p.getOutputChannelCount();
        float[][] input = new float[in][FRAMES];       // zeros
        float[][] output = new float[out][FRAMES];
        p.process(input, output, FRAMES);
        assertAllApproximatelyZero(output, cls.getSimpleName() + " silence-in→silence-out");
    }

    @ParameterizedTest(name = "reset clears state: {0}")
    @MethodSource("discoveredProcessors")
    void resetClearsState(Class<? extends AudioProcessor> cls) throws Exception {
        AudioProcessor p = instantiate(cls, CHANNELS, SAMPLE_RATE);
        int in = p.getInputChannelCount();
        int out = p.getOutputChannelCount();

        // 1) drive signal through the processor so any internal state fills up
        float[][] signal = fill(new float[in][FRAMES], 0.5f);
        float[][] sink = new float[out][FRAMES];
        // A few passes to get past any attack/ramp-up window
        for (int i = 0; i < 4; i++) {
            p.process(signal, sink, FRAMES);
        }

        // 2) reset and 3) feed silence — output should decay to zero
        p.reset();
        float[][] silence = new float[in][FRAMES];
        float[][] output = new float[out][FRAMES];

        // Processors with internal latency (e.g., linear-phase, oversampled)
        // need enough post-reset frames to flush residual state after reset.
        int latency = Math.max(0, p.getLatencySamples());
        int extraBlocks = 1 + (latency + FRAMES - 1) / FRAMES;
        for (int i = 0; i < extraBlocks; i++) {
            p.process(silence, output, FRAMES);
        }
        assertAllApproximatelyZero(output,
                cls.getSimpleName() + " post-reset silence-in→silence-out");
    }

    @ParameterizedTest(name = "non-negative latency: {0}")
    @MethodSource("discoveredProcessors")
    void nonNegativeLatency(Class<? extends AudioProcessor> cls) throws Exception {
        AudioProcessor p = instantiate(cls, CHANNELS, SAMPLE_RATE);
        assertThat(p.getLatencySamples())
                .as("%s.getLatencySamples()", cls.getSimpleName())
                .isGreaterThanOrEqualTo(0);
    }

    @ParameterizedTest(name = "@ProcessorParam range accepts min/max/default: {0}")
    @MethodSource("discoveredProcessors")
    void processorParamRangeSetters(Class<? extends AudioProcessor> cls) throws Exception {
        List<ParamPair> params = discoverProcessorParams(cls);
        if (params.isEmpty()) {
            return; // nothing to exercise
        }
        // Some processors declare cross-parameter constraints (e.g. cutoff must
        // be strictly less than target bandwidth). Test each (param, value)
        // triple on a fresh instance with all OTHER annotated params pushed
        // to the SAME extreme (min/max/default) as the target — monotonic
        // cross-parameter constraints are then satisfied by construction.
        for (ParamPair target : params) {
            for (String label : new String[] {"min", "max", "defaultValue"}) {
                AudioProcessor p = instantiate(cls, CHANNELS, SAMPLE_RATE);
                for (ParamPair other : params) {
                    if (other == target) continue;
                    try {
                        other.setter.invoke(p, coerce(other.setter, valueFor(other, label)));
                    } catch (InvocationTargetException ignored) {
                        // best-effort widening; ignore cross-constraint conflicts
                    }
                }
                setParam(p, target, valueFor(target, label), label);
            }
        }
    }

    private static double valueFor(ParamPair pair, String label) {
        return switch (label) {
            case "min" -> pair.annotation.min();
            case "max" -> pair.annotation.max();
            default    -> pair.annotation.defaultValue();
        };
    }

    @ParameterizedTest(name = "@ProcessorParam getter/setter round-trip: {0}")
    @MethodSource("discoveredProcessors")
    void processorParamRoundTrip(Class<? extends AudioProcessor> cls) throws Exception {
        AudioProcessor p = instantiate(cls, CHANNELS, SAMPLE_RATE);
        List<ParamPair> params = discoverProcessorParams(cls);
        if (params.isEmpty()) {
            return;
        }
        for (ParamPair pair : params) {
            // A value that lies strictly inside [min, max] for all annotated ranges.
            double target = pair.annotation.defaultValue();
            pair.setter.invoke(p, target);
            double actual = ((Number) pair.getter.invoke(p)).doubleValue();
            assertThat(actual)
                    .as("%s.%s round-trip (set %.6f, got %.6f)",
                            cls.getSimpleName(), pair.annotation.name(), target, actual)
                    .isEqualTo(target);
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static AudioProcessor instantiate(Class<? extends AudioProcessor> cls,
                                              int channels, double sampleRate) throws Exception {
        Constructor<?> ctor = ProcessorDiscovery.findStandardConstructor(cls);
        if (ctor == null) {
            throw new AssertionError(cls.getName()
                    + " is missing the standard (int, double) constructor");
        }
        try {
            return (AudioProcessor) ctor.newInstance(channels, sampleRate);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error err) throw err;
            throw e;
        }
    }

    private static float[][] fill(float[][] buf, float value) {
        for (float[] row : buf) java.util.Arrays.fill(row, value);
        return buf;
    }

    private static void assertAllApproximatelyZero(float[][] buf, String context) {
        // Tolerance accommodates numerical noise from IIR biquad coefficients,
        // dither processors intentionally add non-zero noise so they are not
        // considered; only those with a standard (int, double) ctor reach here.
        final float tol = 1e-4f;
        for (int c = 0; c < buf.length; c++) {
            for (int i = 0; i < buf[c].length; i++) {
                if (Math.abs(buf[c][i]) > tol) {
                    fail(String.format(Locale.ROOT,
                            "%s: output[%d][%d] = %g (tol=%g)",
                            context, c, i, buf[c][i], tol));
                }
            }
        }
    }

    private static List<ParamPair> discoverProcessorParams(Class<?> cls) {
        List<ParamPair> pairs = new ArrayList<>();
        for (Method getter : cls.getMethods()) {
            ProcessorParam ann = getter.getAnnotation(ProcessorParam.class);
            if (ann == null) continue;
            if (getter.getParameterCount() != 0) continue;
            Class<?> returnType = getter.getReturnType();
            if (returnType != double.class && returnType != float.class
                    && returnType != int.class && returnType != long.class) {
                continue;
            }
            String name = getter.getName();
            if (!name.startsWith("get") || name.length() <= 3) continue;
            String setterName = "set" + name.substring(3);
            Method setter = null;
            for (Method m : cls.getMethods()) {
                if (!m.getName().equals(setterName)) continue;
                if (m.getParameterCount() != 1) continue;
                Class<?> pt = m.getParameterTypes()[0];
                // Prefer the double setter; fall back to matching return type.
                if (pt == double.class) { setter = m; break; }
                if (pt == returnType) setter = m;
            }
            if (setter != null) {
                pairs.add(new ParamPair(ann, getter, setter));
            }
        }
        return Collections.unmodifiableList(pairs);
    }

    private static void setParam(AudioProcessor p, ParamPair pair, double value, String label)
            throws IllegalAccessException, InvocationTargetException {
        Object arg = coerce(pair.setter, value);
        try {
            pair.setter.invoke(p, arg);
        } catch (InvocationTargetException e) {
            fail(String.format(Locale.ROOT,
                    "%s.%s setter rejected %s=%s: %s",
                    p.getClass().getSimpleName(), pair.annotation.name(),
                    label, arg, e.getCause()), e.getCause());
        }
    }

    private static Object coerce(Method setter, double value) {
        Class<?> pt = setter.getParameterTypes()[0];
        if (pt == double.class)     return value;
        if (pt == float.class)      return (float) value;
        if (pt == int.class)        return (int) Math.round(value);
        if (pt == long.class)       return Math.round(value);
        throw new AssertionError("Unsupported setter parameter type: " + pt);
    }

    private record ParamPair(ProcessorParam annotation, Method getter, Method setter) {}
}
