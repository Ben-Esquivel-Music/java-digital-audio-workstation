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

    /** A bundle declaring two self-contained plugins (§6.8, story 303). */
    private static final String BUNDLE_OF_TWO_JSON = """
            [
              {
                "pluginClass": "com.acme.ReverbPlugin",
                "id": "com.acme.reverb",
                "name": "Acme Reverb",
                "version": "1.0.0",
                "vendor": "Acme Audio",
                "type": "EFFECT",
                "category": "REVERB_AND_DELAY"
              },
              {
                "pluginClass": "com.acme.DelayPlugin",
                "id": "com.acme.delay",
                "name": "Acme Delay",
                "version": "2.0.0",
                "vendor": "Acme Audio",
                "type": "EFFECT"
              }
            ]
            """;

    /** A bundle declaring three self-contained plugins. */
    private static final String BUNDLE_OF_THREE_JSON = """
            [
              {
                "pluginClass": "com.acme.PluginA",
                "id": "com.acme.a",
                "name": "Acme A",
                "version": "1.0.0",
                "vendor": "Acme Audio",
                "type": "EFFECT"
              },
              {
                "pluginClass": "com.acme.PluginB",
                "id": "com.acme.b",
                "name": "Acme B",
                "version": "1.0.0",
                "vendor": "Acme Audio",
                "type": "INSTRUMENT"
              },
              {
                "pluginClass": "com.acme.PluginC",
                "id": "com.acme.c",
                "name": "Acme C",
                "version": "1.0.0",
                "vendor": "Acme Audio",
                "type": "ANALYZER"
              }
            ]
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

    @Test
    void parsesBundleOfTwoInDeclaredOrder() {
        PluginManifestReader.BundleResult result = reader.parseAll(BUNDLE_OF_TWO_JSON);

        assertThat(result.isValid()).isTrue();
        assertThat(result.manifests()).hasSize(2);
        assertThat(result.manifests().get(0).pluginClass()).isEqualTo("com.acme.ReverbPlugin");
        assertThat(result.manifests().get(0).descriptor().category())
                .isEqualTo(PluginCategory.REVERB_AND_DELAY);
        assertThat(result.manifests().get(1).pluginClass()).isEqualTo("com.acme.DelayPlugin");
    }

    @Test
    void parsesBundleOfThreeInDeclaredOrder() {
        PluginManifestReader.BundleResult result = reader.parseAll(BUNDLE_OF_THREE_JSON);

        assertThat(result.isValid()).isTrue();
        assertThat(result.manifests()).hasSize(3);
        assertThat(result.manifests())
                .extracting(PluginManifest::pluginClass)
                .containsExactly("com.acme.PluginA", "com.acme.PluginB", "com.acme.PluginC");
    }

    @Test
    void readsBundleFromJarInDeclaredOrder(@TempDir Path tempDir) throws IOException {
        Path jar = writeJar(tempDir, "bundle.jar",
                PluginManifestReader.MANIFEST_ENTRY, BUNDLE_OF_THREE_JSON.getBytes(StandardCharsets.UTF_8));

        PluginManifestReader.BundleResult result = reader.readAllFromJar(jar);

        assertThat(result.isValid()).isTrue();
        assertThat(result.manifests())
                .extracting(PluginManifest::pluginClass)
                .containsExactly("com.acme.PluginA", "com.acme.PluginB", "com.acme.PluginC");
    }

    @Test
    void parseAllAcceptsSingleObjectAsOnePluginBundle() {
        PluginManifestReader.BundleResult result = reader.parseAll(MINIMAL_JSON);

        assertThat(result.isValid()).isTrue();
        assertThat(result.manifests()).hasSize(1);
        assertThat(result.manifests().get(0).pluginClass()).isEqualTo("com.acme.MinimalPlugin");
    }

    @Test
    void legacyParseRejectsBundleArrayPointingAtBundleApi() {
        PluginManifestReader.Result result = reader.parse(BUNDLE_OF_TWO_JSON);

        assertThat(result).isInstanceOf(PluginManifestReader.Result.Invalid.class);
        assertThat(result.isValid()).isFalse();
        PluginManifestReader.Result.Invalid invalid = (PluginManifestReader.Result.Invalid) result;
        assertThat(invalid.errors()).anyMatch(e -> e.contains("parseAll"));
    }

    @Test
    void bundleAccumulatesPerElementErrorsWithIndexPrefixAndLeaksNoManifests() {
        String json = """
                [
                  {
                    "pluginClass": "com.acme.GoodPlugin",
                    "id": "com.acme.good",
                    "name": "Acme Good",
                    "version": "1.0.0",
                    "vendor": "Acme Audio",
                    "type": "EFFECT"
                  },
                  {
                    "pluginClass": "com.acme.BadPlugin",
                    "id": "com.acme.bad",
                    "name": "Acme Bad",
                    "version": "1.0.0",
                    "type": "EFFECT"
                  }
                ]
                """;

        PluginManifestReader.BundleResult result = reader.parseAll(json);

        assertThat(result.isValid()).isFalse();
        assertThat(result).isInstanceOf(PluginManifestReader.BundleResult.Invalid.class);
        assertThat(result.manifests()).isEmpty();
        PluginManifestReader.BundleResult.Invalid invalid =
                (PluginManifestReader.BundleResult.Invalid) result;
        assertThat(invalid.errors())
                .anyMatch(e -> e.startsWith("plugins[1]:") && e.contains("vendor"))
                .noneMatch(e -> e.startsWith("plugins[0]:"));
    }

    @Test
    void bundleSurfacesUnknownTypeAndCategoryWithIndexPrefix() {
        String json = """
                [
                  {
                    "pluginClass": "com.acme.PluginA",
                    "id": "com.acme.a",
                    "name": "Acme A",
                    "version": "1.0.0",
                    "vendor": "Acme Audio",
                    "type": "BOGUS"
                  },
                  {
                    "pluginClass": "com.acme.PluginB",
                    "id": "com.acme.b",
                    "name": "Acme B",
                    "version": "1.0.0",
                    "vendor": "Acme Audio",
                    "type": "EFFECT",
                    "category": "NONSENSE"
                  }
                ]
                """;

        PluginManifestReader.BundleResult result = reader.parseAll(json);

        assertThat(result.isValid()).isFalse();
        PluginManifestReader.BundleResult.Invalid invalid =
                (PluginManifestReader.BundleResult.Invalid) result;
        assertThat(invalid.errors()).contains("plugins[0]: unknown type: BOGUS");
        assertThat(invalid.errors()).contains("plugins[1]: unknown category: NONSENSE");
    }

    @Test
    void emptyBundleReportedAsInvalid() {
        PluginManifestReader.BundleResult result = reader.parseAll("[]");

        assertThat(result.isValid()).isFalse();
        assertThat(result.manifests()).isEmpty();
        assertThat(((PluginManifestReader.BundleResult.Invalid) result).errors())
                .anyMatch(e -> e.contains("no plugins"));
    }

    @Test
    void bundleWithDuplicatePluginClassReportedAsInvalid() {
        String json = """
                [
                  {
                    "pluginClass": "com.acme.DupPlugin",
                    "id": "com.acme.one",
                    "name": "Acme One",
                    "version": "1.0.0",
                    "vendor": "Acme Audio",
                    "type": "EFFECT"
                  },
                  {
                    "pluginClass": "com.acme.DupPlugin",
                    "id": "com.acme.two",
                    "name": "Acme Two",
                    "version": "1.0.0",
                    "vendor": "Acme Audio",
                    "type": "EFFECT"
                  }
                ]
                """;

        PluginManifestReader.BundleResult result = reader.parseAll(json);

        assertThat(result.isValid()).isFalse();
        assertThat(result.manifests()).isEmpty();
        assertThat(((PluginManifestReader.BundleResult.Invalid) result).errors())
                .anyMatch(e -> e.contains("com.acme.DupPlugin"));
    }

    @Test
    void malformedBundleArrayReportedAsInvalid() {
        PluginManifestReader.BundleResult result = reader.parseAll("[ { not json ");

        assertThat(result).isInstanceOf(PluginManifestReader.BundleResult.Invalid.class);
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
