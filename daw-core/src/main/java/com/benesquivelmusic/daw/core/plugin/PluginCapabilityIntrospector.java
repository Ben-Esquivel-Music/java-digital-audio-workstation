package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.GainReductionProvider;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers the {@link PluginCapabilities} of an
 * {@link AudioProcessor} (or a {@link DawPlugin} that exposes one) through
 * reflection, once per concrete class, and caches the result.
 *
 * <p>The introspection probes, in order:</p>
 * <ol>
 *   <li><b>Interface implementation</b> —
 *       {@link SidechainAwareProcessor}, {@link GainReductionProvider},
 *       {@link AudioProcessor}.</li>
 *   <li><b>Method overrides</b> — checks if
 *       {@link AudioProcessor#getLatencySamples()} is overridden by the concrete
 *       class (its {@code declaringClass} is not {@link AudioProcessor} itself).
 *       This does not invoke the method on the instance.</li>
 *   <li><b>Annotation presence</b> — {@link RealTimeSafe} on the
 *       {@code process(float[][], float[][], int)} method, and the count of
 *       {@link ProcessorParam}-annotated getters (for UI parameter discovery).</li>
 *   <li><b>Constructor signatures</b> — whether the class has a public
 *       {@code (int channels, double sampleRate)} constructor, indicating it
 *       can be instantiated generically by the host.</li>
 * </ol>
 *
 * <p>Results are cached in a {@link ConcurrentHashMap} keyed by the processor's
 * {@link Class}, so repeated lookups are O(1) after the first call.</p>
 *
 * <p>This class has only static members and a private constructor; it must not
 * be instantiated.</p>
 */
public final class PluginCapabilityIntrospector {

    private static final ConcurrentHashMap<Class<?>, PluginCapabilities> CACHE =
            new ConcurrentHashMap<>();

    private PluginCapabilityIntrospector() {
        // utility class — not instantiable
    }

    /**
     * Returns the capabilities of the given {@link AudioProcessor} instance.
     *
     * @param processor the processor to introspect; may be {@code null}
     * @return the discovered capabilities, or {@link PluginCapabilities#NONE} if
     *         {@code processor} is {@code null}
     */
    public static PluginCapabilities capabilitiesOf(AudioProcessor processor) {
        if (processor == null) {
            return PluginCapabilities.NONE;
        }
        return capabilitiesOf(processor.getClass());
    }

    /**
     * Returns the capabilities of the {@link AudioProcessor} exposed by the
     * given {@link DawPlugin}, if any. Plugins that do not expose an
     * {@code AudioProcessor} (analyzers, utilities) report
     * {@link PluginCapabilities#NONE}.
     *
     * @param plugin the plugin to introspect; may be {@code null}
     * @return the discovered capabilities, or {@link PluginCapabilities#NONE}
     *         if {@code plugin} is {@code null} or has no audio processor
     */
    public static PluginCapabilities capabilitiesOf(DawPlugin plugin) {
        if (plugin == null) {
            return PluginCapabilities.NONE;
        }
        Optional<AudioProcessor> processor = plugin.asAudioProcessor();
        return processor.map(PluginCapabilityIntrospector::capabilitiesOf)
                .orElse(PluginCapabilities.NONE);
    }

    /**
     * Returns the capabilities of the given processor class, computing them
     * reflectively on first access and caching the result for subsequent
     * lookups.
     *
     * @param processorClass the processor class to introspect; may be {@code null}
     * @return the discovered capabilities; {@link PluginCapabilities#NONE} if
     *         {@code processorClass} is {@code null}
     */
    public static PluginCapabilities capabilitiesOf(Class<?> processorClass) {
        if (processorClass == null) {
            return PluginCapabilities.NONE;
        }
        return CACHE.computeIfAbsent(processorClass, PluginCapabilityIntrospector::compute);
    }

    /** Clears the capability cache. Intended for tests. */
    static void clearCache() {
        CACHE.clear();
    }

    private static PluginCapabilities compute(Class<?> cls) {
        boolean processesAudio = AudioProcessor.class.isAssignableFrom(cls);
        boolean providesSidechain = SidechainAwareProcessor.class.isAssignableFrom(cls);
        boolean reportsGainReduction = GainReductionProvider.class.isAssignableFrom(cls);

        boolean reportsLatency = detectLatencyOverride(cls);
        boolean realTimeSafeProcess = detectRealTimeSafeProcess(cls);
        int parameterCount = countProcessorParams(cls);
        boolean genericallyConstructible = hasGenericConstructor(cls);
        boolean supportsStereoOnly = detectStereoOnly(cls);
        Set<String> custom = detectCustomCapabilities(cls);

        return new PluginCapabilities(
                processesAudio,
                providesSidechain,
                reportsGainReduction,
                reportsLatency,
                supportsStereoOnly,
                realTimeSafeProcess,
                parameterCount,
                genericallyConstructible,
                custom);
    }

    private static boolean detectLatencyOverride(Class<?> cls) {
        if (!AudioProcessor.class.isAssignableFrom(cls)) {
            return false;
        }
        try {
            Method m = cls.getMethod("getLatencySamples");
            return m.getDeclaringClass() != AudioProcessor.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean detectRealTimeSafeProcess(Class<?> cls) {
        if (!AudioProcessor.class.isAssignableFrom(cls)) {
            return false;
        }
        try {
            Method m = cls.getMethod("process", float[][].class, float[][].class, int.class);
            return m.isAnnotationPresent(RealTimeSafe.class)
                    || cls.isAnnotationPresent(RealTimeSafe.class);
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static int countProcessorParams(Class<?> cls) {
        int count = 0;
        // Walk the class hierarchy so inherited @ProcessorParam getters are counted.
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(ProcessorParam.class)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean hasGenericConstructor(Class<?> cls) {
        for (Constructor<?> ctor : cls.getConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 2 && params[0] == int.class && params[1] == double.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detects whether the processor is restricted to stereo I/O. A processor
     * is considered stereo-only when it exposes a public, no-argument,
     * final/private {@code STEREO_ONLY} field set to {@code true}, or when its
     * class name ends with the marker suffix {@code StereoOnly}. This is
     * intentionally conservative — most processors support arbitrary channel
     * counts and report {@code false}.
     */
    private static boolean detectStereoOnly(Class<?> cls) {
        try {
            java.lang.reflect.Field f = cls.getDeclaredField("STEREO_ONLY");
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && f.getType() == boolean.class) {
                f.setAccessible(true);
                return f.getBoolean(null);
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // fall through
        }
        return cls.getSimpleName().endsWith("StereoOnly");
    }

    /**
     * Collects custom capability keys from
     * {@link ProcessorCapability @ProcessorCapability} annotations on the
     * processor class. This provides an extension point for future capabilities
     * without modifying this introspector.
     */
    private static Set<String> detectCustomCapabilities(Class<?> cls) {
        Set<String> keys = new HashSet<>();
        ProcessorCapability[] caps = cls.getAnnotationsByType(ProcessorCapability.class);
        for (ProcessorCapability c : caps) {
            if (c.value() != null && !c.value().isBlank()) {
                keys.add(c.value());
            }
        }
        return keys;
    }
}
