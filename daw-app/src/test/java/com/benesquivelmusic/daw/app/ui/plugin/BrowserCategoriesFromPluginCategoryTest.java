package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.PluginBrowser.BrowserRow;
import com.benesquivelmusic.daw.app.ui.plugin.PluginBrowser.GroupRow;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginA;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginC;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
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
            registry.register(new ExternalPluginEntry(jar, InstallFixturePluginA.class.getName()));
            registry.register(new ExternalPluginEntry(jar, InstallFixturePluginC.class.getName()));

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
