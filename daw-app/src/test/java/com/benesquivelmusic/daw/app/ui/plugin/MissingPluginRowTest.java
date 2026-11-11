package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.PluginManagerDialog;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.BlockingInstallFixture;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.DescriptorReadHookFixture;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginA;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginC;
import com.benesquivelmusic.daw.core.persistence.InstalledPluginStore;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.InstalledPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.plugin.RestoreReport;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 334 (studio-engineer rulings E9 and G11; critique J27) — the Manage
 * Plugins list is headed {@code Installed Plugins:} and names each installed
 * plugin, its id and the file it was installed from; an entry whose file did
 * not load when the application started stays listed as Missing, with its
 * reason. The list is read again when the startup restore finishes, so an
 * entry the restore was still checking when the dialog opened shows what
 * became of it; so is the plug-in browser's list of installed plugins.
 *
 * <p>Every registry here persists under the test's own folder, never the real
 * {@code ~/.daw}; a "restart" is a new registry over a new store of the same
 * list.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MissingPluginRowTest {

    @TempDir
    Path folder;

    @Test
    void aMissingEntryShowsItsReason() throws Exception {
        Path list = folder.resolve("config").resolve(InstalledPluginStore.FILE_NAME);
        Path missingCopy = installAndQuit(list, InstallFixturePluginC.class, "quietsky-hall.jar",
                InstallFixturePluginA.class, "acme-punch.jar");
        Files.delete(missingCopy);

        PluginRegistry restarted = new PluginRegistry(new InstalledPluginStore(list));
        try {
            RestoreReport report = restarted.restore().get(10, TimeUnit.SECONDS);
            assertThat(report.missing()).extracting(InstalledPlugin::id).containsExactly("com.acme.punch");

            Parent content = runOnFxThread(() -> (Parent) new PluginManagerDialog(restarted).getDialogPane()
                    .getContent());

            assertThat(runOnFxThread(() -> PluginNodes.labelTexts(content))).as("G11: the list's header")
                    .contains("Installed Plugins:").doesNotContain("Loaded Plugins:");
            assertThat(rowTexts(listOf(content))).containsExactly(
                    "QuietSky Hall (com.quietsky.hall) — quietsky-hall.jar",
                    "Acme Punch (com.acme.punch) — acme-punch.jar — Missing: file not found");
        } finally {
            restarted.disposeAll();
        }
    }

    @Test
    void theListIsReadAgainWhenTheRestoreFinishes() throws Exception {
        Path list = folder.resolve("config").resolve(InstalledPluginStore.FILE_NAME);
        BlockingInstallFixture.block = false;
        // The entry the restore checks first is held in its constructor; the second's copy is gone.
        Path missingCopy = installAndQuit(list, BlockingInstallFixture.class, "blocking.jar",
                InstallFixturePluginA.class, "acme-punch.jar");
        Files.delete(missingCopy);

        BlockingInstallFixture.started = new CountDownLatch(1);
        BlockingInstallFixture.release = new CountDownLatch(1);
        BlockingInstallFixture.block = true;
        PluginRegistry restarted = new PluginRegistry(new InstalledPluginStore(list));
        try {
            CompletableFuture<RestoreReport> restoring = restarted.restore();
            assertThat(BlockingInstallFixture.started.await(10, TimeUnit.SECONDS))
                    .as("the restore checks the first entry and is held in its constructor").isTrue();
            PluginManagerDialog dialog = runOnFxThread(() -> new PluginManagerDialog(restarted));
            ListView<?> listView = listOf(runOnFxThread(() -> (Parent) dialog.getDialogPane().getContent()));
            assertThat(rowTexts(listView)).as("both entries are listed while the restore still checks them")
                    .containsExactly("Blocking install (test.blocking.install) — blocking.jar",
                            "Acme Punch (com.acme.punch) — acme-punch.jar");

            BlockingInstallFixture.release.countDown();
            restoring.get(10, TimeUnit.SECONDS);

            List<String> rows = List.of();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                rows = rowTexts(listView);
                if (rows.stream().anyMatch(row -> row.contains("Missing"))) {
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(rows).as("the restore published the deleted copy as Missing, and the list was read again")
                    .containsExactly("Blocking install (test.blocking.install) — blocking.jar",
                            "Acme Punch (com.acme.punch) — acme-punch.jar — Missing: file not found");
            // The dialog stays reachable until here, as its window keeps an open
            // dialog: the restore holds it only weakly (finding FX2-6).
            Reference.reachabilityFence(dialog);
        } finally {
            BlockingInstallFixture.block = false;
            BlockingInstallFixture.release.countDown();
            restarted.disposeAll();
        }
    }

    /**
     * The plug-in browser's third-party group is read again when the startup
     * restore finishes, as the Manage Plugins list is: a browser opened while
     * the restore still checks the entries lists none of them, and then lists
     * each one the restore loaded.
     */
    @Test
    void theBrowsersInstalledPluginsAreReadAgainWhenTheRestoreFinishes() throws Exception {
        Path list = folder.resolve("config").resolve(InstalledPluginStore.FILE_NAME);
        BlockingInstallFixture.block = false;
        installAndQuit(list, BlockingInstallFixture.class, "blocking.jar", InstallFixturePluginA.class,
                "acme-punch.jar");

        BlockingInstallFixture.started = new CountDownLatch(1);
        BlockingInstallFixture.release = new CountDownLatch(1);
        BlockingInstallFixture.block = true;
        PluginRegistry restarted = new PluginRegistry(new InstalledPluginStore(list));
        try {
            CompletableFuture<RestoreReport> restoring = restarted.restore();
            assertThat(BlockingInstallFixture.started.await(10, TimeUnit.SECONDS))
                    .as("the restore checks the first entry and is held in its constructor").isTrue();
            PluginBrowser browser = runOnFxThread(() -> new PluginBrowser(restarted, true, "Vox / Insert 1"));
            assertThat(topGroups(browser)).as("no entry has loaded yet: no third-party group")
                    .containsExactly(PluginBrowser.BUILT_IN_GROUP);

            BlockingInstallFixture.release.countDown();
            restoring.get(10, TimeUnit.SECONDS);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && !topGroups(browser).contains(PluginBrowser.INSTALLED_GROUP)) {
                Thread.sleep(20);
            }
            assertThat(topGroups(browser)).as("the restore loaded both entries, and the browser read them again")
                    .containsExactly(PluginBrowser.BUILT_IN_GROUP, PluginBrowser.INSTALLED_GROUP);
            assertThat(installedVendors(browser)).containsExactly("Acme Audio", "Test");
        } finally {
            BlockingInstallFixture.block = false;
            BlockingInstallFixture.release.countDown();
            restarted.disposeAll();
        }
    }

    /**
     * Story 334 (PR #990 review, ruling R-C2.1): a restore that completes while the browser is
     * being built — after the browser read which plugins are loaded, before its construction
     * returns — is not missed. The browser registers its re-read whatever the restore's state, so
     * after one FX turn its third-party group lists exactly the plugins the restore loaded.
     */
    @Test
    void aBrowserWhoseRestoreFinishesWhileItIsBuiltReadsTheInstalledPluginsAgain() throws Exception {
        Path list = folder.resolve("config").resolve(InstalledPluginStore.FILE_NAME);
        BlockingInstallFixture.block = false;
        // The restore loads the hook's entry, then is held in the blocking entry's constructor.
        installAndQuit(list, DescriptorReadHookFixture.class, "hook.jar", BlockingInstallFixture.class,
                "blocking.jar");

        BlockingInstallFixture.started = new CountDownLatch(1);
        BlockingInstallFixture.release = new CountDownLatch(1);
        BlockingInstallFixture.block = true;
        PluginRegistry restarted = new PluginRegistry(new InstalledPluginStore(list));
        try {
            CompletableFuture<RestoreReport> restoring = restarted.restore();
            assertThat(BlockingInstallFixture.started.await(10, TimeUnit.SECONDS))
                    .as("the restore loaded the hook's entry and is held in the blocking one's constructor")
                    .isTrue();
            // The browser reads the hook's descriptor on FX while it is built, right after it read
            // which plugins are loaded: the restore is let go there and waited for, bounded well
            // inside runOnFxThread's 15 s guard.
            DescriptorReadHookFixture.onDescriptorReadOnFx = () -> {
                BlockingInstallFixture.release.countDown();
                try {
                    restoring.get(10, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                } catch (ExecutionException | TimeoutException failure) {
                    throw new IllegalStateException(failure);
                }
            };
            AtomicReference<List<String>> vendorsWhenBuilt = new AtomicReference<>();
            PluginBrowser browser = runOnFxThread(() -> {
                PluginBrowser built = new PluginBrowser(restarted, true, "Vox / Insert 1");
                vendorsWhenBuilt.set(installedVendors(built));
                return built;
            });

            assertThat(DescriptorReadHookFixture.onDescriptorReadOnFx).as("the hook ran while the browser was built")
                    .isNull();
            assertThat(restoring).as("the restore finished while the browser was being built")
                    .isCompletedWithValueMatching(report -> report.missing().isEmpty());
            assertThat(vendorsWhenBuilt.get()).as("the browser had read the loaded plugins before it finished")
                    .containsExactly(DescriptorReadHookFixture.VENDOR);

            runOnFxThread(() -> Boolean.TRUE); // one FIFO turn: the re-read the browser posted runs first
            assertThat(installedVendors(browser)).as("the browser read the installed plugins again")
                    .containsExactly(DescriptorReadHookFixture.VENDOR, "Test");
            assertThat(installedEntries(browser)).as("exactly the plugins the restore loaded")
                    .containsExactlyInAnyOrderElementsOf(restarted.getLoadedPlugins().keySet())
                    .hasSize(2);
        } finally {
            DescriptorReadHookFixture.onDescriptorReadOnFx = null;
            BlockingInstallFixture.block = false;
            BlockingInstallFixture.release.countDown();
            restarted.disposeAll();
        }
    }

    /**
     * Finding FX2-6: the restore holds a browser or a Manage Plugins dialog
     * opened while it runs only weakly, so one that is closed and dropped is
     * not kept for as long as the restore runs — which is the session, when a
     * plugin's constructor hangs (ruling-3 Q6). The restore here is still held
     * when both are collected.
     */
    @Test
    void aBrowserAndADialogDroppedWhileTheRestoreRunsAreNotKeptByIt() throws Exception {
        Path list = folder.resolve("config").resolve(InstalledPluginStore.FILE_NAME);
        BlockingInstallFixture.block = false;
        installAndQuit(list, BlockingInstallFixture.class, "blocking.jar", InstallFixturePluginA.class,
                "acme-punch.jar");

        BlockingInstallFixture.started = new CountDownLatch(1);
        BlockingInstallFixture.release = new CountDownLatch(1);
        BlockingInstallFixture.block = true;
        PluginRegistry restarted = new PluginRegistry(new InstalledPluginStore(list));
        try {
            CompletableFuture<RestoreReport> restoring = restarted.restore();
            assertThat(BlockingInstallFixture.started.await(10, TimeUnit.SECONDS))
                    .as("the restore checks the first entry and is held in its constructor").isTrue();
            WeakReference<PluginBrowser> browser = runOnFxThread(() ->
                    new WeakReference<>(new PluginBrowser(restarted, true, "Vox / Insert 1")));
            WeakReference<PluginManagerDialog> dialog = runOnFxThread(() ->
                    new WeakReference<>(new PluginManagerDialog(restarted)));

            // Bounded well inside the fixture's own 10 s hold, which would complete the restore.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((browser.get() != null || dialog.get() != null) && System.nanoTime() < deadline) {
                System.gc();
                runOnFxThread(() -> Boolean.TRUE);
                Thread.sleep(20);
            }

            assertThat(restoring).as("the restore still runs").isNotDone();
            assertThat(browser.get()).as("the dropped browser is collected").isNull();
            assertThat(dialog.get()).as("the dropped dialog is collected").isNull();

            BlockingInstallFixture.release.countDown();
            assertThat(restoring.get(10, TimeUnit.SECONDS).missing()).as("the restore completes all the same")
                    .isEmpty();
        } finally {
            BlockingInstallFixture.block = false;
            BlockingInstallFixture.release.countDown();
            restarted.disposeAll();
        }
    }

    /**
     * Installs {@code first} then {@code second} into a registry persisted in
     * {@code list}, as the install flow does, waits for the list to be
     * written, and quits: every catalogue instance released, so the copies
     * can be deleted on Windows.
     *
     * @return the registry's copy of {@code second}'s JAR
     */
    private Path installAndQuit(Path list, Class<? extends DawPlugin> first, String firstJar,
                                Class<? extends DawPlugin> second, String secondJar) throws Exception {
        InstalledPluginStore store = new InstalledPluginStore(list);
        PluginRegistry registry = new PluginRegistry(store);
        try {
            registry.restore().get(10, TimeUnit.SECONDS);
            Path downloads = Files.createDirectories(folder.resolve("downloads"));
            PluginTestJars.install(registry, PluginTestJars.buildJar(downloads, firstJar,
                    List.of(PluginTestJars.manifest(first)), List.of(first)), first);
            PluginTestJars.install(registry, PluginTestJars.buildJar(downloads, secondJar,
                    List.of(PluginTestJars.manifest(second)), List.of(second)), second);
            assertThat(store.awaitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(10)))
                    .as("the list was written").isTrue();
            String secondId = PluginTestJars.manifest(second).descriptor().id();
            return registry.installedJar(secondId).orElseThrow().entry().jarPath();
        } finally {
            registry.disposeAll();
        }
    }

    /** The names of the browser tree's top groups, in order. */
    private static List<String> topGroups(PluginBrowser browser) {
        return runOnFxThread(() -> browser.treeForTest().getRoot().getChildren().stream()
                .map(item -> item.getValue().displayText())
                .toList());
    }

    /** The vendor groups under the browser tree's third-party group, in order. */
    private static List<String> installedVendors(PluginBrowser browser) {
        return runOnFxThread(() -> browser.treeForTest().getRoot().getChildren().stream()
                .filter(item -> item.getValue().displayText().equals(PluginBrowser.INSTALLED_GROUP))
                .flatMap(item -> item.getChildren().stream())
                .map(item -> item.getValue().displayText())
                .toList());
    }

    /** The entries of the plugins listed under the browser tree's third-party group. */
    private static List<ExternalPluginEntry> installedEntries(PluginBrowser browser) {
        return runOnFxThread(() -> browser.treeForTest().getRoot().getChildren().stream()
                .filter(item -> item.getValue().displayText().equals(PluginBrowser.INSTALLED_GROUP))
                .flatMap(item -> item.getChildren().stream())
                .flatMap(vendor -> vendor.getChildren().stream())
                .map(TreeItem::getValue)
                .filter(PluginBrowser.ExternalRow.class::isInstance)
                .map(PluginBrowser.ExternalRow.class::cast)
                .map(PluginBrowser.ExternalRow::entry)
                .toList());
    }

    /** The dialog's list, moved into a scene of its own so its cells render. */
    private static ListView<?> listOf(Parent content) {
        return runOnFxThread(() -> {
            ListView<?> listView = (ListView<?>) content.lookup(".list-view");
            new Scene(new StackPane(listView), 640, 320);
            return listView;
        });
    }

    /** The texts of the list's rows, in order, as its cells render them. */
    private static List<String> rowTexts(ListView<?> listView) {
        return runOnFxThread(() -> {
            Parent root = listView.getScene().getRoot();
            root.applyCss();
            root.layout();
            return listView.lookupAll(".list-cell").stream()
                    .map(node -> (ListCell<?>) node)
                    .filter(cell -> cell.isVisible() && cell.getIndex() >= 0 && !cell.isEmpty()
                            && cell.getText() != null)
                    .sorted(Comparator.comparingInt(cell -> cell.getIndex()))
                    .map(ListCell::getText)
                    .toList();
        });
    }
}
