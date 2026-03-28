package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.export.AtmosExportResult;
import com.benesquivelmusic.daw.core.spatial.objectbased.AtmosSessionConfig;
import com.benesquivelmusic.daw.core.spatial.objectbased.AtmosSessionValidator;
import com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject;
import com.benesquivelmusic.daw.core.spatial.objectbased.BedChannel;
import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Modal dialog for configuring a Dolby Atmos session and initiating
 * ADM BWF export.
 *
 * <p>Provides tabs for configuring bed channel assignments (mapping tracks
 * to speaker positions) and audio objects (with 3D position metadata). The
 * dialog validates the session configuration against Atmos constraints
 * before allowing export.</p>
 *
 * <p>An optional {@link ExportRequestListener} can be registered to handle
 * the actual export when the user clicks the Export button.</p>
 */
public final class AtmosSessionConfigDialog extends Dialog<AtmosSessionConfig> {

    /**
     * Callback interface invoked when the user requests an ADM BWF export.
     */
    @FunctionalInterface
    public interface ExportRequestListener {

        /**
         * Called when the user clicks the Export button with a valid
         * Atmos session configuration.
         *
         * @param config the validated session configuration
         */
        void onExportRequested(AtmosSessionConfig config);
    }

    private final AtmosSessionConfig config;
    private ExportRequestListener exportRequestListener;

    // ── Bed channel controls ──────────────────────────────────────────────────
    private final VBox bedChannelContainer;
    private final List<BedChannelRow> bedChannelRows = new ArrayList<>();

    // ── Audio object controls ──────────────────────────────────────────────────
    private final VBox objectContainer;
    private final List<AudioObjectRow> audioObjectRows = new ArrayList<>();

    // ── Export settings ───────────────────────────────────────────────────────
    private final ComboBox<String> layoutCombo;
    private final ComboBox<String> sampleRateCombo;
    private final ComboBox<String> bitDepthCombo;

    // ── Validation display ────────────────────────────────────────────────────
    private final Label validationLabel;
    private final Label trackCountLabel;

    /**
     * Creates a new Atmos session configuration dialog.
     */
    public AtmosSessionConfigDialog() {
        this(new AtmosSessionConfig());
    }

    /**
     * Creates a new Atmos session configuration dialog initialized with
     * the given configuration.
     *
     * @param config the initial session configuration
     */
    public AtmosSessionConfigDialog(AtmosSessionConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");

        setTitle("Atmos Session Configuration");
        setHeaderText("Configure Dolby Atmos Session for ADM BWF Export");

        // ── Layout/export settings ───────────────────────────────────────────
        layoutCombo = new ComboBox<>();
        layoutCombo.getItems().addAll("7.1.4", "5.1.4", "5.1", "Stereo");
        layoutCombo.setValue(config.getLayout().name());
        layoutCombo.setOnAction(event -> onLayoutChanged());

        sampleRateCombo = new ComboBox<>();
        sampleRateCombo.getItems().addAll("44100", "48000", "88200", "96000");
        sampleRateCombo.setValue(String.valueOf(config.getSampleRate()));

        bitDepthCombo = new ComboBox<>();
        bitDepthCombo.getItems().addAll("16", "24", "32");
        bitDepthCombo.setValue(String.valueOf(config.getBitDepth()));

        validationLabel = new Label("");
        validationLabel.setWrapText(true);
        validationLabel.setStyle("-fx-text-fill: #808080; -fx-font-size: 11px;");

        trackCountLabel = new Label("Tracks: 0 / " + AtmosSessionValidator.MAX_TOTAL_TRACKS);
        trackCountLabel.setStyle("-fx-font-size: 11px;");

        // ── Bed channels tab ─────────────────────────────────────────────────
        bedChannelContainer = new VBox(4);
        bedChannelContainer.setPadding(new Insets(8));

        // ── Audio objects tab ────────────────────────────────────────────────
        objectContainer = new VBox(4);
        objectContainer.setPadding(new Insets(8));

        // Populate from existing config
        for (BedChannel bed : config.getBedChannels()) {
            addBedChannelRow(bed.trackId(), bed.speakerLabel(), bed.gain());
        }
        for (AudioObject obj : config.getAudioObjects()) {
            addAudioObjectRow(obj.getTrackId(), obj.getMetadata());
        }

        // ── Tabs ─────────────────────────────────────────────────────────────
        Tab settingsTab = new Tab("Settings", buildSettingsPane());
        settingsTab.setClosable(false);

        Tab bedTab = new Tab("Bed Channels", buildBedPane());
        bedTab.setClosable(false);

        Tab objectsTab = new Tab("Audio Objects", buildObjectsPane());
        objectsTab.setClosable(false);

        Tab validationTab = new Tab("Validation", buildValidationPane());
        validationTab.setClosable(false);

        TabPane tabPane = new TabPane(settingsTab, bedTab, objectsTab, validationTab);
        tabPane.setPrefWidth(560);
        tabPane.setPrefHeight(420);

        getDialogPane().setContent(tabPane);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button exportButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        exportButton.setText("Export ADM BWF");
        exportButton.setOnAction(event -> {
            event.consume();
            applyConfigAndExport();
        });

        updateTrackCount();
    }

