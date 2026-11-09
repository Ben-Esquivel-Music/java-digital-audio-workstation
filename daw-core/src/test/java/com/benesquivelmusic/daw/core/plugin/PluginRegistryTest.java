package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AnnotatedJarTestPlugin;
import com.benesquivelmusic.daw.core.persistence.InstalledPluginStore;
import com.benesquivelmusic.daw.core.persistence.StoreParameterJarTestPlugin;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The installed-plugin registry in memory and its lookups (story 334): an
 * install lists the entry and its catalogue instance, one entry per id; an
 * install or removal hands back what it took out, undisposed (critique J9);
 * {@link PluginRegistry#resolve} instantiates a fresh plugin for a JAR
 * identity and classifies the others; {@link PluginRegistry#withoutLoading()}
 * answers from what is published, never waiting and never constructing
 * (studio ruling R1).
 */
class PluginRegistryTest {

    private static final AudioFormatPluginContext CONTEXT =
            new AudioFormatPluginContext(new AudioFormat(48_000.0, 2, 24, 256));

    @TempDir
    Path tempDir;

    private PluginRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PluginRegistry();
    }

    @AfterEach
    void releaseThePlugins() {
        ConstructionGatedPlugin.release();
        registry.disposeAll();
    }

    @Test
    void shouldStartEmpty() {
        assertThat(registry.getEntries()).isEmpty();
        assertThat(registry.getLoadedPlugins()).isEmpty();
    }

    @Test
    void shouldReturnUnmodifiableEntries() {
        assertThatThrownBy(() -> registry.getEntries().add(
                new ExternalPluginEntry(Path.of("/test.jar"), "com.example.Plugin")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReturnUnmodifiableLoadedPlugins() {
        assertThatThrownBy(() -> registry.getLoadedPlugins().put(
                new ExternalPluginEntry(Path.of("/test.jar"), "com.example.Plugin"),
                mock(DawPlugin.class)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullEntryOnUnregister() {
        assertThatThrownBy(() -> registry.unregister(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entry");
    }

    @Test
    void unregisteringAnUnknownEntryRemovesNothing() {
        ExternalPluginEntry entry = new ExternalPluginEntry(Path.of("/test.jar"), "com.example.Plugin");
        assertThat(registry.unregister(entry)).isEmpty();
    }

    @Test
    void shouldReturnNullForUnknownPluginEntry() {
        ExternalPluginEntry entry = new ExternalPluginEntry(Path.of("/test.jar"), "com.example.Plugin");
        assertThat(registry.getPlugin(entry)).isNull();
    }

    @Test
    void shouldClearAllOnDisposeAll() throws Exception {
        InstalledPlugin installed = PluginTestJars.install(registry, tempDir, "gain.jar", AnnotatedJarTestPlugin.class);
        AnnotatedJarTestPlugin catalogue = (AnnotatedJarTestPlugin) registry.getPlugin(installed.entry());

        registry.disposeAll();

        assertThat(registry.getEntries()).isEmpty();
        assertThat(registry.getLoadedPlugins()).isEmpty();
        assertThat(catalogue.isDisposed()).isTrue();
    }

    @Test
    void aRegistryInMemoryRestoresAtOnceWithNothingToReport() throws Exception {
        assertThat(registry.restore().get(1, TimeUnit.SECONDS)).isEqualTo(new RestoreReport(List.of(), false, 0, false));
    }

    @Test
    void anInstallListsTheEntryAndItsCatalogueInstance() throws Exception {
        InstalledPlugin installed = PluginTestJars.install(registry, tempDir, "gain.jar", AnnotatedJarTestPlugin.class);

        assertThat(registry.getEntries()).containsExactly(installed.entry());
        assertThat(registry.getLoadedPlugins()).containsOnlyKeys(installed.entry());
        assertThat(registry.getPlugin(installed.entry())).isInstanceOf(AnnotatedJarTestPlugin.class);
        assertThat(registry.installedJar(AnnotatedJarTestPlugin.ID)).contains(installed);
        assertThat(registry.installed(installed.entry())).contains(installed);
        assertThat(registry.missingReason(installed.entry())).isEmpty();
        assertThat(installed.entry().jarPath()).as("without a store the JAR is installed where it is")
                .isEqualTo(tempDir.resolve("gain.jar"));
    }

    @Test
    void anInstallOfAnInstalledIdReplacesItAndHandsBackTheOldEntryUndisposed() throws Exception {
        InstalledPlugin first = PluginTestJars.install(registry, tempDir.resolve("a"), "gain.jar",
                AnnotatedJarTestPlugin.class);
        AnnotatedJarTestPlugin firstCatalogue = (AnnotatedJarTestPlugin) registry.getPlugin(first.entry());
        Path secondJar = PluginTestJars.buildJar(tempDir.resolve("b"), "gain.jar",
                List.of(first.manifest()), List.of(AnnotatedJarTestPlugin.class));
        ExternalPluginEntry secondEntry = new ExternalPluginEntry(secondJar, first.entry().className());
        InstalledPlugin second = new InstalledPlugin(secondEntry, first.manifest(), "gain.jar");

        Optional<RemovedPlugin> replaced =
                registry.install(second, ExternalPluginLoader.loadWithClassLoader(secondEntry));

        assertThat(replaced).map(RemovedPlugin::plugin).contains(first);
        assertThat(registry.getEntries()).containsExactly(secondEntry);
        assertThat(firstCatalogue.isDisposed()).as("the caller disposes it, off the registry").isFalse();
        replaced.orElseThrow().disposal().run();
        assertThat(firstCatalogue.isDisposed()).isTrue();
        assertThat(registry.getPlugin(secondEntry)).isNotSameAs(firstCatalogue);
    }

    @Test
    void unregisteringHandsBackTheEntryForItsCallerToDispose() throws Exception {
        InstalledPlugin installed = PluginTestJars.install(registry, tempDir, "gain.jar", AnnotatedJarTestPlugin.class);
        AnnotatedJarTestPlugin catalogue = (AnnotatedJarTestPlugin) registry.getPlugin(installed.entry());

        Optional<RemovedPlugin> removed = registry.unregister(installed.entry());

        assertThat(removed).map(RemovedPlugin::plugin).contains(installed);
        assertThat(registry.getEntries()).isEmpty();
        assertThat(catalogue.isDisposed()).isFalse();
        removed.orElseThrow().disposal().run();
        assertThat(catalogue.isDisposed()).isTrue();
    }

    @Test
    void aJarIdentityResolvesToAFreshInitializedInstance() throws Exception {
        InstalledPlugin installed = PluginTestJars.install(registry, tempDir, "gain.jar", AnnotatedJarTestPlugin.class);

        PluginResolution resolution = registry.resolve(
                new PluginIdentity(PluginSourceKind.JAR, AnnotatedJarTestPlugin.ID, "0.9"), CONTEXT);

        assertThat(resolution).isInstanceOfSatisfying(PluginResolution.Resolved.class, resolved -> {
            AnnotatedJarTestPlugin plugin = (AnnotatedJarTestPlugin) resolved.loaded().plugin();
            assertThat(plugin).isNotSameAs(registry.getPlugin(installed.entry()));
            assertThat(plugin.processor()).as("initialized with the context").isNotNull();
            resolved.loaded().disposal().run();
            assertThat(plugin.isDisposed()).isTrue();
        });
    }

    @Test
    void whatIsNotAnInstalledJarResolvesToWhy() throws Exception {
        PluginTestJars.install(registry, tempDir, "gain.jar", AnnotatedJarTestPlugin.class);

        assertThat(registry.resolve(new PluginIdentity(PluginSourceKind.JAR, "com.example.gone", "1.0"), CONTEXT))
                .isEqualTo(new PluginResolution.NotInstalled());
        assertThat(registry.resolve(new PluginIdentity(PluginSourceKind.CLAP, AnnotatedJarTestPlugin.ID, "1.0"),
                CONTEXT)).isEqualTo(new PluginResolution.InstalledAsOtherKind(PluginSourceKind.JAR));
        assertThat(registry.resolve(new PluginIdentity(PluginSourceKind.CLAP, "com.example.glue", "1.0"), CONTEXT))
                .as("the registry has no installed-CLAP concept (E9)").isEqualTo(new PluginResolution.NotInstalled());
        assertThatThrownBy(() -> registry.resolve(
                new PluginIdentity(PluginSourceKind.BUILT_IN, "com.benesquivelmusic.daw.builtin.deesser", "1.0"),
                CONTEXT)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The snapshot door's resolver answers from what the registry published —
     * loaded, Missing, still being restored — while the restore is held in a
     * plugin's constructor: it never waits for the restore and never
     * constructs a plugin (studio ruling R1).
     */
    @Test
    void theLookupOnlyResolverNeverWaitsAndNeverConstructs() throws Exception {
        InstalledPluginStore store = new InstalledPluginStore(tempDir.resolve("config")
                .resolve(InstalledPluginStore.FILE_NAME));
        PluginRegistry installing = new PluginRegistry(store);
        installing.restore().get(10, TimeUnit.SECONDS);
        InstalledPlugin gone = PluginTestJars.install(installing, tempDir.resolve("downloads"), "knobs.jar",
                StoreParameterJarTestPlugin.class);
        PluginTestJars.install(installing, tempDir.resolve("downloads"), "gain.jar", AnnotatedJarTestPlugin.class);
        PluginTestJars.install(installing, tempDir.resolve("downloads"), "gated.jar", ConstructionGatedPlugin.class);
        assertThat(store.awaitUntil(System.nanoTime() + Duration.ofSeconds(10).toNanos())).isTrue();
        installing.disposeAll();
        Files.delete(gone.entry().jarPath());

        ConstructionGatedPlugin.hold();
        registry = new PluginRegistry(store);
        registry.restore();
        awaitLoaded(registry, AnnotatedJarTestPlugin.ID);
        ConstructionGatedPlugin.forgetConstructions();
        PluginResolver lookup = registry.withoutLoading();

        assertThat(lookup.resolve(identity(AnnotatedJarTestPlugin.ID), CONTEXT))
                .isEqualTo(new PluginResolution.NotLoadedBySnapshotRestore());
        assertThat(lookup.resolve(identity(ConstructionGatedPlugin.ID), CONTEXT))
                .as("still being restored").isEqualTo(new PluginResolution.NotLoadedBySnapshotRestore());
        assertThat(lookup.resolve(identity(gone.id()), CONTEXT)).isEqualTo(new PluginResolution.MissingAtStartup());
        assertThat(lookup.resolve(identity("com.example.never"), CONTEXT))
                .isEqualTo(new PluginResolution.NotInstalled());
        assertThat(lookup.resolve(new PluginIdentity(PluginSourceKind.CLAP, AnnotatedJarTestPlugin.ID, "1.0"),
                CONTEXT)).isEqualTo(new PluginResolution.InstalledAsOtherKind(PluginSourceKind.JAR));
        assertThat(lookup.resolve(new PluginIdentity(PluginSourceKind.CLAP, "com.example.glue", "1.0"), CONTEXT))
                .isEqualTo(new PluginResolution.NotInstalled());
        assertThat(ConstructionGatedPlugin.constructingThreads()).as("nothing was constructed").isEmpty();
        assertThat(registry.restore()).as("the restore is still held").isNotDone();
    }

    /**
     * Until the restore has listed the store's entries, nobody knows whether
     * a JAR id is installed: the snapshot door's resolver then answers "not
     * loaded by a snapshot restore", whose text is true either way, never
     * "not installed" (studio ruling R1, ruling-3 A3). A CLAP identity is
     * answered as before (ruling-3 Q6).
     */
    @Test
    void theLookupOnlyResolverNeverSaysNotInstalledBeforeTheListIsRead() throws Exception {
        registry = new PluginRegistry(new InstalledPluginStore(tempDir.resolve("config")
                .resolve(InstalledPluginStore.FILE_NAME)));
        PluginResolver lookup = registry.withoutLoading();

        assertThat(lookup.resolve(identity("com.example.comp"), CONTEXT))
                .as("the list is not read yet").isEqualTo(new PluginResolution.NotLoadedBySnapshotRestore());
        assertThat(lookup.resolve(new PluginIdentity(PluginSourceKind.CLAP, "com.example.glue", "1.0"), CONTEXT))
                .isEqualTo(new PluginResolution.NotInstalled());

        registry.restore().get(10, TimeUnit.SECONDS);

        assertThat(lookup.resolve(identity("com.example.comp"), CONTEXT))
                .as("once the list is read").isEqualTo(new PluginResolution.NotInstalled());
    }

    private static PluginIdentity identity(String id) {
        return new PluginIdentity(PluginSourceKind.JAR, id, "1.0");
    }

    /** Waits until the restore published {@code id}'s entry as loaded. */
    static void awaitLoaded(PluginRegistry registry, String id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<InstalledPlugin> installed = registry.installedJar(id);
            if (installed.isPresent() && registry.getPlugin(installed.get().entry()) != null) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("the restore did not load " + id);
    }
}
