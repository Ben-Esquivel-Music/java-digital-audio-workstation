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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 304 (Plugin View Design Book §8.5.2), keeping story 303's discipline —
 * a readable JAR whose {@code daw-plugin.json} is present but malformed must
 * surface the reader's validation errors in the rejection copy (the
 * {@code manifestPresent} arm of the {@link PluginInstallPanel#rejectionMessage}
 * predicate), never the "no manifest" / predates-the-manifest-format copy, and
 * — with the class-name fallback removed — never a class-name form: the §6.8
 * panel refuses to render the invalid inspection at all.
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

        String message = PluginInstallPanel.rejectionMessage(inspection);
        assertThat(message)
                .as("the reader's validation errors are surfaced")
                .contains("missing required field");
        assertThat(message)
                .as("the invalid-manifest headline is shown")
                .contains("daw-plugin.json manifest could not be read");
        assertThat(message)
                .as("a present-but-invalid manifest is not misreported as absent")
                .doesNotContain("predates the manifest format")
                .doesNotContain("has no META-INF/daw-plugin.json manifest");
        assertThat(message)
                .as("story 304 — no class-name input is offered anywhere")
                .doesNotContain("class name");

        PluginRegistry registry = new PluginRegistry();
        try {
            assertThatThrownBy(() -> runOnFxThread(() ->
                    new PluginInstallPanel(inspection, registry, () -> { })))
                    .as("the panel refuses to render an invalid inspection")
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            registry.disposeAll();
        }
    }
}
