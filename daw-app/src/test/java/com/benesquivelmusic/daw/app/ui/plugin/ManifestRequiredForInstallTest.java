package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginC;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 304 (Plugin View Design Book §8.5.2) — a JAR must carry a
 * {@code META-INF/daw-plugin.json} manifest to install; the legacy "type a class
 * name" fallback is gone. Paired with the removal of
 * {@code MissingManifestFallsBackToClassNameTest}: the same manifest-less JAR
 * that used to reach the class-name form is now rejected with the
 * predates-the-manifest-format copy, and the §6.8 panel refuses to render an
 * invalid inspection at all — the user is never offered a class-name field.
 * A manifest-bearing JAR still installs exactly as in story 303.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ManifestRequiredForInstallTest {

    @TempDir
    Path tempDir;

    @Test
    void manifestLessJarIsRejectedWithVendorRebuildCopyAndNoPanel() throws Exception {
        // A JAR with the fixture's class bytes but no manifest entry — the exact
        // shape that used to fall back to the class-name form.
        Path jar = PluginTestJars.buildJar(tempDir, "no-manifest.jar",
                null, List.of(InstallFixturePluginC.class));

        JarInspection inspection = new PluginJarScanner(null).scanBlocking(jar);
        assertThat(inspection.jarReadable())
                .as("the JAR itself is readable").isTrue();
        assertThat(inspection.manifestPresent())
                .as("the JAR carries no daw-plugin.json manifest").isFalse();

        String message = PluginInstallPanel.rejectionMessage(inspection);
        assertThat(message)
                .as("§8.5.2 — the no-manifest copy asks the vendor to rebuild")
                .contains("rebuild against the current SDK");
        assertThat(message)
                .as("§8.5.2 — the copy names the missing manifest requirement")
                .contains("daw-plugin.json");

        PluginRegistry registry = new PluginRegistry();
        try {
            assertThatThrownBy(() -> runOnFxThread(() ->
                    new PluginInstallPanel(inspection, registry, () -> { })))
                    .as("the panel refuses to render an invalid inspection — no "
                            + "class-name field is ever offered")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(registry.getEntries())
                    .as("nothing registers without a manifest")
                    .isEmpty();
        } finally {
            registry.disposeAll();
        }
    }

    @Test
    void manifestBearingJarStillInstallsExactlyAsStory303() throws Exception {
        PluginManifest manifest = PluginTestJars.manifest(InstallFixturePluginC.class);
        Path jar = PluginTestJars.buildJar(tempDir, "with-manifest.jar",
                List.of(manifest), List.of(InstallFixturePluginC.class));

        JarInspection inspection = new PluginJarScanner(null).scanBlocking(jar);
        assertThat(inspection.manifests().isValid())
                .as("the JAR carries a valid one-plugin bundle").isTrue();

        PluginRegistry registry = new PluginRegistry();
        try {
            PluginInstallPanel panel = runOnFxThread(() ->
                    new PluginInstallPanel(inspection, registry, () -> { }));

            assertThat(PluginNodes.findTextFields(panel))
                    .as("the manifest path stays the no-typing path")
                    .isEmpty();

            runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return null;
            });

            assertThat(registry.getEntries())
                    .as("the declared plugin registered without class-name input")
                    .containsExactly(
                            new ExternalPluginEntry(jar, InstallFixturePluginC.class.getName()));
        } finally {
            registry.disposeAll();
        }
    }
}
