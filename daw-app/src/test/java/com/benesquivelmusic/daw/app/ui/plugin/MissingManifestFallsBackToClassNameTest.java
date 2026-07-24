package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginC;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;

import javafx.scene.control.TextField;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 303 — a JAR with classes but NO {@code daw-plugin.json} falls back to the
 * de-emphasised legacy class-name form (kept reachable; story 304 removes it):
 * the §6.8 panel exposes the class-name {@link TextField}, and typing the
 * fixture's FQCN + firing Add registers it.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MissingManifestFallsBackToClassNameTest {

    @TempDir
    Path tempDir;

    @Test
    void manifestLessJarShowsClassNameFallbackThatInstalls() throws Exception {
        // A JAR with the fixture's class bytes but no manifest entry.
        Path jar = PluginTestJars.buildJar(tempDir, "no-manifest.jar",
                null, List.of(InstallFixturePluginC.class));

        JarInspection inspection = new PluginJarScanner(null).scanBlocking(jar);
        assertThat(inspection.manifestPresent())
                .as("the JAR carries no daw-plugin.json manifest").isFalse();

        PluginRegistry registry = new PluginRegistry();
        try {
            PluginInstallPanel panel = runOnFxThread(() ->
                    new PluginInstallPanel(inspection, registry, () -> { }));

            TextField field = panel.classNameFieldForTest();
            assertThat(field)
                    .as("the fallback path exposes the class-name field")
                    .isNotNull();
            assertThat(PluginNodes.findTextFields(panel))
                    .as("the class-name field is present in the panel's node tree")
                    .contains(field);

            runOnFxThread(() -> {
                field.setText(InstallFixturePluginC.class.getName());
                panel.primaryButtonForTest().fire();
                return null;
            });

            assertThat(registry.getEntries())
                    .as("typing the FQCN + Add registers the plugin")
                    .contains(new ExternalPluginEntry(jar, InstallFixturePluginC.class.getName()));
        } finally {
            registry.disposeAll();
        }
    }
}
