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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

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

    private Selection selectionFromTree() {
        TreeItem<BrowserRow> selected = tree.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return null;
        }
        return switch (selected.getValue()) {
            case BuiltInRow builtIn -> new BuiltInSelection(builtIn.type());
            case ExternalRow external -> new ExternalSelection(external.entry());
            case GroupRow ignored -> null;
        };
    }

    private void fireAddIfLeafSelected() {
        TreeItem<BrowserRow> selected = tree.getSelectionModel().getSelectedItem();
        if (selected != null && !(selected.getValue() instanceof GroupRow)
                && getDialogPane().lookupButton(ADD_BUTTON) instanceof Button add) {
            add.fire();
        }
    }

    // ── Tree building ─────────────────────────────────────────────────────────

    private void rebuildTree(String query) {
        boolean searching = query != null && !query.isBlank();
        List<BrowserRow> visible = searching
                ? filter(model, query.toLowerCase(Locale.ROOT))
                : model;
        TreeItem<BrowserRow> root = new TreeItem<>(new GroupRow("", List.of()));
        root.setExpanded(true);
        for (BrowserRow row : visible) {
            root.getChildren().add(toTreeItem(row));
        }
        tree.setRoot(root);
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
            List<String> skipped = new ArrayList<>();
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(folder, "*.jar")) {
                for (Path jar : jars) {
                    JarInspection inspection = scanner.scanBlocking(jar);
                    if (inspection.manifests().isValid()) {
                        manifestBearing.add(inspection);
                    } else {
                        skipped.add(jarName(jar));
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Best-effort — an unreadable folder yields no installs.
            }
            FxDispatcher.runOnFx(fxDispatcher, () -> {
                hideBusyRow();
                for (JarInspection inspection : manifestBearing) {
                    openInstallDialog(inspection);
                }
                if (!skipped.isEmpty()) {
                    DawgDialog.info("Install plugins",
                            "Skipped " + skipped.size() + " JAR(s) with no manifest:\n"
                                    + String.join("\n", skipped)).showAndWait();
                }
            });
        });
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
