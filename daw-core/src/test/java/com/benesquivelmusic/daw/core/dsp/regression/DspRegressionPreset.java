package com.benesquivelmusic.daw.core.dsp.regression;

import com.benesquivelmusic.daw.core.preset.ReflectivePresetSerializer;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Canonical per-processor parameter presets used by the DSP regression
 * framework.
 *
 * <p>The framework specifies that <em>every</em> regression-tested
 * processor must define at least three presets — {@link #DEFAULT},
 * {@link #AGGRESSIVE} and {@link #SUBTLE} — so that golden-file coverage
 * exercises the processor across its dynamic range, not just at one
 * working point.</p>
 *
 * <p>Presets are registered programmatically through
 * {@link #register(Class, String, Function)}: each registration provides
 * a {@link Function} that mutates a freshly-constructed processor into
 * the named preset state. Storing presets as code (not data files)
 * keeps the diffs tightly tied to the processor's parameter API: any
 * rename or removal is caught at compile time.</p>
 *
 * <p>Once registered, {@link #apply(AudioProcessor, String)} drives the
 * processor into the named preset and returns the resulting parameter
 * snapshot for inclusion in golden-file metadata.</p>
 */
public final class DspRegressionPreset {

    /** The neutral, "shipping defaults" preset every processor must define. */
    public static final String DEFAULT    = "Default";
    /** A heavy-handed preset that pushes the processor toward its max effect. */
    public static final String AGGRESSIVE = "Aggressive";
    /** A gentle preset that exercises the processor at low intensity. */
    public static final String SUBTLE     = "Subtle";

    /** Canonical list of required preset names in iteration order. */
    public static final String[] CANONICAL = { DEFAULT, AGGRESSIVE, SUBTLE };

    /** processor class → (preset name → mutator). */
    private static final Map<Class<? extends AudioProcessor>,
                              Map<String, Function<AudioProcessor, AudioProcessor>>> PRESETS =
            new ConcurrentHashMap<>();

    private DspRegressionPreset() {}

    /**
     * Registers a preset for {@code processorClass}. The {@code mutator} is
     * called on a freshly-constructed processor and returns the same instance
     * after applying the preset's parameter values (or a derived instance if
     * the preset re-creates the processor — the result is what gets used).
     *
     * <p>Registrations are typically done in a {@code static} initializer of
     * a class shared by the per-processor regression tests.</p>
     */
    @SuppressWarnings("unchecked")
    public static <P extends AudioProcessor> void register(
            Class<P> processorClass,
            String presetName,
            Function<P, P> mutator) {
        Objects.requireNonNull(processorClass, "processorClass");
        Objects.requireNonNull(presetName, "presetName");
        Objects.requireNonNull(mutator, "mutator");
        PRESETS.computeIfAbsent(processorClass, k -> new ConcurrentHashMap<>())
                .put(presetName, p -> (AudioProcessor) mutator.apply((P) p));
    }

    /** @return {@code true} if the preset has been registered for the given class. */
    public static boolean isRegistered(Class<? extends AudioProcessor> processorClass,
                                       String presetName) {
        Map<String, ?> byName = PRESETS.get(processorClass);
        return byName != null && byName.containsKey(presetName);
    }

    /**
     * Drives {@code processor} into the named preset state and returns its
     * final parameter snapshot (key = {@code @ProcessorParam} name).
     *
     * @throws IllegalArgumentException if the preset has not been registered.
     */
    public static Map<String, Double> apply(AudioProcessor processor, String presetName) {
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(presetName, "presetName");
        Map<String, Function<AudioProcessor, AudioProcessor>> byName =
                PRESETS.get(processor.getClass());
        if (byName == null || !byName.containsKey(presetName)) {
            throw new IllegalArgumentException(
                    "No '" + presetName + "' preset registered for "
                            + processor.getClass().getSimpleName()
                            + " — register one with DspRegressionPreset.register(...)");
        }
        AudioProcessor resolved = byName.get(presetName).apply(processor);
        Map<String, Double> snap = ReflectivePresetSerializer.isSupported(resolved)
                ? ReflectivePresetSerializer.snapshot(resolved)
                : new LinkedHashMap<>();
        return Map.copyOf(snap);
    }

    /**
     * @return the names of all registered presets for the given processor
     *         class — in <em>some</em> order. Used by the coverage check to
     *         report missing canonical presets.
     */
    public static java.util.Set<String> registeredPresets(
            Class<? extends AudioProcessor> processorClass) {
        Map<String, ?> byName = PRESETS.get(processorClass);
        return byName == null ? java.util.Set.of() : java.util.Set.copyOf(byName.keySet());
    }
}
