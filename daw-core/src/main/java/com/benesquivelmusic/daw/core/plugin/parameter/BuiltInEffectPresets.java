package com.benesquivelmusic.daw.core.plugin.parameter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides factory presets for built-in DSP effects.
 *
 * <p>Each method returns a list of {@link ParameterPreset} instances with
 * genre/use-case-specific starting values. These presets serve as starting
 * points for users — they are not final settings.</p>
 *
 * <p>Parameter ids correspond to the parameters exposed by the built-in
 * DSP processors. The ids follow a simple convention:</p>
 * <ul>
 *   <li>Compressor: 0=threshold, 1=ratio, 2=attack, 3=release, 4=knee, 5=makeupGain</li>
 *   <li>Reverb: 0=roomSize, 1=damping, 2=wetLevel, 3=dryLevel, 4=width</li>
 *   <li>Delay: 0=delayTime, 1=feedback, 2=wetLevel, 3=dryLevel</li>
 *   <li>EQ: 0=lowGain, 1=midGain, 2=highGain, 3=lowFreq, 4=midFreq, 5=highFreq</li>
 * </ul>
 */
public final class BuiltInEffectPresets {

    private BuiltInEffectPresets() {
        // utility class
    }

    /**
     * Returns factory presets for the compressor effect.
     *
     * @return a list of compressor presets
     */
    public static List<ParameterPreset> compressorPresets() {
        return List.of(
                ParameterPreset.factory("Drum Bus Compression", mapOf(
                        0, -18.0,  // threshold dB
                        1, 4.0,    // ratio
                        2, 5.0,    // attack ms
                        3, 80.0,   // release ms
                        4, 6.0,    // knee dB
                        5, 4.0     // makeup gain dB
                )),
                ParameterPreset.factory("Gentle Vocal Compression", mapOf(
                        0, -24.0,
                        1, 2.5,
                        2, 15.0,
                        3, 150.0,
                        4, 10.0,
                        5, 3.0
                )),
                ParameterPreset.factory("Parallel Crush", mapOf(
                        0, -30.0,
                        1, 20.0,
                        2, 0.1,
                        3, 50.0,
                        4, 0.0,
                        5, 12.0
                )),
                ParameterPreset.factory("Master Bus Glue", mapOf(
                        0, -16.0,
                        1, 2.0,
                        2, 30.0,
                        3, 200.0,
                        4, 8.0,
                        5, 1.0
                ))
        );
    }

    /**
     * Returns factory presets for the reverb effect.
     *
     * @return a list of reverb presets
     */
    public static List<ParameterPreset> reverbPresets() {
        return List.of(
                ParameterPreset.factory("Small Room", mapOf(
                        0, 0.3,    // room size
                        1, 0.5,    // damping
                        2, 0.25,   // wet level
                        3, 0.75,   // dry level
                        4, 0.8     // width
                )),
                ParameterPreset.factory("Large Hall", mapOf(
                        0, 0.85,
                        1, 0.3,
                        2, 0.35,
                        3, 0.65,
                        4, 1.0
                )),
                ParameterPreset.factory("Plate Vocal", mapOf(
                        0, 0.5,
                        1, 0.7,
                        2, 0.2,
                        3, 0.8,
                        4, 0.9
                )),
                ParameterPreset.factory("Ambient Wash", mapOf(
                        0, 0.95,
                        1, 0.2,
                        2, 0.6,
                        3, 0.4,
                        4, 1.0
                ))
        );
    }

    /**
     * Returns factory presets for the delay effect.
     *
     * @return a list of delay presets
     */
    public static List<ParameterPreset> delayPresets() {
        return List.of(
                ParameterPreset.factory("Slapback", mapOf(
                        0, 80.0,   // delay time ms
                        1, 0.1,    // feedback
                        2, 0.3,    // wet level
                        3, 0.7     // dry level
                )),
                ParameterPreset.factory("Quarter Note Echo", mapOf(
                        0, 375.0,
                        1, 0.35,
                        2, 0.25,
                        3, 0.75
                )),
                ParameterPreset.factory("Ping Pong", mapOf(
                        0, 250.0,
                        1, 0.45,
                        2, 0.3,
                        3, 0.7
                )),
                ParameterPreset.factory("Long Ambient Delay", mapOf(
                        0, 750.0,
                        1, 0.55,
                        2, 0.4,
                        3, 0.6
                ))
        );
    }

    /**
     * Returns factory presets for the parametric EQ effect.
     *
     * @return a list of EQ presets
     */
    public static List<ParameterPreset> eqPresets() {
        return List.of(
                ParameterPreset.factory("Warm Vocal EQ", mapOf(
                        0, -2.0,     // low gain dB
                        1, 3.0,      // mid gain dB
                        2, 1.5,      // high gain dB
                        3, 200.0,    // low freq Hz
                        4, 2500.0,   // mid freq Hz
                        5, 8000.0    // high freq Hz
                )),
                ParameterPreset.factory("Bright Acoustic Guitar", mapOf(
                        0, -1.0,
                        1, 1.5,
                        2, 4.0,
                        3, 150.0,
                        4, 3000.0,
                        5, 10000.0
                )),
                ParameterPreset.factory("Bass Boost", mapOf(
                        0, 5.0,
                        1, 0.0,
                        2, -1.0,
                        3, 80.0,
                        4, 1000.0,
                        5, 6000.0
                )),
                ParameterPreset.factory("High-Pass Cleanup", mapOf(
                        0, -6.0,
                        1, 0.0,
                        2, 0.0,
                        3, 100.0,
                        4, 1000.0,
                        5, 8000.0
                ))
        );
    }

    /**
     * Returns factory presets for all built-in effect types, keyed by effect name.
     *
     * @return a map of effect name to list of presets
     */
    public static Map<String, List<ParameterPreset>> allPresets() {
        Map<String, List<ParameterPreset>> all = new LinkedHashMap<>();
        all.put("Compressor", compressorPresets());
        all.put("Reverb", reverbPresets());
        all.put("Delay", delayPresets());
        all.put("Parametric EQ", eqPresets());
        return Collections.unmodifiableMap(all);
    }

    private static Map<Integer, Double> mapOf(Object... keysAndValues) {
        Map<Integer, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Integer key = (Integer) keysAndValues[i];
            Double value = ((Number) keysAndValues[i + 1]).doubleValue();
            map.put(key, value);
        }
        return map;
    }
}
