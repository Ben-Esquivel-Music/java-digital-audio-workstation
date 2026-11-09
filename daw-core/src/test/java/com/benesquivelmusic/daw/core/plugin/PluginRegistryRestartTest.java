package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.OfflineCause;
import com.benesquivelmusic.daw.core.persistence.AnnotatedJarTestPlugin;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.CopyRefusingStores;
import com.benesquivelmusic.daw.core.persistence.InstalledPluginStore;
import com.benesquivelmusic.daw.core.persistence.LookAheadJarTestPlugin;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.StoreParameterJarTestPlugin;
import com.benesquivelmusic.daw.core.persistence.StripRenders;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.plugin.PluginProjects.FORMAT;
import static com.benesquivelmusic.daw.core.plugin.PluginProjects.folderOf;
import static com.benesquivelmusic.daw.core.plugin.PluginProjects.jar;
import static com.benesquivelmusic.daw.core.plugin.PluginProjects.vox;
import static com.benesquivelmusic.daw.core.plugin.PluginProjects.voxInsert;
import static com.benesquivelmusic.daw.core.plugin.PluginProjects.voxWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The installed-plugin registry across a restart (story 334, studio rulings
 * E9 and E20, findings G6, G18, G22, critique J8): a new registry over the
 * same configuration folder restores every entry; an installed plugin stays
 * usable — its reopened insert renders bit-equal — even after the engineer
 * deleted the original JAR; an entry whose copy is gone stays listed as
 * Missing and its insert opens offline; the open waits for the restore, and
 * a restore that does not finish costs one wait; an install while the
 * restore runs keeps every listed entry in the file, and the startup sweep
 * keeps a JAR staged meanwhile. No test touches the real {@code ~/.daw}.
 */
class PluginRegistryRestartTest {

    @TempDir
    Path tempDir;

    private final List<PluginRegistry> registries = new ArrayList<>();
    private ProjectManager manager;

