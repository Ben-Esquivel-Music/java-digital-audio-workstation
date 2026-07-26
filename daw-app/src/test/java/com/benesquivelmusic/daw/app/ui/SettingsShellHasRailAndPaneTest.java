package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.app.ui.settings.SettingsNavRail;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 306 — the §1.3 replacement proof: the settings surface exposes a
 * navigation rail, a scrolling settings pane, an always-on search field
 * and a footer action bar, and it does <em>not</em> expose a
 * {@code TabPane} (Settings View Design Book §4.A, §7 Stage 2).
 *
 * <p>The shell is built OFF the dialog — {@link SettingsCatalogue#create()}
 * plus a {@link SettingsShell.ValueAccess} over a scratch
 * {@link SettingsModel} on an isolated {@link Preferences} node — inside a
 * {@code Scene} with {@code applyCss() + layout()} so skins attach before
 * lookups (per {@code feedback_javafx_headless_test_pitfalls}). FX-thread
 * assertions are captured and rethrown by {@link #runOnFxThread}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SettingsShellHasRailAndPaneTest {

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

    /**
     * A shell over a scratch model on an isolated preferences node — every
     * model getter returns the canonical default; manager/key-binding ids
     * fall back to their descriptor defaults (no live singleton touched).
     */
    private static SettingsShell newShell(SettingsCatalogue catalogue) {
        Preferences prefs = Preferences.userRoot()
                .node("settingsShellShapeTest_" + System.nanoTime());
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
            // Manager-backed and key-binding ids: the descriptor default IS
            // the persisted value over an empty node — equivalent, and no
            // live manager singleton is touched from the test.
            default -> catalogue.byId(id)
                    .map(descriptor -> (Object) descriptor.defaultValue())
                    .orElse(null);
        };
    }

    /** Scene + applyCss + layout so skins attach before any lookup. */
    private static void showInScene(SettingsShell shell) {
        StackPane root = new StackPane(shell);
        new Scene(root, 1024, 720);
        root.applyCss();
        root.layout();
    }

    /**
     * Deep scene-graph walk. {@code ScrollPane} content is not a child
     * until a skin attaches, so it is followed explicitly; the set
     * deduplicates nodes reachable both ways once skins have attached.
     */
    private static void collectDeep(Node node, Set<Node> into) {
        if (node == null || !into.add(node)) {
            return;
        }
        if (node instanceof ScrollPane scrollPane) {
            collectDeep(scrollPane.getContent(), into);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectDeep(child, into);
            }
        }
    }

    @Test
    void shellShouldExposeRailScrollingPaneSearchFieldAndFooter() throws Exception {
        runOnFxThread(() -> {
            SettingsCatalogue catalogue = SettingsCatalogue.create();
            SettingsShell shell = newShell(catalogue);
            showInScene(shell);

            assertThat(shell.lookup(".settings-nav-rail"))
                    .as("the §5.1 navigation rail")
                    .isInstanceOf(SettingsNavRail.class);
            assertThat(shell.lookup(".settings-pane"))
                    .as("the §4.A settings pane is a vertically scrolling ScrollPane")
                    .isInstanceOf(ScrollPane.class);
            assertThat(shell.lookup(".settings-search-field"))
                    .as("the §5.2 always-on search field")
                    .isInstanceOf(TextField.class);
            assertThat(shell.lookup(".settings-footer"))
                    .as("the §5.5 footer action bar")
                    .isNotNull();
            return null;
        });
    }

    @Test
    void shellShouldNotContainATabPane() throws Exception {
        runOnFxThread(() -> {
            SettingsShell shell = newShell(SettingsCatalogue.create());
            showInScene(shell);

            Set<Node> lookedUp = shell.lookupAll("*");
            assertThat(lookedUp)
                    .as("lookupAll scanned a realized graph")
                    .isNotEmpty()
                    .noneMatch(TabPane.class::isInstance);

            // Belt and braces: lookupAll cannot see un-attached ScrollPane
            // content — walk the graph following getContent() explicitly.
            Set<Node> walked = new LinkedHashSet<>();
            collectDeep(shell, walked);
            assertThat(walked)
                    .as("the explicit deep walk scanned a non-empty graph")
                    .isNotEmpty()
                    .noneMatch(TabPane.class::isInstance);
            return null;
        });
    }

    @Test
    void railShouldCarryOneItemPerCatalogueCategoryInOrder() throws Exception {
        runOnFxThread(() -> {
            SettingsCatalogue catalogue = SettingsCatalogue.create();
            SettingsShell shell = newShell(catalogue);

            List<String> expectedIds = catalogue.categories().stream()
                    .map(SettingsCatalogue.Category::id)
                    .toList();
            List<String> expectedTitles = catalogue.categories().stream()
                    .map(SettingsCatalogue.Category::title)
                    .toList();
            assertThat(expectedIds)
                    .as("the catalogue supplies a non-empty taxonomy")
                    .isNotEmpty();

            assertThat(shell.navRail().getItems().stream()
                    .map(SettingsNavRail.NavItem::categoryId)
                    .toList())
                    .as("one rail item per catalogue category, in catalogue order")
                    .containsExactlyElementsOf(expectedIds);
            assertThat(shell.navRail().getItems().stream()
                    .map(SettingsNavRail.NavItem::title)
                    .toList())
                    .as("rail item titles are the resolved category titles")
                    .containsExactlyElementsOf(expectedTitles);
            return null;
        });
    }
}
