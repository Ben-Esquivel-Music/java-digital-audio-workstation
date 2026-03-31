package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.dsp.ChorusProcessor;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.DelayProcessor;
import com.benesquivelmusic.daw.core.dsp.GraphicEqProcessor;
import com.benesquivelmusic.daw.core.dsp.LimiterProcessor;
import com.benesquivelmusic.daw.core.dsp.NoiseGateProcessor;
import com.benesquivelmusic.daw.core.dsp.ParametricEqProcessor;
import com.benesquivelmusic.daw.core.dsp.ReverbProcessor;
import com.benesquivelmusic.daw.core.dsp.StereoImagerProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Factory for creating built-in DSP processors, their parameter descriptors,
 * and parameter-change handlers from {@link InsertEffectType} values.
 *
 * <p>This class bridges the gap between the {@code InsertEffectType} enum and
 * the concrete {@link AudioProcessor} implementations in the DSP package,
 * enabling the mixer UI to instantiate, configure, and edit built-in effects.</p>
 */
public final class InsertEffectFactory {

    private InsertEffectFactory() {
        // utility class
    }

    /**
     * Creates an {@link AudioProcessor} for the given effect type.
     *
     * @param type       the built-in effect type
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     * @return a new processor instance with default settings
     * @throws IllegalArgumentException if {@code type} is {@link InsertEffectType#CLAP_PLUGIN}
     */
    public static AudioProcessor createProcessor(InsertEffectType type, int channels, double sampleRate) {
        Objects.requireNonNull(type, "type must not be null");
        return switch (type) {
            case COMPRESSOR    -> new CompressorProcessor(channels, sampleRate);
            case LIMITER       -> new LimiterProcessor(channels, sampleRate);
            case REVERB        -> new ReverbProcessor(channels, sampleRate);
            case DELAY         -> new DelayProcessor(channels, sampleRate);
            case CHORUS        -> new ChorusProcessor(channels, sampleRate);
            case NOISE_GATE    -> new NoiseGateProcessor(channels, sampleRate);
            case STEREO_IMAGER -> {
                if (channels != 2) {
                    throw new IllegalArgumentException(
                            "StereoImagerProcessor supports exactly 2 channels, but got " + channels);
                }
                yield new StereoImagerProcessor(sampleRate);
            }
            case PARAMETRIC_EQ -> new ParametricEqProcessor(channels, sampleRate);
            case GRAPHIC_EQ    -> new GraphicEqProcessor(channels, sampleRate);
            case CLAP_PLUGIN   -> throw new IllegalArgumentException(
                    "CLAP plugins must be loaded via ClapPluginManager, not this factory");
        };
    }

    /**
     * Creates an {@link InsertSlot} for the given effect type with a fresh processor.
     *
     * @param type       the built-in effect type
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     * @return a new insert slot containing the processor
     */
    public static InsertSlot createSlot(InsertEffectType type, int channels, double sampleRate) {
        Objects.requireNonNull(type, "type must not be null");
        AudioProcessor processor = createProcessor(type, channels, sampleRate);
        return new InsertSlot(type.getDisplayName(), processor, type);
    }

