package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginA;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginB;
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

/**
 * Story 303 — a three-plugin manifest installs exactly three registry entries
 * from ONE install action, each {@code className} matching the manifest's
 * declared classes in declared order.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class InstallRegistersEveryDeclaredPluginTest {

    @TempDir
    Path tempDir;

    @Test
    void oneInstallActionRegistersEveryDeclaredPluginInOrder() throws Exception {
        PluginManifest a = PluginTestJars.manifest(InstallFixturePluginA.class);
        PluginManifest b = PluginTestJars.manifest(InstallFixturePluginB.class);
        PluginManifest c = PluginTestJars.manifest(InstallFixturePluginC.class);
        Path jar = PluginTestJars.buildJar(tempDir, "three-plugins.jar",
                List.of(a, b, c),
                List.of(InstallFixturePluginA.class,
                        InstallFixturePluginB.class,
                        InstallFixturePluginC.class));

        JarInspection inspection = new PluginJarScanner(null).scanBlocking(jar);
        assertThat(inspection.manifests().manifests())
                .as("the bundle declares three plugins").hasSize(3);

        PluginRegistry registry = new PluginRegistry();
        try {
            PluginInstallPanel panel = runOnFxThread(() ->
                    new PluginInstallPanel(inspection, registry, () -> { }));

            runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return null;
            });

            assertThat(registry.getEntries()).hasSize(3);
            assertThat(registry.getEntries().stream().map(ExternalPluginEntry::className).toList())
                    .as("every declared plugin was registered, in declared order")
                    .containsExactly(
                            InstallFixturePluginA.class.getName(),
                            InstallFixturePluginB.class.getName(),
                            InstallFixturePluginC.class.getName());
        } finally {
            registry.disposeAll();
        }
    }
}
