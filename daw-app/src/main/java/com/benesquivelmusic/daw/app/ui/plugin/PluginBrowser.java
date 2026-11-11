package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.PluginManagerDialog;
import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.editor.PluginCategory;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The §6.7 categorical plugin browser (Plugin View Design Book §6.7, §6.8,
 * §8.4). It replaces the flat {@code ChoiceDialog} the mixer's empty insert slot
 * used to show: one shared, searchable tree with two top groups — <em>Built-in</em>
 * (grouped by {@link PluginCategory}) and <em>Installed (third-party)</em>
 * (grouped by vendor) — plus install affordances ([+ Install from JAR…],
 * [+ Install from folder…]) and a [Manage…] shortcut. Selecting a row (double
 * click, {@code Enter}, or the Add button) yields a {@link Selection}; Cancel /
 * the header close glyph yields {@code null}.
 *
 * <p>The model / filter logic is pure package-private {@code static} so it is
 * unit-testable without a JavaFX scene; the tree, cell rendering and install flow
 * layer on top.</p>
 */
public final class PluginBrowser extends DawgDialog<PluginBrowser.Selection> {

    /** The user's choice: a built-in effect type or a registered external plugin. */
    public sealed interface Selection permits BuiltInSelection, ExternalSelection {}

    /** A built-in DSP effect chosen from the browser. */
    public record BuiltInSelection(InsertEffectType type) implements Selection {}

    /** A registered external plugin chosen from the browser. */
    public record ExternalSelection(ExternalPluginEntry entry) implements Selection {}

    /** A row's identity across rebuilds of the tree: its plugin, or its group's path (story 334, ruling R-N2). */
    private sealed interface RowKey permits PluginKey, GroupKey {}

    /** A plugin row by what Add would return for it. */
    private record PluginKey(Selection plugin) implements RowKey {
        PluginKey {
            Objects.requireNonNull(plugin, "plugin must not be null");
        }
    }

    /** A group row by the names of the groups from the top level down to it. */
    private record GroupKey(List<String> path) implements RowKey {
        GroupKey {
            path = List.copyOf(path);
        }
    }

    static final String BUILT_IN_GROUP = "Built-in";
    static final String INSTALLED_GROUP = "Installed (third-party)";

