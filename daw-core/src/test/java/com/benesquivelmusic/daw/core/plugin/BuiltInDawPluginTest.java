package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInDawPluginTest {

    @Test
    void shouldExtendDawPlugin() {
        assertThat(DawPlugin.class).isAssignableFrom(BuiltInDawPlugin.class);
    }

    @Test
    void shouldBeSealed() {
        assertThat(BuiltInDawPlugin.class.isSealed()).isTrue();
    }

    @Test
    void shouldPermitExactlyTwentyFourSubclasses() {
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        assertThat(permitted).hasSize(24);
    }

    @Test
    void shouldPermitExpectedSubclasses() {
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        assertThat(permitted).containsExactlyInAnyOrder(
                VirtualKeyboardPlugin.class,
                ParametricEqPlugin.class,
                GraphicEqPlugin.class,
                CompressorPlugin.class,
                BusCompressorPlugin.class,
                MultibandCompressorPlugin.class,
                ReverbPlugin.class,
                SpectrumAnalyzerPlugin.class,
                TunerPlugin.class,
                SoundWaveTelemetryPlugin.class,
                SignalGeneratorPlugin.class,
                MetronomePlugin.class,
                AcousticReverbPlugin.class,
                BinauralMonitorPlugin.class,
                WaveshaperPlugin.class,
                MatchEqPlugin.class,
                DeEsserPlugin.class,
                TruePeakLimiterPlugin.class,
                TransientShaperPlugin.class,
                NoiseGatePlugin.class,
                MidSideWrapperPlugin.class,
                ConvolutionReverbPlugin.class,
                ExciterPlugin.class,
                MidiEffectPlugin.class
        );
    }

    @Test
    void allPermittedSubclassesShouldHavePublicNoArgConstructor() throws Exception {
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        for (Class<?> clazz : permitted) {
            // Permitted sub-interfaces (such as MidiEffectPlugin) are category
            // markers and have no constructor — only concrete plugin classes
            // are required to expose a public no-arg ctor.
            if (clazz.isInterface()) {
                continue;
            }
            Constructor<?> ctor = clazz.getConstructor();
            assertThat(ctor).as("Public no-arg constructor for %s", clazz.getName()).isNotNull();
        }
    }

    @Test
    void allPermittedSubclassesShouldBeFinal() {
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        for (Class<?> clazz : permitted) {
            // Skip permitted sub-interfaces — they are category markers and
            // are intentionally non-sealed (open for further extension).
            if (clazz.isInterface()) {
                continue;
            }
            assertThat(java.lang.reflect.Modifier.isFinal(clazz.getModifiers()))
                    .as("%s should be final", clazz.getName())
                    .isTrue();
        }
    }

    @Test
    void discoverAllShouldReturnUnmodifiableList() {
        List<BuiltInDawPlugin> plugins = BuiltInDawPlugin.discoverAll();

        assertThat(plugins).isUnmodifiable();
    }

    @Test
    void discoverAllShouldReturnFreshInstancesOnEachCall() {
        List<BuiltInDawPlugin> first = BuiltInDawPlugin.discoverAll();
        List<BuiltInDawPlugin> second = BuiltInDawPlugin.discoverAll();

        assertThat(first).isNotSameAs(second);
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i)).isNotSameAs(second.get(i));
        }
    }

    @Test
    void allDiscoveredPluginsShouldHaveNonBlankMenuLabel() {
        for (BuiltInDawPlugin plugin : BuiltInDawPlugin.discoverAll()) {
            assertThat(plugin.getMenuLabel())
                    .as("menuLabel for %s", plugin.getClass().getSimpleName())
                    .isNotBlank();
        }
    }

    @Test
    void allDiscoveredPluginsShouldHaveNonBlankMenuIcon() {
        for (BuiltInDawPlugin plugin : BuiltInDawPlugin.discoverAll()) {
            assertThat(plugin.getMenuIcon())
                    .as("menuIcon for %s", plugin.getClass().getSimpleName())
                    .isNotBlank();
        }
    }

    @Test
    void allDiscoveredPluginsShouldHaveNonNullCategory() {
        for (BuiltInDawPlugin plugin : BuiltInDawPlugin.discoverAll()) {
            assertThat(plugin.getCategory())
                    .as("category for %s", plugin.getClass().getSimpleName())
                    .isNotNull();
        }
    }

    @Test
    void allDiscoveredPluginsShouldHaveNonNullDescriptor() {
        for (BuiltInDawPlugin plugin : BuiltInDawPlugin.discoverAll()) {
            assertThat(plugin.getDescriptor())
                    .as("descriptor for %s", plugin.getClass().getSimpleName())
                    .isNotNull();
        }
    }

    @Test
    void allDiscoveredPluginsShouldHaveDistinctDescriptorIds() {
        List<String> ids = BuiltInDawPlugin.discoverAll().stream()
                .map(p -> p.getDescriptor().id())
                .toList();

        assertThat(ids).doesNotHaveDuplicates();
    }

    // ── menuEntries() ────────────────────────────────────────────────────────

    @Test
    void menuEntriesShouldReturnOneEntryPerPermittedConcreteSubclass() {
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        long expected = java.util.Arrays.stream(permitted)
                .filter(c -> !c.isInterface())
                .count();
        List<BuiltInDawPlugin.MenuEntry> entries = BuiltInDawPlugin.menuEntries();

        assertThat(entries).hasSize((int) expected);
    }

    @Test
    void menuEntriesShouldReturnUnmodifiableList() {
        List<BuiltInDawPlugin.MenuEntry> entries = BuiltInDawPlugin.menuEntries();

        assertThat(entries).isUnmodifiable();
    }

    @Test
    void menuEntriesShouldHaveNonBlankLabelsAndIcons() {
        for (BuiltInDawPlugin.MenuEntry entry : BuiltInDawPlugin.menuEntries()) {
            assertThat(entry.label())
                    .as("label for %s", entry.pluginClass().getSimpleName())
                    .isNotBlank();
            assertThat(entry.icon())
                    .as("icon for %s", entry.pluginClass().getSimpleName())
                    .isNotBlank();
            assertThat(entry.category())
                    .as("category for %s", entry.pluginClass().getSimpleName())
                    .isNotNull();
        }
    }

    @Test
    void menuEntriesShouldMatchDiscoverAllMetadata() {
        List<BuiltInDawPlugin> plugins = BuiltInDawPlugin.discoverAll();
        List<BuiltInDawPlugin.MenuEntry> entries = BuiltInDawPlugin.menuEntries();

        assertThat(entries).hasSameSizeAs(plugins);

        Map<Class<?>, BuiltInDawPlugin> pluginsByClass = new HashMap<>();
        for (BuiltInDawPlugin plugin : plugins) {
            pluginsByClass.put(plugin.getClass(), plugin);
        }

        for (BuiltInDawPlugin.MenuEntry entry : entries) {
            BuiltInDawPlugin plugin = pluginsByClass.get(entry.pluginClass());
            assertThat(plugin)
                    .as("Plugin for class %s", entry.pluginClass().getSimpleName())
                    .isNotNull();
            assertThat(entry.label()).isEqualTo(plugin.getMenuLabel());
            assertThat(entry.icon()).isEqualTo(plugin.getMenuIcon());
            assertThat(entry.category()).isEqualTo(plugin.getCategory());
        }
    }
}
