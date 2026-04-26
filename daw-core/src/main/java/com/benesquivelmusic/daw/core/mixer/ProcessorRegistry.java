package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.dsp.AnalogDistortionProcessor;
import com.benesquivelmusic.daw.core.dsp.BassExtensionProcessor;
import com.benesquivelmusic.daw.core.dsp.ChirpPeakReducer;
import com.benesquivelmusic.daw.core.dsp.ChorusProcessor;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.DelayProcessor;
import com.benesquivelmusic.daw.core.dsp.GainStagingProcessor;
import com.benesquivelmusic.daw.core.dsp.GraphicEqProcessor;
import com.benesquivelmusic.daw.core.dsp.HearingLossSimulator;
import com.benesquivelmusic.daw.core.dsp.LeslieProcessor;
import com.benesquivelmusic.daw.core.dsp.LimiterProcessor;
import com.benesquivelmusic.daw.core.dsp.NoiseGateProcessor;
import com.benesquivelmusic.daw.core.dsp.ParametricEqProcessor;
import com.benesquivelmusic.daw.core.dsp.PitchShiftProcessor;
import com.benesquivelmusic.daw.core.dsp.ReverbProcessor;
import com.benesquivelmusic.daw.core.dsp.SpringReverbProcessor;
import com.benesquivelmusic.daw.core.dsp.StereoImagerProcessor;
import com.benesquivelmusic.daw.core.dsp.TimeStretchProcessor;
import com.benesquivelmusic.daw.core.dsp.VelvetNoiseReverbProcessor;
import com.benesquivelmusic.daw.core.dsp.WaveshaperProcessor;
import com.benesquivelmusic.daw.core.dsp.reverb.ConvolutionReverbProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Annotation-driven registry of built-in {@link AudioProcessor} implementations
 * available as mixer insert effects.
 *
 * <p>On first access the registry scans a fixed list of classes for the
 * {@link InsertEffect} annotation, builds a bidirectional
 * {@link InsertEffectType} &#8596; {@link Class} map, and caches a
 * {@link MethodHandle} per class so future instantiations do not allocate a
 * reflection invocation frame.</p>
 *
 * <p>Three constructor conventions are supported (resolved once per class):</p>
 * <ul>
 *   <li>A {@code public static AudioProcessor createInsertEffect(int channels,
 *       double sampleRate)} factory method — used when a processor's
 *       constructor signature does not match the registry contract (e.g. the
 *       second {@code double} is not the sample rate).</li>
 *   <li>A {@code (int channels, double sampleRate)} constructor — the default
 *       for most processors.</li>
 *   <li>A {@code (double sampleRate)} constructor — for stereo-only processors
 *       ({@code stereoOnly = true}); the registry enforces {@code channels == 2}
 *       before invoking.</li>
 * </ul>
 *
 * <p>The registry is a stateless singleton and thread-safe after initialization;
 * all lookups are O(1) on pre-populated {@link Map} instances.</p>
 */
public final class ProcessorRegistry {

    /**
     * Explicit list of all built-in processor classes eligible for the
     * registry. Each entry must carry the {@link InsertEffect} annotation.
     * Adding a new built-in processor is a single-line change here plus the
     * annotated class itself.
     */
    private static final List<Class<? extends AudioProcessor>> KNOWN_PROCESSORS = List.of(
            CompressorProcessor.class,
            LimiterProcessor.class,
            ReverbProcessor.class,
            DelayProcessor.class,
            ChorusProcessor.class,
            NoiseGateProcessor.class,
            StereoImagerProcessor.class,
            ParametricEqProcessor.class,
            GraphicEqProcessor.class,
            AnalogDistortionProcessor.class,
            BassExtensionProcessor.class,
            ChirpPeakReducer.class,
            GainStagingProcessor.class,
            HearingLossSimulator.class,
            LeslieProcessor.class,
            PitchShiftProcessor.class,
            SpringReverbProcessor.class,
            TimeStretchProcessor.class,
            VelvetNoiseReverbProcessor.class,
            WaveshaperProcessor.class,
            ConvolutionReverbProcessor.class);

    /** Lazy holder idiom: initialized on first access, thread-safe via JLS. */
    private static final class Holder {
        static final ProcessorRegistry INSTANCE = new ProcessorRegistry();
    }

    /** Returns the shared registry instance. */
    public static ProcessorRegistry getInstance() {
        return Holder.INSTANCE;
    }

    private final Map<InsertEffectType, Entry> byType;
    private final Map<Class<? extends AudioProcessor>, InsertEffectType> byClass;
    private final List<InsertEffectType> availableTypes;

    private ProcessorRegistry() {
        Map<InsertEffectType, Entry> types = new EnumMap<>(InsertEffectType.class);
        Map<Class<? extends AudioProcessor>, InsertEffectType> classes = new HashMap<>();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();

        for (Class<? extends AudioProcessor> processorClass : KNOWN_PROCESSORS) {
            InsertEffect annotation = processorClass.getAnnotation(InsertEffect.class);
            if (annotation == null) {
                throw new IllegalStateException(
                        processorClass.getName() + " is registered as a built-in processor "
                                + "but is not annotated with @InsertEffect");
            }
            InsertEffectType type;
            try {
                type = InsertEffectType.valueOf(annotation.type());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "@InsertEffect(type=\"" + annotation.type() + "\") on "
                                + processorClass.getName()
                                + " does not match any InsertEffectType constant", e);
            }
            if (type == InsertEffectType.CLAP_PLUGIN) {
                throw new IllegalStateException(
                        "CLAP_PLUGIN must not be registered as a built-in processor");
            }
            if (types.containsKey(type)) {
                throw new IllegalStateException(
                        "Duplicate @InsertEffect registration for " + type + ": "
                                + types.get(type).processorClass.getName()
                                + " and " + processorClass.getName());
            }
            MethodHandle handle = resolveConstructor(lookup, processorClass, annotation);
            types.put(type, new Entry(processorClass, annotation, handle));
            classes.put(processorClass, type);
        }

