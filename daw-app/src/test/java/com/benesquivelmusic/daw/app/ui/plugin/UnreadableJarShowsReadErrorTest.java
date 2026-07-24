package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 303 review follow-up — a JAR the scanner cannot read at all (corrupt /
 * not a zip) must surface the reader's "could not read" error in the §6.8
 * fallback panel instead of being misreported as a JAR that merely has no
 * {@code daw-plugin.json} manifest.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class UnreadableJarShowsReadErrorTest {

    @TempDir
    Path tempDir;

    @Test
    void unreadableJarSurfacesReadErrorInsteadOfNoManifestCopy() throws Exception {
        // Not a zip at all — JarFile fails to open it.
        Path jar = tempDir.resolve("corrupt.jar");
        Files.writeString(jar, "this is not a jar");

        JarInspection inspection = new PluginJarScanner(null).scanBlocking(jar);
        assertThat(inspection.jarReadable())
                .as("the scanner records that the JAR could not be read").isFalse();
        assertThat(inspection.manifests().isValid())
                .as("the reader reports the same problem as Invalid").isFalse();

        PluginRegistry registry = new PluginRegistry();
        try {
            PluginInstallPanel panel = runOnFxThread(() ->
                    new PluginInstallPanel(inspection, registry, () -> { }));

            List<String> labels = PluginNodes.labelTexts(panel);
            assertThat(labels)
                    .as("the reader's read error is surfaced")
                    .anySatisfy(text -> assertThat(text).contains("could not read"));
            assertThat(labels)
                    .as("the unreadable-JAR copy is shown, not the invalid-manifest copy")
                    .anySatisfy(text -> assertThat(text).contains("This JAR could not be read."))
                    .noneSatisfy(text ->
                            assertThat(text).contains("This JAR's manifest could not be read"));
            assertThat(labels)
                    .as("an unreadable JAR is not misreported as manifest-less")
                    .noneSatisfy(text ->
                            assertThat(text).contains("has no daw-plugin.json manifest"));
        } finally {
            registry.disposeAll();
        }
    }
}
