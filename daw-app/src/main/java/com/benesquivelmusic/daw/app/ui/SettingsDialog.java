package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Modal dialog for configuring application settings.
 *
 * <p>Provides tabbed sections for Audio, Project, Appearance,
 * Key Bindings, and Plugins configuration. Settings are persisted
 * through the supplied {@link SettingsModel} using the Java
 * {@link java.util.prefs.Preferences} API.</p>
 *
 * <p>An optional {@link SettingsChangeListener} can be registered to receive
 * a callback after settings are applied, allowing the caller to propagate
 * changes to the running audio engine, transport, UI, or other subsystems.</p>
 *
 * <p>Uses the {@link DawIcon} icon pack for all tab and header graphics.</p>
 */
public final class SettingsDialog extends Dialog<Void> {

    /**
     * Callback interface invoked after settings are applied.
     *
     * <p>Implementations should propagate the updated settings to the
     * appropriate subsystems (e.g., audio engine, transport, UI scale).</p>
     */
    @FunctionalInterface
    public interface SettingsChangeListener {

        /**
         * Called after all settings have been written to the {@link SettingsModel}.
         *
         * @param model the updated settings model
         */
        void onSettingsChanged(SettingsModel model);
    }

    private static final double HEADER_ICON_SIZE = 18;
    private static final double TAB_ICON_SIZE = 14;

    private final SettingsModel model;

    // ── Callback ─────────────────────────────────────────────────────────────
    private SettingsChangeListener settingsChangeListener;
    private AudioEngineController audioEngineController;
    private Button openAudioDeviceDialogButton;

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

    // ── Key Bindings tab state ───────────────────────────────────────────────
    private final Map<DawAction, TextField> keyBindingFields = new EnumMap<>(DawAction.class);
    private final Map<DawAction, KeyCombination> pendingBindings = new EnumMap<>(DawAction.class);

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

        DarkThemeHelper.applyTo(this);

