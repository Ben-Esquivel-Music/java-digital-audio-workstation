package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomPreset;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Setup panel for configuring room dimensions and wall material
 * for the Sound Wave Telemetry visualization.
 *
 * <p>Provides a {@link ComboBox} of {@link RoomPreset} values that
 * auto-fill the width, length, height, and wall material fields when
 * selected. Users may also enter custom dimensions manually. All
 * numeric inputs are validated to be positive values.</p>
 *
 * <p>Extends {@link ScrollPane} with {@code fitToWidth} enabled so the
 * panel works at any window size. Uses inline dark-theme styles
 * consistent with {@link SettingsDialog} and
 * {@link InputPortSelectionDialog}.</p>
 */
public final class TelemetrySetupPanel extends ScrollPane {

    private static final String BACKGROUND_STYLE =
            "-fx-background-color: #1a1a2e; -fx-background: #1a1a2e;";
    private static final String HEADER_STYLE =
            "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #e0e0e0;";
    private static final String LABEL_STYLE =
            "-fx-text-fill: #b0b0b0; -fx-font-size: 13px;";
    private static final String FIELD_STYLE =
            "-fx-background-color: #2a2a4a; -fx-text-fill: #e0e0e0; "
                    + "-fx-border-color: #3a3a6a; -fx-border-radius: 3; "
                    + "-fx-background-radius: 3; -fx-prompt-text-fill: #606080;";
    private static final String COMBO_STYLE =
            "-fx-background-color: #2a2a4a; -fx-mark-color: #b0b0b0;";
    private static final String ERROR_STYLE =
            "-fx-text-fill: #ff5252; -fx-font-size: 12px;";
    private static final String SECTION_LABEL_STYLE =
            "-fx-text-fill: #9c27b0; -fx-font-size: 13px; -fx-font-weight: bold;";
    private static final String SEPARATOR_STYLE =
            "-fx-background-color: #3a3a6a;";
    private static final String BUTTON_STYLE =
            "-fx-background-color: #3a3a6a; -fx-text-fill: #e0e0e0; "
                    + "-fx-border-color: #5a5a8a; -fx-border-radius: 3; "
                    + "-fx-background-radius: 3; -fx-cursor: hand;";

    static final double DEFAULT_POWER_DB = 85.0;

    private final ComboBox<RoomPreset> presetCombo;
    private final TextField widthField;
    private final TextField lengthField;
    private final TextField heightField;
    private final ComboBox<WallMaterial> wallMaterialCombo;
    private final Label errorLabel;

    private final TextField sourceNameField;
    private final TextField sourceXField;
    private final TextField sourceYField;
    private final TextField sourceZField;
    private final Button addSourceButton;
    private final Button removeSourceButton;
    private final ListView<SoundSource> sourceListView;
    private final ObservableList<SoundSource> soundSources;
    private final Label sourceErrorLabel;

    /**
     * Creates a new telemetry setup panel with sensible defaults.
     */
    public TelemetrySetupPanel() {
        setFitToWidth(true);
        setStyle(BACKGROUND_STYLE
                + " -fx-background-insets: 0; -fx-padding: 0;");
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);

        // ── Preset combo ─────────────────────────────────────────────
        presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll(RoomPreset.values());
        presetCombo.setStyle(COMBO_STYLE);
        presetCombo.setMaxWidth(Double.MAX_VALUE);
        presetCombo.setPromptText("Select a room preset…");
        presetCombo.setCellFactory(list -> new PresetCell());
        presetCombo.setButtonCell(new PresetCell());

        // ── Dimension fields ─────────────────────────────────────────
        RoomPreset defaultPreset = RoomPreset.STUDIO;
        RoomDimensions defaultDimensions = defaultPreset.dimensions();

        widthField = createNumericField(String.valueOf(defaultDimensions.width()));
        lengthField = createNumericField(String.valueOf(defaultDimensions.length()));
        heightField = createNumericField(String.valueOf(defaultDimensions.height()));

        // ── Wall material combo ──────────────────────────────────────
        wallMaterialCombo = new ComboBox<>();
        wallMaterialCombo.getItems().addAll(WallMaterial.values());
        wallMaterialCombo.setStyle(COMBO_STYLE);
        wallMaterialCombo.setMaxWidth(Double.MAX_VALUE);
        wallMaterialCombo.setValue(defaultPreset.wallMaterial());
        wallMaterialCombo.setCellFactory(list -> new WallMaterialCell());
        wallMaterialCombo.setButtonCell(new WallMaterialCell());

