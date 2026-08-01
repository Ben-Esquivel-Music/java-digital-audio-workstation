package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.Scope;
import com.benesquivelmusic.daw.app.ui.settings.SettingDescriptor;
import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;
import com.benesquivelmusic.daw.app.ui.settings.SyntheticCatalogues;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 309 — the §1.8 / rejection-#8 fix: scope is finally visible
 * (Settings View Design Book §2.3, §3.2, §5.3). The pane header carries a
 * "Scope: …" chip naming the category's MODAL descriptor scope, and a row
 * whose scope differs from that modal scope renders its own override chip.
 *
 * <p>The chip must derive from the descriptors, never a hard-coded
 * category map — proven by construction: the expected modal scope is
 * recomputed here from the catalogue's descriptors for EVERY category
 * (most-frequent scope, first-encountered tie-break), and every header
 * chip must agree with it. The named categories (audio → "Audio device",
 * appearance/plugins → "Application", project → "Project defaults") pin
 * the §3.2 display vocabulary on top.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ScopeChipReflectsDescriptorTest {

    private <T> T runOnFxThread(Callable<T> callable) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(callable.call());
            } catch (Throwable t) {
                // Capture AssertionError too — an FX-thread assertion must
                // fail the test, not vanish into the FX exception handler.
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("FX task completed within the timeout")
                .isTrue();
        Throwable thrown = error.get();
        if (thrown instanceof Error e) {
            throw e;
        }
        if (thrown instanceof Exception e) {
            throw e;
        }
        return ref.get();
    }

    /** A shell over a scratch model on an isolated preferences node. */
    private static SettingsShell newShell(SettingsCatalogue catalogue) {
        Preferences prefs = Preferences.userRoot()
                .node("scopeChipReflectsDescriptorTest_" + System.nanoTime());
        SettingsModel model = new SettingsModel(prefs);
        return new SettingsShell(catalogue, valueAccess(catalogue, model), null, null);
    }

    private static SettingsShell.ValueAccess valueAccess(
            SettingsCatalogue catalogue, SettingsModel model) {
        return id -> switch (id) {
            case "audio.sampleRate" -> model.getSampleRate();
            case "audio.bitDepth" -> model.getBitDepth();
            case "audio.mixPrecision" -> model.getMixPrecision();
            case "audio.srcQuality" -> model.getSrcQuality();
            case "audio.backend" -> model.getAudioBackend();
            case "audio.inputDevice" -> model.getAudioInputDevice();
            case "audio.outputDevice" -> model.getAudioOutputDevice();
            case "audio.bufferSize" -> model.getBufferSize();
            case "audio.applyLatencyCompensation" -> model.isApplyLatencyCompensation();
            case "audio.workerPoolSize" -> model.getWorkerPoolSize();
            case "audio.masterCpuBudgetFraction" -> model.getMasterCpuBudgetFraction();
            case "project.autoSaveIntervalSeconds" -> model.getAutoSaveIntervalSeconds();
            case "project.defaultTempo" -> model.getDefaultTempo();
            case "project.useJournaledPersistence" -> model.isUseJournaledPersistence();
            case "appearance.uiScale" -> model.getUiScale();
            case "plugins.scanPaths" -> model.getPluginScanPaths();
            default -> catalogue.byId(id)
                    .map(descriptor -> (Object) descriptor.defaultValue())
                    .orElse(null);
        };
    }

    /** Scene + applyCss + layout so skins attach before any lookup. */
    private static StackPane showInScene(SettingsShell shell) {
        StackPane root = new StackPane(shell);
        new Scene(root, 1024, 720);
        root.applyCss();
        root.layout();
        return root;
    }

    private static void selectAndRealize(SettingsShell shell, StackPane root,
                                         String categoryId) {
        shell.selectCategory(categoryId);
        root.applyCss();
        root.layout();
    }

    /**
     * The test's OWN modal-scope computation over the catalogue — the
     * by-construction proof that the chip is descriptor-derived: most
     * frequent scope, ties broken toward the first-encountered scope.
     */
    private static Scope expectedModalScope(SettingsCatalogue.Category category) {
        Map<Scope, Integer> counts = new LinkedHashMap<>();
        for (SettingsCatalogue.Group group : category.groups()) {
            for (SettingDescriptor<?> descriptor : group.settings()) {
                counts.merge(descriptor.scope(), 1, Integer::sum);
            }
        }
        Scope modal = null;
        int best = 0;
        for (Map.Entry<Scope, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > best) {
                modal = entry.getKey();
                best = entry.getValue();
            }
        }
        return modal;
    }

    /** The canonical §3.2 display names (research-daw §4). */
    private static String displayName(Scope scope) {
        return switch (scope) {
            case APPLICATION -> "Application";
            case PROJECT_DEFAULTS -> "Project defaults";
            case THIS_PROJECT -> "This project";
            case AUDIO_DEVICE -> "Audio device";
        };
    }

    @Test
    void headerChipShouldNameTheCanonicalScopePerCategory() throws Exception {
        runOnFxThread(() -> {
            SettingsCatalogue catalogue = SettingsCatalogue.create();
            SettingsShell shell = newShell(catalogue);
            StackPane root = showInScene(shell);

            Map<String, String> expected = Map.of(
                    "audio", "Audio device",
                    "appearance", "Application",
                    "plugins", "Application",
                    "project", "Project defaults");
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                selectAndRealize(shell, root, entry.getKey());
                Node chip = shell.lookup("#settings-scope-chip-" + entry.getKey());
                assertThat(chip)
                        .as("the %s pane header carries a scope chip", entry.getKey())
                        .isInstanceOf(Label.class);
                assertThat(((Label) chip).getText())
                        .as("the %s chip names its §3.2 scope", entry.getKey())
                        .isEqualTo("Scope: " + entry.getValue());
            }
            return null;
        });
    }

    @Test
    void everyHeaderChipShouldAgreeWithTheDescriptorDerivedModalScope() throws Exception {
        runOnFxThread(() -> {
            SettingsCatalogue catalogue = SettingsCatalogue.create();
            SettingsShell shell = newShell(catalogue);
            StackPane root = showInScene(shell);

            for (SettingsCatalogue.Category category : catalogue.categories()) {
                selectAndRealize(shell, root, category.id());
                Scope modal = expectedModalScope(category);
                Node chip = shell.lookup("#settings-scope-chip-" + category.id());
                if (modal == null) {
                    // A descriptor-less category (General) has no scope to
                    // derive — the chip is omitted (the story-309 choice).
                    assertThat(chip)
                            .as("descriptor-less category %s renders no chip",
                                    category.id())
                            .isNull();
                } else {
                    assertThat(chip)
                            .as("category %s renders a chip", category.id())
                            .isInstanceOf(Label.class);
                    assertThat(((Label) chip).getText())
                            .as("category %s chip derives from its descriptors' "
                                    + "modal scope, never a category-name map",
                                    category.id())
                            .isEqualTo("Scope: " + displayName(modal));
                }
            }
            return null;
        });
    }

    /**
     * The discriminating proof the two production-catalogue tests above
     * cannot give (they recompute the expectation from the SAME
     * catalogue): a SYNTHETIC catalogue whose category id is a real
     * category name ({@code "audio"}) but whose descriptors' modal scope
     * is deliberately different (the real APPLICATION-scoped appearance
     * descriptors). Any {@code switch (category.id())} map matching
     * today's catalogue would render "Audio device" here and fail.
     */
    @Test
    void syntheticCatalogueShouldProveDerivationFromDescriptorsNotACategoryNameMap()
            throws Exception {
        runOnFxThread(() -> {
            SettingsCatalogue synthetic =
                    SyntheticCatalogues.audioNamedButApplicationScoped();
            SettingsCatalogue.Category category = synthetic.categories().get(0);
            assertThat(category.id()).isEqualTo("audio");
            assertThat(expectedModalScope(category))
                    .as("precondition: the synthetic modal scope deliberately "
                            + "differs from the real audio category's AUDIO_DEVICE")
                    .isEqualTo(Scope.APPLICATION);

            SettingsShell shell = newShell(synthetic);
            StackPane root = showInScene(shell);
            selectAndRealize(shell, root, "audio");
            Node chip = shell.lookup("#settings-scope-chip-audio");
            assertThat(chip)
                    .as("the synthetic audio pane header carries a scope chip")
                    .isInstanceOf(Label.class);
            assertThat(((Label) chip).getText())
                    .as("the chip derives APPLICATION from the descriptors — "
                            + "a name-keyed category map would say \"Audio device\"")
                    .isEqualTo("Scope: Application");
            return null;
        });
    }

    @Test
    void rowWhoseScopeDiffersFromItsCategoryShouldShowAPerRowOverride() throws Exception {
        runOnFxThread(() -> {
            SettingsCatalogue catalogue = SettingsCatalogue.create();
            SettingsShell shell = newShell(catalogue);
            StackPane root = showInScene(shell);

            // Project is PROJECT_DEFAULTS-modal; useJournaledPersistence is
            // an APPLICATION descriptor inside it → per-row override chip.
            selectAndRealize(shell, root, "project");
            SettingRow journaled = shell.settingRow("project.useJournaledPersistence")
                    .orElseThrow();
            Node overrideChip = journaled.lookup(".setting-row-scope-chip");
            assertThat(overrideChip)
                    .as("the APPLICATION row in a Project-defaults category "
                            + "carries a per-row scope chip")
                    .isInstanceOf(Label.class);
            assertThat(((Label) overrideChip).getText()).isEqualTo("Application");

            // A row matching its category's modal scope renders no chip.
            SettingRow tempo = shell.settingRow("project.defaultTempo").orElseThrow();
            assertThat(tempo.lookup(".setting-row-scope-chip"))
                    .as("a modal-scope row renders no override chip")
                    .isNull();

            // Recording is THIS_PROJECT-modal; metronome.enabled is
            // APPLICATION → override chip there too.
            selectAndRealize(shell, root, "recording");
            SettingRow metronomeEnabled = shell.settingRow("metronome.enabled")
                    .orElseThrow();
            Node recordingChip = metronomeEnabled.lookup(".setting-row-scope-chip");
            assertThat(recordingChip).isInstanceOf(Label.class);
            assertThat(((Label) recordingChip).getText()).isEqualTo("Application");

            SettingRow clickGain = shell.settingRow("metronome.clickGain").orElseThrow();
            assertThat(clickGain.lookup(".setting-row-scope-chip"))
                    .as("a THIS_PROJECT row in the THIS_PROJECT-modal category "
                            + "renders no override chip")
                    .isNull();
            return null;
        });
    }
}