        setResultConverter(button -> {
            if (button == ButtonType.APPLY) {
                applySettings();
            }
            return null;
        });
    }

    /**
     * Sets the listener to be notified after settings are applied.
     *
     * @param listener the settings change listener, or {@code null} to clear
     */
    public void setSettingsChangeListener(SettingsChangeListener listener) {
        this.settingsChangeListener = listener;
    }

    /**
     * Attaches an {@link AudioEngineController} used by the "Audio Device
     * Settings" button to open the dedicated {@link AudioSettingsDialog}.
     * When {@code null} the button is disabled.
     *
     * @param controller the controller, or {@code null} to detach
     */
    public void setAudioEngineController(AudioEngineController controller) {
        this.audioEngineController = controller;
        if (openAudioDeviceDialogButton != null) {
            openAudioDeviceDialogButton.setDisable(controller == null);
        }
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

        openAudioDeviceDialogButton = new Button("Audio Device Settings\u2026");
        openAudioDeviceDialogButton.setGraphic(IconNode.of(DawIcon.HEADPHONES, 12));
        openAudioDeviceDialogButton.setDisable(audioEngineController == null);
        openAudioDeviceDialogButton.setOnAction(_ -> openAudioDeviceDialog());
        grid.add(openAudioDeviceDialogButton, 0, 5, 2, 1);

        Label restartHint = new Label("Changes to audio settings may require a restart.");
        restartHint.setGraphic(IconNode.of(DawIcon.WARNING, 14));
        restartHint.setStyle("-fx-text-fill: #ff9100; -fx-font-size: 10px;");
        grid.add(restartHint, 0, 6, 2, 1);

        return grid;
    }

    private void openAudioDeviceDialog() {
        if (audioEngineController == null) {
            return;
        }
        AudioSettingsDialog dialog = new AudioSettingsDialog(model, audioEngineController);
        dialog.initOwner(getDialogPane().getScene() != null
                ? getDialogPane().getScene().getWindow() : null);
        dialog.showAndWait();
        // Refresh combo values in case the audio dialog persisted new defaults
        sampleRateCombo.setValue(String.valueOf((int) model.getSampleRate()));
        bitDepthCombo.setValue(String.valueOf(model.getBitDepth()));
        bufferSizeCombo.setValue(String.valueOf(model.getBufferSize()));
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

        Label instructions = new Label(
                "Click a shortcut field and press a new key combination to reassign. "
                        + "Press Escape in the field to clear the binding.");
        instructions.setGraphic(IconNode.of(DawIcon.INFO, 14));
        instructions.setStyle("-fx-text-fill: #808080; -fx-font-size: 10px;");
        instructions.setWrapText(true);

        // Reset to Defaults button
        Button resetButton = new Button("Reset to Defaults");
        resetButton.setOnAction(event -> resetKeyBindingsToDefaults());

        HBox buttonBar = new HBox(resetButton);
        buttonBar.setPadding(new Insets(4, 0, 4, 0));

        VBox contentBox = new VBox(8);

        KeyBindingManager keyBindingManager = model.getKeyBindingManager();

        // Load current bindings into pending map
        for (DawAction action : DawAction.values()) {
            Optional<KeyCombination> current = keyBindingManager.getBinding(action);
            current.ifPresent(kc -> pendingBindings.put(action, kc));
        }

        // Build rows grouped by category
        DawAction.Category currentCategory = null;
        GridPane grid = null;
        int row = 0;

        for (DawAction action : DawAction.values()) {
            if (action.category() != currentCategory) {
                currentCategory = action.category();
                if (grid != null) {
                    contentBox.getChildren().add(grid);
                }
                Label categoryLabel = new Label(currentCategory.displayName());
                categoryLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
                contentBox.getChildren().add(categoryLabel);
                contentBox.getChildren().add(new Separator());
                grid = createGrid();
                row = 0;
            }

            Label nameLabel = new Label(action.displayName() + ":");
            TextField field = new TextField();
            field.setEditable(false);
            field.setPrefWidth(180);

            KeyCombination currentBinding = pendingBindings.get(action);
            if (currentBinding != null) {
                field.setText(currentBinding.getDisplayText());
            }

            field.setOnKeyPressed(event -> handleKeyBindingCapture(action, field, event));

            keyBindingFields.put(action, field);

            grid.add(nameLabel, 0, row);
            grid.add(field, 1, row);
            row++;
        }
        if (grid != null) {
            contentBox.getChildren().add(grid);
        }

        ScrollPane scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        vbox.getChildren().addAll(header, instructions, buttonBar, new Separator(), scrollPane);
        return vbox;
    }

    /**
     * Handles key press events in a key binding capture field.
     * Modifier-only presses are ignored. Escape clears the binding.
     */
    private void handleKeyBindingCapture(DawAction action, TextField field, KeyEvent event) {
        event.consume();

        // Ignore modifier-only presses
        switch (event.getCode()) {
            case SHIFT, CONTROL, ALT, META, COMMAND, WINDOWS, UNDEFINED:
                return;
            default:
                break;
        }

        // Escape in the field clears the binding
        if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE
                && !event.isControlDown() && !event.isShiftDown()
                && !event.isAltDown() && !event.isMetaDown()) {
            pendingBindings.remove(action);
            field.setText("");
            field.setStyle("");
            return;
        }

        // Build the key combination from the event
        java.util.List<KeyCombination.Modifier> modifiers = new java.util.ArrayList<>();
        if (event.isShortcutDown()) {
            modifiers.add(KeyCombination.SHORTCUT_DOWN);
        }
        if (event.isShiftDown()) {
            modifiers.add(KeyCombination.SHIFT_DOWN);
        }
        if (event.isAltDown()) {
            modifiers.add(KeyCombination.ALT_DOWN);
        }

        KeyCombination newBinding = new javafx.scene.input.KeyCodeCombination(
                event.getCode(),
                modifiers.toArray(new KeyCombination.Modifier[0]));

        // Check for conflicts within pending bindings
        String conflictName = null;
        for (Map.Entry<DawAction, KeyCombination> entry : pendingBindings.entrySet()) {
            if (entry.getKey() != action
                    && entry.getValue().getName().equals(newBinding.getName())) {
                conflictName = entry.getKey().displayName();
                break;
            }
        }

        if (conflictName != null) {
            field.setText(newBinding.getDisplayText() + " (conflict: " + conflictName + ")");
            field.setStyle("-fx-text-fill: red;");
        } else {
            pendingBindings.put(action, newBinding);
            field.setText(newBinding.getDisplayText());
            field.setStyle("");
        }
    }

    private void resetKeyBindingsToDefaults() {
        pendingBindings.clear();
        for (DawAction action : DawAction.values()) {
            KeyCombination defaultBinding = action.defaultBinding();
            TextField field = keyBindingFields.get(action);
            if (defaultBinding != null) {
                pendingBindings.put(action, defaultBinding);
                if (field != null) {
                    field.setText(defaultBinding.getDisplayText());
                    field.setStyle("");
                }
            } else if (field != null) {
                field.setText("");
                field.setStyle("");
            }
        }
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

        // Key Bindings — apply pending bindings that have no conflicts
        applyKeyBindings();

        // Notify listener of applied changes
        if (settingsChangeListener != null) {
            settingsChangeListener.onSettingsChanged(model);
        }
    }

    /**
     * Persists the pending key binding changes to the {@link KeyBindingManager}.
     * Only non-conflicting bindings are applied.
     */
    private void applyKeyBindings() {
        KeyBindingManager keyBindingManager = model.getKeyBindingManager();

        // First clear all bindings so we can re-assign without transient conflicts
        for (DawAction action : DawAction.values()) {
            keyBindingManager.setBinding(action, null);
        }
        // Then apply pending bindings
        for (DawAction action : DawAction.values()) {
            KeyCombination pending = pendingBindings.get(action);
            if (pending != null) {
                keyBindingManager.setBinding(action, pending);
            }
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
