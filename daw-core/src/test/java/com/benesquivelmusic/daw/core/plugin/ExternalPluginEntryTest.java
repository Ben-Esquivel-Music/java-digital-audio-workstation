package com.benesquivelmusic.daw.core.plugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalPluginEntryTest {

    @Test
    void shouldCreateEntryWithValidArguments() {
        ExternalPluginEntry entry = new ExternalPluginEntry(
                Path.of("/plugins/my-reverb.jar"), "com.example.MyReverb");

        assertThat(entry.jarPath()).isEqualTo(Path.of("/plugins/my-reverb.jar"));
        assertThat(entry.className()).isEqualTo("com.example.MyReverb");
    }

    @Test
    void shouldRejectNullJarPath() {
        assertThatThrownBy(() -> new ExternalPluginEntry(null, "com.example.MyPlugin"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("jarPath");
    }

    @Test
    void shouldRejectNullClassName() {
        assertThatThrownBy(() -> new ExternalPluginEntry(Path.of("/test.jar"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("className");
    }

    @Test
    void shouldRejectBlankClassName() {
        assertThatThrownBy(() -> new ExternalPluginEntry(Path.of("/test.jar"), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("className");
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        ExternalPluginEntry a = new ExternalPluginEntry(Path.of("/plugins/test.jar"), "com.example.Plugin");
        ExternalPluginEntry b = new ExternalPluginEntry(Path.of("/plugins/test.jar"), "com.example.Plugin");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldImplementToString() {
        Path jarPath = Path.of("/plugins/test.jar");
        ExternalPluginEntry entry = new ExternalPluginEntry(jarPath, "com.example.Plugin");
        assertThat(entry.toString()).contains(jarPath.toString(), "com.example.Plugin");
    }
}