        this.byType = Collections.unmodifiableMap(types);
        this.byClass = Collections.unmodifiableMap(classes);
        this.availableTypes = List.copyOf(types.keySet());
    }

    /**
     * Creates a fresh processor instance for the given effect type.
     *
     * @param type       the built-in effect type (must not be {@link InsertEffectType#CLAP_PLUGIN})
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     * @return a new processor instance with default settings
     * @throws IllegalArgumentException if the type is not registered or the
     *                                  processor rejects the channel count
     */
    public AudioProcessor createProcessor(InsertEffectType type, int channels, double sampleRate) {
        Objects.requireNonNull(type, "type must not be null");
        Entry entry = byType.get(type);
        if (entry == null) {
            throw new IllegalArgumentException(
                    "No registered processor for " + type
                            + " (CLAP plugins must be loaded via ClapPluginManager)");
        }
        if (entry.annotation.stereoOnly() && channels != 2) {
            throw new IllegalArgumentException(
                    entry.processorClass.getSimpleName()
                            + " supports exactly 2 channels, but got " + channels);
        }
        try {
            return switch (entry.invocationKind) {
                case STATIC_FACTORY, CHANNELS_AND_SAMPLE_RATE ->
                        (AudioProcessor) entry.handle.invoke(channels, sampleRate);
                case SAMPLE_RATE_ONLY ->
                        (AudioProcessor) entry.handle.invoke(sampleRate);
            };
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "Failed to instantiate " + entry.processorClass.getName(), t);
        }
    }

    /**
     * Returns the {@link InsertEffectType} associated with the given processor
     * instance via class-identity lookup, or {@code null} if the processor is
     * not a registered built-in type.
     */
    public InsertEffectType inferType(AudioProcessor processor) {
        if (processor == null) {
            return null;
        }
        return byClass.get(processor.getClass());
    }

    /**
     * Returns the concrete processor {@link Class} bound to the given effect
     * type, or {@code null} if the type is not registered (e.g. CLAP_PLUGIN).
     */
    public Class<? extends AudioProcessor> processorClassFor(InsertEffectType type) {
        Entry entry = byType.get(type);
        return entry == null ? null : entry.processorClass;
    }

    /**
     * Returns the list of all registered built-in effect types
     * (excludes {@link InsertEffectType#CLAP_PLUGIN}).
     */
    public List<InsertEffectType> availableTypes() {
        return availableTypes;
    }

    /**
     * Returns {@code true} if the given type has a registered processor class.
     */
    public boolean isRegistered(InsertEffectType type) {
        return byType.containsKey(type);
    }

    // ── Constructor resolution ──────────────────────────────────────────────

    private static MethodHandle resolveConstructor(MethodHandles.Lookup lookup,
                                                   Class<? extends AudioProcessor> cls,
                                                   InsertEffect annotation) {
        // 1) Optional static factory method: lets processors whose ctor has
        //    non-standard semantics (e.g. second double is not sample rate)
        //    participate in the uniform registry contract.
        try {
            Method factory = cls.getMethod("createInsertEffect", int.class, double.class);
            if (java.lang.reflect.Modifier.isStatic(factory.getModifiers())
                    && AudioProcessor.class.isAssignableFrom(factory.getReturnType())) {
                return lookup.unreflect(factory);
            }
        } catch (NoSuchMethodException ignored) {
            // fall through
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "createInsertEffect on " + cls.getName() + " is not accessible", e);
        }

        // 2) stereoOnly processors: single-argument (double sampleRate) ctor.
        if (annotation.stereoOnly()) {
            try {
                return lookup.findConstructor(cls, MethodType.methodType(void.class, double.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new IllegalStateException(
                        "stereoOnly processor " + cls.getName()
                                + " must declare a public (double sampleRate) constructor", e);
            }
        }

        // 3) Default: (int channels, double sampleRate) ctor.
        try {
            return lookup.findConstructor(cls,
                    MethodType.methodType(void.class, int.class, double.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(
                    cls.getName() + " must declare a public (int channels, double sampleRate) "
                            + "constructor or a public static AudioProcessor "
                            + "createInsertEffect(int, double) factory method", e);
        }
    }

    private enum InvocationKind {
        STATIC_FACTORY, CHANNELS_AND_SAMPLE_RATE, SAMPLE_RATE_ONLY
    }

    private static final class Entry {
        final Class<? extends AudioProcessor> processorClass;
        final InsertEffect annotation;
        final MethodHandle handle;
        final InvocationKind invocationKind;

        Entry(Class<? extends AudioProcessor> processorClass,
              InsertEffect annotation,
              MethodHandle handle) {
            this.processorClass = processorClass;
            this.annotation = annotation;
            this.handle = handle;
            // Detect kind from handle's parameter list: constructors are
            // adapted to return the class; static factories return AudioProcessor.
            MethodType type = handle.type();
            if (type.parameterCount() == 1) {
                this.invocationKind = InvocationKind.SAMPLE_RATE_ONLY;
            } else if (type.returnType().equals(processorClass)) {
                this.invocationKind = InvocationKind.CHANNELS_AND_SAMPLE_RATE;
            } else {
                this.invocationKind = InvocationKind.STATIC_FACTORY;
            }
        }
    }
}
