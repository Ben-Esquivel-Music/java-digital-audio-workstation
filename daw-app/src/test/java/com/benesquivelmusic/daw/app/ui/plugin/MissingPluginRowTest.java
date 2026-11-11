package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.PluginManagerDialog;
import com.benesquivelmusic.daw.app.ui.plugin.PluginBrowser.BrowserRow;
import com.benesquivelmusic.daw.app.ui.plugin.PluginBrowser.BuiltInRow;
import com.benesquivelmusic.daw.app.ui.plugin.PluginBrowser.ExternalRow;
import com.benesquivelmusic.daw.app.ui.plugin.PluginBrowser.GroupRow;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.BlockingInstallFixture;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.DescriptorReadHookFixture;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginA;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginB;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginC;
import com.benesquivelmusic.daw.core.persistence.InstalledPluginStore;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.InstalledPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.plugin.RestoreReport;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

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
            runOnFxThread(() -> {
                listView.getSelectionModel().select(1);
                return Boolean.TRUE;
            });

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
            assertThat(selectedRowText(listView)).as("the entry the user chose stays chosen when it turns Missing")
                    .contains("Acme Punch (com.acme.punch) — acme-punch.jar — Missing: file not found");
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
     * Story 334, ruling R-N2: after Remove Selected of a middle row, which on a
     * dialog's first re-read JavaFX alone would move to the row now at its
     * index, nothing is selected; and the entry chosen next stays chosen when
     * the startup restore's re-read finds it Missing.
     */
    @Test
    void theManageListKeepsTheSelectedEntryWhenItTurnsMissingAfterAnEarlierRemove() throws Exception {
        Path list = folder.resolve("config").resolve(InstalledPluginStore.FILE_NAME);
        BlockingInstallFixture.block = false;
        // The entry the restore checks first is held in its constructor; the third's copy is gone.
        List<Path> copies = installAndQuit(list, List.of(
                new FixtureJar(BlockingInstallFixture.class, "blocking.jar"),
                new FixtureJar(InstallFixturePluginB.class, "acme-echo.jar"),
                new FixtureJar(InstallFixturePluginA.class, "acme-punch.jar")));
        Files.delete(copies.get(2));

        BlockingInstallFixture.started = new CountDownLatch(1);
        BlockingInstallFixture.release = new CountDownLatch(1);
        BlockingInstallFixture.block = true;
        InstalledPluginStore store = new InstalledPluginStore(list);
        PluginRegistry restarted = new PluginRegistry(store);
        try {
            CompletableFuture<RestoreReport> restoring = restarted.restore();
            assertThat(BlockingInstallFixture.started.await(10, TimeUnit.SECONDS))
                    .as("the restore checks the first entry and is held in its constructor").isTrue();
            PluginManagerDialog dialog = runOnFxThread(() -> new PluginManagerDialog(restarted));
            Parent content = runOnFxThread(() -> (Parent) dialog.getDialogPane().getContent());
            ListView<?> listView = listOf(content);
            assertThat(rowTexts(listView)).as("the three entries are listed while the restore still checks them")
                    .containsExactly("Blocking install (test.blocking.install) — blocking.jar",
                            "Acme Echo (com.acme.echo) — acme-echo.jar",
                            "Acme Punch (com.acme.punch) — acme-punch.jar");

            runOnFxThread(() -> {
                listView.getSelectionModel().select(1);
                buttonNamed(content, "Remove Selected").fire();
                return Boolean.TRUE;
            });
            assertThat(rowTexts(listView)).as("Remove Selected removed the second entry and read the list again")
                    .containsExactly("Blocking install (test.blocking.install) — blocking.jar",
                            "Acme Punch (com.acme.punch) — acme-punch.jar");
            assertThat(selectedIndex(listView)).as("nothing is selected after Remove Selected, never a neighbour")
                    .isEqualTo(-1);
            assertThat(runOnFxThread(() -> listView.getSelectionModel().getSelectedItem() == null))
                    .as("no selected item").isTrue();

            runOnFxThread(() -> {
                listView.getSelectionModel().select(1);
                return Boolean.TRUE;
            });
            BlockingInstallFixture.release.countDown();
            restoring.get(10, TimeUnit.SECONDS);
            awaitUntil(() -> rowTexts(listView).stream().anyMatch(row -> row.contains("Missing")));

            assertThat(selectedRowText(listView)).as("the entry the user chose stays chosen when it turns Missing")
                    .contains("Acme Punch (com.acme.punch) — acme-punch.jar — Missing: file not found");
            assertThat(selectedIndex(listView)).isEqualTo(1);
            assertThat(store.awaitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(10)))
                    .as("the removal's write of the list is over before the folder is deleted").isTrue();
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
     * each one the restore loaded. The built-in the user chose meanwhile stays
     * chosen, with the keyboard focus on it, and Add stays enabled (PR #990
     * review; story 334, ruling R-N2).
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
            BrowserRow chosen = selectFirst(browser, BuiltInRow.class, _ -> true);
            int chosenIndex = selectedIndex(browser);
            assertThat(addDisabled(browser)).as("a built-in is chosen: Add is enabled").isFalse();

            BlockingInstallFixture.release.countDown();
            restoring.get(10, TimeUnit.SECONDS);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && !topGroups(browser).contains(PluginBrowser.INSTALLED_GROUP)) {
                Thread.sleep(20);
            }
            assertThat(topGroups(browser)).as("the restore loaded both entries, and the browser read them again")
                    .containsExactly(PluginBrowser.BUILT_IN_GROUP, PluginBrowser.INSTALLED_GROUP);
            assertThat(installedVendors(browser)).containsExactly("Acme Audio", "Test");
            assertThat(selectedRow(browser)).as("the re-read keeps the built-in the user chose").contains(chosen);
            assertThat(addDisabled(browser)).as("Add stays enabled").isFalse();
            assertThat(focusedIndex(browser)).as("the keyboard focus stays on the choice")
                    .isEqualTo(selectedIndex(browser));
            assertThat(selectedIndex(browser)).as("the third-party group is added below Built-in: the choice keeps its row")
                    .isEqualTo(chosenIndex);
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
     * Story 334, ruling R-N2: a third-party plugin the user chose before the
     * startup restore finished stays chosen when the browser reads the
     * installed plugins again — the same installed entry, with the keyboard
     * focus on it and Add enabled.
     */
    @Test
    void aSelectedThirdPartyPluginStaysSelectedWhenTheRestoreFinishes() throws Exception {
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
            PluginBrowser browser = runOnFxThread(() -> new PluginBrowser(restarted, true, "Vox / Insert 1"));
            ExternalPluginEntry chosen = selectFirst(browser, ExternalRow.class,
                    item -> parentName(item).equals(DescriptorReadHookFixture.VENDOR)).entry();

            BlockingInstallFixture.release.countDown();
            restoring.get(10, TimeUnit.SECONDS);
            awaitUntil(() -> installedVendors(browser).contains("Test"));

            assertThat(installedVendors(browser)).as("the browser read the installed plugins again")
                    .containsExactly(DescriptorReadHookFixture.VENDOR, "Test");
            assertThat(selectedEntry(browser)).as("the re-read keeps the installed entry the user chose")
                    .contains(chosen);
            assertThat(addDisabled(browser)).as("Add stays enabled").isFalse();
            assertThat(focusedIndex(browser)).as("the keyboard focus stays on the choice")
                    .isEqualTo(selectedIndex(browser));
        } finally {
            BlockingInstallFixture.block = false;
            BlockingInstallFixture.release.countDown();
            restarted.disposeAll();
        }
    }

    /**
     * Story 334, ruling R-N2: a choice the browser's re-read no longer lists
     * leaves nothing selected — never the row that took its place — so Add
     * stays disabled.
     */
    @Test
    void aChoiceTheReReadNoLongerListsLeavesNothingSelected() throws Exception {
        Path list = folder.resolve("config").resolve(InstalledPluginStore.FILE_NAME);
        BlockingInstallFixture.block = false;
        // The restore loads the hook's entry, then is held in the blocking entry's constructor.
        installAndQuit(list, DescriptorReadHookFixture.class, "hook.jar", BlockingInstallFixture.class,
                "blocking.jar");

        BlockingInstallFixture.started = new CountDownLatch(1);
        BlockingInstallFixture.release = new CountDownLatch(1);
        BlockingInstallFixture.block = true;
        InstalledPluginStore store = new InstalledPluginStore(list);
        PluginRegistry restarted = new PluginRegistry(store);
        try {
            CompletableFuture<RestoreReport> restoring = restarted.restore();
            assertThat(BlockingInstallFixture.started.await(10, TimeUnit.SECONDS))
                    .as("the restore loaded the hook's entry and is held in the blocking one's constructor")
                    .isTrue();
            PluginBrowser browser = runOnFxThread(() -> new PluginBrowser(restarted, true, "Vox / Insert 1"));
            ExternalPluginEntry hookEntry = selectFirst(browser, ExternalRow.class,
                    item -> parentName(item).equals(DescriptorReadHookFixture.VENDOR)).entry();
            // Its copy is in the @TempDir: its class loader is released, so the copy can be deleted on Windows.
            restarted.unregister(hookEntry).ifPresent(removed -> removed.disposal().run());

            BlockingInstallFixture.release.countDown();
            restoring.get(10, TimeUnit.SECONDS);
            awaitUntil(() -> installedVendors(browser).contains("Test"));

            assertThat(installedVendors(browser)).as("the browser read the installed plugins again")
                    .containsExactly("Test");
            assertThat(selectedRow(browser)).as("the choice is gone: nothing is selected, never a neighbour")
                    .isEmpty();
            assertThat(selectedIndex(browser)).isEqualTo(-1);
            assertThat(addDisabled(browser)).as("Add stays disabled").isTrue();
            assertThat(installedEntries(browser)).doesNotContain(hookEntry);
            assertThat(store.awaitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(10)))
                    .as("the removal's write of the list is over before the folder is deleted").isTrue();
        } finally {
            BlockingInstallFixture.block = false;
            BlockingInstallFixture.release.countDown();
            restarted.disposeAll();
        }
    }

    /**
     * Story 334, ruling R-N2: a browser opened once the startup restore is over
     * still reads the installed plugins again, one FX turn after it was built;
     * the built-in the user chose before that stays chosen, and Add enabled.
     */
    @Test
    void aBrowserOpenedAfterTheRestoreKeepsTheChoiceMadeBeforeItsReRead() throws Exception {
        Path list = folder.resolve("config").resolve(InstalledPluginStore.FILE_NAME);
        installAndQuit(list, InstallFixturePluginC.class, "quietsky-hall.jar", InstallFixturePluginA.class,
                "acme-punch.jar");

        PluginRegistry restarted = new PluginRegistry(new InstalledPluginStore(list));
        try {
            assertThat(restarted.restore().get(10, TimeUnit.SECONDS).missing()).as("the restore is over")
                    .isEmpty();
            AtomicReference<BrowserRow> chosen = new AtomicReference<>();
            PluginBrowser browser = runOnFxThread(() -> {
                PluginBrowser built = new PluginBrowser(restarted, true, "Vox / Insert 1");
                chosen.set(selectFirst(built, BuiltInRow.class, _ -> true));
                return built;
            });
            runOnFxThread(() -> Boolean.TRUE); // one FIFO turn: the re-read the browser posted runs first

            assertThat(selectedRow(browser)).as("the re-read keeps the built-in chosen before it")
                    .contains(chosen.get());
            assertThat(addDisabled(browser)).as("Add stays enabled").isFalse();
        } finally {
            restarted.disposeAll();
        }
    }

    /**
     * Story 334, ruling R-N2: a group the user collapsed stays collapsed, and
     * selected, when the restore's re-read rebuilds the tree; a search shows
     * it expanded, and clearing the search collapses it again.
     */
    @Test
    void aGroupTheUserCollapsedStaysCollapsedAndSelectedAcrossTheReReadAndASearch() throws Exception {
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
            // The user collapses the first category under Built-in and selects it; a search will list a plugin of it.
            AtomicReference<String> pluginOfTheGroup = new AtomicReference<>();
            List<String> path = runOnFxThread(() -> {
                TreeView<BrowserRow> tree = browser.treeForTest();
                TreeItem<BrowserRow> group = rowsUnder(tree.getRoot())
                        .filter(item -> item.getValue() instanceof GroupRow
                                && parentName(item).equals(PluginBrowser.BUILT_IN_GROUP))
                        .findFirst()
                        .orElseThrow();
                pluginOfTheGroup.set(group.getChildren().getFirst().getValue().displayText());
                group.setExpanded(false);
                tree.getSelectionModel().select(group);
                return pathOf(group);
            });
            assertThat(path).hasSize(2).first().isEqualTo(PluginBrowser.BUILT_IN_GROUP);

            BlockingInstallFixture.release.countDown();
            restoring.get(10, TimeUnit.SECONDS);
            awaitUntil(() -> topGroups(browser).contains(PluginBrowser.INSTALLED_GROUP));

            assertThat(topGroups(browser)).as("the browser read the installed plugins again")
                    .containsExactly(PluginBrowser.BUILT_IN_GROUP, PluginBrowser.INSTALLED_GROUP);
            assertThat(collapsedGroups(browser)).as("the group the user collapsed stays collapsed; every other is expanded")
                    .containsExactly(path);
            assertThat(selectedPath(browser)).as("the group the user chose stays chosen").contains(path);
            assertThat(selectedRow(browser)).hasValueSatisfying(row -> assertThat(row).isInstanceOf(GroupRow.class));
            assertThat(addDisabled(browser)).as("a group is chosen: Add stays disabled").isTrue();

            runOnFxThread(() -> {
                browser.applyFilterForTest(pluginOfTheGroup.get().toLowerCase(Locale.ROOT));
                return Boolean.TRUE;
            });
            assertThat(groupPaths(browser)).as("the search lists the group").contains(path);
            assertThat(collapsedGroups(browser)).as("a search shows every group it lists expanded").isEmpty();

            runOnFxThread(() -> {
                browser.applyFilterForTest("");
                return Boolean.TRUE;
            });
            assertThat(collapsedGroups(browser)).as("clearing the search collapses the group again")
                    .containsExactly(path);
        } finally {
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
        return installAndQuit(list, List.of(new FixtureJar(first, firstJar), new FixtureJar(second, secondJar)))
                .get(1);
    }

    /** A fixture plugin and the name of the JAR it is installed from. */
    private record FixtureJar(Class<? extends DawPlugin> plugin, String jarName) {
        FixtureJar {
            Objects.requireNonNull(plugin, "plugin must not be null");
            Objects.requireNonNull(jarName, "jarName must not be null");
        }
    }

    /**
     * Installs each of {@code jars}, in order, into a registry persisted in
     * {@code list}, as the install flow does, waits for the list to be
     * written, and quits: every catalogue instance released, so the copies
     * can be deleted on Windows.
     *
     * @return the registry's copy of each JAR, in the order of {@code jars}
     */
    private List<Path> installAndQuit(Path list, List<FixtureJar> jars) throws Exception {
        InstalledPluginStore store = new InstalledPluginStore(list);
        PluginRegistry registry = new PluginRegistry(store);
        try {
            registry.restore().get(10, TimeUnit.SECONDS);
            Path downloads = Files.createDirectories(folder.resolve("downloads"));
            for (FixtureJar jar : jars) {
                PluginTestJars.install(registry, PluginTestJars.buildJar(downloads, jar.jarName(),
                        List.of(PluginTestJars.manifest(jar.plugin())), List.of(jar.plugin())), jar.plugin());
            }
            assertThat(store.awaitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(10)))
                    .as("the list was written").isTrue();
            List<Path> copies = new ArrayList<>();
            for (FixtureJar jar : jars) {
                String id = PluginTestJars.manifest(jar.plugin()).descriptor().id();
                copies.add(registry.installedJar(id).orElseThrow().entry().jarPath());
            }
            return List.copyOf(copies);
        } finally {
            registry.disposeAll();
        }
    }

    /** Waits, for up to 10 s, until {@code done} holds; the caller then asserts what it waited for. */
    private static void awaitUntil(BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !done.getAsBoolean()) {
            Thread.sleep(20);
        }
    }

    /** Every row under {@code item}, depth first in tree order; {@code item} itself is not one of them. */
    private static Stream<TreeItem<BrowserRow>> rowsUnder(TreeItem<BrowserRow> item) {
        return item.getChildren().stream().flatMap(child -> Stream.concat(Stream.of(child), rowsUnder(child)));
    }

    /** The display text of {@code item}'s parent row: a plugin's vendor or category, a category's top group. */
    private static String parentName(TreeItem<BrowserRow> item) {
        return item.getParent().getValue().displayText();
    }

    /** The display texts from the top level down to {@code item}; the tree's hidden root is not one of them. */
    private static List<String> pathOf(TreeItem<BrowserRow> item) {
        List<String> names = new ArrayList<>();
        for (TreeItem<BrowserRow> at = item; at.getParent() != null; at = at.getParent()) {
            names.addFirst(at.getValue().displayText());
        }
        return List.copyOf(names);
    }

    /**
     * Selects, on the FX thread, the first row in tree order that is a
     * {@code type} and that {@code which} accepts, as the user's click does.
     *
     * @return the row selected
     */
    private static <R extends BrowserRow> R selectFirst(PluginBrowser browser, Class<R> type,
                                                        Predicate<TreeItem<BrowserRow>> which) {
        return runOnFxThread(() -> {
            TreeView<BrowserRow> tree = browser.treeForTest();
            TreeItem<BrowserRow> item = rowsUnder(tree.getRoot())
                    .filter(row -> type.isInstance(row.getValue()) && which.test(row))
                    .findFirst()
                    .orElseThrow();
            tree.getSelectionModel().select(item);
            return type.cast(item.getValue());
        });
    }

    /** The browser's selected row; empty when nothing is selected. */
    private static Optional<BrowserRow> selectedRow(PluginBrowser browser) {
        return runOnFxThread(() -> Optional.ofNullable(browser.treeForTest().getSelectionModel().getSelectedItem())
                .map(TreeItem::getValue));
    }

    /** The installed entry of the browser's selected row; empty unless a third-party plugin is selected. */
    private static Optional<ExternalPluginEntry> selectedEntry(PluginBrowser browser) {
        return selectedRow(browser).filter(ExternalRow.class::isInstance).map(ExternalRow.class::cast)
                .map(ExternalRow::entry);
    }

    /** The path ({@link #pathOf}) of the browser's selected row; empty when nothing is selected. */
    private static Optional<List<String>> selectedPath(PluginBrowser browser) {
        return runOnFxThread(() -> Optional.ofNullable(browser.treeForTest().getSelectionModel().getSelectedItem())
                .map(MissingPluginRowTest::pathOf));
    }

    private static int selectedIndex(PluginBrowser browser) {
        return runOnFxThread(() -> browser.treeForTest().getSelectionModel().getSelectedIndex());
    }

    private static int focusedIndex(PluginBrowser browser) {
        return runOnFxThread(() -> browser.treeForTest().getFocusModel().getFocusedIndex());
    }

    /** Whether the browser's Add button is disabled; found by its button data, as the footer lays it out. */
    private static boolean addDisabled(PluginBrowser browser) {
        return runOnFxThread(() -> {
            DialogPane pane = browser.getDialogPane();
            ButtonType add = pane.getButtonTypes().stream()
                    .filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                    .findFirst()
                    .orElseThrow();
            return pane.lookupButton(add).isDisabled();
        });
    }

    /** The paths ({@link #pathOf}) of every group in the browser's tree, in tree order. */
    private static List<List<String>> groupPaths(PluginBrowser browser) {
        return runOnFxThread(() -> rowsUnder(browser.treeForTest().getRoot())
                .filter(item -> item.getValue() instanceof GroupRow)
                .map(MissingPluginRowTest::pathOf)
                .toList());
    }

    /** The paths ({@link #pathOf}) of the groups in the browser's tree that are collapsed, in tree order. */
    private static List<List<String>> collapsedGroups(PluginBrowser browser) {
        return runOnFxThread(() -> rowsUnder(browser.treeForTest().getRoot())
                .filter(item -> item.getValue() instanceof GroupRow && !item.isExpanded())
                .map(MissingPluginRowTest::pathOf)
                .toList());
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

    /** The text of the list's selected row, as its cell renders it; empty when nothing is selected. */
    private static Optional<String> selectedRowText(ListView<?> listView) {
        return runOnFxThread(() -> {
            int selected = listView.getSelectionModel().getSelectedIndex();
            List<String> texts = rowTexts(listView);
            return selected >= 0 && selected < texts.size() ? Optional.of(texts.get(selected)) : Optional.empty();
        });
    }

    private static int selectedIndex(ListView<?> listView) {
        return runOnFxThread(() -> listView.getSelectionModel().getSelectedIndex());
    }

    /** The button in {@code content} whose text is {@code text}. */
    private static Button buttonNamed(Parent content, String text) {
        return content.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }
}