    /**
     * Sets the listener to be notified when the user requests export.
     *
     * @param listener the export request listener, or {@code null} to clear
     */
    public void setExportRequestListener(ExportRequestListener listener) {
        this.exportRequestListener = listener;
    }

    /** Returns the current session configuration. */
    public AtmosSessionConfig getConfig() {
        return config;
    }

    /** Returns the layout combo box (for testing). */
    ComboBox<String> getLayoutCombo() {
        return layoutCombo;
    }

    /** Returns the sample rate combo box (for testing). */
    ComboBox<String> getSampleRateCombo() {
        return sampleRateCombo;
    }

    /** Returns the bit depth combo box (for testing). */
    ComboBox<String> getBitDepthCombo() {
        return bitDepthCombo;
    }

    /** Returns the validation label (for testing). */
    Label getValidationLabel() {
        return validationLabel;
    }

    /** Returns the track count label (for testing). */
    Label getTrackCountLabel() {
        return trackCountLabel;
    }

    /** Returns the bed channel container (for testing). */
    VBox getBedChannelContainer() {
        return bedChannelContainer;
    }

    /** Returns the object container (for testing). */
    VBox getObjectContainer() {
        return objectContainer;
    }

    // ── Tab builders ─────────────────────────────────────────────────────────

    private Node buildSettingsPane() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        Label header = new Label("Export Settings");
        header.setStyle("-fx-font-weight: bold;");

        grid.add(header, 0, 0, 2, 1);
        grid.add(new Separator(), 0, 1, 2, 1);
        grid.add(new Label("Speaker Layout:"), 0, 2);
        grid.add(layoutCombo, 1, 2);
        grid.add(new Label("Sample Rate (Hz):"), 0, 3);
        grid.add(sampleRateCombo, 1, 3);
        grid.add(new Label("Bit Depth:"), 0, 4);
        grid.add(bitDepthCombo, 1, 4);
        grid.add(new Separator(), 0, 5, 2, 1);
        grid.add(trackCountLabel, 0, 6, 2, 1);

