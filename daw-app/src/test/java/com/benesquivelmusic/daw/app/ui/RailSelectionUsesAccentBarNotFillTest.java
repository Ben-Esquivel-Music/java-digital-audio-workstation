package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 306 — rail selection is a drawn accent bar, never an accent fill
 * (UI Design Book rejection §6, Settings View Design Book §5.3): the
 * selected {@code .settings-nav-cell} carries the {@code selected}
 * pseudo-class and contains the always-present
 * {@code .settings-nav-accent-bar} {@code Region}, and the pane's
 * {@code .settings-category-title} label is a plain label — no inline
 * style, no accent-ish style class.
 *
 * <p>The shell is built OFF the dialog ({@link SettingsCatalogue#create()}
 * + a {@link SettingsShell.ValueAccess} over a scratch
 * {@link SettingsModel} on an isolated {@link Preferences} node) inside a
 * {@code Scene} with {@code applyCss() + layout()} so the rail skin's
 * cells are realized before the pseudo-class assertions.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RailSelectionUsesAccentBarNotFillTest {

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
                .node("railSelectionAccentBarTest_" + System.nanoTime());
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

    private static List<Node> collectByStyleClass(Node from, String styleClass) {
        Set<Node> nodes = new LinkedHashSet<>();
        collectDeep(from, nodes);
        return nodes.stream()
                .filter(node -> node.getStyleClass().contains(styleClass))
                .toList();
    }

    private static String categoryTitle(SettingsCatalogue catalogue, String categoryId) {
        return catalogue.categories().stream()
                .filter(category -> category.id().equals(categoryId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no category " + categoryId))
                .title();
    }

    @Test
    void selectedCellShouldCarryAccentBarNodeAndSelectedPseudoClass() throws Exception {
        runOnFxThread(() -> {
            SettingsCatalogue catalogue = SettingsCatalogue.create();
            SettingsShell shell = newShell(catalogue);
            showInScene(shell);

            // Move selection off the default first category to prove the
            // pseudo-class follows the selection.
            shell.navRail().setSelectedCategoryId("appearance");

            List<Node> cells = collectByStyleClass(shell, "settings-nav-cell");
            assertThat(cells)
                    .as("one realized rail cell per category")
                    .hasSize(catalogue.categories().size());

            // The accent bar Region is ALWAYS in the graph, in every cell —
            // selection only changes its paint (rejection §6).
            for (Node cell : cells) {
                List<Node> bars = collectByStyleClass(cell, "settings-nav-accent-bar");
                assertThat(bars)
                        .as("cell %s carries exactly one accent-bar node", cell)
                        .hasSize(1);
                assertThat(bars.get(0))
                        .as("the accent bar is a drawn Region")
                        .isInstanceOf(Region.class);
            }

            PseudoClass selected = PseudoClass.getPseudoClass("selected");
            List<Node> selectedCells = cells.stream()
                    .filter(cell -> cell.getPseudoClassStates().contains(selected))
                    .toList();
            assertThat(selectedCells)
                    .as("exactly one cell carries :selected")
                    .hasSize(1);

            // The selected cell is the appearance cell — identified by its
            // title label carrying the resolved category title.
            List<Node> titles =
                    collectByStyleClass(selectedCells.get(0), "settings-nav-title");
            assertThat(titles).as("the selected cell has one title label").hasSize(1);
            assertThat(((Label) titles.get(0)).getText())
                    .isEqualTo(categoryTitle(catalogue, "appearance"));
            return null;
        });
    }

    @Test
    void paneCategoryTitleShouldBeAPlainLabelWithoutAccentStyling() throws Exception {
        runOnFxThread(() -> {
            SettingsCatalogue catalogue = SettingsCatalogue.create();
            SettingsShell shell = newShell(catalogue);
            showInScene(shell);
            shell.navRail().setSelectedCategoryId("appearance");

            ScrollPane pane = (ScrollPane) shell.lookup(".settings-pane");
            List<Node> titles =
                    collectByStyleClass(pane.getContent(), "settings-category-title");
            assertThat(titles)
                    .as("the shown pane has exactly one category title")
                    .hasSize(1);

            Label title = (Label) titles.get(0);
            assertThat(title.getText())
                    .isEqualTo(categoryTitle(catalogue, "appearance"));
            assertThat(title.getStyle())
                    .as("the category title carries no inline style (rejection §6/§2.6)")
                    .isEmpty();
            assertThat(title.getStyleClass())
                    .as("style classes limited to the contract — a plain label")
                    .containsExactlyInAnyOrder("label", "settings-category-title");
            assertThat(title.getStyleClass())
                    .as("no accent-ish style class on the title (rejection §6)")
                    .noneMatch(styleClass ->
                            styleClass.toLowerCase(Locale.ROOT).contains("accent"));
            return null;
        });
    }
}
