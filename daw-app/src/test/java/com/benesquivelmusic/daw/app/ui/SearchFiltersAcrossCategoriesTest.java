package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingDescriptor;
import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.app.ui.settings.SettingsSearchIndex;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
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
 * Story 306 — the §6.1 always-on search over the story-305 catalogue
 * (Settings View Design Book §2.2, §5.2, §6.1). Typing "latency" surfaces
 * buffer size, latency compensation and the device rows regardless of the
 * selected category (synonym hits); matching is case- and
 * diacritic-insensitive on both sides; a no-match query dims every rail
 * category; exact-label hits rank above weaker tiers.
 *
 * <p>Shell-level tests build the shell OFF the dialog
 * ({@link SettingsCatalogue#create()} + a {@link SettingsShell.ValueAccess}
 * over a scratch {@link SettingsModel} on an isolated {@link Preferences}
 * node) inside a {@code Scene} with {@code applyCss() + layout()}; ranking
 * assertions drive {@link SettingsSearchIndex} directly (pure in-memory
 * string work — no toolkit involvement).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SearchFiltersAcrossCategoriesTest {

    /** The four "latency" hits named by the story AC (book §2.2 example). */
    private static final List<String> LATENCY_IDS = List.of(
            "audio.bufferSize",
            "audio.applyLatencyCompensation",
            "audio.inputDevice",
            "audio.outputDevice");

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
                .node("searchFiltersAcrossCategoriesTest_" + System.nanoTime());
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

    private static TextField searchField(SettingsShell shell) {
        Node field = shell.lookup(".settings-search-field");
        assertThat(field).as("the always-on search field").isInstanceOf(TextField.class);
        return (TextField) field;
    }

    /**
     * The descriptor ids of every {@link SettingRow} currently shown in the
     * settings pane, in graph order (search mode: the results pane).
     */
    private static Set<String> visibleRowIds(SettingsShell shell) {
        ScrollPane pane = (ScrollPane) shell.lookup(".settings-pane");
        Set<Node> nodes = new LinkedHashSet<>();
        collectDeep(pane.getContent(), nodes);
        Set<String> ids = new LinkedHashSet<>();
        for (Node node : nodes) {
            if (node instanceof SettingRow row) {
                ids.add(row.descriptor().id());
            }
        }
        return ids;
    }

    @Test
    void typingLatencyShouldSurfaceMatchesFromNonSelectedCategories() throws Exception {
        runOnFxThread(() -> {
            SettingsCatalogue catalogue = SettingsCatalogue.create();
            SettingsShell shell = newShell(catalogue);
            showInScene(shell);

            // Browse a NON-audio category first — search results must not
            // depend on the selected category (book §2.2).
            shell.navRail().setSelectedCategoryId("plugins");
            searchField(shell).setText("latency");

            assertThat(visibleRowIds(shell))
                    .as("\"latency\" surfaces the buffer-size, compensation and device rows")
                    .contains(LATENCY_IDS.toArray(String[]::new));

            // §5.2: the matching category shows a count and is not dimmed.
            var audioItem = shell.navRail().getItems().stream()
                    .filter(item -> "audio".equals(item.categoryId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no audio rail item"));
            assertThat(audioItem.dimmedProperty().get())
                    .as("the audio category has matches and is not dimmed")
                    .isFalse();
            assertThat(audioItem.matchCountProperty().get())
                    .as("the audio category shows a match count while searching")
                    .isGreaterThanOrEqualTo(1);
            return null;
        });
    }

    @Test
    void searchShouldBeCaseAndDiacriticInsensitive() throws Exception {
        runOnFxThread(() -> {
            SettingsShell shell = newShell(SettingsCatalogue.create());
            showInScene(shell);
            TextField field = searchField(shell);

            field.setText("latency");
            Set<String> plainIds = visibleRowIds(shell);
            assertThat(plainIds).contains(LATENCY_IDS.toArray(String[]::new));

            field.setText("LÁTENCY"); // "LÁTENCY" — case + diacritic
            assertThat(visibleRowIds(shell))
                    .as("\"LÁTENCY\" yields exactly the \"latency\" result set")
                    .containsExactlyElementsOf(plainIds);
            return null;
        });
    }

    @Test
    void noMatchQueryShouldDimEveryRailCategory() throws Exception {
        runOnFxThread(() -> {
            SettingsCatalogue catalogue = SettingsCatalogue.create();
            SettingsShell shell = newShell(catalogue);
            showInScene(shell);

            searchField(shell).setText("zzzqqq");

            assertThat(visibleRowIds(shell))
                    .as("a no-match query shows no rows")
                    .isEmpty();
            assertThat(shell.navRail().getItems())
                    .as("the rail still has every category item")
                    .isNotEmpty()
                    .allSatisfy(item -> {
                        assertThat(item.dimmedProperty().get())
                                .as("category %s is dimmed", item.categoryId())
                                .isTrue();
                        assertThat(item.matchCountProperty().get())
                                .as("category %s shows a zero count", item.categoryId())
                                .isZero();
                    });

            // Skin level: every cell carries the dimmed pseudo-class.
            Set<Node> nodes = new LinkedHashSet<>();
            collectDeep(shell, nodes);
            List<Node> cells = nodes.stream()
                    .filter(node -> node.getStyleClass().contains("settings-nav-cell"))
                    .toList();
            assertThat(cells)
                    .as("one realized rail cell per category")
                    .hasSize(catalogue.categories().size());
            PseudoClass dimmed = PseudoClass.getPseudoClass("dimmed");
            assertThat(cells).allSatisfy(cell ->
                    assertThat(cell.getPseudoClassStates())
                            .as("cell %s carries :dimmed", cell)
                            .contains(dimmed));
            return null;
        });
    }

    @Test
    void exactLabelQueryShouldRankThatDescriptorFirst() {
        SettingsCatalogue catalogue = SettingsCatalogue.create();
        SettingsSearchIndex index = SettingsSearchIndex.of(catalogue);
        SettingDescriptor<?> bufferSize = catalogue.byId("audio.bufferSize")
                .orElseThrow(() -> new AssertionError("audio.bufferSize not catalogued"));

        List<SettingsSearchIndex.Match> matches = index.search(bufferSize.label());

        assertThat(matches).as("the exact label matches at least itself").isNotEmpty();
        assertThat(matches.get(0).descriptor().id())
                .as("the exact-label hit ranks first (§6.1)")
                .isEqualTo("audio.bufferSize");
        assertThat(matches.get(0).field())
                .isEqualTo(SettingsSearchIndex.MatchField.LABEL_EXACT);
    }

    @Test
    void latencyRankingShouldPlaceLabelHitsAboveSynonymHits() {
        SettingsSearchIndex index = SettingsSearchIndex.of(SettingsCatalogue.create());

        List<SettingsSearchIndex.Match> matches = index.search("latency");
        List<String> ids = matches.stream()
                .map(match -> match.descriptor().id())
                .toList();

        assertThat(ids).contains(LATENCY_IDS.toArray(String[]::new));
        // "Apply latency compensation…" is the only label containing the
        // query — a LABEL-tier hit that must precede every synonym hit.
        assertThat(ids.get(0)).isEqualTo("audio.applyLatencyCompensation");
        assertThat(matches.get(0).field()).isEqualTo(SettingsSearchIndex.MatchField.LABEL);
        for (String synonymId : List.of(
                "audio.inputDevice", "audio.outputDevice", "audio.bufferSize")) {
            SettingsSearchIndex.Match match = matches.stream()
                    .filter(m -> synonymId.equals(m.descriptor().id()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no match for " + synonymId));
            assertThat(match.field())
                    .as("%s matches \"latency\" through its synonyms", synonymId)
                    .isEqualTo(SettingsSearchIndex.MatchField.SYNONYM);
        }
        // Rank tiers never weaken back down the list.
        for (int i = 1; i < matches.size(); i++) {
            assertThat(matches.get(i).field().ordinal())
                    .as("result %d keeps tier order", i)
                    .isGreaterThanOrEqualTo(matches.get(i - 1).field().ordinal());
        }
    }

    @Test
    void indexShouldTreatDiacriticQueryIdenticallyToAsciiQuery() {
        SettingsSearchIndex index = SettingsSearchIndex.of(SettingsCatalogue.create());

        assertThat(index.search("LÁTENCY"))
                .as("\"LÁTENCY\" normalizes to \"latency\" (NFD + strip marks + lower-case)")
                .isNotEmpty()
                .containsExactlyElementsOf(index.search("latency"));
    }

    @Test
    void indexShouldStripSurroundingWhitespaceFromQuery() {
        SettingsCatalogue catalogue = SettingsCatalogue.create();
        SettingsSearchIndex index = SettingsSearchIndex.of(catalogue);

        // Pasted-with-spaces query filters exactly like the bare query —
        // and therefore agrees with SettingRowSkin's stripped highlight.
        assertThat(index.search(" latency "))
                .as("\" latency \" (pasted with spaces) matches what \"latency\" matches")
                .isNotEmpty()
                .containsExactlyElementsOf(index.search("latency"));
        assertThat(index.matchCountsByCategory("\tlatency \n"))
                .as("rail counts agree for the padded query")
                .isEqualTo(index.matchCountsByCategory("latency"));

        // Padding must not demote an exact-label hit out of LABEL_EXACT.
        SettingDescriptor<?> bufferSize = catalogue.byId("audio.bufferSize")
                .orElseThrow(() -> new AssertionError("audio.bufferSize not catalogued"));
        List<SettingsSearchIndex.Match> matches =
                index.search(" " + bufferSize.label() + " ");
        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).descriptor().id()).isEqualTo("audio.bufferSize");
        assertThat(matches.get(0).field())
                .isEqualTo(SettingsSearchIndex.MatchField.LABEL_EXACT);
    }
}
