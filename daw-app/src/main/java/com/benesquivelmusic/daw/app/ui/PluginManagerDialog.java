package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.plugin.PluginIcons;
import com.benesquivelmusic.daw.app.ui.plugin.PluginInstallPanel;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Dialog for managing external DAW plugins (Plugin View Design Book §6.8, §8.4;
 * story 303).
 *
 * <p>Lists the registry's plugins with descriptor-driven icons (via
 * {@link PluginIcons}), lets the user install a plugin by dropping / picking a
 * JAR — the DAW reads the JAR's {@code META-INF/daw-plugin.json} manifest and
 * installs every plugin it declares, so the user never types a class name (a
 * manifest-less JAR falls back to the class-name form inside the install flow) —
 * and removes a selected plugin. The class-name / JAR-path text fields the legacy
 * dialog showed at all times are gone; icons come from the descriptor category /
 * {@code iconHint} rather than the retired keyword-sniffing heuristic.</p>
 *
 * <p>Uses the {@link DawIcon} icon pack for all button graphics.</p>
 */
public final class PluginManagerDialog extends DawgDialog<Void> {

    private static final double BUTTON_ICON_SIZE = 16;
    private static final double HEADER_ICON_SIZE = 18;
    private static final double BUSY_SPINNER_SIZE = 18;

    private final PluginRegistry registry;
    private final ObservableList<ExternalPluginEntry> entryList;
    private final ListView<ExternalPluginEntry> listView;
    private final HBox busyRow;
    private final Label busyLabel;

    /**
     * The FX-thread marshalling seam (story 289) used for off-thread JAR scans.
     * Nullable — {@link PluginJarScanner} defaults through the app-scoped seam.
     */
    private FxDispatcher fxDispatcher;

    /**
     * Creates a new plugin manager dialog.
     *
     * @param registry the plugin registry to manage
     */
    public PluginManagerDialog(PluginRegistry registry) {
        this.registry = registry;
        this.entryList = FXCollections.observableArrayList(registry.getEntries());

        setTitle("Manage Plugins");
        setHeaderText("Add or remove external plugins");
        setGraphic(IconNode.of(DawIcon.SETTINGS, 24));

        // --- Plugin list ---
        listView = new ListView<>(entryList);
        listView.setPrefHeight(200);
        listView.setCellFactory(_ -> new ListCell<>() {
            @Override
            protected void updateItem(ExternalPluginEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    DawPlugin plugin = registry.getPlugin(entry);
                    if (plugin != null) {
                        PluginDescriptor desc = plugin.getDescriptor();
                        setText(desc.name() + " (" + desc.id() + ") — " + entry.jarPath().getFileName());
                        // Descriptor-driven icon (category / iconHint), story 303
                        setGraphic(PluginIcons.of(desc.category(), desc.iconHint(), 14));
                    } else {
                        setText(entry.className() + " — " + entry.jarPath().getFileName());
                        setGraphic(IconNode.of(DawIcon.KNOB, 14));
                    }
                }
            }
        });

        // --- Header + info ---
        Label headerLabel = new Label("Loaded Plugins:");
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

        // --- Truthful session notice (§8.4) ---
        Label notificationLabel = new Label(
                "Installed plugins are ready to use immediately. "
                        + "The plugin list is not yet saved between sessions.");
        notificationLabel.setGraphic(IconNode.of(DawIcon.NOTIFICATION, 14));
        notificationLabel.setWrapText(true);
        notificationLabel.getStyleClass().add("plugin-manager-notice");

        VBox content = new VBox(8,
                headerLabel,
                listView,
                infoLabel,
                buttonRow,
                busyRow,
                notificationLabel
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
            PluginInstallPanel.showDialog(inspection, registry,
                    () -> entryList.setAll(registry.getEntries()));
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
        ExternalPluginEntry selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Please select a plugin to remove.");
            return;
        }
        registry.unregister(selected);
        entryList.setAll(registry.getEntries());
    }

    private void showError(String message) {
        // story 276 — route through DawgDialog.error(...) so the error dialog
        // gets the §5.9 flat chrome instead of JavaFX's Alert.
        DawgDialog.error("Plugin Error", message).showAndWait();
    }
}
