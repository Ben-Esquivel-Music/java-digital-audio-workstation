package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginA;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginB;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginC;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 303 — §6.8 backward compatibility: a JAR carrying the story-300
 * <em>legacy single-object</em> manifest (not the story-303 bundle array) still
 * flows through the manifest install path — one listed row, no class-name
 * field, an "Install" confirm — and registers its one declared plugin. Also
 * pins the §6.8 confirm-button label contract: "Install" / "Install both" /
 * "Install N plugins".
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LegacySingleManifestInstallTest {

    @TempDir
    Path tempDir;

    @Test
    void legacySingleObjectManifestInstallsItsOnePlugin() throws Exception {
        PluginManifest manifest = PluginTestJars.manifest(InstallFixturePluginC.class);
        // The single-manifest writer overload emits the story-300 flat object,
        // NOT the story-303 top-level array.
        String legacyJson = new PluginManifestWriter().toJson(manifest);
        assertThat(legacyJson.stripLeading()).startsWith("{");
        Path jar = PluginTestJars.buildJarWithManifestJson(tempDir, "legacy-single.jar",
                legacyJson, List.of(InstallFixturePluginC.class));

        JarInspection inspection = new PluginJarScanner(null).scanBlocking(jar);
        assertThat(inspection.manifestPresent())
                .as("the JAR carries a daw-plugin.json manifest").isTrue();
        assertThat(inspection.manifests().isValid())
                .as("a legacy single-object manifest is a valid one-plugin bundle").isTrue();
        assertThat(inspection.manifests().manifests()).hasSize(1);

        PluginRegistry registry = new PluginRegistry();
        try {
            PluginInstallPanel panel = runOnFxThread(() ->
                    new PluginInstallPanel(inspection, registry, () -> { }));

            assertThat(PluginNodes.findTextFields(panel))
                    .as("the legacy-manifest path is still the no-typing path")
                    .isEmpty();
            assertThat(panel.primaryButtonForTest().getText())
                    .as("§6.8 — a one-plugin manifest confirms with plain \"Install\"")
                    .isEqualTo("Install");

            runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return null;
            });

            assertThat(registry.getEntries())
                    .as("the single declared plugin registered without class-name input")
                    .containsExactly(
                            new ExternalPluginEntry(jar, InstallFixturePluginC.class.getName()));
        } finally {
            registry.disposeAll();
        }
    }

    @Test
    void installButtonLabelReflectsDeclaredPluginCount() throws Exception {
        PluginRegistry registry = new PluginRegistry();
        try {
            assertThat(installLabelFor(registry, "two.jar",
                    List.of(InstallFixturePluginA.class, InstallFixturePluginB.class)))
                    .as("§6.8 — two declared plugins confirm with \"Install both\"")
                    .isEqualTo("Install both");
            assertThat(installLabelFor(registry, "three.jar",
                    List.of(InstallFixturePluginA.class, InstallFixturePluginB.class,
                            InstallFixturePluginC.class)))
                    .as("§6.8 — N > 2 declared plugins confirm with \"Install N plugins\"")
                    .isEqualTo("Install 3 plugins");
        } finally {
            registry.disposeAll();
        }
    }

    private String installLabelFor(PluginRegistry registry, String jarName,
                                   List<Class<?>> fixtures) throws Exception {
        List<PluginManifest> manifests = fixtures.stream()
                .map(cls -> PluginTestJars.manifest(cls.asSubclass(
                        com.benesquivelmusic.daw.sdk.plugin.DawPlugin.class)))
                .toList();
        Path jar = PluginTestJars.buildJar(tempDir, jarName, manifests, fixtures);
        JarInspection inspection = new PluginJarScanner(null).scanBlocking(jar);
        assertThat(inspection.manifests().isValid()).isTrue();
        PluginInstallPanel panel = runOnFxThread(() ->
                new PluginInstallPanel(inspection, registry, () -> { }));
        return panel.primaryButtonForTest().getText();
    }
}