        return grid;
    }

    private Node buildBedPane() {
        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(8));

        Label header = new Label("Bed Channel Assignments");
        header.setStyle("-fx-font-weight: bold;");

        Label info = new Label("Assign tracks to speaker positions in the bed layout.");
        info.setStyle("-fx-text-fill: #808080; -fx-font-size: 10px;");
        info.setWrapText(true);

        Button addButton = new Button("Add Bed Channel");
        addButton.setOnAction(event -> addBedChannelRow("", SpeakerLabel.L, 1.0));

        ScrollPane scrollPane = new ScrollPane(bedChannelContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(280);

        vbox.getChildren().addAll(header, info, addButton, new Separator(), scrollPane);
        return vbox;
    }

    private Node buildObjectsPane() {
        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(8));

        Label header = new Label("Audio Objects");
        header.setStyle("-fx-font-weight: bold;");

        Label info = new Label("Add audio objects with 3D position metadata (max "
                + AtmosSessionValidator.MAX_OBJECTS + " objects).");
        info.setStyle("-fx-text-fill: #808080; -fx-font-size: 10px;");
        info.setWrapText(true);

        Button addButton = new Button("Add Audio Object");
        addButton.setOnAction(event -> addAudioObjectRow("", ObjectMetadata.DEFAULT));

        ScrollPane scrollPane = new ScrollPane(objectContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(280);

        vbox.getChildren().addAll(header, info, addButton, new Separator(), scrollPane);
        return vbox;
    }

    private Node buildValidationPane() {
        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(16));

        Label header = new Label("Session Validation");
        header.setStyle("-fx-font-weight: bold;");

        Button validateButton = new Button("Validate Session");
        validateButton.setOnAction(event -> runValidation());

        vbox.getChildren().addAll(header, new Separator(), validateButton, validationLabel);
        return vbox;
    }

    // ── Bed channel row management ──────────────────────────────────────────

    private void addBedChannelRow(String trackId, SpeakerLabel speaker, double gain) {
        BedChannelRow row = new BedChannelRow(trackId, speaker, gain);
        bedChannelRows.add(row);
        bedChannelContainer.getChildren().add(row.getNode());
        updateTrackCount();
    }

    /** Removes a bed channel row. */
    void removeBedChannelRow(BedChannelRow row) {
        bedChannelRows.remove(row);
        bedChannelContainer.getChildren().remove(row.getNode());
        updateTrackCount();
    }

    // ── Audio object row management ─────────────────────────────────────────

    private void addAudioObjectRow(String trackId, ObjectMetadata metadata) {
        AudioObjectRow row = new AudioObjectRow(trackId, metadata);
        audioObjectRows.add(row);
        objectContainer.getChildren().add(row.getNode());
        updateTrackCount();
    }

    /** Removes an audio object row. */
    void removeAudioObjectRow(AudioObjectRow row) {
        audioObjectRows.remove(row);
        objectContainer.getChildren().remove(row.getNode());
        updateTrackCount();
    }

    // ── Validation and export ───────────────────────────────────────────────

    /** Runs validation and updates the validation label. */
    void runValidation() {
        applyToConfig();
        List<String> errors = config.validate();
        if (errors.isEmpty()) {
            validationLabel.setText("✓ Session configuration is valid.");
            validationLabel.setStyle("-fx-text-fill: #4caf50; -fx-font-size: 11px;");
        } else {
            StringBuilder sb = new StringBuilder("Validation errors:\n");
            for (String error : errors) {
                sb.append("  • ").append(error).append("\n");
            }
            validationLabel.setText(sb.toString());
            validationLabel.setStyle("-fx-text-fill: #f44336; -fx-font-size: 11px;");
        }
    }

    /** Applies dialog state to the config and triggers export. */
    void applyConfigAndExport() {
        applyToConfig();
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Cannot export — validation errors:\n");
            for (String error : errors) {
                sb.append("  • ").append(error).append("\n");
            }
            validationLabel.setText(sb.toString());
            validationLabel.setStyle("-fx-text-fill: #f44336; -fx-font-size: 11px;");
            return;
        }

        if (exportRequestListener != null) {
            exportRequestListener.onExportRequested(config);
        }
        setResult(config);
        close();
    }

    /** Transfers the dialog field values into the config model. */
    void applyToConfig() {
        config.setLayout(resolveLayout(layoutCombo.getValue()));
        config.setSampleRate(Integer.parseInt(sampleRateCombo.getValue()));
        config.setBitDepth(Integer.parseInt(bitDepthCombo.getValue()));

        config.clearBedChannels();
        for (BedChannelRow row : bedChannelRows) {
            String trackId = row.trackIdField.getText().trim();
            if (!trackId.isEmpty()) {
                SpeakerLabel speaker = row.speakerCombo.getValue();
                double gain = parseGain(row.gainField.getText());
                config.addBedChannel(new BedChannel(trackId, speaker, gain));
            }
        }

        config.clearAudioObjects();
        for (AudioObjectRow row : audioObjectRows) {
            String trackId = row.trackIdField.getText().trim();
            if (!trackId.isEmpty()) {
                double x = parseCoord(row.xField.getText());
                double y = parseCoord(row.yField.getText());
                double z = parseCoord(row.zField.getText());
                double size = parseGain(row.sizeField.getText());
                double gain = parseGain(row.gainField.getText());
                ObjectMetadata meta = new ObjectMetadata(x, y, z, size, gain);
                config.addAudioObject(new AudioObject(trackId, meta));
            }
        }
    }

    private void updateTrackCount() {
        int total = bedChannelRows.size() + audioObjectRows.size();
        trackCountLabel.setText("Tracks: " + total + " / " + AtmosSessionValidator.MAX_TOTAL_TRACKS);
    }

    private void onLayoutChanged() {
        SpeakerLayout layout = resolveLayout(layoutCombo.getValue());
        config.setLayout(layout);
    }

    private static SpeakerLayout resolveLayout(String name) {
        return switch (name) {
            case "7.1.4" -> SpeakerLayout.LAYOUT_7_1_4;
            case "5.1.4" -> SpeakerLayout.LAYOUT_5_1_4;
            case "5.1" -> SpeakerLayout.LAYOUT_5_1;
            case "Stereo" -> SpeakerLayout.LAYOUT_STEREO;
            default -> SpeakerLayout.LAYOUT_7_1_4;
        };
    }

    private static double parseGain(String text) {
        try {
            double value = Double.parseDouble(text.trim());
            return Math.max(0.0, Math.min(1.0, value));
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    private static double parseCoord(String text) {
        try {
            double value = Double.parseDouble(text.trim());
            return Math.max(-1.0, Math.min(1.0, value));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // ── Inner row classes ───────────────────────────────────────────────────

    /** Represents a single bed channel row in the dialog. */
    final class BedChannelRow {
        final TextField trackIdField;
        final ComboBox<SpeakerLabel> speakerCombo;
        final TextField gainField;
        private final HBox node;

        BedChannelRow(String trackId, SpeakerLabel speaker, double gain) {
            trackIdField = new TextField(trackId);
            trackIdField.setPromptText("Track ID");
            trackIdField.setPrefWidth(120);

            speakerCombo = new ComboBox<>();
            speakerCombo.getItems().addAll(SpeakerLabel.values());
            speakerCombo.setValue(speaker);

            gainField = new TextField(String.format("%.2f", gain));
            gainField.setPrefWidth(60);

            Button removeButton = new Button("✕");
            removeButton.setOnAction(event -> removeBedChannelRow(this));

            node = new HBox(8, new Label("Track:"), trackIdField,
                    new Label("Speaker:"), speakerCombo,
                    new Label("Gain:"), gainField, removeButton);
            node.setPadding(new Insets(2));
        }

        Node getNode() {
            return node;
        }
    }

    /** Represents a single audio object row in the dialog. */
    final class AudioObjectRow {
        final TextField trackIdField;
        final TextField xField;
        final TextField yField;
        final TextField zField;
        final TextField sizeField;
        final TextField gainField;
        private final HBox node;

        AudioObjectRow(String trackId, ObjectMetadata metadata) {
            trackIdField = new TextField(trackId);
            trackIdField.setPromptText("Track ID");
            trackIdField.setPrefWidth(100);

            xField = new TextField(String.format("%.2f", metadata.x()));
            xField.setPrefWidth(50);

            yField = new TextField(String.format("%.2f", metadata.y()));
            yField.setPrefWidth(50);

            zField = new TextField(String.format("%.2f", metadata.z()));
            zField.setPrefWidth(50);

            sizeField = new TextField(String.format("%.2f", metadata.size()));
            sizeField.setPrefWidth(50);

            gainField = new TextField(String.format("%.2f", metadata.gain()));
            gainField.setPrefWidth(50);

            Button removeButton = new Button("✕");
            removeButton.setOnAction(event -> removeAudioObjectRow(this));

            node = new HBox(4,
                    new Label("Track:"), trackIdField,
                    new Label("X:"), xField,
                    new Label("Y:"), yField,
                    new Label("Z:"), zField,
                    new Label("Size:"), sizeField,
                    new Label("Gain:"), gainField,
                    removeButton);
            node.setPadding(new Insets(2));
        }

        Node getNode() {
            return node;
        }
    }
}