    @BeforeEach
    void createTheManager() {
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    @AfterEach
    void releaseEverything() {
        ConstructionGatedPlugin.release();
        DescriptorFailingPlugin.reset();
        manager.closeProject().join();
        registries.forEach(PluginRegistry::disposeAll);
    }

    @Test
    void anInstalledPluginIsIntactAndUsableAfterARestart() throws Exception {
        InstalledPluginStore firstStore = store();
        PluginRegistry first = restored(firstStore);
        InstalledPlugin installed = PluginTestJars.install(first, downloads(), "annotated-gain.jar",
                AnnotatedJarTestPlugin.class);
        awaitWrites(firstStore);
        DawProject project = voxWith(first, AnnotatedJarTestPlugin.ID, Map.of("Gain", 0.5));
        float[][] beforeTheRestart = StripRenders.render(vox(project), FORMAT);
        Path folder = folderOf(manager, project, tempDir.resolve("projects"));
        first.disposeAll();

        PluginRegistry second = registry(store());
        RestoreReport report = second.restore().get(10, TimeUnit.SECONDS);

        assertThat(report).isEqualTo(new RestoreReport(List.of(), false, 0, false));
        assertThat(second.getEntries()).containsExactly(installed.entry());
        assertThat(second.installedJar(AnnotatedJarTestPlugin.ID)).contains(installed);
        assertThat(second.getPlugin(installed.entry())).as("the catalogue instance")
                .isInstanceOf(AnnotatedJarTestPlugin.class);
        assertThat(installed.entry().jarPath()).as("the registry's own copy")
                .startsWith(firstStore.pluginsFolder());
        manager.setPluginResolver(second);
        manager.openProject(folder);
        assertThat(manager.getLastUnreadItems()).isEmpty();
        assertThat(voxInsert(manager.getCurrentDawProject()).getPlugin()).isInstanceOf(AnnotatedJarTestPlugin.class);
        assertThat(StripRenders.render(vox(manager.getCurrentDawProject()), FORMAT))
                .as("usable: the reopened insert renders bit-equal to the plugin before the restart (G18)")
                .isDeepEqualTo(beforeTheRestart);
    }

    @Test
    void aDeletedJarCopyStaysListedAsMissingAndItsSlotOpensOffline() throws Exception {
        InstalledPluginStore firstStore = store();
        PluginRegistry first = restored(firstStore);
        InstalledPlugin installed = PluginTestJars.install(first, downloads(), "annotated-gain.jar",
                AnnotatedJarTestPlugin.class);
        Path folder = folderOf(manager, voxWith(first, AnnotatedJarTestPlugin.ID, Map.of()), tempDir.resolve("p"));
        awaitWrites(firstStore);
        first.disposeAll();
        Files.delete(installed.entry().jarPath());

        PluginRegistry second = registry(store());
        RestoreReport report;
        List<String> warnings;
        try (LogLines log = LogLines.of(PluginRegistry.class)) {
            report = second.restore().get(10, TimeUnit.SECONDS);
            warnings = log.at(Level.WARNING);
        }

        assertThat(report).isEqualTo(new RestoreReport(List.of(installed), false, 0, false));
        assertThat(warnings).containsExactly("Plugin registry: 'com.example.annotated.gain' version '1.0.0' did not"
                + " load — file not found; it stays listed as Missing");
        assertThat(second.getEntries()).as("kept, never dropped (E9)").containsExactly(installed.entry());
        assertThat(second.missingReason(installed.entry())).contains(new MissingReason.FileNotFound());
        assertThat(second.getPlugin(installed.entry())).isNull();
        assertThat(store().load().plugins()).as("still in the file").containsExactly(installed);

        manager.setPluginResolver(second);
        manager.openProject(folder);
        InsertSlot insert = voxInsert(manager.getCurrentDawProject());
        assertThat(insert.offline()).hasValueSatisfying(offline -> assertThat(offline.cause())
                .isEqualTo(new OfflineCause.MissingAtStartup(PluginSourceKind.JAR, AnnotatedJarTestPlugin.ID,
                        "1.0.0")));
        assertThat(manager.getLastUnreadItems()).extracting(UnreadProjectItem::detail)
                .containsExactly("JAR plugin 'com.example.annotated.gain' version '1.0.0' is installed, but its file"
                        + " did not load when the application started");
    }

    /** E9: a project opened while the startup restore still runs waits for it and resolves its JAR insert. */
    @Test
    void theOpenWaitsForTheRegistry() throws Exception {
        InstalledPluginStore firstStore = store();
        PluginRegistry first = restored(firstStore);
        PluginTestJars.install(first, downloads(), "gated.jar", ConstructionGatedPlugin.class);
        Path folder = folderOf(manager, voxWith(first, ConstructionGatedPlugin.ID, Map.of()), tempDir.resolve("p"));
        awaitWrites(firstStore);
        first.disposeAll();

        ConstructionGatedPlugin.hold();
        PluginRegistry second = registry(store());
        second.restore();
        manager.setPluginResolver(second);
        CompletableFuture<Void> open = new CompletableFuture<>();
        Thread.ofVirtual().name("test-project-open").start(() -> {
            try {
                manager.openProject(folder);
                open.complete(null);
            } catch (IOException | RuntimeException failed) {
                open.completeExceptionally(failed);
            }
        });

        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(open).as("the open waits while the restore checks the plugin").isNotDone();
        ConstructionGatedPlugin.release();
        open.get(10, TimeUnit.SECONDS);

        assertThat(voxInsert(manager.getCurrentDawProject()).getPlugin()).isInstanceOf(ConstructionGatedPlugin.class);
        assertThat(manager.getLastUnreadItems()).isEmpty();
    }

    @Test
    void aDeletedOriginalLeavesTheInstalledCopyUsable() throws Exception {
        InstalledPluginStore firstStore = store();
        PluginRegistry first = restored(firstStore);
        InstalledPlugin installed = PluginTestJars.install(first, downloads(), "annotated-gain.jar",
                AnnotatedJarTestPlugin.class);
        awaitWrites(firstStore);
        first.disposeAll();
        Files.delete(downloads().resolve("annotated-gain.jar"));

        PluginRegistry second = registry(store());

        assertThat(second.restore().get(10, TimeUnit.SECONDS)).isEqualTo(new RestoreReport(List.of(), false, 0, false));
        assertThat(second.installedJar(AnnotatedJarTestPlugin.ID)).contains(installed);
        PluginResolution resolution = second.resolve(jar(AnnotatedJarTestPlugin.ID),
                new AudioFormatPluginContext(FORMAT));
        assertThat(resolution).isInstanceOf(PluginResolution.Resolved.class);
        ((PluginResolution.Resolved) resolution).loaded().disposal().run();
    }

    /**
     * G6: a restore that does not finish within the wait makes a JAR insert
     * open offline as failed to load, never "not installed"; the first wait
     * logs one WARNING and no later call waits.
     */
    @Test
    void aRestoreThatDoesNotFinishCostsOneWaitAndOpensJarInsertsAsFailedToLoad() throws Exception {
        InstalledPluginStore firstStore = store();
        PluginRegistry first = restored(firstStore);
        PluginTestJars.install(first, downloads(), "gated.jar", ConstructionGatedPlugin.class);
        Path folder = folderOf(manager, voxWith(first, ConstructionGatedPlugin.ID, Map.of()), tempDir.resolve("p"));
        awaitWrites(firstStore);
        first.disposeAll();

        ConstructionGatedPlugin.hold();
        PluginRegistry second = new PluginRegistry(store(), Duration.ofSeconds(1));
        registries.add(second);
        AudioFormatPluginContext context = new AudioFormatPluginContext(FORMAT);
        List<String> warnings;
        PluginResolution waited;
        PluginResolution notWaited;
        long firstCall;
        long laterCall;
        try (LogLines log = LogLines.of(PluginRegistry.class)) {
            long started = System.nanoTime();
            waited = second.resolve(jar(ConstructionGatedPlugin.ID), context);
            firstCall = System.nanoTime() - started;
            started = System.nanoTime();
            notWaited = second.resolve(jar(ConstructionGatedPlugin.ID), context);
            laterCall = System.nanoTime() - started;
            manager.setPluginResolver(second);
            manager.openProject(folder);
            warnings = log.at(Level.WARNING);
        }

        assertThat(waited).isEqualTo(new PluginResolution.FailedToLoad("TimeoutException"));
        assertThat(Duration.ofNanos(firstCall)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(notWaited).isEqualTo(waited);
        assertThat(Duration.ofNanos(laterCall)).as("a later call does not wait").isLessThan(Duration.ofMillis(500));
        assertThat(manager.getLastUnreadItems()).extracting(UnreadProjectItem::detail)
                .containsExactly("JAR plugin 'com.example.gated' version '1.0' failed to load (TimeoutException)");
        assertThat(warnings).containsExactly("Plugin registry: a project open waited 1 s for the installed plugins"
                + " to load; its plugin inserts open offline");
        assertThat(PluginRegistry.RESTORE_WAIT).as("the application's wait, whose line reads 'waited 60 s'"
                + " (ruling-3 Q6)").isEqualTo(Duration.ofSeconds(60));
    }

    /**
     * Ruling-3 C4: an installed plugin whose own code throws an error other
     * than a {@link VirtualMachineError} while the startup checks it — an
     * {@link AssertionError} from {@code getDescriptor()} — fails to load:
     * the instance the check loaded is released with its loader, the entry
     * stays listed as Missing, and the restore goes on to load the entries
     * after it and completes.
     */
    @Test
    void aPluginThatThrowsAnErrorWhileItIsCheckedIsMissingAndTheRestoreGoesOn() throws Exception {
        InstalledPluginStore firstStore = store();
        PluginRegistry first = restored(firstStore);
        InstalledPlugin gain = PluginTestJars.install(first, downloads(), "annotated-gain.jar",
                AnnotatedJarTestPlugin.class);
        InstalledPlugin failing = PluginTestJars.install(first, downloads(), "failing.jar",
                DescriptorFailingPlugin.class);
        InstalledPlugin knobs = PluginTestJars.install(first, downloads(), "knobs.jar",
                StoreParameterJarTestPlugin.class);
        InstalledPlugin gated = PluginTestJars.install(first, downloads(), "gated.jar", ConstructionGatedPlugin.class);
        awaitWrites(firstStore);
        first.disposeAll();
        DescriptorFailingPlugin.reset();
        DescriptorFailingPlugin.failFromNowOn();

        PluginRegistry second = registry(store());
        RestoreReport report;
        List<String> warnings;
        try (LogLines log = LogLines.of(PluginRegistry.class)) {
            report = second.restore().get(10, TimeUnit.SECONDS);
            warnings = log.at(Level.WARNING);
        }

        assertThat(report).as("the restore completes normally")
                .isEqualTo(new RestoreReport(List.of(failing), false, 0, false));
        assertThat(second.missingReason(failing.entry())).contains(new MissingReason.FailedToLoad("AssertionError"));
        assertThat(DescriptorFailingPlugin.disposals()).as("the instance the check loaded is released with its loader")
                .isEqualTo(1);
        assertThat(second.getPlugin(knobs.entry())).as("the entries after it load")
                .isInstanceOf(StoreParameterJarTestPlugin.class);
        assertThat(second.getPlugin(gated.entry())).isInstanceOf(ConstructionGatedPlugin.class);
        assertThat(second.getPlugin(gain.entry())).isInstanceOf(AnnotatedJarTestPlugin.class);
        assertThat(second.getEntries()).as("kept, never dropped (E9)")
                .containsExactly(gain.entry(), failing.entry(), knobs.entry(), gated.entry());
        assertThat(warnings).containsExactly("Plugin registry: '" + DescriptorFailingPlugin.ID + "' version '"
                + DescriptorFailingPlugin.VERSION + "' did not load — failed to load (AssertionError); it stays"
                + " listed as Missing");
    }

    /**
     * The manager's decision on finding FX2-7: a {@link StackOverflowError}
     * the plugin's own code throws while the startup checks it is the plugin
     * failing, like any other error but the virtual machine's own: the entry
     * stays listed as Missing, failed to load, the instance the check loaded
     * is released, and the restore goes on and completes.
     */
    @Test
    void aPluginWhoseCheckOverflowsTheStackIsMissingAndTheRestoreGoesOn() throws Exception {
        InstalledPluginStore firstStore = store();
        PluginRegistry first = restored(firstStore);
        InstalledPlugin failing = PluginTestJars.install(first, downloads(), "failing.jar",
                DescriptorFailingPlugin.class);
        InstalledPlugin knobs = PluginTestJars.install(first, downloads(), "knobs.jar",
                StoreParameterJarTestPlugin.class);
        awaitWrites(firstStore);
        first.disposeAll();
        DescriptorFailingPlugin.reset();
        DescriptorFailingPlugin.failFromNowOn(StackOverflowError::new);

        PluginRegistry second = registry(store());
        RestoreReport report = second.restore().get(10, TimeUnit.SECONDS);

        assertThat(report).as("the restore completes normally")
                .isEqualTo(new RestoreReport(List.of(failing), false, 0, false));
        assertThat(second.missingReason(failing.entry()))
                .contains(new MissingReason.FailedToLoad("StackOverflowError"));
        assertThat(DescriptorFailingPlugin.disposals()).as("the instance the check loaded is released").isEqualTo(1);
        assertThat(second.getPlugin(knobs.entry())).as("the entry after it loads")
                .isInstanceOf(StoreParameterJarTestPlugin.class);
    }

    /**
     * Ruling-4 Q8 (5): the startup check never asks a plugin for its latency —
     * its catalogue instance is never initialized, so the answer would mean
     * nothing — so a plugin whose latency query throws loads, with nothing to
     * tell at startup: no Missing entry, no notification. The plugin's
     * processor exists before it is initialized, and its latency query throws
     * on the catalogue instance too, so a check that asked would fail the
     * entry.
     */
    @Test
    void aPluginWhoseLatencyQueryThrowsLoadsAtTheStartupRestore() throws Exception {
        InstalledPluginStore firstStore = store();
        PluginRegistry first = restored(firstStore);
        InstalledPlugin comp = PluginTestJars.install(first, downloads(), "comp-x.jar", LookAheadJarTestPlugin.class);
        awaitWrites(firstStore);
        first.disposeAll();

        PluginRegistry second = registry(store());
        RestoreReport report = second.restore().get(10, TimeUnit.SECONDS);

        assertThat(report).as("nothing for the startup notification to tell")
                .isEqualTo(new RestoreReport(List.of(), false, 0, false));
        assertThat(second.missingReason(comp.entry())).isEmpty();
        DawPlugin checked = second.getPlugin(comp.entry());
        assertThat(checked).as("loaded").isInstanceOf(LookAheadJarTestPlugin.class);
        assertThatThrownBy(() -> checked.asAudioProcessor().orElseThrow().getLatencySamples())
                .as("the instance the check loaded throws when asked for its latency")
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Ruling-3 A1 and ruling-4 Q7: a startup that cannot read the list sweeps
     * nothing — the JAR copies are all its kept copy of the list can bring
     * back — and while that copy is kept beside the list, a startup that
     * reads the list with no problem sweeps nothing either, and says so once.
     * Once every copy of the list is deleted, the first such startup sweeps.
     */
    @Test
    void aStartupThatCannotReadTheListKeepsEveryJarCopy() throws Exception {
        Installed installed = installGainAndKnobs();
        Path stale = staleCopy();
        String list = Files.readString(listFile());
        Files.writeString(listFile(), list.replace("\"schema\": \"1\"", "\"schema\": \"2\""));

        RestoreReport report = registry(store()).restore().get(10, TimeUnit.SECONDS);

        assertThat(report).isEqualTo(new RestoreReport(List.of(), true, 0, false));
        assertThat(installed.gain().entry().jarPath()).as("kept: the copy of the list names it").exists();
        assertThat(installed.knobs().entry().jarPath()).exists();
        assertThat(stale).as("nothing is swept").exists();
        Path keptCopy = keptCopyOfTheList();

        Files.writeString(listFile(), list);
        RestoreReport clean;
        List<String> infos;
        try (LogLines log = LogLines.of(InstalledPluginStore.class)) {
            clean = registry(store()).restore().get(10, TimeUnit.SECONDS);
            infos = log.at(Level.INFO);
        }

        assertThat(clean).isEqualTo(new RestoreReport(List.of(), false, 0, false));
        assertThat(stale).as("the kept copy of the list holds the sweep (ruling-4 Q7)").exists();
        assertThat(infos).containsExactly(sweepHeldLine(keptCopy));

        Files.delete(keptCopy);
        RestoreReport released = registry(store()).restore().get(10, TimeUnit.SECONDS);

        assertThat(released).isEqualTo(new RestoreReport(List.of(), false, 0, false));
        assertThat(stale.getParent().getParent()).as("once no copy of the list is kept, a clean startup sweeps")
                .doesNotExist();
        assertThat(installed.gain().entry().jarPath()).exists();
        assertThat(installed.knobs().entry().jarPath()).exists();
    }

    /**
     * Ruling-4 Q7, the reviewer's sequence (FX2-4): after a startup that kept
     * a copy of an unreadable list, the engineer reinstalls one plugin, and
     * that install rewrites the list with it alone. The next startup reads
     * that list with no problem, yet deletes no JAR copy the kept list names
     * while the kept copy is there; once it is deleted, a startup sweeps the
     * copies no entry holds and keeps the one it lists.
     */
    @Test
    void aKeptCopyOfTheListHoldsTheSweepAfterAnInstallRewritesTheList() throws Exception {
        Installed installed = installGainAndKnobs();
        Files.writeString(listFile(), Files.readString(listFile()).replace("\"schema\": \"1\"", "\"schema\": \"2\""));
        InstalledPluginStore firstStore = store();
        PluginRegistry first = registry(firstStore);

        assertThat(first.restore().get(10, TimeUnit.SECONDS)).isEqualTo(new RestoreReport(List.of(), true, 0, false));
        Path keptCopy = keptCopyOfTheList();
        assertThat(installed.gain().entry().jarPath()).exists();
        assertThat(installed.knobs().entry().jarPath()).exists();
        InstalledPlugin reinstalled = PluginTestJars.install(first, downloads(), "failing.jar",
                DescriptorFailingPlugin.class);
        awaitWrites(firstStore);
        first.disposeAll();

        RestoreReport second;
        List<String> infos;
        try (LogLines log = LogLines.of(InstalledPluginStore.class)) {
            second = registry(store()).restore().get(10, TimeUnit.SECONDS);
            infos = log.at(Level.INFO);
        }

        assertThat(second).as("the list holds the reinstalled plugin alone, read with no problem")
                .isEqualTo(new RestoreReport(List.of(), false, 0, false));
        assertThat(installed.gain().entry().jarPath()).as("the kept list names it").exists();
        assertThat(installed.knobs().entry().jarPath()).exists();
        assertThat(reinstalled.entry().jarPath()).exists();
        assertThat(infos).containsExactly(sweepHeldLine(keptCopy));

        Files.delete(keptCopy);
        List<String> laterInfos;
        try (LogLines log = LogLines.of(InstalledPluginStore.class)) {
            registry(store()).restore().get(10, TimeUnit.SECONDS);
            laterInfos = log.at(Level.INFO);
        }

        assertThat(idFolderOf(installed.gain())).as("no entry holds it, and no kept list names it").doesNotExist();
        assertThat(idFolderOf(installed.knobs())).doesNotExist();
        assertThat(reinstalled.entry().jarPath()).exists();
        assertThat(laterInfos).as("nothing holds the sweep").isEmpty();
    }

    /**
     * Ruling-4 Q7: an entry the startup's load removed stays named by the kept
     * copy of the list after a removal rewrites the list without it, so the
     * next startup keeps its JAR copy.
     */
    @Test
    void aRemovedEntrysJarCopyOutlivesTheSaveThatDropsItWhileTheListIsKept() throws Exception {
        Installed installed = installGainAndKnobs();
        Files.writeString(listFile(), withTheTypeOf(StoreParameterJarTestPlugin.ID, "SYNTH",
                Files.readString(listFile())));
        InstalledPluginStore firstStore = store();
        PluginRegistry first = registry(firstStore);
        assertThat(first.restore().get(10, TimeUnit.SECONDS)).isEqualTo(new RestoreReport(List.of(), false, 1, false));
        keptCopyOfTheList();

        first.unregister(installed.gain().entry()).ifPresent(removed -> removed.disposal().run());
        awaitWrites(firstStore);
        assertThat(Files.readString(listFile())).as("the save rewrote the list without the removed entry")
                .doesNotContain(StoreParameterJarTestPlugin.ID);
        first.disposeAll();

        assertThat(registry(store()).restore().get(10, TimeUnit.SECONDS))
                .isEqualTo(new RestoreReport(List.of(), false, 0, false));
        assertThat(installed.knobs().entry().jarPath()).as("the removed entry's copy").exists();
    }

    /** Ruling-3 A1: an entry the startup's load removes keeps its JAR copy, and nothing is swept. */
    @Test
    void aStartupThatRemovesAnEntryKeepsItsJarCopy() throws Exception {
        Installed installed = installGainAndKnobs();
        Path stale = staleCopy();
        Files.writeString(listFile(), withTheTypeOf(StoreParameterJarTestPlugin.ID, "SYNTH",
                Files.readString(listFile())));

        RestoreReport report = registry(store()).restore().get(10, TimeUnit.SECONDS);

        assertThat(report).isEqualTo(new RestoreReport(List.of(), false, 1, false));
        assertThat(installed.knobs().entry().jarPath()).as("the removed entry's copy").exists();
        assertThat(installed.gain().entry().jarPath()).exists();
        assertThat(stale).as("nothing is swept").exists();
    }

    /**
     * Ruling-3 Q2 and A2: when the startup could not copy the list it could
     * not read, the report says the copy is owed — the notification must not
     * say a copy was kept — and nothing is swept.
     */
    @Test
    void aStartupThatCannotCopyTheListItCannotReadReportsTheCopyOwed() throws Exception {
        Installed installed = installGainAndKnobs();
        Files.writeString(listFile(), Files.readString(listFile()).replace("\"schema\": \"1\"", "\"schema\": \"2\""));

        RestoreReport report = registry(CopyRefusingStores.of(listFile(), () -> true)).restore()
                .get(10, TimeUnit.SECONDS);

        assertThat(report).isEqualTo(new RestoreReport(List.of(), true, 0, true));
        assertThat(installed.gain().entry().jarPath()).as("nothing is swept").exists();
        assertThat(installed.knobs().entry().jarPath()).exists();
    }

    /** Ruling-3 A1: the entry of a repeated id, which the startup's load removes, keeps its JAR copy. */
    @Test
    void aStartupThatFindsARepeatedIdKeepsTheRepeatsJarCopy() throws Exception {
        Installed installed = installGainAndKnobs();
        String repeatJar = "plugins/" + AnnotatedJarTestPlugin.ID + "/0.9/annotated-gain.jar";
        Path repeatCopy = listFile().resolveSibling(repeatJar);
        Files.createDirectories(repeatCopy.getParent());
        Files.write(repeatCopy, new byte[] {2});
        String list = Files.readString(listFile());
        assertThat(list).endsWith("\n  ]\n}\n");
        Files.writeString(listFile(), list.replace("\n  ]\n}\n", ",\n    {\"kind\": \"jar\", \"id\": \""
                + AnnotatedJarTestPlugin.ID + "\", \"version\": \"0.9\", \"name\": \"" + AnnotatedJarTestPlugin.NAME
                + "\", \"vendor\": \"Example Audio\", \"type\": \"EFFECT\", \"category\": \"UTILITY\","
                + " \"iconHint\": \"\", \"pluginClass\": \"" + AnnotatedJarTestPlugin.class.getName()
                + "\", \"jar\": \"" + repeatJar + "\", \"originalFile\": \"annotated-gain.jar\"}\n  ]\n}\n"));

        RestoreReport report = registry(store()).restore().get(10, TimeUnit.SECONDS);

        assertThat(report).as("a repeated id is not counted").isEqualTo(new RestoreReport(List.of(), false, 0, false));
        assertThat(repeatCopy).as("the repeat's copy").exists();
        assertThat(installed.gain().entry().jarPath()).exists();
        assertThat(installed.knobs().entry().jarPath()).exists();
    }

    /**
     * Critique J8: an install while the restore still checks the listed
     * entries writes every listed entry — the one still being checked
     * included — never a list of the new entry alone.
     */
    @Test
    void anInstallDuringTheRestoreKeepsEveryListedEntryInTheFile() throws Exception {
        InstalledPluginStore firstStore = store();
        PluginRegistry first = restored(firstStore);
        InstalledPlugin gain = PluginTestJars.install(first, downloads(), "annotated-gain.jar",
                AnnotatedJarTestPlugin.class);
        InstalledPlugin gated = PluginTestJars.install(first, downloads(), "gated.jar", ConstructionGatedPlugin.class);
        awaitWrites(firstStore);
        first.disposeAll();

        ConstructionGatedPlugin.hold();
        InstalledPluginStore secondStore = store();
        PluginRegistry second = registry(secondStore);
        second.restore();
        PluginRegistryTest.awaitLoaded(second, AnnotatedJarTestPlugin.ID);
        InstalledPlugin knobs = PluginTestJars.install(second, downloads(), "knobs.jar",
                StoreParameterJarTestPlugin.class);
        awaitWrites(secondStore);

        assertThat(store().load().plugins()).containsExactly(gain, gated, knobs);
        assertThat(second.getEntries()).containsExactly(gain.entry(), gated.entry(), knobs.entry());
        assertThat(second.getPlugin(gated.entry())).as("still being checked").isNull();
        ConstructionGatedPlugin.release();
        assertThat(second.restore().get(10, TimeUnit.SECONDS)).isEqualTo(new RestoreReport(List.of(), false, 0, false));
        assertThat(second.getPlugin(gated.entry())).isInstanceOf(ConstructionGatedPlugin.class);
    }

    /**
     * E20, G22: the startup sweep deletes the copies no entry holds, but
     * never a JAR staged while the restore ran.
     */
    @Test
    void theStartupSweepDeletesUnheldCopiesButKeepsAJarStagedDuringTheRestore() throws Exception {
        InstalledPluginStore firstStore = store();
        PluginRegistry first = restored(firstStore);
        InstalledPlugin gain = PluginTestJars.install(first, downloads(), "annotated-gain.jar",
                AnnotatedJarTestPlugin.class);
        InstalledPlugin gated = PluginTestJars.install(first, downloads(), "gated.jar", ConstructionGatedPlugin.class);
        awaitWrites(firstStore);
        first.disposeAll();
        Path stale = firstStore.pluginsFolder().resolve("com.example.old").resolve("0.9").resolve("old.jar");
        Files.createDirectories(stale.getParent());
        Files.write(stale, new byte[] {1});

        ConstructionGatedPlugin.hold();
        PluginRegistry second = registry(store());
        second.restore();
        PluginRegistryTest.awaitLoaded(second, AnnotatedJarTestPlugin.ID);
        PluginManifest knobsManifest = PluginTestJars.manifest(StoreParameterJarTestPlugin.class);
        Path knobsJar = PluginTestJars.buildJar(downloads(), "knobs.jar", List.of(knobsManifest),
                List.of(StoreParameterJarTestPlugin.class));
        Path staged = second.stageJar(knobsJar, knobsManifest);
        ConstructionGatedPlugin.release();
        second.restore().get(10, TimeUnit.SECONDS);
        awaitGone(stale.getParent().getParent());
        // A staging waits for a sweep that runs, so once it returns the sweep is over.
        assertThat(second.stageJar(knobsJar, knobsManifest)).isEqualTo(staged);

        assertThat(staged).as("staged while the restore ran, not yet installed").exists();
        assertThat(gain.entry().jarPath()).exists();
        assertThat(gated.entry().jarPath()).exists();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private InstalledPluginStore store() {
        return new InstalledPluginStore(listFile());
    }

    private Path listFile() {
        return tempDir.resolve("config").resolve(InstalledPluginStore.FILE_NAME);
    }

    /** The two plugins a first registry installed, its catalogue instances released. */
    private record Installed(InstalledPlugin gain, InstalledPlugin knobs) {
    }

    private Installed installGainAndKnobs() throws Exception {
        InstalledPluginStore firstStore = store();
        PluginRegistry first = restored(firstStore);
        InstalledPlugin gain = PluginTestJars.install(first, downloads(), "annotated-gain.jar",
                AnnotatedJarTestPlugin.class);
        InstalledPlugin knobs = PluginTestJars.install(first, downloads(), "knobs.jar",
                StoreParameterJarTestPlugin.class);
        awaitWrites(firstStore);
        first.disposeAll();
        return new Installed(gain, knobs);
    }

    /** The one copy of the list a startup kept beside it. */
    private Path keptCopyOfTheList() throws IOException {
        try (Stream<Path> entries = Files.list(listFile().getParent())) {
            List<Path> copies = entries.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(InstalledPluginStore.FILE_NAME + ".") && name.endsWith(".bak");
            }).toList();
            assertThat(copies).as("the startup kept one copy of the list").hasSize(1);
            return copies.getFirst();
        }
    }

    /** The folder of the JAR copies. */
    private Path pluginsFolder() {
        return listFile().resolveSibling(InstalledPluginStore.PLUGINS_FOLDER_NAME);
    }

    /** The line a clean startup logs when {@code keptCopy} holds its sweep (ruling-4 Q7). */
    private String sweepHeldLine(Path keptCopy) {
        return "Plugin registry: no JAR copy under '" + UnreadProjectItem.logText(pluginsFolder().toString())
                + "' is deleted while a copy of the list is kept beside it ('"
                + UnreadProjectItem.logText(keptCopy.getFileName().toString()) + "'); once every copy of the list"
                + " is deleted, a startup deletes the JAR copies no entry holds";
    }

    /** The folder of {@code plugin}'s id under the plugins folder. */
    private Path idFolderOf(InstalledPlugin plugin) {
        return pluginsFolder().resolve(plugin.id());
    }

    /** A JAR copy in a folder no entry holds, which a startup that sweeps deletes. */
    private Path staleCopy() throws IOException {
        Path stale = listFile().resolveSibling(InstalledPluginStore.PLUGINS_FOLDER_NAME).resolve("com.example.old")
                .resolve("0.9").resolve("old.jar");
        Files.createDirectories(stale.getParent());
        Files.write(stale, new byte[] {1});
        return stale;
    }

    /** {@code list} with the {@code type} of the entry of {@code id} replaced by {@code type}. */
    private static String withTheTypeOf(String id, String type, String list) {
        String effect = "\"type\": \"EFFECT\"";
        int at = list.indexOf(effect, list.indexOf("\"id\": \"" + id + "\""));
        assertThat(at).as("the entry of %s and its type", id).isPositive();
        return list.substring(0, at) + "\"type\": \"" + type + "\"" + list.substring(at + effect.length());
    }

    private Path downloads() {
        return tempDir.resolve("Downloads");
    }

    private PluginRegistry registry(InstalledPluginStore store) {
        PluginRegistry registry = new PluginRegistry(store);
        registries.add(registry);
        return registry;
    }

    /** A registry over {@code store} whose restore finished. */
    private PluginRegistry restored(InstalledPluginStore store) throws Exception {
        PluginRegistry registry = registry(store);
        registry.restore().get(10, TimeUnit.SECONDS);
        return registry;
    }

    private static void awaitWrites(InstalledPluginStore store) throws InterruptedException {
        assertThat(store.awaitUntil(System.nanoTime() + Duration.ofSeconds(10).toNanos()))
                .as("the registry's writes landed").isTrue();
    }

    private static void awaitGone(Path path) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (Files.exists(path)) {
            if (System.nanoTime() > deadline) {
                throw new UncheckedIOException(new IOException("the sweep left " + path));
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }
}
