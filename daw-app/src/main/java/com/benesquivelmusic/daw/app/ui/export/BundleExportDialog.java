package com.benesquivelmusic.daw.app.ui.export;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.sdk.export.AudioExportConfig;
import com.benesquivelmusic.daw.sdk.export.BundleMetadata;
import com.benesquivelmusic.daw.sdk.export.BundlePreset;
import com.benesquivelmusic.daw.sdk.export.DeliverableBundle;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Modal dialog for configuring and launching a single-click "Mixdown to
 * Stems / Bounce Mix" deliverable bundle export.
 *
 * <p>The dialog presents:</p>
 * <ul>
 *   <li>A preset selector (Master + Stems for Mastering, Master Only,
 *       Stems Only with Reference, Streaming Delivery) that fills in
 *       sensible defaults.</li>
 *   <li>Project metadata fields (title, engineer, key).</li>
 *   <li>A list of stem checkboxes, one per track.</li>
 *   <li>Master format dropdown.</li>
 *   <li>A track-sheet PDF toggle.</li>
 *   <li>Output folder + zip filename inputs.</li>
 *   <li>A progress bar with a status label that the controller updates
 *       during export.</li>
 * </ul>
 *
 * <p>The dialog returns a fully populated {@link DeliverableBundle} on OK,
 * or {@code null} on cancel. The actual export is performed by
 * {@code BundleExportService} (in {@code daw-core}); this dialog only
 * builds the request.</p>
 */
public final class BundleExportDialog extends Dialog<DeliverableBundle> {

    /**
     * Lightweight description of a track that can appear as a stem
     * candidate in the dialog.
     *
     * @param trackIndex the project track index
     * @param name       the display / file name
     */
    public record TrackOption(int trackIndex, String name) {
        public TrackOption {
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    private final List<TrackOption> trackOptions;

    // ── Controls ──────────────────────────────────────────────────────────────
    private final ComboBox<BundlePreset> presetCombo;
    private final TextField titleField;
    private final TextField engineerField;
    private final TextField keyField;
    private final TextField masterBaseNameField;
    private final ComboBox<String> masterFormatCombo;
    private final CheckBox trackSheetCheckBox;
    private final TextField outputFolderField;
    private final TextField zipNameField;
    private final List<CheckBox> stemCheckBoxes = new ArrayList<>();
    private final ProgressBar progressBar;
    private final Label progressLabel;

    /**
     * Creates the dialog for the given list of available tracks.
     *
     * @param trackOptions        the candidate stems
     * @param defaultTitle        the default project title
     * @param sampleRate          the project sample rate (Hz)
     * @param bitDepth            the project bit depth
     * @param defaultOutputFolder the default output folder for the zip
     */
    public BundleExportDialog(List<TrackOption> trackOptions,
                              String defaultTitle,
                              int sampleRate,
                              int bitDepth,
                              Path defaultOutputFolder) {
        Objects.requireNonNull(trackOptions, "trackOptions must not be null");
        Objects.requireNonNull(defaultTitle, "defaultTitle must not be null");
        Objects.requireNonNull(defaultOutputFolder, "defaultOutputFolder must not be null");
        this.trackOptions = List.copyOf(trackOptions);

        setTitle("Export Deliverable Bundle");
        setHeaderText("Master + stems → ZIP for mastering / supervisor delivery");

        // ── Preset selector ───────────────────────────────────────────────────
        presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll(BundlePreset.builtIns());
        presetCombo.setValue(BundlePreset.MASTER_AND_STEMS);
        presetCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(BundlePreset p) {
                return p == null ? "" : p.name();
            }

            @Override
            public BundlePreset fromString(String s) {
                return null;
            }
        });
        presetCombo.setOnAction(e -> applyPreset());

