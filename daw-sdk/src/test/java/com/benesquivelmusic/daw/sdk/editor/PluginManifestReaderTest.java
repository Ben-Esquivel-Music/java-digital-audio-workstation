package com.benesquivelmusic.daw.sdk.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link PluginManifestReader} (Plugin View Design Book §4.5): a
 * well-formed {@code META-INF/daw-plugin.json} parses to a valid
 * {@link PluginManifest}, optional fields default sensibly, and every malformed or
 * missing input degrades to a {@link PluginManifestReader.Result.Invalid} rather
 * than throwing (§1.4, §6.8).
 */
class PluginManifestReaderTest {

    /** Minimal document carrying only the six required fields (type {@code EFFECT}). */
    private static final String MINIMAL_JSON = """
            {
              "pluginClass": "com.acme.MinimalPlugin",
              "id": "com.acme.minimal",
              "name": "Acme Minimal",
              "version": "1.0.0",
              "vendor": "Acme Audio",
              "type": "EFFECT"
            }
            """;

    private final PluginManifestReader reader = new PluginManifestReader();

    @Test
    void parsesWellFormedManifest() {
        String json = """
                {
                  "pluginClass": "com.acme.ReverbPlugin",
                  "id": "com.acme.reverb",
                  "name": "Acme Reverb",
                  "version": "1.0.0",
                  "vendor": "Acme Audio",
                  "type": "EFFECT",
                  "category": "DYNAMICS",
                  "iconHint": "compressor"
                }
                """;

        PluginManifestReader.Result result = reader.parse(json);

        assertThat(result.isValid()).isTrue();
        PluginManifest manifest = result.manifest().orElseThrow();
        assertThat(manifest.pluginClass()).isEqualTo("com.acme.ReverbPlugin");
        assertThat(manifest.descriptor().type()).isEqualTo(PluginType.EFFECT);
        assertThat(manifest.descriptor().category()).isEqualTo(PluginCategory.DYNAMICS);
        assertThat(manifest.descriptor().iconHint()).isEqualTo("compressor");
    }

    @Test
    void missingIconHintDefaultsToEmpty() {
        PluginManifestReader.Result result = reader.parse(MINIMAL_JSON);

        assertThat(result.isValid()).isTrue();
        assertThat(result.manifest().orElseThrow().descriptor().iconHint()).isEmpty();
    }

    @Test
    void missingCategoryDefaultsToUtility() {
        PluginManifestReader.Result result = reader.parse(MINIMAL_JSON);

        assertThat(result.isValid()).isTrue();
        assertThat(result.manifest().orElseThrow().descriptor().category())
                .isEqualTo(PluginCategory.UTILITY);
    }

    @Test
    void malformedJsonReportedAsInvalid() {
        PluginManifestReader.Result result = reader.parse("{ not json ");

        assertThat(result).isInstanceOf(PluginManifestReader.Result.Invalid.class);
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void missingRequiredFieldReportedAsInvalid() {
        PluginManifestReader.Result result = reader.parse("{\"name\":\"x\"}");

        assertThat(result).isInstanceOf(PluginManifestReader.Result.Invalid.class);
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void unknownTypeReportedAsInvalid() {
        String json = """
                {
                  "pluginClass": "com.acme.BogusPlugin",
                  "id": "com.acme.bogus",
                  "name": "Acme Bogus",
                  "version": "1.0.0",
                  "vendor": "Acme Audio",
                  "type": "BOGUS"
                }
                """;

        PluginManifestReader.Result result = reader.parse(json);

        assertThat(result).isInstanceOf(PluginManifestReader.Result.Invalid.class);
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void readsWellFormedManifestFromJar(@TempDir Path tempDir) throws IOException {
        Path jar = writeJar(tempDir, "plugin.jar",
                PluginManifestReader.MANIFEST_ENTRY, MINIMAL_JSON.getBytes(StandardCharsets.UTF_8));

        PluginManifestReader.Result result = reader.readFromJar(jar);

        assertThat(result.isValid()).isTrue();
        PluginManifest manifest = result.manifest().orElseThrow();
        assertThat(manifest.pluginClass()).isEqualTo("com.acme.MinimalPlugin");
        assertThat(manifest.descriptor().category()).isEqualTo(PluginCategory.UTILITY);
    }

    @Test
    void missingManifestInJarReportedAsInvalid(@TempDir Path tempDir) throws IOException {
        Path jar = writeJar(tempDir, "no-manifest.jar",
                "com/acme/MinimalPlugin.class", new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});

        PluginManifestReader.Result result = reader.readFromJar(jar);

        assertThat(result).isInstanceOf(PluginManifestReader.Result.Invalid.class);
        assertThat(result.isValid()).isFalse();
    }

    private static Path writeJar(Path dir, String jarName, String entryName, byte[] content) throws IOException {
        Path jar = dir.resolve(jarName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry(entryName));
            jos.write(content);
            jos.closeEntry();
        }
        return jar;
    }
}
