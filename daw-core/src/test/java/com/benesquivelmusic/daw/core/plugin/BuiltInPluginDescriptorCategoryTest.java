package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.sdk.editor.PluginCategory;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies every built-in {@link BuiltInDawPlugin} declares the
 * {@link PluginCategory} and {@code iconHint} the §6.7 plugin browser groups
 * and renders by. The expected category per plugin is hand-maintained here so
 * a future built-in plugin cannot skip declaring one, and each descriptor's
 * {@code iconHint} is cross-checked reflectively against the plugin's
 * {@link BuiltInPlugin} annotation {@code icon()}.
 *
 * <p>Descriptors are read from each plugin's static {@code DESCRIPTOR} constant
 * — the instance its {@code getDescriptor()} returns — rather than by
 * instantiating the plugin, because a no-arg construction that probes an audio
 * backend can fail headlessly and the mapping must be verified everywhere.</p>
 */
class BuiltInPluginDescriptorCategoryTest {

    /**
     * Hand-maintained expected browser category per built-in plugin class.
     * {@link ArpeggiatorPlugin} is a {@code MidiEffectPlugin} that is
     * intentionally not registered as a {@code ServiceLoader} provider (see the
     * {@code daw.core} {@code module-info}); it is listed here so its descriptor
     * is still verified.
     */
    private static final Map<Class<?>, PluginCategory> EXPECTED = Map.ofEntries(
            Map.entry(CompressorPlugin.class,          PluginCategory.DYNAMICS),
            Map.entry(BusCompressorPlugin.class,       PluginCategory.DYNAMICS),
            Map.entry(MultibandCompressorPlugin.class, PluginCategory.DYNAMICS),
            Map.entry(NoiseGatePlugin.class,           PluginCategory.DYNAMICS),
            Map.entry(DeEsserPlugin.class,             PluginCategory.DYNAMICS),
            Map.entry(TransientShaperPlugin.class,     PluginCategory.DYNAMICS),
            Map.entry(TruePeakLimiterPlugin.class,     PluginCategory.DYNAMICS),
            Map.entry(ParametricEqPlugin.class,        PluginCategory.EQ_AND_FILTER),
            Map.entry(GraphicEqPlugin.class,           PluginCategory.EQ_AND_FILTER),
            Map.entry(MatchEqPlugin.class,             PluginCategory.EQ_AND_FILTER),
            Map.entry(ReverbPlugin.class,              PluginCategory.REVERB_AND_DELAY),
            Map.entry(AcousticReverbPlugin.class,      PluginCategory.REVERB_AND_DELAY),
            Map.entry(ConvolutionReverbPlugin.class,   PluginCategory.REVERB_AND_DELAY),
            Map.entry(MidSideWrapperPlugin.class,      PluginCategory.SPATIAL),
            Map.entry(BinauralMonitorPlugin.class,     PluginCategory.SPATIAL),
            Map.entry(SpectrumAnalyzerPlugin.class,    PluginCategory.ANALYZER),
            Map.entry(SoundWaveTelemetryPlugin.class,  PluginCategory.ANALYZER),
            Map.entry(TunerPlugin.class,               PluginCategory.ANALYZER),
            Map.entry(DitherPlugin.class,              PluginCategory.UTILITY),
            Map.entry(MetronomePlugin.class,           PluginCategory.UTILITY),
            Map.entry(SignalGeneratorPlugin.class,     PluginCategory.UTILITY),
            Map.entry(ExciterPlugin.class,             PluginCategory.UTILITY),
            Map.entry(WaveshaperPlugin.class,          PluginCategory.UTILITY),
            Map.entry(VirtualKeyboardPlugin.class,     PluginCategory.INSTRUMENT),
            Map.entry(ArpeggiatorPlugin.class,         PluginCategory.MIDI_EFFECT));

    @Test
    void everyDiscoveredBuiltInPluginMustDeclareAnExpectedCategory() {
        // A future registered plugin fails here until it is added to EXPECTED.
        for (Class<? extends BuiltInDawPlugin> clazz : BuiltInPluginProviders.providerClasses()) {
            assertThat(EXPECTED)
                    .as("%s must declare an expected browser category", clazz.getSimpleName())
                    .containsKey(clazz);
        }
    }

    @Test
    void everyDescriptorCategoryAndIconHintMustMatchTheAnnotation() {
        for (Map.Entry<Class<?>, PluginCategory> expected : EXPECTED.entrySet()) {
            Class<?> clazz = expected.getKey();
            BuiltInPlugin annotation = clazz.getAnnotation(BuiltInPlugin.class);
            assertThat(annotation)
                    .as("%s must be annotated with @BuiltInPlugin", clazz.getSimpleName())
                    .isNotNull();

            PluginDescriptor descriptor = staticDescriptor(clazz);

            assertThat(descriptor.category())
                    .as("%s descriptor category", clazz.getSimpleName())
                    .isEqualTo(expected.getValue());
            assertThat(descriptor.iconHint())
                    .as("%s descriptor iconHint", clazz.getSimpleName())
                    .isNotBlank()
                    .isEqualTo(annotation.icon());
        }
    }

    /**
     * Reads {@code clazz}'s static {@code DESCRIPTOR} constant — the instance
     * its {@code getDescriptor()} returns — without instantiating the plugin,
     * so the mapping assertions can never be skipped by a construction failure.
     */
    private static PluginDescriptor staticDescriptor(Class<?> clazz) {
        try {
            Field field = clazz.getDeclaredField("DESCRIPTOR");
            field.setAccessible(true);
            return (PluginDescriptor) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(clazz.getSimpleName()
                    + " must expose its descriptor as a static DESCRIPTOR constant"
                    + " so this test can verify it without instantiating the plugin", e);
        }
    }
}
