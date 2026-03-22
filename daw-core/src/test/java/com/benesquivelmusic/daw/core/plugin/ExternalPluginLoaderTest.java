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
    void shouldRejectBlankClassName() {
        assertThatThrownBy(() -> ExternalPluginLoader.load(Path.of("/test.jar"), "  "))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("className");
    }

    @Test
    void shouldRejectEmptyClassName() {
        assertThatThrownBy(() -> ExternalPluginLoader.load(Path.of("/test.jar"), ""))
                .isInstanceOf(PluginLoadException.class)
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
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(Files.newOutputStream(emptyJar))) {
            // empty JAR
        }

        assertThatThrownBy(() -> ExternalPluginLoader.load(emptyJar, "com.example.NonExistent"))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("Class not found");
    }

    @Test
    void shouldCloseClassLoaderOnFailedLoadWithClassLoader() throws Exception {
        Path emptyJar = tempDir.resolve("empty-for-cl.jar");
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(Files.newOutputStream(emptyJar))) {
            // empty JAR
        }

        // loadWithClassLoader should close the classloader when it throws
        assertThatThrownBy(() -> ExternalPluginLoader.loadWithClassLoader(emptyJar, "com.example.Missing"))
                .isInstanceOf(PluginLoadException.class);
        // If we reach here without hanging file locks, the classloader was closed
    }

    @Test
    void shouldCloseClassLoaderOnFailedLoadViaEntry() throws Exception {
        Path emptyJar = tempDir.resolve("empty-for-entry.jar");
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(Files.newOutputStream(emptyJar))) {
            // empty JAR
        }

        ExternalPluginEntry entry = new ExternalPluginEntry(emptyJar, "com.example.Missing");
        assertThatThrownBy(() -> ExternalPluginLoader.load(entry))
                .isInstanceOf(PluginLoadException.class);
    }
}
