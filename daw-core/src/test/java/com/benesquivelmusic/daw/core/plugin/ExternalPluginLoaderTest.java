package com.benesquivelmusic.daw.core.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalPluginLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRejectNullEntry() {
        assertThatThrownBy(() -> ExternalPluginLoader.load((ExternalPluginEntry) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entry");
    }

    @Test
    void shouldRejectNullJarPath() {
        assertThatThrownBy(() -> ExternalPluginLoader.load(null, "com.example.Plugin"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("jarPath");
    }

    @Test
    void shouldRejectNullClassName() {
        assertThatThrownBy(() -> ExternalPluginLoader.load(Path.of("/test.jar"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("className");
    }

    @Test
    void shouldThrowWhenJarDoesNotExist() {
        Path nonExistent = tempDir.resolve("missing.jar");

        assertThatThrownBy(() -> ExternalPluginLoader.load(nonExistent, "com.example.Plugin"))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void shouldThrowWhenPathIsDirectory() throws Exception {
        Path dir = tempDir.resolve("not-a-jar");
        Files.createDirectory(dir);

        assertThatThrownBy(() -> ExternalPluginLoader.load(dir, "com.example.Plugin"))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("not a regular file");
    }

    @Test
    void shouldThrowWhenClassNotFoundInJar() throws Exception {
        // Create a minimal valid JAR file (empty zip with JAR structure)
        Path emptyJar = tempDir.resolve("empty.jar");
        try (var jos = new java.util.jar.JarOutputStream(Files.newOutputStream(emptyJar))) {
            // empty JAR
        }

        assertThatThrownBy(() -> ExternalPluginLoader.load(emptyJar, "com.example.NonExistent"))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("Class not found");
    }
}
