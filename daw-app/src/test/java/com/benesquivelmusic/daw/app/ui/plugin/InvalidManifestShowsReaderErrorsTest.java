package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 303 review follow-up — a readable JAR whose {@code daw-plugin.json} is
 * present but malformed must surface the reader's validation errors above the
 * §6.8 class-name fallback (the {@code manifestPresent} arm of the fallback
 * predicate), not the "no manifest" copy.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class InvalidManifestShowsReaderErrorsTest {

    @TempDir
    Path tempDir;

    @Test
    void malformedManifestSurfacesValidationErrorsInsteadOfNoManifestCopy() throws Exception {
        // A real JAR whose manifest entry is missing every required field but one.
        Path jar = PluginTestJars.buildJarWithManifestJson(tempDir, "bad-manifest.jar",
                "{\"pluginClass\": \"com.example.MissingEverythingElse\"}", List.of());

        JarInspection inspection = new PluginJarScanner(null).scanBlocking(jar);
        assertThat(inspection.jarReadable())
                .as("the JAR itself is readable").isTrue();
        assertThat(inspection.manifestPresent())
                .as("the manifest entry is present").isTrue();
        assertThat(inspection.manifests().isValid())
                .as("the manifest fails validation").isFalse();

        PluginRegistry registry = new PluginRegistry();
        try {
            PluginInstallPanel panel = runOnFxThread(() ->
                    new PluginInstallPanel(inspection, registry, () -> { }));

            List<String> labels = PluginNodes.labelTexts(panel);
            assertThat(labels)
                    .as("the reader's validation errors are surfaced")
                    .anySatisfy(text -> assertThat(text).contains("missing required field"));
            assertThat(labels)
                    .as("the invalid-manifest copy is shown")
                    .anySatisfy(text ->
                            assertThat(text).contains("This JAR's manifest could not be read"));
            assertThat(labels)
                    .as("a present-but-invalid manifest is not misreported as absent")
                    .noneSatisfy(text ->
                            assertThat(text).contains("has no daw-plugin.json manifest"));
        } finally {
            registry.disposeAll();
        }
    }
}
