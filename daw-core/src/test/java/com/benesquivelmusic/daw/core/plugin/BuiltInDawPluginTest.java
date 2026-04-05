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
    void shouldPermitExactlyFiveSubclasses() {
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        assertThat(permitted).hasSize(5);
    }

    @Test
    void shouldPermitExpectedSubclasses() {
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        assertThat(permitted).containsExactlyInAnyOrder(
                VirtualKeyboardPlugin.class,
                ParametricEqPlugin.class,
                CompressorPlugin.class,
                ReverbPlugin.class,
                SpectrumAnalyzerPlugin.class
        );
    }

    @Test
    void allPermittedSubclassesShouldHavePublicNoArgConstructor() throws Exception {
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        for (Class<?> clazz : permitted) {
            Constructor<?> ctor = clazz.getConstructor();
            assertThat(ctor).as("Public no-arg constructor for %s", clazz.getName()).isNotNull();
        }
    }

    @Test
    void allPermittedSubclassesShouldBeFinal() {
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        for (Class<?> clazz : permitted) {
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
    void menuEntriesShouldReturnOneEntryPerPermittedSubclass() {
        Class<?>[] permitted = BuiltInDawPlugin.class.getPermittedSubclasses();
        List<BuiltInDawPlugin.MenuEntry> entries = BuiltInDawPlugin.menuEntries();

        assertThat(entries).hasSize(permitted.length);
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
