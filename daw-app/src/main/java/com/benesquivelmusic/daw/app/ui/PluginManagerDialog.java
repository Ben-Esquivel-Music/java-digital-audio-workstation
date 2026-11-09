package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.plugin.PluginIcons;
import com.benesquivelmusic.daw.app.ui.plugin.PluginInstallPanel;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner;
import com.benesquivelmusic.daw.app.ui.plugin.PluginText;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.InstalledPlugin;
import com.benesquivelmusic.daw.core.plugin.MissingReason;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.plugin.RemovedPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dialog for managing external DAW plugins (Plugin View Design Book §6.8, §8.4;
 * story 303).
 *
 * <p>Lists the registry's plugins with descriptor-driven icons (via
 * {@link PluginIcons}), lets the user install a plugin by dropping / picking a
 * JAR — the DAW reads the JAR's {@code META-INF/daw-plugin.json} manifest and
 * installs every plugin it declares, so the user never types a class name (a JAR
 * must carry that manifest; otherwise the install flow rejects it with a clear
 * error — story 304, §8.5.2) — and removes a selected plugin. The class-name /
 * JAR-path text fields the legacy dialog showed at all times are gone; icons come
 * from the descriptor category / {@code iconHint} rather than the retired
 * keyword-sniffing heuristic.</p>
 *
 * <p>Story 334 (studio-engineer rulings E9 and G11) — the installed plugins
 * persist between sessions, so story 303's notice that they did not is gone.
 * Each row names the installed plugin, its id and the file it was installed
 * from; an entry whose file did not load at startup stays listed as Missing,
 * with its reason, until the engineer removes it. The list is read again when
 * the startup restore finishes (critique J27).</p>
 *
 * <p>Uses the {@link DawIcon} icon pack for all button graphics.</p>
 */
public final class PluginManagerDialog extends DawgDialog<Void> {

    private static final Logger LOG = Logger.getLogger(PluginManagerDialog.class.getName());

    private static final double BUTTON_ICON_SIZE = 16;
    private static final double HEADER_ICON_SIZE = 18;
    private static final double ROW_ICON_SIZE = 14;
    private static final double BUSY_SPINNER_SIZE = 18;

    /**
     * A row of the list, as the registry held its entry when the list was
     * read: equal rows render alike, so a row whose entry turned Missing is a
     * new row and renders again.
     *
     * @param entry      the registry's entry
     * @param descriptor the descriptor of the manifest it was installed from
     * @param text       the row's words
     */
    private record PluginRow(ExternalPluginEntry entry, PluginDescriptor descriptor, String text) {
        PluginRow {
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(descriptor, "descriptor must not be null");
            Objects.requireNonNull(text, "text must not be null");
        }
    }

    private final PluginRegistry registry;
    private final ObservableList<PluginRow> rows;
    private final ListView<PluginRow> listView;
    private final HBox busyRow;
    private final Label busyLabel;

    /**
     * The FX-thread marshalling seam (story 289) used for off-thread JAR scans
     * and the restore's refresh. Nullable — {@link PluginJarScanner} and
     * {@link FxDispatcher#runOnFx} default through the app-scoped seam.
     */
    private FxDispatcher fxDispatcher;

    /**
     * Creates a new plugin manager dialog.
     *
     * @param registry the plugin registry to manage
     */
    public PluginManagerDialog(PluginRegistry registry) {
        this.registry = registry;
        this.rows = FXCollections.observableArrayList(rowsOf(registry));

        setTitle("Manage Plugins");
        setHeaderText("Add or remove external plugins");
        setGraphic(IconNode.of(DawIcon.SETTINGS, 24));

        // --- Plugin list ---
        listView = new ListView<>(rows);
        listView.setPrefHeight(200);
        listView.setCellFactory(_ -> new ListCell<>() {
            @Override
            protected void updateItem(PluginRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(row.text());
                    // Descriptor-driven icon (category / iconHint), story 303
                    setGraphic(PluginIcons.of(row.descriptor().category(), row.descriptor().iconHint(),
                            ROW_ICON_SIZE));
                }
            }
        });

        // --- Header + info ---
        Label headerLabel = new Label(PluginText.text("pluginManager.header"));
        headerLabel.setGraphic(IconNode.of(DawIcon.LIBRARY, HEADER_ICON_SIZE));

        Label infoLabel = new Label("Drag a JAR here, or use Install from JAR, to add a plugin.");
        infoLabel.setGraphic(IconNode.of(DawIcon.INFO, 14));
        infoLabel.getStyleClass().add("plugin-manager-info");

        // --- Action buttons ---
        Button installButton = new Button("Install from JAR…");
        installButton.setGraphic(IconNode.of(DawIcon.DOWNLOAD, BUTTON_ICON_SIZE));
        installButton.getStyleClass().addAll("dawg-button", "size-default", "primary");
        installButton.setOnAction(_ -> chooseAndInstallJar());

        Button removeButton = new Button("Remove Selected");
        removeButton.setGraphic(IconNode.of(DawIcon.CLOSE, BUTTON_ICON_SIZE));
        removeButton.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        removeButton.setOnAction(_ -> removeSelectedPlugin());

        HBox buttonRow = new HBox(8, installButton, removeButton);
        buttonRow.setPadding(new Insets(8, 0, 0, 0));

        // --- Busy row (JAR scan in flight) ---
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(BUSY_SPINNER_SIZE, BUSY_SPINNER_SIZE);
        busyLabel = new Label();
        busyRow = new HBox(8, spinner, busyLabel);
        busyRow.getStyleClass().add("plugin-row-busy");
        busyRow.setVisible(false);
        busyRow.setManaged(false);

