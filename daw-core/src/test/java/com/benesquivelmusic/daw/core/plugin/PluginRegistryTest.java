package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PluginRegistryTest {

    private PluginRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PluginRegistry();
    }

    @Test
    void shouldStartEmpty() {
        assertThat(registry.getEntries()).isEmpty();
        assertThat(registry.getLoadedPlugins()).isEmpty();
    }

    @Test
    void shouldReturnUnmodifiableEntries() {
        assertThatThrownBy(() -> registry.getEntries().add(
                new ExternalPluginEntry(Path.of("/test.jar"), "com.example.Plugin")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReturnUnmodifiableLoadedPlugins() {
        assertThatThrownBy(() -> registry.getLoadedPlugins().put(
                new ExternalPluginEntry(Path.of("/test.jar"), "com.example.Plugin"),
                mock(DawPlugin.class)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullEntryOnRegister() {
        assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entry");
    }

    @Test
    void shouldRejectNullEntryOnUnregister() {
        assertThatThrownBy(() -> registry.unregister(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entry");
    }

    @Test
    void shouldReturnFalseWhenUnregisteringUnknownEntry() {
        ExternalPluginEntry entry = new ExternalPluginEntry(Path.of("/test.jar"), "com.example.Plugin");
        assertThat(registry.unregister(entry)).isFalse();
    }

    @Test
    void shouldReturnNullForUnknownPluginEntry() {
        ExternalPluginEntry entry = new ExternalPluginEntry(Path.of("/test.jar"), "com.example.Plugin");
        assertThat(registry.getPlugin(entry)).isNull();
    }

    @Test
    void shouldRejectRegisterWhenJarDoesNotExist(@TempDir Path tempDir) {
        ExternalPluginEntry entry = new ExternalPluginEntry(
                tempDir.resolve("nonexistent.jar"), "com.example.Plugin");

        assertThatThrownBy(() -> registry.register(entry))
                .isInstanceOf(PluginLoadException.class);

        // Entry should NOT be added on failure
        assertThat(registry.getEntries()).isEmpty();
    }

    @Test
    void shouldClearAllOnDisposeAll() {
        // disposeAll on empty registry should not throw
        registry.disposeAll();
        assertThat(registry.getEntries()).isEmpty();
        assertThat(registry.getLoadedPlugins()).isEmpty();
    }
}
