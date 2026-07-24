package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginA;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginB;
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

/**
 * Story 303 — a JAR whose manifest declares TWO plugins installs BOTH from a
 * single action, driven entirely off the manifest with zero class-name input:
 * the §6.8 install panel lists a row per declared plugin, shows no
 * {@code TextField} anywhere, and firing its install action registers both
 * {@link ExternalPluginEntry}s.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ManifestDrivenInstallTest {

    @TempDir
    Path tempDir;

    @Test
    void twoPluginManifestInstallsBothWithNoClassNameInput() throws Exception {
        PluginManifest a = PluginTestJars.manifest(InstallFixturePluginA.class);
        PluginManifest b = PluginTestJars.manifest(InstallFixturePluginB.class);
        Path jar = PluginTestJars.buildJar(tempDir, "two-plugins.jar",
                List.of(a, b),
                List.of(InstallFixturePluginA.class, InstallFixturePluginB.class));

        JarInspection inspection = new PluginJarScanner(null).scanBlocking(jar);
        assertThat(inspection.manifestPresent())
                .as("the JAR carries a daw-plugin.json manifest").isTrue();
        assertThat(inspection.manifests().isValid())
                .as("the manifest bundle parses").isTrue();
        assertThat(inspection.manifests().manifests())
                .as("the bundle declares two plugins").hasSize(2);

        PluginRegistry registry = new PluginRegistry();
        try {
            PluginInstallPanel panel = runOnFxThread(() ->
                    new PluginInstallPanel(inspection, registry, () -> { }));

            assertThat(PluginNodes.findTextFields(panel))
                    .as("the manifest install path never asks for a class name")
                    .isEmpty();
            assertThat(PluginNodes.labelTexts(panel))
                    .as("both declared plugins are listed with their manifest names")
                    .anyMatch(text -> text.contains("Acme Punch"))
                    .anyMatch(text -> text.contains("Acme Echo"));

            runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return null;
            });

            assertThat(registry.getEntries())
                    .as("firing install registered BOTH declared plugins, class-name-free")
                    .contains(
                            new ExternalPluginEntry(jar, InstallFixturePluginA.class.getName()),
                            new ExternalPluginEntry(jar, InstallFixturePluginB.class.getName()));
        } finally {
            registry.disposeAll();
        }
    }
}
