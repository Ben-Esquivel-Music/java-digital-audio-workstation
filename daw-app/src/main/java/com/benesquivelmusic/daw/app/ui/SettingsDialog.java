package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Modal dialog for configuring application settings.
 *
 * <p>Provides tabbed sections for Audio, Project, Appearance,
 * Key Bindings, and Plugins configuration. Settings are persisted
 * through the supplied {@link SettingsModel} using the Java
 * {@link java.util.prefs.Preferences} API.</p>
 *
 * <p>Uses the {@link DawIcon} icon pack for all tab and header graphics.</p>
 */
public final class SettingsDialog extends Dialog<Void> {

    private static final double HEADER_ICON_SIZE = 18;
    private static final double TAB_ICON_SIZE = 14;

    private final SettingsModel model;

    // ── Audio tab controls ───────────────────────────────────────────────────
    private final ComboBox<String> sampleRateCombo;
    private final ComboBox<String> bitDepthCombo;
    private final ComboBox<String> bufferSizeCombo;

    // ── Project tab controls ─────────────────────────────────────────────────
    private final ComboBox<String> autoSaveCombo;
    private final TextField tempoField;

    // ── Appearance tab controls ──────────────────────────────────────────────
    private final Slider uiScaleSlider;
    private final Label uiScaleValueLabel;

    // ── Plugins tab controls ─────────────────────────────────────────────────
    private final TextField pluginScanPathsField;

    /**
     * Creates a new settings dialog backed by the given model.
     *
     * @param model the settings model to read from and write to
     */
    public SettingsDialog(SettingsModel model) {
        this.model = model;

        setTitle("Settings");
        setHeaderText("Application Preferences");
        setGraphic(IconNode.of(DawIcon.SETTINGS, 24));

        // ── Audio tab ────────────────────────────────────────────────────────
        sampleRateCombo = new ComboBox<>();
        sampleRateCombo.getItems().addAll("44100", "48000", "88200", "96000", "176400", "192000");
        sampleRateCombo.setValue(String.valueOf((int) model.getSampleRate()));

        bitDepthCombo = new ComboBox<>();
        bitDepthCombo.getItems().addAll("16", "24", "32");
        bitDepthCombo.setValue(String.valueOf(model.getBitDepth()));

        bufferSizeCombo = new ComboBox<>();
        bufferSizeCombo.getItems().addAll("64", "128", "256", "512", "1024", "2048");
        bufferSizeCombo.setValue(String.valueOf(model.getBufferSize()));

        Tab audioTab = new Tab("Audio", buildAudioPane());
        audioTab.setGraphic(IconNode.of(DawIcon.HEADPHONES, TAB_ICON_SIZE));
        audioTab.setClosable(false);

        // ── Project tab ──────────────────────────────────────────────────────
        autoSaveCombo = new ComboBox<>();
        autoSaveCombo.getItems().addAll("30", "60", "120", "300", "600");
        autoSaveCombo.setValue(String.valueOf(model.getAutoSaveIntervalSeconds()));

        tempoField = new TextField(String.format("%.1f", model.getDefaultTempo()));

        Tab projectTab = new Tab("Project", buildProjectPane());
        projectTab.setGraphic(IconNode.of(DawIcon.FOLDER, TAB_ICON_SIZE));
        projectTab.setClosable(false);

        // ── Appearance tab ───────────────────────────────────────────────────
        uiScaleSlider = new Slider(0.5, 3.0, model.getUiScale());
        uiScaleSlider.setMajorTickUnit(0.5);
        uiScaleSlider.setMinorTickCount(4);
        uiScaleSlider.setShowTickLabels(true);
        uiScaleSlider.setShowTickMarks(true);

        uiScaleValueLabel = new Label(String.format("%.1fx", model.getUiScale()));
        uiScaleSlider.valueProperty().addListener((_, _, newVal) ->
                uiScaleValueLabel.setText(String.format("%.1fx", newVal.doubleValue())));

        Tab appearanceTab = new Tab("Appearance", buildAppearancePane());
        appearanceTab.setGraphic(IconNode.of(DawIcon.MONITOR, TAB_ICON_SIZE));
        appearanceTab.setClosable(false);

        // ── Key Bindings tab ─────────────────────────────────────────────────
        Tab keyBindingsTab = new Tab("Key Bindings", buildKeyBindingsPane());
        keyBindingsTab.setGraphic(IconNode.of(DawIcon.KEYBOARD, TAB_ICON_SIZE));
        keyBindingsTab.setClosable(false);

        // ── Plugins tab ──────────────────────────────────────────────────────
        pluginScanPathsField = new TextField(model.getPluginScanPaths());
        pluginScanPathsField.setPromptText("/path/to/plugins;/another/path");

        Tab pluginsTab = new Tab("Plugins", buildPluginsPane());
        pluginsTab.setGraphic(IconNode.of(DawIcon.EQUALIZER, TAB_ICON_SIZE));
        pluginsTab.setClosable(false);

        // ── Assemble ─────────────────────────────────────────────────────────
        TabPane tabPane = new TabPane(audioTab, projectTab, appearanceTab, keyBindingsTab, pluginsTab);
        tabPane.setPrefWidth(520);
        tabPane.setPrefHeight(340);

        getDialogPane().setContent(tabPane);
        getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);