    /**
     * Returns the {@link PluginParameter} descriptors for the given effect type.
     *
     * <p>Each parameter has a unique id (starting from 0), a human-readable name,
     * min/max range, and default value. These descriptors can be passed directly
     * to a UI parameter editor panel or other application-layer component responsible for editing parameters.</p>
     *
     * @param type the built-in effect type
     * @return the list of parameter descriptors
     */
    public static List<PluginParameter> getParameterDescriptors(InsertEffectType type) {
        Objects.requireNonNull(type, "type must not be null");
        return switch (type) {
            case COMPRESSOR -> List.of(
                    new PluginParameter(0, "Threshold (dB)", -60.0, 0.0, -20.0),
                    new PluginParameter(1, "Ratio", 1.0, 20.0, 4.0),
                    new PluginParameter(2, "Attack (ms)", 0.01, 100.0, 10.0),
                    new PluginParameter(3, "Release (ms)", 10.0, 1000.0, 100.0),
                    new PluginParameter(4, "Knee (dB)", 0.0, 24.0, 6.0),
                    new PluginParameter(5, "Makeup Gain (dB)", 0.0, 30.0, 0.0));
            case LIMITER -> List.of(
                    new PluginParameter(0, "Ceiling (dB)", -12.0, 0.0, -1.0),
                    new PluginParameter(1, "Attack (ms)", 0.01, 50.0, 0.3),
                    new PluginParameter(2, "Release (ms)", 10.0, 500.0, 100.0));
            case REVERB -> List.of(
                    new PluginParameter(0, "Room Size", 0.0, 1.0, 0.5),
                    new PluginParameter(1, "Decay", 0.0, 1.0, 0.5),
                    new PluginParameter(2, "Damping", 0.0, 1.0, 0.3),
                    new PluginParameter(3, "Mix", 0.0, 1.0, 0.3));
            case DELAY -> List.of(
                    new PluginParameter(0, "Delay (ms)", 1.0, 2000.0, 500.0),
                    new PluginParameter(1, "Feedback", 0.0, 0.99, 0.3),
                    new PluginParameter(2, "Mix", 0.0, 1.0, 0.5));
            case CHORUS -> List.of(
                    new PluginParameter(0, "Rate (Hz)", 0.1, 10.0, 1.0),
                    new PluginParameter(1, "Depth (ms)", 0.1, 20.0, 5.0),
                    new PluginParameter(2, "Base Delay (ms)", 1.0, 50.0, 10.0),
                    new PluginParameter(3, "Mix", 0.0, 1.0, 0.5));
            case NOISE_GATE -> List.of(
                    new PluginParameter(0, "Threshold (dB)", -80.0, 0.0, -40.0),
                    new PluginParameter(1, "Attack (ms)", 0.01, 50.0, 1.0),
                    new PluginParameter(2, "Hold (ms)", 0.0, 500.0, 50.0),
                    new PluginParameter(3, "Release (ms)", 1.0, 500.0, 50.0),
                    new PluginParameter(4, "Range (dB)", -80.0, 0.0, -80.0));
            case STEREO_IMAGER -> List.of(
                    new PluginParameter(0, "Width", 0.0, 2.0, 1.0));
            case PARAMETRIC_EQ -> List.of();
            case GRAPHIC_EQ -> List.of(
                    new PluginParameter(0, "Q", 0.1, 10.0, 1.0));
            case CLAP_PLUGIN -> List.of();
        };
    }