        // ── Metadata fields ───────────────────────────────────────────────────
        titleField = new TextField(defaultTitle);
        engineerField = new TextField(System.getProperty("user.name", ""));
        keyField = new TextField("");
        keyField.setPromptText("e.g. Cm or F# major (optional)");

        masterBaseNameField = new TextField(sanitize(defaultTitle) + "_Master");

        masterFormatCombo = new ComboBox<>();
        masterFormatCombo.getItems().addAll("WAV", "FLAC");
        masterFormatCombo.setValue("WAV");

        trackSheetCheckBox = new CheckBox("Include track-sheet PDF (peak / RMS / LUFS per stem)");
        trackSheetCheckBox.setSelected(true);

        outputFolderField = new TextField(defaultOutputFolder.toString());
        zipNameField = new TextField(sanitize(defaultTitle) + "_Bundle.zip");

        // ── Stems checkboxes ──────────────────────────────────────────────────
        VBox stemBox = new VBox(4);
        for (TrackOption opt : this.trackOptions) {
            CheckBox cb = new CheckBox(opt.name());
            cb.setSelected(true);
            stemCheckBoxes.add(cb);
            stemBox.getChildren().add(cb);
        }
        if (this.trackOptions.isEmpty()) {
            stemBox.getChildren().add(new Label("(no tracks available)"));
        }

        // ── Progress display (controller updates these) ───────────────────────
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(420);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        progressLabel = new Label("Ready");
        progressLabel.setStyle("-fx-font-size: 11px;");

        // ── Layout ────────────────────────────────────────────────────────────
        GridPane top = new GridPane();
        top.setHgap(8);
        top.setVgap(6);
        top.setPadding(new Insets(8));
        int row = 0;
        top.add(new Label("Preset:"), 0, row);
        top.add(presetCombo, 1, row++);
        top.add(new Label("Project title:"), 0, row);
        top.add(titleField, 1, row++);
        top.add(new Label("Engineer:"), 0, row);
        top.add(engineerField, 1, row++);
        top.add(new Label("Key:"), 0, row);
        top.add(keyField, 1, row++);
        top.add(new Label("Master file name:"), 0, row);
        top.add(masterBaseNameField, 1, row++);
        top.add(new Label("Master format:"), 0, row);
        top.add(masterFormatCombo, 1, row++);
        top.add(trackSheetCheckBox, 0, row++, 2, 1);
        top.add(new Label("Output folder:"), 0, row);
        top.add(outputFolderField, 1, row++);
        top.add(new Label("Zip file name:"), 0, row);
        top.add(zipNameField, 1, row++);

        TitledPane stemsPane = new TitledPane("Stems", stemBox);
        stemsPane.setCollapsible(false);

        VBox progressBox = new VBox(4, progressBar, progressLabel);
        progressBox.setPadding(new Insets(8, 0, 0, 0));

        VBox content = new VBox(8, top, stemsPane, progressBox);
        content.setPadding(new Insets(8));
        content.setPrefWidth(520);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setText("Export Bundle");

        DarkThemeHelper.applyTo(this);

        setResultConverter(button -> {
            if (button == ButtonType.OK) {
                return buildBundle(sampleRate, bitDepth);
            }
            return null;
        });

        applyPreset();
    }

    /**
     * Updates the progress bar and status label. Must be called on the
     * JavaFX application thread.
     *
     * @param progress 0.0 – 1.0
     * @param stage    short status text
     */
    public void updateProgress(double progress, String stage) {
        progressBar.setProgress(progress);
        progressLabel.setText(stage == null ? "" : stage);
    }

