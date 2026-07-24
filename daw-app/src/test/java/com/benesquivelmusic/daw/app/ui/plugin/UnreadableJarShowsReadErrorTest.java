package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 304 (Plugin View Design Book §8.5.2), keeping story 303's discipline —
 * a JAR the scanner cannot read at all (corrupt / not a zip) must surface the
 * reader's "could not read" error in the rejection copy (the
 * {@code !jarReadable} arm of the {@link PluginInstallPanel#rejectionMessage}
 * predicate) with no vendor-rebuild copy — an I/O failure is not a format-era
 * problem and is never misreported as a missing or invalid manifest. With the
 * class-name fallback removed, the §6.8 panel refuses to render the invalid
 * inspection at all.
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

        String message = PluginInstallPanel.rejectionMessage(inspection);
        assertThat(message)
                .as("the unreadable-JAR headline is shown")
                .contains("corrupt.jar could not be read.");
        assertThat(message)
                .as("the reader's read error is surfaced")
                .contains("could not read");
        assertThat(message)
                .as("an unreadable JAR is not misreported as an invalid manifest")
                .doesNotContain("manifest could not be read");
        assertThat(message)
                .as("an unreadable JAR is not misreported as manifest-less, and an "
                        + "I/O failure gets no vendor-rebuild copy")
                .doesNotContain("predates the manifest format")
                .doesNotContain("has no META-INF/daw-plugin.json manifest")
                .doesNotContain("current SDK");
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
