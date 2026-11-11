package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.PluginBrowser.BrowserRow;
import com.benesquivelmusic.daw.app.ui.plugin.PluginBrowser.BuiltInRow;
import com.benesquivelmusic.daw.app.ui.plugin.PluginBrowser.GroupRow;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginA;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginC;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 303 — the §6.7 browser groups built-in effects by their SDK
 * {@link com.benesquivelmusic.daw.sdk.editor.PluginCategory} (via
 * {@link InsertEffectFactory#getCategory}) and installed third-party plugins by
 * vendor, over one shared, searchable model. Most assertions drive the pure
 * static model / filter; one end-to-end assertion checks the constructed
 * {@link PluginBrowser}'s tree.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class BrowserCategoriesFromPluginCategoryTest {

    @TempDir
    Path tempDir;

    @Test
    void builtInsGroupByCategoryAndThirdPartyByVendorWithSearch() throws Exception {
        PluginManifest a = PluginTestJars.manifest(InstallFixturePluginA.class); // Acme Audio
        PluginManifest c = PluginTestJars.manifest(InstallFixturePluginC.class); // QuietSky DSP
        Path jar = PluginTestJars.buildJar(tempDir, "ac.jar",
                List.of(a, c),
                List.of(InstallFixturePluginA.class, InstallFixturePluginC.class));

        PluginRegistry registry = new PluginRegistry();
        try {
            PluginTestJars.install(registry, jar, InstallFixturePluginA.class);
            PluginTestJars.install(registry, jar, InstallFixturePluginC.class);

            List<BrowserRow> model = PluginBrowser.buildModel(
                    InsertEffectFactory.availableTypes(), true, registry.getLoadedPlugins());

            // ── Built-in grouped by PluginCategory display name ──────────────
            GroupRow builtIn = group(model, PluginBrowser.BUILT_IN_GROUP);
            assertThat(subgroupNames(builtIn)).contains("Dynamics", "Reverb & Delay");
            assertThat(leafLabels(group(builtIn.children(), "Dynamics")))
                    .as("the Compressor sits under Dynamics").contains("Compressor");
            assertThat(leafLabels(group(builtIn.children(), "Reverb & Delay")))
                    .as("the Reverb sits under Reverb & Delay").contains("Reverb");

            // ── Third-party grouped by vendor ────────────────────────────────
            GroupRow installed = group(model, PluginBrowser.INSTALLED_GROUP);
            assertThat(subgroupNames(installed))
                    .containsExactlyInAnyOrder("Acme Audio", "QuietSky DSP");

            // ── Search "compress" keeps Built-in/Dynamics, drops the rest ────
            List<BrowserRow> filtered = PluginBrowser.filter(model, "compress");
            GroupRow filteredBuiltIn = group(filtered, PluginBrowser.BUILT_IN_GROUP);
            assertThat(subgroupNames(filteredBuiltIn))
                    .as("only the Dynamics subgroup (holding the match) survives")
                    .containsExactly("Dynamics");
            assertThat(leafLabels(group(filteredBuiltIn.children(), "Dynamics")))
                    .containsExactly("Compressor");
            assertThat(groupOrNull(filtered, PluginBrowser.INSTALLED_GROUP))
                    .as("no third-party name matches 'compress'").isNull();

            // ── Search matching nothing yields no rows ───────────────────────
            assertThat(PluginBrowser.filter(model, "zzzznotathing")).isEmpty();

            // ── End-to-end: the constructed browser's tree top groups ────────
            PluginBrowser browser = runOnFxThread(() ->
                    new PluginBrowser(registry, true, "Test / Insert 1"));
            List<String> topGroups = runOnFxThread(() ->
                    browser.treeForTest().getRoot().getChildren().stream()
                            .map(item -> item.getValue().displayText())
                            .toList());
            assertThat(topGroups)
                    .containsExactly(PluginBrowser.BUILT_IN_GROUP, PluginBrowser.INSTALLED_GROUP);
        } finally {
            registry.disposeAll();
        }
    }

    /**
     * Story 334, ruling R-N2: a search keeps the plugin the user chose while
     * the filtered tree still lists it, so Add stays enabled; a search that
     * hides it drops it, and clearing the search does not bring it back.
     */
    @Test
    void aSearchKeepsAChoiceItStillListsAndDropsOneItHides() {
        PluginBrowser browser = runOnFxThread(() -> new PluginBrowser(null, true, "Test / Insert 1"));
        BrowserRow chosen = runOnFxThread(() -> {
            TreeView<BrowserRow> tree = browser.treeForTest();
            TreeItem<BrowserRow> first = rowsUnder(tree.getRoot())
                    .filter(item -> item.getValue() instanceof BuiltInRow)
                    .findFirst()
                    .orElseThrow();
            tree.getSelectionModel().select(first);
            return first.getValue();
        });

        search(browser, chosen.displayText().toLowerCase(Locale.ROOT));
        assertThat(selectedRow(browser)).as("the search still lists the choice: it stays chosen").contains(chosen);
        assertThat(addDisabled(browser)).as("Add stays enabled").isFalse();

        search(browser, "no-such-plugin");
        assertThat(selectedRow(browser)).as("the search hides the choice: nothing is selected").isEmpty();
        assertThat(addDisabled(browser)).as("Add is disabled").isTrue();

        search(browser, "");
        assertThat(selectedRow(browser)).as("clearing the search does not bring the choice back").isEmpty();
    }

    // ── Browser helpers ───────────────────────────────────────────────────────

    /** Types {@code query} into the browser's search box, on the FX thread. */
    private static void search(PluginBrowser browser, String query) {
        runOnFxThread(() -> {
            browser.applyFilterForTest(query);
            return Boolean.TRUE;
        });
    }

    /** Every row under {@code item}, depth first in tree order; {@code item} itself is not one of them. */
    private static Stream<TreeItem<BrowserRow>> rowsUnder(TreeItem<BrowserRow> item) {
        return item.getChildren().stream().flatMap(child -> Stream.concat(Stream.of(child), rowsUnder(child)));
    }

    /** The browser's selected row; empty when nothing is selected. */
    private static Optional<BrowserRow> selectedRow(PluginBrowser browser) {
        return runOnFxThread(() -> Optional.ofNullable(browser.treeForTest().getSelectionModel().getSelectedItem())
                .map(TreeItem::getValue));
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

    // ── Model navigation helpers ──────────────────────────────────────────────

    private static GroupRow group(List<BrowserRow> rows, String name) {
        GroupRow found = groupOrNull(rows, name);
        assertThat(found).as("group '%s' present", name).isNotNull();
        return found;
    }

    private static GroupRow groupOrNull(List<BrowserRow> rows, String name) {
        for (BrowserRow row : rows) {
            if (row instanceof GroupRow group && group.name().equals(name)) {
                return group;
            }
        }
        return null;
    }

    private static List<String> subgroupNames(GroupRow group) {
        return group.children().stream()
                .filter(GroupRow.class::isInstance)
                .map(child -> ((GroupRow) child).name())
                .toList();
    }

    private static List<String> leafLabels(GroupRow group) {
        return group.children().stream()
                .filter(child -> !(child instanceof GroupRow))
                .map(BrowserRow::displayText)
                .toList();
    }
}