        // ── Error label ──────────────────────────────────────────────
        errorLabel = new Label();
        errorLabel.setStyle(ERROR_STYLE);
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        // ── Sound source fields ──────────────────────────────────────
        sourceNameField = new TextField();
        sourceNameField.setStyle(FIELD_STYLE);
        sourceNameField.setPromptText("Source name (e.g. Guitar)");
        sourceNameField.setPrefColumnCount(12);

        sourceXField = createNumericField("0.0");
        sourceYField = createNumericField("0.0");
        sourceZField = createNumericField("0.0");

        addSourceButton = new Button("+ Add Source");
        addSourceButton.setStyle(BUTTON_STYLE);
        addSourceButton.setOnAction(event -> addSource());

        removeSourceButton = new Button("- Remove");
        removeSourceButton.setStyle(BUTTON_STYLE);
        removeSourceButton.setOnAction(event -> removeSelectedSource());

        soundSources = FXCollections.observableArrayList();
        sourceListView = new ListView<>(soundSources);
        sourceListView.setPrefHeight(120);
        sourceListView.setStyle("-fx-background-color: #2a2a4a; -fx-border-color: #3a3a6a;");
        sourceListView.setCellFactory(list -> new SoundSourceCell());

        sourceErrorLabel = new Label();
        sourceErrorLabel.setStyle(ERROR_STYLE);
        sourceErrorLabel.setWrapText(true);
        sourceErrorLabel.setVisible(false);
        sourceErrorLabel.setManaged(false);

