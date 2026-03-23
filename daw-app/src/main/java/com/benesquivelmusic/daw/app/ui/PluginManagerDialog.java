package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.PluginLoadException;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;

/**
 * Dialog for managing external DAW plugins.
 *
 * <p>Allows users to add plugins by specifying the path to a pre-compiled JAR file
 * and the fully qualified class name of their plugin implementation. This eliminates
 * the need for users to manually create {@code META-INF/services} files.</p>
 *
 * <p>Uses the {@link DawIcon} icon pack for all button graphics.</p>
 */
public final class PluginManagerDialog extends Dialog<Void> {

    private static final double BUTTON_ICON_SIZE = 16;
    private static final double HEADER_ICON_SIZE = 18;

    private final PluginRegistry registry;
    private final ObservableList<ExternalPluginEntry> entryList;
    private final ListView<ExternalPluginEntry> listView;
    private final TextField jarPathField;
    private final TextField classNameField;

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
                        setGraphic(IconNode.of(pluginTypeIcon(desc.type()), 14));
                    } else {
                        setText(entry.className() + " — " + entry.jarPath().getFileName());
                        setGraphic(IconNode.of(DawIcon.KNOB, 14));
                    }
                }
            }
        });

        // --- JAR path input ---
        jarPathField = new TextField();
        jarPathField.setPromptText("Path to plugin JAR file");
        HBox.setHgrow(jarPathField, Priority.ALWAYS);

        Button browseButton = new Button("Browse…");
        browseButton.setGraphic(IconNode.of(DawIcon.SEARCH, BUTTON_ICON_SIZE));
        browseButton.setOnAction(_ -> browseForJar());

        Label jarLabel = new Label("JAR Path:");
        jarLabel.setGraphic(IconNode.of(DawIcon.FOLDER, 14));

        HBox jarPathRow = new HBox(8, jarLabel, jarPathField, browseButton);
        jarPathRow.setPadding(new Insets(4, 0, 4, 0));

        // --- Class name input ---
        classNameField = new TextField();
        classNameField.setPromptText("e.g. com.example.MyReverbPlugin");
        HBox.setHgrow(classNameField, Priority.ALWAYS);

        Label classLabel = new Label("Plugin Class:");
        classLabel.setGraphic(IconNode.of(DawIcon.TAG, 14));

        HBox classNameRow = new HBox(8, classLabel, classNameField);
        classNameRow.setPadding(new Insets(4, 0, 4, 0));

        // --- Action buttons ---
        Button addButton = new Button("Add Plugin");
        addButton.setGraphic(IconNode.of(DawIcon.DOWNLOAD, BUTTON_ICON_SIZE));
        addButton.setOnAction(_ -> addPlugin());

        Button removeButton = new Button("Remove Selected");
        removeButton.setGraphic(IconNode.of(DawIcon.CLOSE, BUTTON_ICON_SIZE));
        removeButton.setOnAction(_ -> removeSelectedPlugin());

        HBox buttonRow = new HBox(8, addButton, removeButton);
        buttonRow.setPadding(new Insets(8, 0, 0, 0));

        // --- Layout ---
        Label headerLabel = new Label("Loaded Plugins:");
        headerLabel.setGraphic(IconNode.of(DawIcon.LIBRARY, HEADER_ICON_SIZE));

        VBox content = new VBox(8,
                headerLabel,
                listView,
                jarPathRow,
                classNameRow,
                buttonRow
        );
        content.setPadding(new Insets(16));
        content.setPrefWidth(600);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    }

    private void browseForJar() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Plugin JAR");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JAR Files", "*.jar"));
        File file = fileChooser.showOpenDialog(getOwner());
        if (file != null) {
            jarPathField.setText(file.getAbsolutePath());
        }
    }

    private void addPlugin() {
        String jarPathText = jarPathField.getText();
        String className = classNameField.getText();

        if (jarPathText == null || jarPathText.isBlank()) {
            showError("Please specify the path to the plugin JAR file.");
            return;
        }
        if (className == null || className.isBlank()) {
            showError("Please specify the fully qualified plugin class name.");
            return;
        }

        Path jarPath = Path.of(jarPathText.strip());
        ExternalPluginEntry entry = new ExternalPluginEntry(jarPath, className.strip());

        try {
            registry.register(entry);
            entryList.setAll(registry.getEntries());
            jarPathField.clear();
            classNameField.clear();
        } catch (PluginLoadException e) {
            showError("Failed to load plugin:\n" + e.getMessage());
        }
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
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Plugin Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.setGraphic(IconNode.of(DawIcon.ERROR, 32));
        alert.showAndWait();
    }

    /**
     * Returns the appropriate icon for a given plugin type.
     */
    private static DawIcon pluginTypeIcon(PluginType type) {
        return switch (type) {
            case EFFECT      -> DawIcon.REVERB;
            case INSTRUMENT  -> DawIcon.KEYBOARD;
            case ANALYZER    -> DawIcon.OSCILLOSCOPE;
            case MIDI_EFFECT -> DawIcon.MIDI;
        };
    }
}