        setResultConverter(button -> {
            if (button == ButtonType.APPLY) {
                applySettings();
            }
            return null;
        });
    }

    // ── Tab builders ─────────────────────────────────────────────────────────

    private Node buildAudioPane() {
        GridPane grid = createGrid();

        Label header = new Label("Audio Settings");
        header.setGraphic(IconNode.of(DawIcon.HEADPHONES, HEADER_ICON_SIZE));
        header.setStyle("-fx-font-weight: bold;");

        grid.add(header, 0, 0, 2, 1);
        grid.add(new Separator(), 0, 1, 2, 1);
        grid.add(new Label("Sample Rate (Hz):"), 0, 2);
        grid.add(sampleRateCombo, 1, 2);
        grid.add(new Label("Bit Depth:"), 0, 3);
        grid.add(bitDepthCombo, 1, 3);
        grid.add(new Label("Buffer Size (frames):"), 0, 4);
        grid.add(bufferSizeCombo, 1, 4);

        Label restartHint = new Label("Changes to audio settings may require a restart.");
        restartHint.setGraphic(IconNode.of(DawIcon.WARNING, 14));
        restartHint.setStyle("-fx-text-fill: #ff9100; -fx-font-size: 10px;");
        grid.add(restartHint, 0, 6, 2, 1);

        return grid;
    }

    private Node buildProjectPane() {
        GridPane grid = createGrid();

        Label header = new Label("Project Defaults");
        header.setGraphic(IconNode.of(DawIcon.FOLDER, HEADER_ICON_SIZE));
        header.setStyle("-fx-font-weight: bold;");

        grid.add(header, 0, 0, 2, 1);
        grid.add(new Separator(), 0, 1, 2, 1);
        grid.add(new Label("Auto-Save Interval (seconds):"), 0, 2);
        grid.add(autoSaveCombo, 1, 2);
        grid.add(new Label("Default Tempo (BPM):"), 0, 3);
        grid.add(tempoField, 1, 3);

        return grid;
    }

    private Node buildAppearancePane() {
        GridPane grid = createGrid();

        Label header = new Label("Appearance");
        header.setGraphic(IconNode.of(DawIcon.MONITOR, HEADER_ICON_SIZE));
        header.setStyle("-fx-font-weight: bold;");

        grid.add(header, 0, 0, 2, 1);
        grid.add(new Separator(), 0, 1, 2, 1);
        grid.add(new Label("UI Scale:"), 0, 2);
        grid.add(uiScaleSlider, 1, 2);
        grid.add(uiScaleValueLabel, 2, 2);

        Label themeNote = new Label("Theme customization coming in a future release.");
        themeNote.setGraphic(IconNode.of(DawIcon.INFO, 14));
        themeNote.setStyle("-fx-text-fill: #808080; -fx-font-size: 10px;");
        grid.add(themeNote, 0, 4, 3, 1);

        return grid;
    }

    private Node buildKeyBindingsPane() {
        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(16));

        Label header = new Label("Key Bindings");
        header.setGraphic(IconNode.of(DawIcon.KEYBOARD, HEADER_ICON_SIZE));
        header.setStyle("-fx-font-weight: bold;");

        GridPane grid = createGrid();
        grid.add(new Label("Play / Pause:"), 0, 0);
        grid.add(new Label("Space"), 1, 0);
        grid.add(new Label("Stop:"), 0, 1);
        grid.add(new Label("Escape"), 1, 1);
        grid.add(new Label("Record:"), 0, 2);
        grid.add(new Label("R"), 1, 2);
        grid.add(new Label("Undo:"), 0, 3);
        grid.add(new Label("Ctrl+Z"), 1, 3);
        grid.add(new Label("Redo:"), 0, 4);
        grid.add(new Label("Ctrl+Shift+Z"), 1, 4);
        grid.add(new Label("Save:"), 0, 5);
        grid.add(new Label("Ctrl+S"), 1, 5);
        grid.add(new Label("Add Audio Track:"), 0, 6);
        grid.add(new Label("Ctrl+Shift+A"), 1, 6);
        grid.add(new Label("Add MIDI Track:"), 0, 7);
        grid.add(new Label("Ctrl+Shift+M"), 1, 7);

        Label customizeNote = new Label("Key binding customization coming in a future release.");
        customizeNote.setGraphic(IconNode.of(DawIcon.INFO, 14));
        customizeNote.setStyle("-fx-text-fill: #808080; -fx-font-size: 10px;");

        vbox.getChildren().addAll(header, new Separator(), grid, customizeNote);
        return vbox;
    }

    private Node buildPluginsPane() {
        GridPane grid = createGrid();

        Label header = new Label("Plugins");
        header.setGraphic(IconNode.of(DawIcon.EQUALIZER, HEADER_ICON_SIZE));
        header.setStyle("-fx-font-weight: bold;");

        grid.add(header, 0, 0, 2, 1);
        grid.add(new Separator(), 0, 1, 2, 1);
        grid.add(new Label("Plugin Scan Paths:"), 0, 2);
        grid.add(pluginScanPathsField, 1, 2);

        Label hint = new Label("Separate multiple paths with semicolons (;).");
        hint.setGraphic(IconNode.of(DawIcon.INFO, 14));
        hint.setStyle("-fx-text-fill: #808080; -fx-font-size: 10px;");
        grid.add(hint, 0, 3, 2, 1);

        return grid;
    }

    // ── Apply ────────────────────────────────────────────────────────────────

    /**
     * Applies the current dialog control values to the settings model.
     */
    void applySettings() {
        // Audio
        String sampleRateText = sampleRateCombo.getValue();
        if (sampleRateText != null && !sampleRateText.isBlank()) {
            model.setSampleRate(Double.parseDouble(sampleRateText));
        }

        String bitDepthText = bitDepthCombo.getValue();
        if (bitDepthText != null && !bitDepthText.isBlank()) {
            model.setBitDepth(Integer.parseInt(bitDepthText));
        }

        String bufferSizeText = bufferSizeCombo.getValue();
        if (bufferSizeText != null && !bufferSizeText.isBlank()) {
            model.setBufferSize(Integer.parseInt(bufferSizeText));
        }

        // Project
        String autoSaveText = autoSaveCombo.getValue();
        if (autoSaveText != null && !autoSaveText.isBlank()) {
            model.setAutoSaveIntervalSeconds(Integer.parseInt(autoSaveText));
        }

        String tempoText = tempoField.getText();
        if (tempoText != null && !tempoText.isBlank()) {
            double tempo = Double.parseDouble(tempoText);
            if (tempo >= 20.0 && tempo <= 999.0) {
                model.setDefaultTempo(tempo);
            }
        }

        // Appearance
        model.setUiScale(uiScaleSlider.getValue());

        // Plugins
        String paths = pluginScanPathsField.getText();
        if (paths != null) {
            model.setPluginScanPaths(paths);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static GridPane createGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));
        return grid;
    }

    /**
     * Returns the settings model backing this dialog (for testing).
     */
    SettingsModel getModel() {
        return model;
    }
}