    /**
     * Builds a {@link DeliverableBundle} from the current dialog state.
     */
    DeliverableBundle buildBundle(int sampleRate, int bitDepth) {
        BundlePreset preset = presetCombo.getValue();
        if (preset == null) {
            preset = BundlePreset.MASTER_AND_STEMS;
        }

        // Collect selected stems.
        List<BundlePreset.StemDescriptor> selected = new ArrayList<>();
        for (int i = 0; i < trackOptions.size(); i++) {
            if (stemCheckBoxes.get(i).isSelected() && !stemCheckBoxes.get(i).isDisabled()) {
                TrackOption opt = trackOptions.get(i);
                selected.add(new BundlePreset.StemDescriptor(opt.trackIndex(), opt.name()));
            }
        }

        BundleMetadata template = BundleMetadata.template(
                titleField.getText().isBlank() ? "Untitled" : titleField.getText(),
                engineerField.getText() == null ? "" : engineerField.getText(),
                120.0,
                keyField.getText() == null ? "" : keyField.getText(),
                sampleRate, bitDepth);

        Path zipOut = Paths.get(outputFolderField.getText())
                .resolve(zipNameField.getText());

        // If user overrode master format, swap the preset's masterConfig.
        BundlePreset adjusted = adjustMasterFormat(preset);

        // Override track-sheet flag from the checkbox.
        BundlePreset finalPreset = new BundlePreset(adjusted.name(),
                adjusted.masterConfig(), adjusted.stemConfig(),
                trackSheetCheckBox.isSelected());

        return finalPreset.toBundle(zipOut, masterBaseNameField.getText(),
                selected, template);
    }

    private BundlePreset adjustMasterFormat(BundlePreset preset) {
        if (preset.masterConfig() == null) {
            return preset;
        }
        String chosen = masterFormatCombo.getValue();
        if (chosen == null
                || chosen.equalsIgnoreCase(preset.masterConfig().format().name())) {
            return preset;
        }
        com.benesquivelmusic.daw.sdk.export.AudioExportFormat fmt;
        try {
            fmt = com.benesquivelmusic.daw.sdk.export.AudioExportFormat.valueOf(
                    chosen.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return preset;
        }
        AudioExportConfig orig = preset.masterConfig();
        AudioExportConfig replaced = new AudioExportConfig(
                fmt, orig.sampleRate(), orig.bitDepth(),
                orig.ditherType(), orig.metadata(), orig.quality());
        return new BundlePreset(preset.name(), replaced,
                preset.stemConfig(), preset.includeTrackSheet());
    }

    private void applyPreset() {
        BundlePreset preset = presetCombo.getValue();
        if (preset == null) {
            return;
        }
        if (preset.masterConfig() != null) {
            masterFormatCombo.setValue(preset.masterConfig().format().name());
            masterFormatCombo.setDisable(false);
            masterBaseNameField.setDisable(false);
        } else {
            masterFormatCombo.setDisable(true);
            masterBaseNameField.setDisable(true);
        }
        boolean hasStems = preset.stemConfig() != null;
        for (CheckBox cb : stemCheckBoxes) {
            cb.setDisable(!hasStems);
        }
        trackSheetCheckBox.setSelected(preset.includeTrackSheet());
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9\\-_ ]", "_").trim().replace(' ', '_');
    }

    // ── Test hooks ───────────────────────────────────────────────────────────

    /** Returns the preset combo box (test-visible). */
    ComboBox<BundlePreset> getPresetCombo() {
        return presetCombo;
    }

    /** Returns the project-title field (test-visible). */
    TextField getTitleField() {
        return titleField;
    }

    /** Returns the master format combo box (test-visible). */
    ComboBox<String> getMasterFormatCombo() {
        return masterFormatCombo;
    }

    /** Returns the track-sheet checkbox (test-visible). */
    CheckBox getTrackSheetCheckBox() {
        return trackSheetCheckBox;
    }

    /** Returns the list of stem checkboxes (test-visible). */
    List<CheckBox> getStemCheckBoxes() {
        return stemCheckBoxes;
    }

    /** Returns the progress bar (test-visible). */
    ProgressBar getProgressBar() {
        return progressBar;
    }

    /** Returns the progress label (test-visible). */
    Label getProgressLabel() {
        return progressLabel;
    }
}
