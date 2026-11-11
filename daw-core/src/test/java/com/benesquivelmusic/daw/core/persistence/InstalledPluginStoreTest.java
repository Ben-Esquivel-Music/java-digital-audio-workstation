package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.InstalledPlugin;
import com.benesquivelmusic.daw.sdk.editor.PluginCategory;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The installed-plugin registry's store (story 334, studio rulings E9 and
 * E20, findings G4, G5, G22, critique J8, J23): the list round-trips; a list
 * or an entry this build cannot read is copied when it is read and logged
 * with its exact line; a failed write leaves the previous list and reaches
 * the listener once per session; staged JAR copies stay inside the
 * {@code plugins} folder; the exit can wait for a pending write; the sweep
 * deletes only what no entry holds.
 */
class InstalledPluginStoreTest {

    private static final String NOT_SAVED = "Plugin registry: the list of installed plugins could not be saved"
            + " (AccessDeniedException); the next install or removal saves it again";

    /** The end of each line of a load whose copy of the list failed (ruling-3 Q2). */
    private static final String NOT_KEPT = ", and the list is left as it is until a copy of it can be kept"
            + " (AccessDeniedException)";

    private static final InstalledPluginStore.Listing NOTHING_LISTED =
            new InstalledPluginStore.Listing(List.of(), false, 0, InstalledPluginStore.ListCopy.NOT_NEEDED);

    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve("config").resolve(InstalledPluginStore.FILE_NAME);
    }

    @Test
    void aSavedRegistryLoadsBackEqual() throws IOException {
        InstalledPluginStore store = new InstalledPluginStore(file());
        InstalledPlugin comp = plugin(store.pluginsFolder().resolve("com.example.comp/1.2.0/comp-x.jar"),
                "com.example.comp", "1.2.0", "Comp X");
        InstalledPlugin quoted = plugin(store.pluginsFolder().resolve("com.example.odd/2.0/odd.jar"),
                "com.example.odd", "2.0", "Odd \"Box\" \\ —\n\t\u0001");

        store.save(List.of(comp, quoted));

        assertThat(new InstalledPluginStore(file()).load())
                .isEqualTo(new InstalledPluginStore.Listing(List.of(comp, quoted), false, 0,
                        InstalledPluginStore.ListCopy.NOT_NEEDED));
        assertThat(Files.readString(file()))
                .startsWith("{\n  \"schema\": \"1\",\n  \"plugins\": [\n    {\n      \"kind\": \"jar\",\n")
                .contains("\"jar\": \"plugins/com.example.comp/1.2.0/comp-x.jar\"",
                        "\"name\": \"Odd \\\"Box\\\" \\\\ —\\n\\t\\u0001\"", "\"category\": \"DYNAMICS\"",
                        "\"originalFile\": \"comp-x.jar\"");
        assertThat(backups()).isEmpty();
    }

    @Test
    void anEmptyListLoadsEmptyAndAMissingFileLoadsEmptyWithNothingLogged() throws IOException {
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(InstalledPluginStore.class)) {
            assertThat(new InstalledPluginStore(file()).load()).isEqualTo(NOTHING_LISTED);
            assertThat(file()).doesNotExist();

            new InstalledPluginStore(file()).save(List.of());
            assertThat(Files.readString(file())).isEqualTo("{\n  \"schema\": \"1\",\n  \"plugins\": []\n}\n");
            assertThat(new InstalledPluginStore(file()).load()).isEqualTo(NOTHING_LISTED);
            assertThat(log.at(Level.WARNING)).isEmpty();
        }
        assertThat(backups()).isEmpty();
    }

    /**
     * A file that is not a list of installed plugins this build reads lists
     * nothing, logs its one line and is copied as it is read — before any save
     * (G4: "A copy of the list was kept" is true when the notification shows).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "not a list at all",
            "{\"schema\": \"2\", \"plugins\": []}",
            "{\"schema\": \"1\"}",
            "{\"schema\": 1, \"plugins\": []}",
            "{\"schema\": \"1\", \"plugins\": {}}",
            "[{\"schema\": \"1\", \"plugins\": []}]",
            "{\"schema\": \"1\", \"plugins\": []} trailing",
            "{\"schema\": \"1\", \"plugins\": [}"})
    void anUnreadableFileLoadsEmptyAndIsBackedUpWhenRead(String content) throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), content);
        InstalledPluginStore store = new InstalledPluginStore(file());

        InstalledPluginStore.Listing listing;
        List<String> warnings;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(InstalledPluginStore.class)) {
            listing = store.load();
            warnings = log.at(Level.WARNING);
        }

        assertThat(listing).isEqualTo(new InstalledPluginStore.Listing(List.of(), true, 0,
                InstalledPluginStore.ListCopy.KEPT));
        List<Path> backups = backups();
        assertThat(backups).as("copied when read, before any save").hasSize(1);
        assertThat(backups.getFirst().getFileName().toString())
                .matches("plugin-registry\\.json\\.\\d{8}-\\d{6}\\.bak");
        assertThat(Files.readString(backups.getFirst())).isEqualTo(content);
        assertThat(warnings).containsExactly("Plugin registry: '" + UnreadProjectItem.logText(file().toString())
                + "' is not a list of installed plugins this build can read; no installed plugin is restored, and"
                + " the file is kept as '" + UnreadProjectItem.logText(backups.getFirst().toString()) + "'");

        store.save(List.of());
        assertThat(backups()).as("the save makes no second copy").containsExactlyElementsOf(backups);
        assertThat(Files.readString(backups.getFirst())).isEqualTo(content);
    }

    @Test
    void anUnreadableEntryIsRemovedCountedAndTheListIsKept() throws IOException {
        Path jar = tempDir.resolve("config/plugins/com.example.comp/1.2.0/comp-x.jar");
        String content = list(
                entry("com.example.comp", "1.2.0", "Comp X", "plugins/com.example.comp/1.2.0/comp-x.jar"),
                entry("com.example.blank", "1.0", "Blank", " "),
                entry("com.example.vst", "1.0", "Vst", "plugins/x.jar").replace("\"kind\": \"jar\"",
                        "\"kind\": \"vst3\""),
                entry("com.example.kindless", "1.0", "Kindless", "plugins/x.jar")
                        .replace("\"kind\": \"jar\", ", ""),
                entry("com.example.typed", "1.0", "Typed", "plugins/x.jar")
                        .replace("\"type\": \"EFFECT\"", "\"type\": \"SYNTH<\""),
                "\"just text\"",
                entry("com.example.sorted", "1.0", "Sorted", "plugins/x.jar")
                        .replace("\"category\": \"DYNAMICS\"", "\"category\": \"NOT_A_CATEGORY\""),
                entry("com.example.pathless", "1.0", "Pathless", "plugins/x\\u0000.jar"));
        Files.createDirectories(file().getParent());
        Files.writeString(file(), content);

        InstalledPluginStore.Listing listing;
        List<String> warnings;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(InstalledPluginStore.class)) {
            listing = new InstalledPluginStore(file()).load();
            warnings = log.at(Level.WARNING);
        }

        assertThat(listing.plugins()).containsExactly(plugin(jar, "com.example.comp", "1.2.0", "Comp X"));
        assertThat(listing.unreadable()).isFalse();
        assertThat(listing.removedEntries()).isEqualTo(7);
        List<Path> backups = backups();
        assertThat(backups).as("one copy for every problem of the load").hasSize(1);
        assertThat(Files.readString(backups.getFirst())).isEqualTo(content);
        String kept = ", and the list is kept as '" + UnreadProjectItem.logText(backups.getFirst().toString()) + "'";
        assertThat(warnings).containsExactly(
                "Plugin registry: entry 2 cannot be read (its 'jar' value is missing or blank); it is removed" + kept,
                "Plugin registry: entry 3 cannot be read (its kind 'vst3' is not jar); it is removed" + kept,
                "Plugin registry: entry 4 cannot be read (its 'kind' value is missing or blank); it is removed"
                        + kept,
                "Plugin registry: entry 5 cannot be read (its 'type' value 'SYNTH&lt;' is not one this build"
                        + " knows); it is removed" + kept,
                "Plugin registry: entry 6 cannot be read (it is not a JSON object); it is removed" + kept,
                "Plugin registry: entry 7 cannot be read (its 'category' value 'NOT_A_CATEGORY' is not one this"
                        + " build knows); it is removed" + kept,
                "Plugin registry: entry 8 cannot be read (its 'jar' value 'plugins/x&#x0;.jar' is not a path); it"
                        + " is removed" + kept);
    }

    /** One entry per id (E20): the first is used; the repeat is logged, not counted (G4), and the list kept. */
    @Test
    void aRepeatedIdKeepsTheFirstEntry() throws IOException {
        String content = list(
                entry("com.example.comp", "1.1.0", "Comp X", "plugins/com.example.comp/1.1.0/comp-x.jar"),
                entry("com.example.comp", "1.2.0", "Comp X", "plugins/com.example.comp/1.2.0/comp-x.jar"));
        Files.createDirectories(file().getParent());
        Files.writeString(file(), content);

        InstalledPluginStore.Listing listing;
        List<String> warnings;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(InstalledPluginStore.class)) {
            listing = new InstalledPluginStore(file()).load();
            warnings = log.at(Level.WARNING);
        }

        assertThat(listing.plugins()).extracting(InstalledPlugin::version).containsExactly("1.1.0");
        assertThat(listing.removedEntries()).as("a repeated id is not counted").isZero();
        List<Path> backups = backups();
        assertThat(backups).hasSize(1);
        assertThat(warnings).containsExactly("Plugin registry: 'com.example.comp' is listed again as entry 2; the"
                + " first entry is used and this one is removed, and the list is kept as '"
                + UnreadProjectItem.logText(backups.getFirst().toString()) + "'");
    }

    /**
     * Ruling-3 Q2 and A2: a load that cannot copy the list it found problems
     * in says the copy is owed, and each of its lines says the list is left
     * as it is; every later save makes the owed copy first — of the list as
     * the load read it — and, while the copy fails, fails without replacing
     * the list (prober P15: a save that writes before the copy fails this).
     */
    @Test
    void aCopyTheLoadCouldNotMakeIsOwedAndEverySaveMakesItBeforeReplacingTheList() throws IOException {
        String content = list(
                entry("com.example.comp", "1.2.0", "Comp X", "plugins/com.example.comp/1.2.0/comp-x.jar"),
                entry("com.example.comp", "1.3.0", "Comp X", "plugins/com.example.comp/1.3.0/comp-x.jar"),
                entry("com.example.typed", "1.0", "Typed", "plugins/x.jar")
                        .replace("\"type\": \"EFFECT\"", "\"type\": \"SYNTH\""));
        Files.createDirectories(file().getParent());
        Files.writeString(file(), content);
        AtomicBoolean copiesFail = new AtomicBoolean(true);
        InstalledPluginStore store = CopyRefusingStores.of(file(), copiesFail::get);

        InstalledPluginStore.Listing listing;
        List<String> warnings;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(InstalledPluginStore.class)) {
            listing = store.load();
            warnings = log.at(Level.WARNING);
        }

        assertThat(listing.copy()).isEqualTo(InstalledPluginStore.ListCopy.OWED);
        assertThat(listing.removedEntries()).isEqualTo(1);
        assertThat(listing.plugins()).extracting(InstalledPlugin::version).containsExactly("1.2.0");
        assertThat(warnings).containsExactly(
                "Plugin registry: 'com.example.comp' is listed again as entry 2; the first entry is used and this"
                        + " one is removed" + NOT_KEPT,
                "Plugin registry: entry 3 cannot be read (its 'type' value 'SYNTH' is not one this build knows); it"
                        + " is removed" + NOT_KEPT);
        assertThat(backups()).isEmpty();

        assertThatThrownBy(() -> store.save(listing.plugins())).isInstanceOf(AccessDeniedException.class);
        assertThat(Files.readString(file())).as("never replaced before a copy of it exists").isEqualTo(content);
        assertThat(backups()).isEmpty();

        copiesFail.set(false);
        store.save(listing.plugins());

        assertThat(backups()).singleElement().satisfies(backup -> assertThat(Files.readString(backup))
                .as("the owed copy holds the list as the load read it").isEqualTo(content));
        assertThat(Files.readString(file())).isEqualTo(store.toJson(listing.plugins()));
        store.save(listing.plugins());
        assertThat(backups()).as("the copy is made once").hasSize(1);
    }

    /** Ruling-3 Q2 and A2: an unreadable list whose copy fails says so, and the copy is owed. */
    @Test
    void anUnreadableListWhoseCopyFailsIsLeftAsItIsAndItsCopyOwed() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{\"schema\": \"2\", \"plugins\": []}");
        InstalledPluginStore store = CopyRefusingStores.of(file(), () -> true);

        InstalledPluginStore.Listing listing;
        List<String> warnings;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(InstalledPluginStore.class)) {
            listing = store.load();
            warnings = log.at(Level.WARNING);
        }

        assertThat(listing).isEqualTo(new InstalledPluginStore.Listing(List.of(), true, 0,
                InstalledPluginStore.ListCopy.OWED));
        assertThat(warnings).containsExactly("Plugin registry: '" + UnreadProjectItem.logText(file().toString())
                + "' is not a list of installed plugins this build can read; no installed plugin is restored, and"
                + " the file is left as it is until a copy of it can be kept (AccessDeniedException)");
        assertThat(backups()).isEmpty();
    }

    @Test
    void aSaveWhosePublishFailsKeepsThePreviousFileAndLeavesNoTemp() throws IOException {
        InstalledPlugin comp = plugin(tempDir.resolve("config/plugins/com.example.comp/1.2.0/comp-x.jar"),
                "com.example.comp", "1.2.0", "Comp X");
        new InstalledPluginStore(file()).save(List.of(comp));
        String before = Files.readString(file());
        InstalledPluginStore failing = new InstalledPluginStore(file(), new AtomicFileWriter(
                AtomicFileWriter.TEMP_CHANNEL_OPENER, (temp, target, options) -> {
                    throw new AccessDeniedException(target.toString());
                }));

        assertThatThrownBy(() -> failing.save(List.of())).isInstanceOf(AccessDeniedException.class);

        assertThat(Files.readString(file())).as("the previous list is as it was").isEqualTo(before);
        try (Stream<Path> entries = Files.list(file().getParent())) {
            assertThat(entries).as("no temp left beside the list").containsExactly(file());
        }
    }

    /**
     * A write off the caller's thread that fails logs its line each time; the
     * session's first failure, only, reaches the listener (G5: one
     * notification per session).
     */
    @Test
    void aFailedWriteIsLoggedAndOnlyTheSessionsFirstFailureReachesTheListener() throws Exception {
        InstalledPluginStore failing = new InstalledPluginStore(file(), new AtomicFileWriter(
                AtomicFileWriter.TEMP_CHANNEL_OPENER, (temp, target, options) -> {
                    throw new AccessDeniedException(target.toString());
                }));
        List<String> heard = new CopyOnWriteArrayList<>();
        failing.setSaveFailureListener(heard::add);

        List<String> warnings;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(InstalledPluginStore.class)) {
            failing.saveLater(List.of());
            failing.saveLater(List.of());
            assertThat(failing.awaitUntil(System.nanoTime() + Duration.ofSeconds(10).toNanos())).isTrue();
            warnings = log.at(Level.WARNING);
        }

        assertThat(heard).containsExactly("AccessDeniedException");
        assertThat(warnings).containsExactly(NOT_SAVED, NOT_SAVED);
        assertThat(failing.pending()).isFalse();
        assertThat(file()).doesNotExist();
    }

    @Test
    void aPendingWriteIsAwaitedUntilTheDeadline() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InstalledPluginStore held = new InstalledPluginStore(file(), new AtomicFileWriter(
                AtomicFileWriter.TEMP_CHANNEL_OPENER, (temp, target, options) -> {
                    reached.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", interrupted);
                    }
                    Files.move(temp, target, options);
                }));
        try {
            held.saveLater(List.of());
            assertThat(reached.await(10, TimeUnit.SECONDS)).as("the write is being published").isTrue();

            assertThat(held.pending()).isTrue();
            assertThat(held.awaitUntil(System.nanoTime() + Duration.ofMillis(200).toNanos()))
                    .as("the deadline runs out while the write is held").isFalse();
        } finally {
            release.countDown();
        }
        assertThat(held.awaitUntil(System.nanoTime() + Duration.ofSeconds(10).toNanos())).isTrue();
        assertThat(held.pending()).isFalse();
        assertThat(Files.readString(file())).contains("\"plugins\": []");
    }

    @Test
    void stagingCopiesTheJarUnderItsIdAndVersion() throws IOException {
        InstalledPluginStore store = new InstalledPluginStore(file());
        Path original = Files.createDirectories(tempDir.resolve("Downloads")).resolve("comp-x.jar");
        Files.write(original, new byte[] {1, 2, 3});
        PluginManifest manifest = manifest("com.example.comp", "1.2.0");

        Path staged = store.stage(original, manifest);

        assertThat(staged).isEqualTo(store.pluginsFolder().resolve("com.example.comp").resolve("1.2.0")
                .resolve("comp-x.jar"));
        assertThat(Files.readAllBytes(staged)).containsExactly(1, 2, 3);
        assertThat(store.stage(original, manifest)).as("the same bytes reuse the copy").isEqualTo(staged);

        Files.write(original, new byte[] {4, 5});
        Path rebuilt = store.stage(original, manifest);
        assertThat(rebuilt).as("a rebuild of the same version never replaces the copy (R6)")
                .isEqualTo(store.pluginsFolder().resolve("com.example.comp").resolve("1.2.0~2")
                        .resolve("comp-x.jar"));
        assertThat(Files.readAllBytes(rebuilt)).containsExactly(4, 5);
        assertThat(Files.readAllBytes(staged)).containsExactly(1, 2, 3);
    }

    /** Ids and versions become safe segments; a copy never leaves the {@code plugins} folder (critique J23). */
    @Test
    void aStagedCopyNeverLeavesThePluginsFolder() throws IOException {
        InstalledPluginStore store = new InstalledPluginStore(file());
        Path original = Files.createDirectories(tempDir.resolve("Downloads")).resolve("odd.jar");
        Files.write(original, new byte[] {7});

        assertThat(store.stage(original, manifest("../evil", "..")))
                .isEqualTo(store.pluginsFolder().resolve(".._evil").resolve("_").resolve("odd.jar"));
        assertThat(store.stage(original, manifest("..", "1.0 beta:2")))
                .isEqualTo(store.pluginsFolder().resolve("_").resolve("1.0_beta_2").resolve("odd.jar"));
        assertThat(store.stage(original, manifest("com\\example", ".")))
                .isEqualTo(store.pluginsFolder().resolve("com_example").resolve("_").resolve("odd.jar"));
    }

    /**
     * The sweep deletes, deepest first, every folder under {@code plugins}
     * that holds no listed JAR and no copy staged in this session (E20, G22).
     */
    @Test
    void theSweepDeletesOnlyFoldersThatHoldNoListedOrStagedJar() throws IOException {
        InstalledPluginStore store = new InstalledPluginStore(file());
        Path listed = jar(store, "com.example.listed/1.0/listed.jar");
        Path replaced = jar(store, "com.example.replaced/1.0/replaced.jar");
        Path leftover = store.pluginsFolder().resolve("com.example.gone/3.0/notes.txt");
        Files.createDirectories(leftover.getParent());
        Files.writeString(leftover, "left over");
        Path original = Files.createDirectories(tempDir.resolve("Downloads")).resolve("replaced.jar");
        Files.write(original, new byte[] {9});
        Path stagedNow = store.stage(original, manifest("com.example.replaced", "2.0"));

        store.sweepUnreferenced(() -> List.of(listed));

        assertThat(listed).exists();
        assertThat(stagedNow).as("staged in this session, not yet listed").exists();
        assertThat(replaced.getParent()).as("the replaced version's folder").doesNotExist();
        assertThat(store.pluginsFolder().resolve("com.example.gone")).doesNotExist();
        assertThat(file()).as("the sweep touches only the plugins folder").doesNotExist();
    }

    /**
     * Ruling-4 Q7: while a copy of the list is kept beside it, the sweep
     * deletes nothing under the plugins folder, and says why once; the hold
     * is the file on disk, not anything the store remembers — once the copy
     * is deleted, the same store sweeps as it would have. The copy's name is
     * matched ignoring case, so a copy renamed in capitals, as Windows lets
     * the engineer do, holds the sweep too.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "plugin-registry.json.20260101-000000-2.bak",
            "PLUGIN-REGISTRY.JSON.20260101-000000.BAK"})
    void aKeptCopyOfTheListHoldsTheSweepUntilItIsDeleted(String keptCopyName) throws IOException {
        InstalledPluginStore store = new InstalledPluginStore(file());
        Path listed = jar(store, "com.example.listed/1.0/listed.jar");
        Path replaced = jar(store, "com.example.replaced/1.0/replaced.jar");
        Path leftover = store.pluginsFolder().resolve("com.example.gone/3.0/notes.txt");
        Files.createDirectories(leftover.getParent());
        Files.writeString(leftover, "left over");
        Path original = Files.createDirectories(tempDir.resolve("Downloads")).resolve("replaced.jar");
        Files.write(original, new byte[] {9});
        Path stagedNow = store.stage(original, manifest("com.example.replaced", "2.0"));
        Path keptCopy = file().resolveSibling(keptCopyName);
        Files.writeString(keptCopy, "{\"schema\": \"2\"}");

        List<String> infos;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(InstalledPluginStore.class)) {
            store.sweepUnreferenced(() -> List.of(listed));
            infos = log.at(Level.INFO);
        }

        assertThat(listed).exists();
        assertThat(stagedNow).exists();
        assertThat(replaced).as("held: the kept list may name it").exists();
        assertThat(leftover).exists();
        assertThat(infos).containsExactly("Plugin registry: no JAR copy under '"
                + UnreadProjectItem.logText(store.pluginsFolder().toString()) + "' is deleted while a copy of the"
                + " list is kept beside it ('" + keptCopyName + "'); once every copy of the"
                + " list is deleted, a startup deletes the JAR copies no entry holds");

        Files.delete(keptCopy);
        store.sweepUnreferenced(() -> List.of(listed));

        assertThat(listed).exists();
        assertThat(stagedNow).as("staged in this session, not yet listed").exists();
        assertThat(replaced.getParent()).as("the replaced version's folder").doesNotExist();
        assertThat(store.pluginsFolder().resolve("com.example.gone")).doesNotExist();
        assertThat(file()).as("the sweep touches only the plugins folder").doesNotExist();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static InstalledPlugin plugin(Path jar, String id, String version, String name) {
        PluginDescriptor descriptor = new PluginDescriptor(id, name, version, "Example Audio", PluginType.EFFECT,
                PluginCategory.DYNAMICS, "compressor");
        return new InstalledPlugin(new ExternalPluginEntry(jar, "com.example.CompX"),
                new PluginManifest("com.example.CompX", descriptor), "comp-x.jar");
    }

    private static PluginManifest manifest(String id, String version) {
        return new PluginManifest("com.example.CompX",
                new PluginDescriptor(id, "Comp X", version, "Example Audio", PluginType.EFFECT));
    }

    private static Path jar(InstalledPluginStore store, String relative) throws IOException {
        Path jar = store.pluginsFolder().resolve(relative);
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[] {0});
        return jar;
    }

    /** A registry list holding {@code entries}, each a JSON value. */
    private static String list(String... entries) {
        return "{\"schema\": \"1\", \"plugins\": [" + String.join(", ", entries) + "]}";
    }

    private static String entry(String id, String version, String name, String jar) {
        return "{\"kind\": \"jar\", \"id\": \"" + id + "\", \"version\": \"" + version + "\", \"name\": \"" + name
                + "\", \"vendor\": \"Example Audio\", \"type\": \"EFFECT\", \"category\": \"DYNAMICS\","
                + " \"iconHint\": \"compressor\", \"pluginClass\": \"com.example.CompX\", \"jar\": \"" + jar
                + "\", \"originalFile\": \"comp-x.jar\"}";
    }

    private List<Path> backups() throws IOException {
        if (!Files.isDirectory(file().getParent())) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(file().getParent())) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".bak")).sorted().toList();
        }
    }
}
