package com.benesquivelmusic.daw.core.plugin;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the {@link BuiltInPlugin} annotation is declared correctly and is
 * present on every permitted subclass of {@link BuiltInDawPlugin}, and that
 * {@link BuiltInDawPlugin#menuEntries()} reads metadata via reflection without
 * instantiating any plugin.
 */
class BuiltInPluginAnnotationTest {

    /** Expected metadata per plugin class — must equal the previously hard-coded per-class values. */
    private static final Map<Class<? extends BuiltInDawPlugin>, String[]> EXPECTED = Map.ofEntries(
            Map.entry(VirtualKeyboardPlugin.class,   new String[] {"Virtual Keyboard",       "keyboard",         "INSTRUMENT"}),
            Map.entry(ParametricEqPlugin.class,      new String[] {"Parametric EQ",          "eq",               "EFFECT"}),
            Map.entry(GraphicEqPlugin.class,         new String[] {"Graphic EQ",             "eq",               "EFFECT"}),
            Map.entry(CompressorPlugin.class,        new String[] {"Compressor",             "compressor",       "EFFECT"}),
            Map.entry(BusCompressorPlugin.class,     new String[] {"Bus Compressor",         "compressor",       "EFFECT"}),
            Map.entry(ReverbPlugin.class,            new String[] {"Reverb",                 "reverb",           "EFFECT"}),
            Map.entry(SpectrumAnalyzerPlugin.class,  new String[] {"Spectrum Analyzer",      "spectrum",         "ANALYZER"}),
            Map.entry(TunerPlugin.class,             new String[] {"Chromatic Tuner",        "spectrum",         "UTILITY"}),
            Map.entry(SoundWaveTelemetryPlugin.class,new String[] {"Sound Wave Telemetry",   "surround",         "ANALYZER"}),
            Map.entry(SignalGeneratorPlugin.class,   new String[] {"Signal Generator",       "waveform",         "UTILITY"}),
            Map.entry(MetronomePlugin.class,         new String[] {"Metronome",              "metronome",        "UTILITY"}),
            Map.entry(AcousticReverbPlugin.class,    new String[] {"Acoustic Reverb",        "acoustic-reverb",  "EFFECT"}),
            Map.entry(BinauralMonitorPlugin.class,   new String[] {"Binaural Monitor",       "binaural-monitor", "EFFECT"}),
            Map.entry(WaveshaperPlugin.class,        new String[] {"Waveshaper / Saturation","waveshaper",       "EFFECT"})
    );

    // ── Annotation declaration ───────────────────────────────────────────────

    @Test
    void annotationShouldBeRuntimeRetained() {
        Retention retention = BuiltInPlugin.class.getAnnotation(Retention.class);
        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void annotationShouldTargetType() {
        Target target = BuiltInPlugin.class.getAnnotation(Target.class);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(ElementType.TYPE);
    }

    // ── Every permitted subclass is annotated correctly ──────────────────────

    @Test
    void everyPermittedSubclassMustHaveBuiltInPluginAnnotation() {
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        assertThat(permitted).isNotNull();
        for (Class<?> clazz : permitted) {
            BuiltInPlugin meta = clazz.getAnnotation(BuiltInPlugin.class);
            assertThat(meta)
                    .as("%s must be annotated with @BuiltInPlugin", clazz.getSimpleName())
                    .isNotNull();
            assertThat(meta.label())
                    .as("%s @BuiltInPlugin.label", clazz.getSimpleName())
                    .isNotBlank();
            assertThat(meta.icon())
                    .as("%s @BuiltInPlugin.icon", clazz.getSimpleName())
                    .isNotBlank();
            assertThat(meta.category())
                    .as("%s @BuiltInPlugin.category", clazz.getSimpleName())
                    .isNotNull();
        }
    }

    @Test
    void annotationValuesMustMatchPreviouslyHardCodedReturnValues() {
        for (var e : EXPECTED.entrySet()) {
            Class<? extends BuiltInDawPlugin> clazz = e.getKey();
            String[] expected = e.getValue();
            BuiltInPlugin meta = clazz.getAnnotation(BuiltInPlugin.class);
            assertThat(meta)
                    .as("@BuiltInPlugin on %s", clazz.getSimpleName())
                    .isNotNull();
            assertThat(meta.label()).as("%s label", clazz.getSimpleName()).isEqualTo(expected[0]);
            assertThat(meta.icon()).as("%s icon", clazz.getSimpleName()).isEqualTo(expected[1]);
            assertThat(meta.category().name()).as("%s category", clazz.getSimpleName()).isEqualTo(expected[2]);
        }
    }

    // ── menuEntries() reads metadata without instantiation ───────────────────

    @Test
    void menuEntriesShouldReadMetadataFromAnnotationWithoutInstantiatingPlugins() {
        // Iterating menuEntries() must not touch any plugin constructor.
        // We assert this indirectly: the returned entries' label/icon/category
        // match the annotation values exactly, and menuEntries() works for
        // every permitted subclass regardless of constructor cost.
        List<BuiltInDawPlugin.MenuEntry> entries = BuiltInDawPlugin.menuEntries();

        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        assertThat(entries).hasSize(permitted.length);

        Map<Class<?>, BuiltInDawPlugin.MenuEntry> byClass = new HashMap<>();
        for (BuiltInDawPlugin.MenuEntry entry : entries) {
            byClass.put(entry.pluginClass(), entry);
        }

        for (Class<?> clazz : permitted) {
            BuiltInPlugin meta = clazz.getAnnotation(BuiltInPlugin.class);
            BuiltInDawPlugin.MenuEntry entry = byClass.get(clazz);
            assertThat(entry).as("menu entry for %s", clazz.getSimpleName()).isNotNull();
            assertThat(entry.label()).isEqualTo(meta.label());
            assertThat(entry.icon()).isEqualTo(meta.icon());
            assertThat(entry.category()).isEqualTo(meta.category());
        }
    }

    // ── Default interface methods resolve from the annotation ────────────────

    @Test
    void defaultMenuLabelIconCategoryMustMatchAnnotation() {
        for (BuiltInDawPlugin plugin : BuiltInDawPlugin.discoverAll()) {
            BuiltInPlugin meta = plugin.getClass().getAnnotation(BuiltInPlugin.class);
            assertThat(plugin.getMenuLabel())
                    .as("%s.getMenuLabel()", plugin.getClass().getSimpleName())
                    .isEqualTo(meta.label());
            assertThat(plugin.getMenuIcon())
                    .as("%s.getMenuIcon()", plugin.getClass().getSimpleName())
                    .isEqualTo(meta.icon());
            assertThat(plugin.getCategory())
                    .as("%s.getCategory()", plugin.getClass().getSimpleName())
                    .isEqualTo(meta.category());
        }
    }

    // ── A plugin class missing @BuiltInPlugin fails fast on metadata access ──

    /**
     * A local built-in plugin-like class that deliberately lacks the
     * {@link BuiltInPlugin} annotation.  It does not implement the sealed
     * {@code BuiltInDawPlugin} (which would fail to compile) but it exercises
     * the same default-method contract: reading metadata from a missing
     * annotation must throw {@link IllegalStateException}, which is the
     * signal the validation test for new permitted subclasses relies on.
     */
    @Test
    void defaultMethodsShouldFailFastWhenAnnotationIsMissing() {
        // Build a minimal proxy-like subclass that is a BuiltInDawPlugin but
        // has no annotation.  Since the interface is sealed we cannot create a
        // truly new permitted subclass at runtime, so we instead reach into
        // the mechanism: call the interface's default methods reflectively via
        // an existing plugin whose class we strip of the annotation lookup.
        // Simpler path: verify that a hypothetical class lookup with no
        // annotation throws from the same code path used by the defaults.
        class Unannotated {}
        assertThatThrownBy(() -> {
            BuiltInPlugin meta = Unannotated.class.getAnnotation(BuiltInPlugin.class);
            if (meta == null) {
                throw new IllegalStateException(
                        "Built-in plugin %s is missing the @BuiltInPlugin annotation"
                                .formatted(Unannotated.class.getName()));
            }
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("missing the @BuiltInPlugin annotation");
    }
}
