package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.persistence.AnnotatedJarTestPlugin;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.InstalledPluginStore;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static com.benesquivelmusic.daw.core.plugin.PluginProjects.folderOf;
import static com.benesquivelmusic.daw.core.plugin.PluginProjects.voxInsert;
import static com.benesquivelmusic.daw.core.plugin.PluginProjects.voxWith;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plugin's identity in the installed-plugin registry (story 334, studio
 * rulings E20 and E21, finding G18): an install is refused when the manifest
 * and the loaded plugin disagree on the id or version, or when a bundle
 * declares one id for two classes; installing a new version of an installed
 * id replaces its entry, and a project saved with the old version reopens
 * the new one, through the real registry, with the one version line.
 */
class PluginRegistryIdentityTest {

    @TempDir
    Path tempDir;

    private ProjectManager manager;
    private PluginRegistry registry;

    @BeforeEach
    void createTheManager() {
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    @AfterEach
    void releaseEverything() {
        manager.closeProject().join();
        if (registry != null) {
            registry.disposeAll();
        }
    }

    @Test
    void aMismatchedDescriptorIsRefused() {
        AnnotatedJarTestPlugin loaded = new AnnotatedJarTestPlugin();
        PluginManifest otherId = manifest(AnnotatedJarTestPlugin.class.getName(), "com.example.comp", "1.0.0");
        PluginManifest otherVersion = manifest(AnnotatedJarTestPlugin.class.getName(), AnnotatedJarTestPlugin.ID,
                "1.2.0");

        assertThat(PluginRegistry.refusalFor("comp-x.jar", otherId, loaded)).contains(
                new InstallRefusal.DescriptorMismatch("comp-x.jar", "AnnotatedJarTestPlugin",
                        AnnotatedJarTestPlugin.ID, "1.0.0", "com.example.comp", "1.0.0"));
        assertThat(PluginRegistry.refusalFor("comp-x.jar", otherVersion, loaded)).contains(
                new InstallRefusal.DescriptorMismatch("comp-x.jar", "AnnotatedJarTestPlugin",
                        AnnotatedJarTestPlugin.ID, "1.0.0", AnnotatedJarTestPlugin.ID, "1.2.0"));
        assertThat(PluginRegistry.refusalFor("comp-x.jar",
                PluginTestJars.manifest(AnnotatedJarTestPlugin.class), loaded)).isEmpty();
    }

    /** One refusal per repeated id, in manifest order (R11 d), its classes by simple name. */
    @Test
    void aBundleDeclaringOneIdTwiceIsRefused() {
        List<PluginManifest> bundle = List.of(
                manifest("com.example.CompX", "com.example.comp", "1.2.0"),
                manifest("com.example.Gate", "com.example.gate", "1.0"),
                manifest("com.example.CompY", "com.example.comp", "1.2.0"),
                manifest("com.example.Unique", "com.example.unique", "1.0"),
                manifest("com.example.Outer$GateTwo", "com.example.gate", "1.0"),
                manifest("com.example.CompZ", "com.example.comp", "1.3.0"));

        assertThat(PluginRegistry.refusalsFor("comp-x.jar", bundle)).containsExactly(
                new InstallRefusal.DuplicateId("comp-x.jar", "com.example.comp", List.of("CompX", "CompY", "CompZ")),
                new InstallRefusal.DuplicateId("comp-x.jar", "com.example.gate", List.of("Gate", "GateTwo")));
        assertThat(PluginRegistry.refusalsFor("comp-x.jar", List.of(bundle.get(0), bundle.get(1)))).isEmpty();
    }

    /**
     * E20: installing 1.1 then 1.2 of one id leaves one entry, 1.2; G18: a
     * project saved while 1.1 was installed reopens the 1.2 plugin through
     * the real registry, with E21's exact INFO line and no unread item.
     */
    @Test
    void installingANewVersionReplacesTheEntry() throws Exception {
        Path listFile = tempDir.resolve("config").resolve(InstalledPluginStore.FILE_NAME);
        InstalledPluginStore store = new InstalledPluginStore(listFile);
        registry = new PluginRegistry(store);
        registry.restore().get(10, TimeUnit.SECONDS);
        InstalledPlugin release11 = PluginTestJars.install(registry, tempDir.resolve("Downloads"),
                "versioned-gain-1.1.jar", VersionedGainPlugin.Release11.class);
        VersionedGainPlugin catalogue11 = (VersionedGainPlugin) registry.getPlugin(release11.entry());
        Path folder = folderOf(manager, voxWith(registry, VersionedGainPlugin.ID, Map.of("Gain", 0.5)),
                tempDir.resolve("projects"));

        PluginManifest manifest12 = PluginTestJars.manifest(VersionedGainPlugin.Release12.class);
        Path jar12 = PluginTestJars.buildJar(tempDir.resolve("Downloads"), "versioned-gain-1.2.jar",
                List.of(manifest12), List.of(VersionedGainPlugin.Release12.class));
        ExternalPluginEntry entry12 = new ExternalPluginEntry(registry.stageJar(jar12, manifest12),
                manifest12.pluginClass());
        InstalledPlugin release12 = new InstalledPlugin(entry12, manifest12, "versioned-gain-1.2.jar");
        Optional<RemovedPlugin> replaced = registry.install(release12,
                ExternalPluginLoader.loadWithClassLoader(entry12));

        assertThat(replaced).map(RemovedPlugin::plugin).contains(release11);
        replaced.orElseThrow().disposal().run();
        assertThat(registry.getEntries()).containsExactly(entry12);
        assertThat(registry.installedJar(VersionedGainPlugin.ID)).contains(release12);
        assertThat(registry.getPlugin(entry12)).isNotSameAs(catalogue11);
        assertThat(entry12.jarPath()).isEqualTo(store.pluginsFolder().resolve(VersionedGainPlugin.ID).resolve("1.2")
                .resolve("versioned-gain-1.2.jar"));
        assertThat(store.awaitUntil(System.nanoTime() + Duration.ofSeconds(10).toNanos())).isTrue();
        assertThat(store.load().plugins()).containsExactly(release12);

        List<String> infos;
        List<String> warnings;
        manager.setPluginResolver(registry);
        try (LogLines log = LogLines.of(ProjectDeserializer.class)) {
            manager.openProject(folder);
            infos = log.at(Level.INFO);
            warnings = log.at(Level.WARNING);
        }

        assertThat(voxInsert(manager.getCurrentDawProject()).getPlugin())
                .isInstanceOfSatisfying(VersionedGainPlugin.Release12.class, plugin ->
                        assertThat(plugin.processor().getGain()).as("the saved state is applied").isEqualTo(0.5));
        assertThat(infos).containsExactly("Track 1 'Vox': insert 1 'Versioned Gain' JAR plugin"
                + " 'com.example.versioned.gain' was saved with version '1.1' and opens with installed version 1.2");
        assertThat(warnings).isEmpty();
        assertThat(manager.getLastUnreadItems()).isEmpty();
        assertThat(release11.entry().jarPath()).as("1.1's copy stays until a startup sweeps it").exists();

        manager.closeProject().join();
        registry.disposeAll();
        registry = new PluginRegistry(new InstalledPluginStore(listFile));
        registry.restore().get(10, TimeUnit.SECONDS);
        awaitGone(release11.entry().jarPath().getParent());
        assertThat(entry12.jarPath()).exists();
    }

    private static PluginManifest manifest(String pluginClass, String id, String version) {
        return new PluginManifest(pluginClass,
                new PluginDescriptor(id, "Comp X", version, "Example Audio", PluginType.EFFECT));
    }

    /** E20: the previous version's copy is deleted at the next startup at the latest. */
    private static void awaitGone(Path folder) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (Files.exists(folder)) {
            assertThat(System.nanoTime()).as("the startup sweep deletes " + folder).isLessThan(deadline);
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }
}
