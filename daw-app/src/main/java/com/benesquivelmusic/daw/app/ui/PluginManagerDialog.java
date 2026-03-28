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
                        // Use detail icon for known plugin types, falls back to type icon
                        setGraphic(IconNode.of(pluginDetailIcon(desc), 14));
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

        Label infoLabel = new Label("Drop a JAR or browse to add a plugin");
        infoLabel.setGraphic(IconNode.of(DawIcon.INFO, 14));
        infoLabel.setStyle("-fx-text-fill: #808080; -fx-font-size: 11px;");

        Label notificationLabel = new Label("Plugin changes apply after restart");
        notificationLabel.setGraphic(IconNode.of(DawIcon.NOTIFICATION, 14));
        notificationLabel.setStyle("-fx-text-fill: #ff9100; -fx-font-size: 10px;");

        VBox content = new VBox(8,
                headerLabel,
                listView,
                infoLabel,
                jarPathRow,
                classNameRow,
                buttonRow,
                notificationLabel
        );
        content.setPadding(new Insets(16));
        content.setPrefWidth(600);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        DarkThemeHelper.applyTo(this);
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
        DarkThemeHelper.applyTo(alert);
        alert.showAndWait();
    }

    /**
     * Returns the appropriate icon for a given plugin type.
     *
     * <p>Maps plugin types to icons from the <em>DAW</em>, <em>Instruments</em>,
     * <em>Metering</em>, and <em>Media</em> categories.</p>
     */
    private static DawIcon pluginTypeIcon(PluginType type) {
        return switch (type) {
            case EFFECT      -> DawIcon.REVERB;
            case INSTRUMENT  -> DawIcon.KEYBOARD;
            case ANALYZER    -> DawIcon.OSCILLOSCOPE;
            case MIDI_EFFECT -> DawIcon.MIDI;
        };
    }

    /**
     * Returns a secondary detail icon for a plugin based on its descriptor.
     *
     * <p>Uses keywords in the plugin name to pick a category-specific icon
     * from the <em>DAW</em>, <em>Media</em>, <em>General</em>, <em>Connectivity</em>,
     * and other categories for the richest possible visual mapping.</p>
     */
    private static DawIcon pluginDetailIcon(PluginDescriptor desc) {
        String lower = desc.name().toLowerCase();
        // DAW category
        if (lower.contains("compressor") || lower.contains("comp"))   return DawIcon.COMPRESSOR;
        if (lower.contains("delay") || lower.contains("echo"))       return DawIcon.DELAY;
        if (lower.contains("distort"))                                return DawIcon.DISTORTION;
        if (lower.contains("flang"))                                  return DawIcon.FLANGER;
        if (lower.contains("chorus"))                                 return DawIcon.CHORUS;
        if (lower.contains("phas"))                                   return DawIcon.PHASER;
        if (lower.contains("pitch"))                                  return DawIcon.PITCH_SHIFT;
        if (lower.contains("gate") || lower.contains("noise"))       return DawIcon.NOISE_GATE;
        if (lower.contains("limit"))                                  return DawIcon.LIMITER;
        if (lower.contains("eq") || lower.contains("equaliz"))       return DawIcon.EQ;
        if (lower.contains("filter") || lower.contains("low pass"))  return DawIcon.LOW_PASS;
        if (lower.contains("high pass"))                              return DawIcon.HIGH_PASS;
        if (lower.contains("gain"))                                   return DawIcon.GAIN;
        if (lower.contains("automat"))                                return DawIcon.AUTOMATION;
        if (lower.contains("marker"))                                 return DawIcon.MARKER;
        if (lower.contains("waveform"))                               return DawIcon.WAVEFORM;
        if (lower.contains("shuffle"))                                return DawIcon.SHUFFLE;
        // Media category
        if (lower.contains("amp"))                                    return DawIcon.AMPLIFIER;
        if (lower.contains("meter") || lower.contains("analyz"))     return DawIcon.VU_METER;
        if (lower.contains("rms"))                                    return DawIcon.RMS;
        if (lower.contains("correlat"))                               return DawIcon.CORRELATION;
        if (lower.contains("turntable") || lower.contains("vinyl"))  return DawIcon.TURNTABLE;
        if (lower.contains("radio"))                                  return DawIcon.RADIO;
        if (lower.contains("record"))                                 return DawIcon.RECORD_PLAYER;
        if (lower.contains("cd") || lower.contains("disc"))          return DawIcon.CD;
        if (lower.contains("boombox"))                                return DawIcon.BOOMBOX;
        if (lower.contains("monitor") || lower.contains("screen"))   return DawIcon.MONITOR;
        if (lower.contains("camera") || lower.contains("video"))     return DawIcon.CAMERA;
        if (lower.contains("film"))                                   return DawIcon.FILM_STRIP;
        if (lower.contains("mp3"))                                    return DawIcon.MP3_PLAYER;
        if (lower.contains("wireless") || lower.contains("bluetooth")) return DawIcon.SPEAKER_WIRELESS;
        if (lower.contains("drum machine"))                           return DawIcon.DRUM;
        // Connectivity category
        if (lower.contains("bluetooth"))                              return DawIcon.BLUETOOTH;
        if (lower.contains("wifi") || lower.contains("wireless"))    return DawIcon.WIFI;
        if (lower.contains("cloud"))                                  return DawIcon.CLOUD;
        if (lower.contains("airplay"))                                return DawIcon.AIRPLAY;
        if (lower.contains("ethernet"))                               return DawIcon.ETHERNET;
        if (lower.contains("nfc"))                                    return DawIcon.NFC;
        if (lower.contains("optical"))                                return DawIcon.OPTICAL;
        if (lower.contains("antenna"))                                return DawIcon.ANTENNA;
        if (lower.contains("aux"))                                    return DawIcon.AUX_CABLE;
        if (lower.contains("cast"))                                   return DawIcon.CAST;
        // General category
        if (lower.contains("album"))                                  return DawIcon.ALBUM;
        if (lower.contains("cassette") || lower.contains("tape"))    return DawIcon.CASSETTE;
        if (lower.contains("vinyl"))                                  return DawIcon.VINYL;
        if (lower.contains("podcast"))                                return DawIcon.PODCAST;
        if (lower.contains("queue"))                                  return DawIcon.QUEUE_MUSIC;
        // Playback category
        if (lower.contains("repeat"))                                 return DawIcon.REPEAT;
        if (lower.contains("fast forward") || lower.contains("ff"))  return DawIcon.FAST_FORWARD;
        if (lower.contains("rewind"))                                 return DawIcon.REWIND;
        if (lower.contains("slow"))                                   return DawIcon.SLOW_MOTION;
        if (lower.contains("speed"))                                  return DawIcon.SPEED_UP;
        if (lower.contains("eject"))                                  return DawIcon.EJECT;
        if (lower.contains("next") || lower.contains("queue"))       return DawIcon.QUEUE_NEXT;
        // Volume category
        if (lower.contains("bass boost"))                             return DawIcon.BASS_BOOST;
        if (lower.contains("treble"))                                 return DawIcon.TREBLE_BOOST;
        if (lower.contains("loudness"))                               return DawIcon.LOUDNESS;
        if (lower.contains("volume off") || lower.contains("silent")) return DawIcon.VOLUME_OFF;
        return pluginTypeIcon(desc.type());
    }
}
