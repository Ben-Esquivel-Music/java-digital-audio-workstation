package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;

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
    void discoverAllShouldReturnAllPermittedInstances() {
        List<BuiltInDawPlugin> plugins = BuiltInDawPlugin.discoverAll();

        assertThat(plugins).hasSize(5);
        assertThat(plugins.stream().map(Object::getClass).toList())
                .containsExactlyInAnyOrder(
                        VirtualKeyboardPlugin.class,
                        ParametricEqPlugin.class,
                        CompressorPlugin.class,
                        ReverbPlugin.class,
                        SpectrumAnalyzerPlugin.class
                );
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
}