    private static final double ICON_SIZE = 14;
    private static final double BUSY_SPINNER_SIZE = 18;
    private static final ButtonType ADD_BUTTON = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);

    private final PluginRegistry registry;
    private final boolean stereo;
    private final List<InsertEffectType> builtInTypes;

    private final TreeView<BrowserRow> tree = new TreeView<>();
    private final TextField searchField = new TextField();
    private final Label busyLabel = new Label();
    private final HBox busyRow;

    private List<BrowserRow> model;
    private FxDispatcher fxDispatcher;

    /** The paths of the groups the user collapsed, as the tree last showed them with the search box empty. */
    private Set<List<String>> collapsedGroups = Set.of();
    /** Whether the tree shows a search's matches rather than every row. */
    private boolean showingSearch;

    /**
     * Creates a browser targeting an insert slot.
     *
     * @param registryOrNull the plugin registry (may be {@code null}: no
     *                       third-party group, install / Manage disabled)
     * @param stereo         whether the target has &ge; 2 channels (gates the
     *                       Stereo Imager built-in)
     * @param targetLabel    a human label for the target slot (e.g.
     *                       {@code "Drum Bus / Insert 2"}), shown in the header
     */
    public PluginBrowser(PluginRegistry registryOrNull, boolean stereo, String targetLabel) {
        this.registry = registryOrNull;
        this.stereo = stereo;
        this.builtInTypes = InsertEffectFactory.availableTypes();
        this.model = buildModel(builtInTypes, stereo, loadedPlugins());

        setTitle("Add plugin");
        setHeaderText(targetLabel == null || targetLabel.isBlank()
                ? "Add plugin" : "Add plugin to " + targetLabel);

        // ── Search bar + Manage… ─────────────────────────────────────────────
        searchField.setPromptText("Search plugins…");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((_, _, query) -> rebuildTree(query));

        Button manageButton = new Button("Manage…");
        manageButton.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        manageButton.setDisable(registry == null);
        manageButton.setOnAction(_ -> {
            if (registry != null) {
                PluginManagerDialog manager = new PluginManagerDialog(registry);
                manager.setFxDispatcher(fxDispatcher);
                manager.showAndWait();
                refreshThirdParty();
            }
        });

        HBox searchBar = new HBox(8, searchField, manageButton);
        searchBar.setAlignment(Pos.CENTER_LEFT);

        // ── Tree ─────────────────────────────────────────────────────────────
        tree.getStyleClass().add("plugin-browser-tree");
        tree.setShowRoot(false);
        tree.setCellFactory(_ -> new BrowserCell());
        VBox.setVgrow(tree, Priority.ALWAYS);
        rebuildTree("");

        tree.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                fireAddIfLeafSelected();
            }
        });
        tree.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                fireAddIfLeafSelected();
            }
        });

        // ── Busy row (scan in flight) ────────────────────────────────────────
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(BUSY_SPINNER_SIZE, BUSY_SPINNER_SIZE);
        busyRow = new HBox(8, spinner, busyLabel);
        busyRow.setAlignment(Pos.CENTER_LEFT);
        busyRow.getStyleClass().add("plugin-row-busy");
        busyRow.setVisible(false);
        busyRow.setManaged(false);

        // ── Install actions ──────────────────────────────────────────────────
        Button installJar = new Button("+ Install from JAR…");
        installJar.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        installJar.setDisable(registry == null);
        installJar.setOnAction(_ -> chooseAndInstallJar());

        Button installFolder = new Button("+ Install from folder…");
        installFolder.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        installFolder.setDisable(registry == null);
        installFolder.setOnAction(_ -> chooseAndInstallFolder());

        HBox installBar = new HBox(8, installJar, installFolder);
        installBar.setAlignment(Pos.CENTER_LEFT);
        installBar.getStyleClass().add("plugin-install-row");

        VBox content = new VBox(12, searchBar, tree, busyRow, installBar);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("plugin-browser");
        installDragDrop(content);

        getDialogPane().setContent(content);
        sized(DawgDialog.Size.LARGE);

        // ── Footer buttons (Cancel + Add) ────────────────────────────────────
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ADD_BUTTON);
        setResultConverter(buttonType -> buttonType == ADD_BUTTON ? selectionFromTree() : null);

        if (getDialogPane().lookupButton(ADD_BUTTON) instanceof Button add) {
            add.getStyleClass().addAll("dawg-button", "size-default", "primary");
            add.setDisable(true);
            tree.getSelectionModel().selectedItemProperty().addListener((_, _, selected) ->
                    add.setDisable(selected == null || selected.getValue() instanceof GroupRow));
        }

        // Story 334 — as the Manage Plugins list does (critique J27): a browser
        // opened while the startup restore still checks the installed plugins
        // lists only those loaded so far, so its third-party group is read again
        // once the restore has published what became of each entry. The callback
        // is registered whatever the restore's state: the restore may have
        // finished after the list above was read, and a restore already over
        // runs it at once, which posts the re-read to a later FX turn. The
        // restore holds the browser only weakly: one closed while a restore that
        // never completes still runs (a hung plugin constructor, ruling-3 Q6) is
        // not kept for the session (finding FX2-6).
        if (registry != null) {
            WeakReference<PluginBrowser> browser = new WeakReference<>(this);
            registry.restore().thenAccept(_ -> refreshWhileReachable(browser));
        }
    }

    /** Re-reads {@code browser}'s third-party group on the FX thread, unless the browser was collected. */
    private static void refreshWhileReachable(WeakReference<PluginBrowser> browser) {
        PluginBrowser reachable = browser.get();
        if (reachable != null) {
            FxDispatcher.runOnFx(reachable.fxDispatcher, () -> {
                PluginBrowser stillReachable = browser.get();
                if (stillReachable != null) {
                    stillReachable.refreshThirdParty();
                }
            });
        }
    }

    /**
     * Injects the FX-thread marshalling seam used for off-thread JAR scans
     * (story 289); defaults through the app-scoped seam when {@code null}.
     *
     * @param dispatcher the dispatcher, or {@code null} for the default
     */
    public void setFxDispatcher(FxDispatcher dispatcher) {
        this.fxDispatcher = dispatcher;
    }

    // ── Selection commit ──────────────────────────────────────────────────────

    /** The selected plugin; {@code null}, the result converter's "no result", for no row or a group. */
    private Selection selectionFromTree() {
        return Optional.ofNullable(tree.getSelectionModel().getSelectedItem())
                .flatMap(selected -> pluginOf(selected.getValue()))
                .orElse(null);
    }

    private void fireAddIfLeafSelected() {
        TreeItem<BrowserRow> selected = tree.getSelectionModel().getSelectedItem();
        if (selected != null && !(selected.getValue() instanceof GroupRow)
                && getDialogPane().lookupButton(ADD_BUTTON) instanceof Button add) {
            add.fire();
        }
    }

    // ── Tree building ─────────────────────────────────────────────────────────

    /**
     * Rebuilds the tree from the model, filtered by {@code query}, and keeps the
     * user's place in it (story 334, ruling R-N2): the row that was selected is
     * selected again when the new tree lists it — a built-in effect by its type, a
     * third-party plugin by its installed entry, a group by the names on its path —
     * with the keyboard focus on it, and the view scrolls to it only when its row
     * moved; a row the new tree does not list leaves nothing selected, never a
     * neighbour, so Add stays disabled. While the search box is empty, the groups
     * the user collapsed stay collapsed, also after a search is cleared, but for a
     * group that holds the row selected again: the reselect expands it to show the
     * choice, and it stays expanded (a choice made during a search can lie in a
     * group collapsed before it). A search shows every group it lists expanded.
     * Every refresh comes through here: the first build, each search keystroke,
     * Manage…, an install and the startup restore's re-read.
     */
    private void rebuildTree(String query) {
        Optional<RowKey> chosen = Optional.ofNullable(tree.getSelectionModel().getSelectedItem())
                .map(PluginBrowser::keyOf);
        int rowBefore = tree.getSelectionModel().getSelectedIndex();
        if (!showingSearch && tree.getRoot() != null) {
            collapsedGroups = rowsUnder(tree.getRoot())
                    .filter(item -> item.getValue() instanceof GroupRow && !item.isExpanded())
                    .map(PluginBrowser::groupPath)
                    .collect(Collectors.toUnmodifiableSet());
        }
        boolean searching = query != null && !query.isBlank();
        List<BrowserRow> visible = searching
                ? filter(model, query.toLowerCase(Locale.ROOT))
                : model;
        TreeItem<BrowserRow> root = new TreeItem<>(new GroupRow("", List.of()));
        root.setExpanded(true);
        for (BrowserRow row : visible) {
            root.getChildren().add(toTreeItem(row));
        }
        if (!searching) {
            rowsUnder(root)
                    .filter(item -> item.getValue() instanceof GroupRow && collapsedGroups.contains(groupPath(item)))
                    .forEach(item -> item.setExpanded(false));
        }
        tree.setRoot(root); // JavaFX clears the selection here and moves the focus to the first row
        chosen.flatMap(key -> rowsUnder(root).filter(item -> keyOf(item).equals(key)).findFirst())
                .ifPresent(item -> {
                    tree.getSelectionModel().select(item); // expands its groups; the focus follows the selection
                    int row = tree.getSelectionModel().getSelectedIndex();
                    if (row != rowBefore) {
                        tree.scrollTo(row);
                    }
                });
        showingSearch = searching;
    }

    /** Every row under {@code item}, depth first in tree order; {@code item} itself is not one of them. */
    private static Stream<TreeItem<BrowserRow>> rowsUnder(TreeItem<BrowserRow> item) {
        return item.getChildren().stream().flatMap(child -> Stream.concat(Stream.of(child), rowsUnder(child)));
    }

    /** The key of {@code item}, a row of the tree: the plugin Add would return for it, or its group's path. */
    private static RowKey keyOf(TreeItem<BrowserRow> item) {
        return pluginOf(item.getValue()).<RowKey>map(PluginKey::new)
                .orElseGet(() -> new GroupKey(groupPath(item)));
    }

    /** What Add returns for {@code row}: its plugin; nothing for a group. */
    private static Optional<Selection> pluginOf(BrowserRow row) {
        return switch (row) {
            case BuiltInRow builtIn -> Optional.of(new BuiltInSelection(builtIn.type()));
            case ExternalRow external -> Optional.of(new ExternalSelection(external.entry()));
            case GroupRow _ -> Optional.empty();
        };
    }

    /** The names of the groups from the top level down to {@code group}; the tree's hidden root is not one of them. */
    private static List<String> groupPath(TreeItem<BrowserRow> group) {
        List<String> names = new ArrayList<>();
        for (TreeItem<BrowserRow> at = group; at.getParent() != null; at = at.getParent()) {
            names.addFirst(at.getValue().displayText());
        }
        return names;
    }

    private TreeItem<BrowserRow> toTreeItem(BrowserRow row) {
        TreeItem<BrowserRow> item = new TreeItem<>(row);
        if (row instanceof GroupRow group) {
            item.setExpanded(true);
            for (BrowserRow child : group.children()) {
                item.getChildren().add(toTreeItem(child));
            }
        }
        return item;
    }

    private Map<ExternalPluginEntry, DawPlugin> loadedPlugins() {
        return registry == null ? Map.of() : registry.getLoadedPlugins();
    }

    private void refreshThirdParty() {
        this.model = buildModel(builtInTypes, stereo, loadedPlugins());
        rebuildTree(searchField.getText());
    }

    // ── Install flow ──────────────────────────────────────────────────────────

    private void chooseAndInstallJar() {
        if (registry == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Install plugin from JAR");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR Files", "*.jar"));
        File file = chooser.showOpenDialog(getOwner());
        if (file != null) {
            beginJarInstall(file.toPath());
        }
    }

    private void beginJarInstall(Path jar) {
        if (registry == null) {
            return;
        }
        runScan(jar, new PluginJarScanner(fxDispatcher), this::openInstallDialog);
    }

    private Thread runScan(Path jar, PluginJarScanner scanner, Consumer<JarInspection> onReady) {
        showBusyRow(jarName(jar));
        return scanner.scan(jar, inspection -> {
            hideBusyRow();
            onReady.accept(inspection);
        });
    }

    private void openInstallDialog(JarInspection inspection) {
        PluginInstallPanel.showDialog(inspection, registry, this::refreshThirdParty);
    }

    private void chooseAndInstallFolder() {
        if (registry == null) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Install plugins from folder");
        File dir = chooser.showDialog(getOwner());
        if (dir != null) {
            installFromFolder(dir.toPath());
        }
    }

    private void installFromFolder(Path folder) {
        showBusyRow(jarName(folder));
        PluginJarScanner scanner = new PluginJarScanner(fxDispatcher);
        Thread.ofVirtual().name("daw-plugin-folder-scan").start(() -> {
            List<JarInspection> manifestBearing = new ArrayList<>();
            List<JarInspection> rejected = new ArrayList<>();
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(folder, "*.jar")) {
                for (Path jar : jars) {
                    JarInspection inspection = scanner.scanBlocking(jar);
                    if (inspection.manifests().isValid()) {
                        manifestBearing.add(inspection);
                    } else {
                        rejected.add(inspection);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Best-effort — an unreadable folder yields no installs.
            }
            String skippedSummary = skippedSummary(rejected);
            FxDispatcher.runOnFx(fxDispatcher, () -> {
                hideBusyRow();
                for (JarInspection inspection : manifestBearing) {
                    openInstallDialog(inspection);
                }
                if (skippedSummary != null) {
                    DawgDialog.info("Install plugins", skippedSummary).showAndWait();
                }
            });
        });
    }

    /**
     * The skipped-JARs dialog body for a folder install, one section per skip
     * reason so an unreadable JAR (or one whose manifest failed to parse) is
     * never misreported as manifest-less; {@code null} when nothing was skipped.
     * Package-private for tests.
     */
    static String skippedSummary(List<JarInspection> rejected) {
        List<String> unreadable = new ArrayList<>();
        List<String> invalidManifest = new ArrayList<>();
        List<String> noManifest = new ArrayList<>();
        for (JarInspection inspection : rejected) {
            String name = jarName(inspection.jar());
            if (!inspection.jarReadable()) {
                unreadable.add(name);
            } else if (inspection.manifestPresent()) {
                invalidManifest.add(name);
            } else {
                noManifest.add(name);
            }
        }
        List<String> sections = new ArrayList<>();
        if (!unreadable.isEmpty()) {
            sections.add("Skipped " + unreadable.size() + " JAR(s) that could not be read:\n"
                    + String.join("\n", unreadable));
        }
        if (!invalidManifest.isEmpty()) {
            sections.add("Skipped " + invalidManifest.size() + " JAR(s) with an invalid manifest:\n"
                    + String.join("\n", invalidManifest));
        }
        if (!noManifest.isEmpty()) {
            sections.add("Skipped " + noManifest.size() + " JAR(s) with no manifest:\n"
                    + String.join("\n", noManifest));
        }
        return sections.isEmpty() ? null : String.join("\n\n", sections);
    }

    private void installDragDrop(Region content) {
        content.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (registry != null && db.hasFiles() && containsJar(db)) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        content.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean ok = false;
            if (registry != null && db.hasFiles()) {
                List<Path> jars = jarPaths(db);
                if (!jars.isEmpty()) {
                    // Mirror AudioImportController: handle the first dropped JAR.
                    beginJarInstall(jars.getFirst());
                    ok = true;
                }
            }
            event.setDropCompleted(ok);
            event.consume();
        });
    }

    private void showBusyRow(String label) {
        busyLabel.setText("Scanning " + label + "…");
        busyRow.setVisible(true);
        busyRow.setManaged(true);
    }

    private void hideBusyRow() {
        busyRow.setVisible(false);
        busyRow.setManaged(false);
    }

    // ── Pure model + filter (package-private, static, toolkit-free) ────────────

    /** A row in the browser tree — a group header or a selectable plugin leaf. */
    sealed interface BrowserRow permits GroupRow, BuiltInRow, ExternalRow {
        String displayText();
    }

    /** A group header (top group, a category, or a vendor) with child rows. */
    record GroupRow(String name, List<BrowserRow> children) implements BrowserRow {
        @Override
        public String displayText() {
            return name;
        }
    }

    /** A built-in DSP effect leaf. */
    record BuiltInRow(InsertEffectType type) implements BrowserRow {
        @Override
        public String displayText() {
            return type.getDisplayName();
        }
    }

    /** A registered third-party plugin leaf. */
    record ExternalRow(ExternalPluginEntry entry, String name, String version,
                       PluginCategory category, String iconHint) implements BrowserRow {
        @Override
        public String displayText() {
            return name;
        }
    }

    /**
     * Builds the shared browser model: a "Built-in" group whose subgroups are the
     * non-empty {@link PluginCategory}s (in enum order), and an
     * "Installed (third-party)" group whose subgroups are the vendors (sorted) of
     * the registered {@link PluginType#EFFECT} plugins. Empty top groups are
     * omitted. Pure and {@code static} for unit testing.
     */
    static List<BrowserRow> buildModel(List<InsertEffectType> builtInTypes, boolean stereo,
                                       Map<ExternalPluginEntry, DawPlugin> loadedPlugins) {
        List<BrowserRow> roots = new ArrayList<>();

        Map<PluginCategory, List<BrowserRow>> byCategory = new EnumMap<>(PluginCategory.class);
        for (InsertEffectType type : builtInTypes) {
            if (type == InsertEffectType.STEREO_IMAGER && !stereo) {
                continue;
            }
            byCategory.computeIfAbsent(categoryOf(type), _ -> new ArrayList<>())
                    .add(new BuiltInRow(type));
        }
        List<BrowserRow> builtInGroups = new ArrayList<>();
        for (PluginCategory category : PluginCategory.values()) {
            List<BrowserRow> rows = byCategory.get(category);
            if (rows != null && !rows.isEmpty()) {
                builtInGroups.add(new GroupRow(category.displayName(), rows));
            }
        }
        if (!builtInGroups.isEmpty()) {
            roots.add(new GroupRow(BUILT_IN_GROUP, builtInGroups));
        }

        Map<String, List<BrowserRow>> byVendor = new TreeMap<>();
        for (Map.Entry<ExternalPluginEntry, DawPlugin> entry : loadedPlugins.entrySet()) {
            PluginDescriptor descriptor = entry.getValue().getDescriptor();
            if (descriptor.type() != PluginType.EFFECT) {
                continue;
            }
            byVendor.computeIfAbsent(descriptor.vendor(), _ -> new ArrayList<>())
                    .add(new ExternalRow(entry.getKey(), descriptor.name(),
                            descriptor.version(), descriptor.category(), descriptor.iconHint()));
        }
        List<BrowserRow> vendorGroups = new ArrayList<>();
        for (Map.Entry<String, List<BrowserRow>> entry : byVendor.entrySet()) {
            vendorGroups.add(new GroupRow(entry.getKey(), entry.getValue()));
        }
        if (!vendorGroups.isEmpty()) {
            roots.add(new GroupRow(INSTALLED_GROUP, vendorGroups));
        }

        return roots;
    }

    /**
     * Pure recursive filter: keeps a group when its own name matches
     * {@code lowerQuery} (whole group retained) or any descendant survives, keeps
     * a leaf when its display text matches, and prunes empty groups.
     * {@code lowerQuery} must already be lower-cased ({@link Locale#ROOT}); an
     * empty query keeps everything. Package-private + {@code static} for tests.
     */
    static List<BrowserRow> filter(List<BrowserRow> rows, String lowerQuery) {
        List<BrowserRow> out = new ArrayList<>();
        for (BrowserRow row : rows) {
            BrowserRow kept = filterRow(row, lowerQuery);
            if (kept != null) {
                out.add(kept);
            }
        }
        return out;
    }

    private static BrowserRow filterRow(BrowserRow row, String lowerQuery) {
        if (lowerQuery.isEmpty()) {
            return row;
        }
        boolean nameMatches = row.displayText().toLowerCase(Locale.ROOT).contains(lowerQuery);
        if (row instanceof GroupRow group) {
            if (nameMatches) {
                return group;
            }
            List<BrowserRow> kept = filter(group.children(), lowerQuery);
            return kept.isEmpty() ? null : new GroupRow(group.name(), kept);
        }
        return nameMatches ? row : null;
    }

    private static PluginCategory categoryOf(InsertEffectType type) {
        try {
            return InsertEffectFactory.getCategory(type);
        } catch (IllegalArgumentException e) {
            // An unregistered/CLAP type (availableTypes() excludes CLAP) — group
            // under Utility rather than dropping the row.
            return PluginCategory.UTILITY;
        }
    }

    private static boolean containsJar(Dragboard db) {
        return db.getFiles().stream().anyMatch(PluginBrowser::isJar);
    }

    private static List<Path> jarPaths(Dragboard db) {
        List<Path> jars = new ArrayList<>();
        for (File file : db.getFiles()) {
            if (isJar(file)) {
                jars.add(file.toPath());
            }
        }
        return jars;
    }

    private static boolean isJar(File file) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static String jarName(Path path) {
        Path name = path.getFileName();
        return name != null ? name.toString() : path.toString();
    }

    // ── Cell rendering ────────────────────────────────────────────────────────

    private static final class BrowserCell extends TreeCell<BrowserRow> {
        @Override
        protected void updateItem(BrowserRow row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().removeAll("plugin-browser-group", "plugin-browser-row");
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            switch (row) {
                case GroupRow group -> {
                    setText(group.name());
                    setGraphic(null);
                    getStyleClass().add("plugin-browser-group");
                }
                case BuiltInRow builtIn -> {
                    setText(builtIn.type().getDisplayName());
                    setGraphic(PluginIcons.of(categoryOf(builtIn.type()), "", ICON_SIZE));
                    getStyleClass().add("plugin-browser-row");
                }
                case ExternalRow external -> {
                    setText(external.name() + "  v" + external.version()
                            + "   [EFFECT · " + external.category().displayName() + "]");
                    setGraphic(PluginIcons.of(external.category(), external.iconHint(), ICON_SIZE));
                    getStyleClass().add("plugin-browser-row");
                }
            }
        }
    }

    // ── Test hooks ────────────────────────────────────────────────────────────

    /** The backing tree, for structural assertions. */
    TreeView<BrowserRow> treeForTest() {
        return tree;
    }

    /** The busy row node (style class {@code plugin-row-busy}), for scan tests. */
    Region busyRowForTest() {
        return busyRow;
    }

    /** Applies the search filter as if the user typed {@code query}. */
    void applyFilterForTest(String query) {
        searchField.setText(query);
    }

    /**
     * Drives the same busy-row + off-thread scan flow production uses, with an
     * injected scanner and completion consumer — the deterministic seam for
     * {@code NonBlockingScanShowsRowSpinnerTest}.
     */
    Thread runScanForTest(Path jar, PluginJarScanner scanner, Consumer<JarInspection> onReady) {
        return runScan(jar, scanner, onReady);
    }
}