        // ── Auto-fill on preset selection ────────────────────────────
        presetCombo.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                RoomDimensions dimensions = newValue.dimensions();
                widthField.setText(String.valueOf(dimensions.width()));
                lengthField.setText(String.valueOf(dimensions.length()));
                heightField.setText(String.valueOf(dimensions.height()));
                wallMaterialCombo.setValue(newValue.wallMaterial());
                validateInputs();
            }
        });

        // ── Validate on text change ──────────────────────────────────
        widthField.textProperty().addListener((observable, oldValue, newValue) -> validateInputs());
        lengthField.textProperty().addListener((observable, oldValue, newValue) -> validateInputs());
        heightField.textProperty().addListener((observable, oldValue, newValue) -> validateInputs());

        // ── Build layout ─────────────────────────────────────────────
        VBox content = new VBox(12);
        content.setPadding(new Insets(20));
        content.setStyle(BACKGROUND_STYLE);

        Label header = new Label("🎙  Room Configuration");
        header.setStyle(HEADER_STYLE);

        Separator headerSep = new Separator();
        headerSep.setStyle(SEPARATOR_STYLE);

        Label presetSectionLabel = new Label("Room Preset");
        presetSectionLabel.setStyle(SECTION_LABEL_STYLE);

        Label dimensionsSectionLabel = new Label("Room Dimensions");
        dimensionsSectionLabel.setStyle(SECTION_LABEL_STYLE);

        GridPane dimensionsGrid = createDimensionsGrid();

        Label materialSectionLabel = new Label("Wall Material");
        materialSectionLabel.setStyle(SECTION_LABEL_STYLE);

        Label sourceSectionLabel = new Label("Sound Sources");
        sourceSectionLabel.setStyle(SECTION_LABEL_STYLE);

        GridPane sourceGrid = createSourceGrid();

        HBox sourceButtons = new HBox(8, addSourceButton, removeSourceButton);

        content.getChildren().addAll(
                header,
                headerSep,
                presetSectionLabel,
                presetCombo,
                new Separator() {{ setStyle(SEPARATOR_STYLE); }},
                dimensionsSectionLabel,
                dimensionsGrid,
                new Separator() {{ setStyle(SEPARATOR_STYLE); }},
                materialSectionLabel,
                wallMaterialCombo,
                errorLabel,
                new Separator() {{ setStyle(SEPARATOR_STYLE); }},
                sourceSectionLabel,
                sourceGrid,
                sourceButtons,
                sourceErrorLabel,
                sourceListView
        );

        setContent(content);
    }

    // ── Public accessors ─────────────────────────────────────────────

    /**
     * Returns the room preset combo box.
     *
     * @return the preset combo box
     */
    public ComboBox<RoomPreset> getPresetCombo() {
        return presetCombo;
    }

    /**
     * Returns the width text field.
     *
     * @return the width field
     */
    public TextField getWidthField() {
        return widthField;
    }

    /**
     * Returns the length text field.
     *
     * @return the length field
     */
    public TextField getLengthField() {
        return lengthField;
    }

    /**
     * Returns the height text field.
     *
     * @return the height field
     */
    public TextField getHeightField() {
        return heightField;
    }

    /**
     * Returns the wall material combo box.
     *
     * @return the wall material combo box
     */
    public ComboBox<WallMaterial> getWallMaterialCombo() {
        return wallMaterialCombo;
    }

    /**
     * Returns the error label used for validation messages.
     *
     * @return the error label
     */
    public Label getErrorLabel() {
        return errorLabel;
    }

    /**
     * Returns the source name text field.
     *
     * @return the source name field
     */
    public TextField getSourceNameField() {
        return sourceNameField;
    }

    /**
     * Returns the source X position text field.
     *
     * @return the source X field
     */
    public TextField getSourceXField() {
        return sourceXField;
    }

    /**
     * Returns the source Y position text field.
     *
     * @return the source Y field
     */
    public TextField getSourceYField() {
        return sourceYField;
    }

    /**
     * Returns the source Z position text field.
     *
     * @return the source Z field
     */
    public TextField getSourceZField() {
        return sourceZField;
    }

    /**
     * Returns the add source button.
     *
     * @return the add source button
     */
    public Button getAddSourceButton() {
        return addSourceButton;
    }

    /**
     * Returns the remove source button.
     *
     * @return the remove source button
     */
    public Button getRemoveSourceButton() {
        return removeSourceButton;
    }

    /**
     * Returns the list view displaying configured sound sources.
     *
     * @return the source list view
     */
    public ListView<SoundSource> getSourceListView() {
        return sourceListView;
    }

    /**
     * Returns the observable list of sound sources.
     *
     * @return the sound sources list
     */
    public ObservableList<SoundSource> getSoundSources() {
        return soundSources;
    }

    /**
     * Returns the error label used for source validation messages.
     *
     * @return the source error label
     */
    public Label getSourceErrorLabel() {
        return sourceErrorLabel;
    }

    /**
     * Returns the currently configured room dimensions, or {@code null}
     * if any input is invalid.
     *
     * @return the room dimensions, or {@code null} if validation fails
     */
    public RoomDimensions getRoomDimensions() {
        Double width = parsePositiveDouble(widthField.getText());
        Double length = parsePositiveDouble(lengthField.getText());
        Double height = parsePositiveDouble(heightField.getText());
        if (width == null || length == null || height == null) {
            return null;
        }
        return new RoomDimensions(width, length, height);
    }

    /**
     * Returns the currently selected wall material.
     *
     * @return the selected wall material, or {@code null} if none selected
     */
    public WallMaterial getSelectedWallMaterial() {
        return wallMaterialCombo.getValue();
    }

    // ── Validation ───────────────────────────────────────────────────

    /**
     * Validates all numeric input fields and updates the error label.
     *
     * @return {@code true} if all inputs are valid
     */
    boolean validateInputs() {
        StringBuilder errors = new StringBuilder();

        if (parsePositiveDouble(widthField.getText()) == null) {
            errors.append("Width must be a positive number. ");
        }
        if (parsePositiveDouble(lengthField.getText()) == null) {
            errors.append("Length must be a positive number. ");
        }
        if (parsePositiveDouble(heightField.getText()) == null) {
            errors.append("Height must be a positive number. ");
        }

        if (errors.isEmpty()) {
            errorLabel.setText("");
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
            return true;
        } else {
            errorLabel.setText(errors.toString().trim());
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
            return false;
        }
    }

    // ── Source management ───────────────────────────────────────────

    /**
     * Validates source inputs and adds a new sound source to the list.
     * Displays an error message if validation fails.
     */
    void addSource() {
        StringBuilder errors = new StringBuilder();

        String name = sourceNameField.getText();
        if (name == null || name.isBlank()) {
            errors.append("Source name is required. ");
        }

        Double xVal = parseNonNegativeDouble(sourceXField.getText());
        if (xVal == null) {
            errors.append("X must be a non-negative number. ");
        }

        Double yVal = parseNonNegativeDouble(sourceYField.getText());
        if (yVal == null) {
            errors.append("Y must be a non-negative number. ");
        }

        Double zVal = parseNonNegativeDouble(sourceZField.getText());
        if (zVal == null) {
            errors.append("Z must be a non-negative number. ");
        }

        if (!errors.isEmpty()) {
            sourceErrorLabel.setText(errors.toString().trim());
            sourceErrorLabel.setVisible(true);
            sourceErrorLabel.setManaged(true);
            return;
        }

        Position3D position = new Position3D(xVal, yVal, zVal);
        SoundSource source = new SoundSource(name.trim(), position, DEFAULT_POWER_DB);
        soundSources.add(source);

        sourceNameField.clear();
        sourceXField.setText("0.0");
        sourceYField.setText("0.0");
        sourceZField.setText("0.0");
        sourceErrorLabel.setText("");
        sourceErrorLabel.setVisible(false);
        sourceErrorLabel.setManaged(false);
    }

    /**
     * Removes the currently selected sound source from the list.
     */
    void removeSelectedSource() {
        SoundSource selected = sourceListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            soundSources.remove(selected);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private TextField createNumericField(String defaultValue) {
        TextField field = new TextField(defaultValue);
        field.setStyle(FIELD_STYLE);
        field.setPrefColumnCount(8);
        return field;
    }

    private GridPane createDimensionsGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);

        Label widthLabel = new Label("Width (m):");
        widthLabel.setStyle(LABEL_STYLE);
        Label lengthLabel = new Label("Length (m):");
        lengthLabel.setStyle(LABEL_STYLE);
        Label heightLabel = new Label("Height (m):");
        heightLabel.setStyle(LABEL_STYLE);

        grid.add(widthLabel, 0, 0);
        grid.add(widthField, 1, 0);
        grid.add(lengthLabel, 0, 1);
        grid.add(lengthField, 1, 1);
        grid.add(heightLabel, 0, 2);
        grid.add(heightField, 1, 2);

        return grid;
    }

    private GridPane createSourceGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);

        Label nameLabel = new Label("Name:");
        nameLabel.setStyle(LABEL_STYLE);
        Label xLabel = new Label("X (m):");
        xLabel.setStyle(LABEL_STYLE);
        Label yLabel = new Label("Y (m):");
        yLabel.setStyle(LABEL_STYLE);
        Label zLabel = new Label("Z (m):");
        zLabel.setStyle(LABEL_STYLE);

        grid.add(nameLabel, 0, 0);
        grid.add(sourceNameField, 1, 0);
        grid.add(xLabel, 0, 1);
        grid.add(sourceXField, 1, 1);
        grid.add(yLabel, 0, 2);
        grid.add(sourceYField, 1, 2);
        grid.add(zLabel, 0, 3);
        grid.add(sourceZField, 1, 3);

        return grid;
    }

    private static Double parsePositiveDouble(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            if (value > 0) {
                return value;
            }
            return null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static Double parseNonNegativeDouble(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            if (value >= 0) {
                return value;
            }
            return null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static String formatPresetName(RoomPreset preset) {
        RoomDimensions dimensions = preset.dimensions();
        String name = formatEnumName(preset.name());
        return String.format("%s  (%.1f × %.1f × %.1f m)",
                name, dimensions.width(), dimensions.length(), dimensions.height());
    }

    static String formatMaterialName(WallMaterial material) {
        String name = formatEnumName(material.name());
        return String.format("%s  (absorption: %.2f)",
                name, material.absorptionCoefficient());
    }

    private static String formatEnumName(String enumName) {
        String[] parts = enumName.split("_");
        StringBuilder formatted = new StringBuilder();
        for (String part : parts) {
            if (!formatted.isEmpty()) {
                formatted.append(" ");
            }
            formatted.append(part.charAt(0));
            formatted.append(part.substring(1).toLowerCase());
        }
        return formatted.toString();
    }

    // ── Custom cells ─────────────────────────────────────────────────

    private static final class PresetCell extends ListCell<RoomPreset> {
        @Override
        protected void updateItem(RoomPreset preset, boolean empty) {
            super.updateItem(preset, empty);
            if (empty || preset == null) {
                setText(null);
            } else {
                setText(formatPresetName(preset));
            }
        }
    }

    private static final class WallMaterialCell extends ListCell<WallMaterial> {
        @Override
        protected void updateItem(WallMaterial material, boolean empty) {
            super.updateItem(material, empty);
            if (empty || material == null) {
                setText(null);
            } else {
                setText(formatMaterialName(material));
            }
        }
    }

    private static final class SoundSourceCell extends ListCell<SoundSource> {
        @Override
        protected void updateItem(SoundSource source, boolean empty) {
            super.updateItem(source, empty);
            if (empty || source == null) {
                setText(null);
            } else {
                Position3D pos = source.position();
                setText(String.format("%s  (%.1f, %.1f, %.1f)",
                        source.name(), pos.x(), pos.y(), pos.z()));
            }
        }
    }
}