        VBox content = new VBox(8,
                headerLabel,
                listView,
                infoLabel,
                buttonRow,
                busyRow
        );
        content.setPadding(new Insets(16));
        content.setPrefWidth(600);
        installDragDrop(content);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        // story 276 — the footer CLOSE button is the dismiss path, so the
        // redundant header close glyph is retracted by DawgDialog (§5.9); the
        // header keeps this dialog's own SETTINGS graphic (set above).
        sized(DawgDialog.Size.LARGE);

        // Story 334 (J27) — an entry the startup restore is still checking is
        // listed already; read the list again once the restore has published
        // what became of each entry (loaded, or Missing with its reason). The
        // restore holds the dialog only weakly: one closed while a restore that
        // never completes still runs (a hung plugin constructor, ruling-3 Q6)
        // is not kept for the session (finding FX2-6).
        WeakReference<PluginManagerDialog> dialog = new WeakReference<>(this);
        registry.restore().thenAccept(_ -> refreshWhileReachable(dialog));
    }

    /** Re-reads {@code dialog}'s rows on the FX thread, unless the dialog was collected. */
    private static void refreshWhileReachable(WeakReference<PluginManagerDialog> dialog) {
        PluginManagerDialog reachable = dialog.get();
        if (reachable != null) {
            FxDispatcher.runOnFx(reachable.fxDispatcher, () -> {
                PluginManagerDialog stillReachable = dialog.get();
                if (stillReachable != null) {
                    stillReachable.refreshRows();
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

    /**
     * The rows of {@code registry}'s entries, in list order: each names the
     * installed plugin, its id and the file it was installed from, and — for
     * an entry that did not load at startup — that it is Missing, and why
     * (studio ruling E9).
     */
    private static List<PluginRow> rowsOf(PluginRegistry registry) {
        List<PluginRow> rows = new ArrayList<>();
        for (ExternalPluginEntry entry : registry.getEntries()) {
            Optional<InstalledPlugin> installed = registry.installed(entry);
            if (installed.isEmpty()) {
                continue; // removed since the entries were read
            }
            InstalledPlugin plugin = installed.get();
            Optional<MissingReason> missing = registry.missingReason(entry);
            String text = missing.isPresent()
                    ? PluginText.text("pluginManager.row.missing", plugin.name(), plugin.id(),
                            plugin.originalFileName(), reasonText(missing.get()))
                    : PluginText.text("pluginManager.row", plugin.name(), plugin.id(), plugin.originalFileName());
            rows.add(new PluginRow(entry, plugin.manifest().descriptor(), text));
        }
        return rows;
    }

    /** E9's words for why an entry is Missing. */
    private static String reasonText(MissingReason reason) {
        return switch (reason) {
            case MissingReason.FileNotFound _ -> PluginText.text("pluginManager.missing.fileNotFound");
            case MissingReason.ManifestInvalid _ -> PluginText.text("pluginManager.missing.manifestInvalid");
            case MissingReason.ManifestNoLongerDeclares _ ->
                    PluginText.text("pluginManager.missing.manifestNoLongerDeclares");
            case MissingReason.IdOrVersionChanged _ -> PluginText.text("pluginManager.missing.idOrVersionChanged");
            case MissingReason.FailedToLoad failed ->
                    PluginText.text("pluginManager.missing.failedToLoad", failed.failure());
        };
    }

    private void refreshRows() {
        rows.setAll(rowsOf(registry));
    }

    private void chooseAndInstallJar() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Plugin JAR");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JAR Files", "*.jar"));
        File file = fileChooser.showOpenDialog(getOwner());
        if (file != null) {
            beginJarInstall(file.toPath());
        }
    }

    private void beginJarInstall(Path jar) {
        busyLabel.setText("Scanning " + jar.getFileName() + "…");
        busyRow.setVisible(true);
        busyRow.setManaged(true);
        new PluginJarScanner(fxDispatcher).scan(jar, inspection -> {
            busyRow.setVisible(false);
            busyRow.setManaged(false);
            PluginInstallPanel.showDialog(inspection, registry, this::refreshRows);
        });
    }

    private void installDragDrop(VBox content) {
        content.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasFiles() && db.getFiles().stream()
                    .anyMatch(f -> f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"))) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        content.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean ok = false;
            if (db.hasFiles()) {
                List<Path> jars = db.getFiles().stream()
                        .filter(f -> f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .map(File::toPath)
                        .toList();
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

    private void removeSelectedPlugin() {
        PluginRow selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Please select a plugin to remove.");
            return;
        }
        // Story 334 (critique J9) — the registry takes the entry out and hands
        // it back; its catalogue instance is disposed, then its loader closed,
        // on a virtual thread (JEP 444): third-party code never runs on FX.
        registry.unregister(selected.entry()).ifPresent(removed ->
                Thread.ofVirtual().name("daw-plugin-registry-dispose").start(() -> release(removed)));
        refreshRows();
    }

    private static void release(RemovedPlugin removed) {
        try {
            removed.disposal().run();
        } catch (RuntimeException | LinkageError failure) {
            LOG.log(Level.WARNING, failure, () -> "A removed plugin failed while it was disposed: "
                    + removed.plugin().id());
        }
    }

    private void showError(String message) {
        // story 276 — route through DawgDialog.error(...) so the error dialog
        // gets the §5.9 flat chrome instead of JavaFX's Alert.
        DawgDialog.error("Plugin Error", message).showAndWait();
    }
}
