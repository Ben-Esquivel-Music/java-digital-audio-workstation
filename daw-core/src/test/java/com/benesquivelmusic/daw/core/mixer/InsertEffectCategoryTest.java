package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.editor.PluginCategory;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the {@link PluginCategory} declared by every registered
 * {@link InsertEffect @InsertEffect} processor. The §6.7 plugin browser groups
 * built-in insert effects by these categories, so a newly registered effect
 * must fail here until it is added to {@link #EXPECTED}.
 */
class InsertEffectCategoryTest {

    /** Hand-maintained expected category per registered built-in effect type. */
    private static final Map<InsertEffectType, PluginCategory> EXPECTED = expected();

    private static Map<InsertEffectType, PluginCategory> expected() {
        Map<InsertEffectType, PluginCategory> map = new EnumMap<>(InsertEffectType.class);
        map.put(InsertEffectType.COMPRESSOR, PluginCategory.DYNAMICS);
        map.put(InsertEffectType.LIMITER, PluginCategory.DYNAMICS);
        map.put(InsertEffectType.NOISE_GATE, PluginCategory.DYNAMICS);
        map.put(InsertEffectType.CHIRP_PEAK_REDUCER, PluginCategory.DYNAMICS);
        map.put(InsertEffectType.PARAMETRIC_EQ, PluginCategory.EQ_AND_FILTER);
        map.put(InsertEffectType.GRAPHIC_EQ, PluginCategory.EQ_AND_FILTER);
        map.put(InsertEffectType.BASS_EXTENSION, PluginCategory.EQ_AND_FILTER);
        map.put(InsertEffectType.CHORUS, PluginCategory.MODULATION);
        map.put(InsertEffectType.LESLIE, PluginCategory.MODULATION);
        map.put(InsertEffectType.PITCH_SHIFT, PluginCategory.MODULATION);
        map.put(InsertEffectType.REVERB, PluginCategory.REVERB_AND_DELAY);
        map.put(InsertEffectType.DELAY, PluginCategory.REVERB_AND_DELAY);
        map.put(InsertEffectType.SPRING_REVERB, PluginCategory.REVERB_AND_DELAY);
        map.put(InsertEffectType.VELVET_NOISE_REVERB, PluginCategory.REVERB_AND_DELAY);
        map.put(InsertEffectType.CONVOLUTION_REVERB, PluginCategory.REVERB_AND_DELAY);
        map.put(InsertEffectType.STEREO_IMAGER, PluginCategory.SPATIAL);
        map.put(InsertEffectType.ANALOG_DISTORTION, PluginCategory.UTILITY);
        map.put(InsertEffectType.GAIN_STAGING, PluginCategory.UTILITY);
        map.put(InsertEffectType.HEARING_LOSS_SIMULATOR, PluginCategory.UTILITY);
        map.put(InsertEffectType.TIME_STRETCH, PluginCategory.UTILITY);
        map.put(InsertEffectType.WAVESHAPER, PluginCategory.UTILITY);
        return map;
    }

    @Test
    void expectedMappingShouldExactlyCoverEveryRegisteredType() {
        List<InsertEffectType> available = InsertEffectFactory.availableTypes();
        // Both directions: every registered effect must be mapped, and no stale
        // mapping may survive for a type that is no longer registered. A newly
        // registered effect therefore fails until it is added to EXPECTED.
        assertThat(EXPECTED.keySet()).containsExactlyInAnyOrderElementsOf(available);
    }

    @Test
    void getCategoryShouldMatchExpectedForEveryRegisteredType() {
        for (Map.Entry<InsertEffectType, PluginCategory> entry : EXPECTED.entrySet()) {
            assertThat(InsertEffectFactory.getCategory(entry.getKey()))
                    .as("category of %s", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    void getCategoryShouldRejectClapPlugin() {
        assertThatThrownBy(() -> InsertEffectFactory.getCategory(InsertEffectType.CLAP_PLUGIN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