    /**
     * Returns a {@link BiConsumer} that applies parameter value changes to the
     * given processor.
     *
     * <p>The consumer maps parameter ids (from {@link #getParameterDescriptors})
     * to the appropriate setter on the processor. Unknown ids are silently ignored.</p>
     *
     * @param type      the effect type
     * @param processor the processor instance to configure
     * @return a parameter-change handler
     */
    public static BiConsumer<Integer, Double> createParameterHandler(InsertEffectType type,
                                                                     AudioProcessor processor) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(processor, "processor must not be null");
        return switch (type) {
            case COMPRESSOR -> {
                if (!(processor instanceof CompressorProcessor p)) {
                    throw new IllegalArgumentException("Expected CompressorProcessor, got " + processor.getClass().getSimpleName());
                }
                yield compressorHandler(p);
            }
            case LIMITER -> {
                if (!(processor instanceof LimiterProcessor p)) {
                    throw new IllegalArgumentException("Expected LimiterProcessor, got " + processor.getClass().getSimpleName());
                }
                yield limiterHandler(p);
            }
            case REVERB -> {
                if (!(processor instanceof ReverbProcessor p)) {
                    throw new IllegalArgumentException("Expected ReverbProcessor, got " + processor.getClass().getSimpleName());
                }
                yield reverbHandler(p);
            }
            case DELAY -> {
                if (!(processor instanceof DelayProcessor p)) {
                    throw new IllegalArgumentException("Expected DelayProcessor, got " + processor.getClass().getSimpleName());
                }
                yield delayHandler(p);
            }
            case CHORUS -> {
                if (!(processor instanceof ChorusProcessor p)) {
                    throw new IllegalArgumentException("Expected ChorusProcessor, got " + processor.getClass().getSimpleName());
                }
                yield chorusHandler(p);
            }
            case NOISE_GATE -> {
                if (!(processor instanceof NoiseGateProcessor p)) {
                    throw new IllegalArgumentException("Expected NoiseGateProcessor, got " + processor.getClass().getSimpleName());
                }
                yield noiseGateHandler(p);
            }
            case STEREO_IMAGER -> {
                if (!(processor instanceof StereoImagerProcessor p)) {
                    throw new IllegalArgumentException("Expected StereoImagerProcessor, got " + processor.getClass().getSimpleName());
                }
                yield stereoImagerHandler(p);
            }
            case PARAMETRIC_EQ -> (_, _) -> { };
            case GRAPHIC_EQ -> {
                if (!(processor instanceof GraphicEqProcessor p)) {
                    throw new IllegalArgumentException("Expected GraphicEqProcessor, got " + processor.getClass().getSimpleName());
                }
                yield graphicEqHandler(p);
            }
            case CLAP_PLUGIN -> (_, _) -> { };
        };
    }

    /**
     * Returns the list of built-in effect types available for insert slots
     * (excludes {@link InsertEffectType#CLAP_PLUGIN}).
     *
     * @return the available built-in effect types
     */
    public static List<InsertEffectType> availableTypes() {
        return List.of(
                InsertEffectType.PARAMETRIC_EQ,
                InsertEffectType.COMPRESSOR,
                InsertEffectType.LIMITER,
                InsertEffectType.REVERB,
                InsertEffectType.DELAY,
                InsertEffectType.CHORUS,
                InsertEffectType.NOISE_GATE,
                InsertEffectType.STEREO_IMAGER,
                InsertEffectType.GRAPHIC_EQ);
    }

    /**
     * Returns the current parameter values from the given processor, keyed by
     * the parameter ids defined in {@link #getParameterDescriptors}.
     *
     * <p>This enables a parameter editor to initialize its controls with the
     * processor's actual state rather than the descriptor defaults.</p>
     *
     * @param type      the effect type
     * @param processor the processor instance to read values from
     * @return a map of parameter id to current value
     */
    public static Map<Integer, Double> getParameterValues(InsertEffectType type,
                                                           AudioProcessor processor) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(processor, "processor must not be null");
        Map<Integer, Double> values = new LinkedHashMap<>();
        switch (type) {
            case COMPRESSOR -> {
                if (!(processor instanceof CompressorProcessor p)) {
                    throw new IllegalArgumentException("Expected CompressorProcessor, got " + processor.getClass().getSimpleName());
                }
                values.put(0, p.getThresholdDb());
                values.put(1, p.getRatio());
                values.put(2, p.getAttackMs());
                values.put(3, p.getReleaseMs());
                values.put(4, p.getKneeDb());
                values.put(5, p.getMakeupGainDb());
            }
            case LIMITER -> {
                if (!(processor instanceof LimiterProcessor p)) {
                    throw new IllegalArgumentException("Expected LimiterProcessor, got " + processor.getClass().getSimpleName());
                }
                values.put(0, p.getCeilingDb());
                values.put(1, p.getAttackMs());
                values.put(2, p.getReleaseMs());
            }
            case REVERB -> {
                if (!(processor instanceof ReverbProcessor p)) {
                    throw new IllegalArgumentException("Expected ReverbProcessor, got " + processor.getClass().getSimpleName());
                }
                values.put(0, p.getRoomSize());
                values.put(1, p.getDecay());
                values.put(2, p.getDamping());
                values.put(3, p.getMix());
            }
            case DELAY -> {
                if (!(processor instanceof DelayProcessor p)) {
                    throw new IllegalArgumentException("Expected DelayProcessor, got " + processor.getClass().getSimpleName());
                }
                values.put(0, p.getDelayMs());
                values.put(1, p.getFeedback());
                values.put(2, p.getMix());
            }
            case CHORUS -> {
                if (!(processor instanceof ChorusProcessor p)) {
                    throw new IllegalArgumentException("Expected ChorusProcessor, got " + processor.getClass().getSimpleName());
                }
                values.put(0, p.getRateHz());
                values.put(1, p.getDepthMs());
                values.put(2, p.getBaseDelayMs());
                values.put(3, p.getMix());
            }
            case NOISE_GATE -> {
                if (!(processor instanceof NoiseGateProcessor p)) {
                    throw new IllegalArgumentException("Expected NoiseGateProcessor, got " + processor.getClass().getSimpleName());
                }
                values.put(0, p.getThresholdDb());
                values.put(1, p.getAttackMs());
                values.put(2, p.getHoldMs());
                values.put(3, p.getReleaseMs());
                values.put(4, p.getRangeDb());
            }
            case STEREO_IMAGER -> {
                if (!(processor instanceof StereoImagerProcessor p)) {
                    throw new IllegalArgumentException("Expected StereoImagerProcessor, got " + processor.getClass().getSimpleName());
                }
                values.put(0, p.getWidth());
            }
            case GRAPHIC_EQ -> {
                if (!(processor instanceof GraphicEqProcessor p)) {
                    throw new IllegalArgumentException("Expected GraphicEqProcessor, got " + processor.getClass().getSimpleName());
                }
                values.put(0, p.getQ());
            }
            case PARAMETRIC_EQ, CLAP_PLUGIN -> { }
        }
        return values;
    }

    // ── Parameter handlers for each processor type ──────────────────────────

    private static BiConsumer<Integer, Double> compressorHandler(CompressorProcessor p) {
        return (id, value) -> {
            switch (id) {
                case 0 -> p.setThresholdDb(value);
                case 1 -> p.setRatio(value);
                case 2 -> p.setAttackMs(value);
                case 3 -> p.setReleaseMs(value);
                case 4 -> p.setKneeDb(value);
                case 5 -> p.setMakeupGainDb(value);
                default -> { }
            }
        };
    }

    private static BiConsumer<Integer, Double> limiterHandler(LimiterProcessor p) {
        return (id, value) -> {
            switch (id) {
                case 0 -> p.setCeilingDb(value);
                case 1 -> p.setAttackMs(value);
                case 2 -> p.setReleaseMs(value);
                default -> { }
            }
        };
    }

    private static BiConsumer<Integer, Double> reverbHandler(ReverbProcessor p) {
        return (id, value) -> {
            switch (id) {
                case 0 -> p.setRoomSize(value);
                case 1 -> p.setDecay(value);
                case 2 -> p.setDamping(value);
                case 3 -> p.setMix(value);
                default -> { }
            }
        };
    }

    private static BiConsumer<Integer, Double> delayHandler(DelayProcessor p) {
        return (id, value) -> {
            switch (id) {
                case 0 -> p.setDelayMs(value);
                case 1 -> p.setFeedback(value);
                case 2 -> p.setMix(value);
                default -> { }
            }
        };
    }

    private static BiConsumer<Integer, Double> chorusHandler(ChorusProcessor p) {
        return (id, value) -> {
            switch (id) {
                case 0 -> p.setRateHz(value);
                case 1 -> p.setDepthMs(value);
                case 2 -> p.setBaseDelayMs(value);
                case 3 -> p.setMix(value);
                default -> { }
            }
        };
    }

    private static BiConsumer<Integer, Double> noiseGateHandler(NoiseGateProcessor p) {
        return (id, value) -> {
            switch (id) {
                case 0 -> p.setThresholdDb(value);
                case 1 -> p.setAttackMs(value);
                case 2 -> p.setHoldMs(value);
                case 3 -> p.setReleaseMs(value);
                case 4 -> p.setRangeDb(value);
                default -> { }
            }
        };
    }

    private static BiConsumer<Integer, Double> stereoImagerHandler(StereoImagerProcessor p) {
        return (id, value) -> {
            if (id == 0) {
                p.setWidth(value);
            }
        };
    }

    private static BiConsumer<Integer, Double> graphicEqHandler(GraphicEqProcessor p) {
        return (id, value) -> {
            if (id == 0) {
                p.setQ(value);
            }
        };
    }
}
