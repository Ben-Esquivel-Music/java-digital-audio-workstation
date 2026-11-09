package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.plugin.PluginText;
import com.benesquivelmusic.daw.core.persistence.InstalledPluginStore;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.InstalledPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.plugin.RestoreReport;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 334 (studio-engineer rulings E9, G4, G5 and G19) — the words of the
 * installed-plugin registry's one startup notification
 * ({@code MainController.missingPluginsMessage}): nothing when every entry
 * loaded; otherwise the applicable sentences in one message, in this order,
 * joined by one space — the list could not be read (always alone), entries
 * could not be read and were removed, entries stay listed as Missing — one
 * and many forms right; its action is {@code Manage Plugins…}. When the
 * read could not keep the copy of the list it owed, the unreadable and removed
 * sentences say so instead, and never that a copy was kept (ruling-3 Q2,
 * finding A2). A clean startup whose sweep a kept copy of the list holds
 * tells nothing (ruling-4 Q7). And the words of the one notification a failed save of the
 * list shows. Which notification is shown once, and where, is pinned by
 * {@code SourcePinnedWiringTest} ({@code MainController} is never FXML-loaded
 * in tests).
 */
class MainControllerMissingPluginsNoticeTest {

    private static final String ONE_MISSING = "1 installed plugin did not load: its file is missing or no longer"
            + " valid. It stays listed as Missing in Manage Plugins.";
    private static final String UNREADABLE = "The list of installed plugins could not be read, so no installed"
            + " plugin was loaded. A copy of the list was kept. Reinstall your plugins in Manage Plugins.";
    private static final String COPY_KEPT = "A copy of the list was kept";

    @Test
    void nothingToTellShowsNothing() {
        assertThat(MainController.missingPluginsMessage(new RestoreReport(List.of(), false, 0, false))).isEmpty();
    }

    @Test
    void oneMissingEntryIsTheOneForm() {
        assertThat(MainController.missingPluginsMessage(
                new RestoreReport(List.of(installed("comp")), false, 0, false)))
                .contains(ONE_MISSING);
    }

    @Test
    void twoMissingEntriesAreOneMessageInTheManyForm() {
        assertThat(MainController.missingPluginsMessage(
                new RestoreReport(List.of(installed("comp"), installed("gate")), false, 0, false)))
                .contains("2 installed plugins did not load: their files are missing or no longer valid. They stay"
                        + " listed as Missing in Manage Plugins.");
    }

    @Test
    void anUnreadableListIsExactlyItsOwnSentence() {
        assertThat(MainController.missingPluginsMessage(new RestoreReport(List.of(), true, 0, false)))
                .contains(UNREADABLE);
    }

    @Test
    void aRemovedEntryAndAMissingOneAreOneMessageRemovedFirst() {
        assertThat(MainController.missingPluginsMessage(
                new RestoreReport(List.of(installed("comp")), false, 1, false)))
                .contains("1 entry in the list of installed plugins could not be read and was removed. A copy of"
                        + " the list was kept. " + ONE_MISSING);
        assertThat(MainController.missingPluginsMessage(new RestoreReport(List.of(), false, 3, false)))
                .contains("3 entries in the list of installed plugins could not be read and were removed. A copy"
                        + " of the list was kept.");
    }

    @Test
    void anUnreadableListWhoseCopyFailedSaysTheListIsLeftAsItIs() {
        assertThat(MainController.missingPluginsMessage(new RestoreReport(List.of(), true, 0, true)))
                .contains("The list of installed plugins could not be read, so no installed plugin was loaded. A copy"
                        + " of the list could not be made, so the list is left as it is. Reinstall your plugins in"
                        + " Manage Plugins.")
                .hasValueSatisfying(message -> assertThat(message).doesNotContain(COPY_KEPT));
    }

    @Test
    void removedEntriesWhoseCopyFailedWereNotLoadedAndTheListIsLeftAsItIs() {
        assertThat(MainController.missingPluginsMessage(
                new RestoreReport(List.of(installed("comp")), false, 1, true)))
                .contains("1 entry in the list of installed plugins could not be read and was not loaded. A copy of"
                        + " the list could not be made, so the list is left as it is. " + ONE_MISSING);
        assertThat(MainController.missingPluginsMessage(new RestoreReport(List.of(), false, 3, true)))
                .contains("3 entries in the list of installed plugins could not be read and were not loaded. A copy"
                        + " of the list could not be made, so the list is left as it is.")
                .hasValueSatisfying(message -> assertThat(message).doesNotContain(COPY_KEPT));
    }

    @Test
    void aCopyOwedForARepeatedIdAloneHasNothingToTell() {
        assertThat(MainController.missingPluginsMessage(new RestoreReport(List.of(), false, 0, true))).isEmpty();
    }

    /**
     * Ruling-4 Q7 (6): a startup that read the list with no problem, whose
     * sweep a kept copy of the list holds, has nothing to tell the engineer:
     * the hold is logged only.
     */
    @Test
    void aCleanStartupWhoseSweepAKeptCopyHoldsShowsNothing(@TempDir Path folder) throws Exception {
        Path list = folder.resolve("config").resolve(InstalledPluginStore.FILE_NAME);
        Files.createDirectories(list.getParent());
        Files.writeString(list, "{\n  \"schema\": \"1\",\n  \"plugins\": []\n}\n");
        Files.writeString(list.resolveSibling(InstalledPluginStore.FILE_NAME + ".20261108-101500.bak"),
                "{\"schema\": \"2\"}");
        Path stale = list.resolveSibling(InstalledPluginStore.PLUGINS_FOLDER_NAME).resolve("com.example.old")
                .resolve("0.9").resolve("old.jar");
        Files.createDirectories(stale.getParent());
        Files.write(stale, new byte[] {1});
        PluginRegistry registry = new PluginRegistry(new InstalledPluginStore(list));
        try {
            RestoreReport report = registry.restore().get(10, TimeUnit.SECONDS);

            assertThat(stale).as("the kept copy of the list holds the sweep").exists();
            assertThat(report).isEqualTo(new RestoreReport(List.of(), false, 0, false));
            assertThat(MainController.missingPluginsMessage(report)).isEmpty();
        } finally {
            registry.disposeAll();
        }
    }

    @Test
    void theActionAndTheSaveFailureUseTheRuledWords() {
        assertThat(PluginText.text("pluginRegistry.startupMissing.action")).isEqualTo("Manage Plugins…");
        assertThat(PluginText.text("pluginRegistry.saveFailed")).isEqualTo("The list of installed plugins could"
                + " not be saved. Plugins installed or removed in this session will not be remembered after a"
                + " restart unless a later install or removal saves the list.");
    }

    private static InstalledPlugin installed(String name) {
        String className = "com.example." + name;
        return new InstalledPlugin(new ExternalPluginEntry(Path.of(name + ".jar"), className),
                new PluginManifest(className, new PluginDescriptor("com.example." + name, name, "1.0",
                        "Example Audio", PluginType.EFFECT)), name + ".jar");
    }
}
