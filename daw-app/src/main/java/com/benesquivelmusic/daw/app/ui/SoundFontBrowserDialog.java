package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.sdk.midi.SoundFontInfo;
import com.benesquivelmusic.daw.sdk.midi.SoundFontPreset;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRenderer;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Modal dialog for browsing SoundFont (.sf2) files and selecting an
 * instrument preset for a MIDI track.
 *
 * <p>The dialog presents two lists: the loaded SoundFont files on the
 * left, and the presets (instruments) contained in the selected
 * SoundFont on the right. Users can load additional SF2 files, browse
 * their presets, and select an instrument to assign to a MIDI track.</p>
 *
 * <p>When a preset is selected, a short MIDI phrase is played through
 * the renderer so the user can audition the instrument before confirming.</p>
 */
public final class SoundFontBrowserDialog extends Dialog<SoundFontAssignment> {

    private static final Logger LOG = Logger.getLogger(SoundFontBrowserDialog.class.getName());
    private static final double HEADER_ICON_SIZE = 24;

    private final SoundFontRenderer renderer;
    private final ListView<SoundFontInfo> soundFontListView;
    private final ListView<SoundFontPreset> presetListView;
    private final ObservableList<SoundFontInfo> loadedSoundFonts;

    /**
     * Creates a new SoundFont browser dialog.
     *
     * @param renderer          the SoundFont renderer used to load files and preview presets
     * @param currentAssignment the currently assigned SoundFont preset, or {@code null}
     */
    public SoundFontBrowserDialog(SoundFontRenderer renderer, SoundFontAssignment currentAssignment) {
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");

        setTitle("SoundFont Browser");
        setHeaderText("Select an instrument from a SoundFont file");
        setGraphic(IconNode.of(DawIcon.PIANO, HEADER_ICON_SIZE));

        // Initialize observable list from already-loaded SoundFonts
        loadedSoundFonts = FXCollections.observableArrayList(new ArrayList<>(renderer.getLoadedSoundFonts()));

        // ── SoundFont list (left) ───────────────────────────────────────────
        soundFontListView = new ListView<>(loadedSoundFonts);
        soundFontListView.setPrefHeight(320);
        soundFontListView.setPrefWidth(280);
        soundFontListView.setCellFactory(_ -> new SoundFontCell());

        Label sfLabel = new Label("SoundFont Files");
        sfLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");

        Button loadButton = new Button("Load SF2…");
        loadButton.setGraphic(IconNode.of(DawIcon.FOLDER, 14));
        loadButton.setOnAction(_ -> onLoadSoundFont());

        VBox sfBox = new VBox(6, sfLabel, soundFontListView, loadButton);

        // ── Preset list (right) ─────────────────────────────────────────────
        presetListView = new ListView<>();
        presetListView.setPrefHeight(320);
        presetListView.setPrefWidth(340);
        presetListView.setCellFactory(_ -> new PresetCell());

        Label presetLabel = new Label("Presets (Instruments)");
        presetLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");

        VBox presetBox = new VBox(6, presetLabel, presetListView);
        HBox.setHgrow(presetBox, Priority.ALWAYS);

        // ── Wiring: when a SoundFont is selected, update preset list ────────
        soundFontListView.getSelectionModel().selectedItemProperty().addListener(
                (_, _, newValue) -> onSoundFontSelected(newValue));

        // ── Preview: when a preset is selected, play a short phrase ─────────
        presetListView.getSelectionModel().selectedItemProperty().addListener(
                (_, _, newValue) -> onPresetSelected(newValue));

        // ── Info label ──────────────────────────────────────────────────────
        Label infoLabel = new Label("Select a SoundFont and instrument, then click OK to assign");
        infoLabel.setGraphic(IconNode.of(DawIcon.INFO, 14));
        infoLabel.setStyle("-fx-text-fill: #808080; -fx-font-size: 11px;");

        HBox listsBox = new HBox(12, sfBox, presetBox);
        VBox content = new VBox(8, listsBox, infoLabel);
        content.setPadding(new Insets(16));

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        DarkThemeHelper.applyTo(this);

        // Pre-select the current assignment if one exists
        if (currentAssignment != null) {
            preselectAssignment(currentAssignment);
        }

        setResultConverter(button -> {
            if (button == ButtonType.OK) {
                return buildAssignment();
            }
            return null;
        });
    }

    /**
     * Shows the dialog and returns the selected SoundFont assignment, if any.
     *
     * @return an {@link Optional} containing the selected assignment, or empty if cancelled
     */
    public Optional<SoundFontAssignment> showAndGetResult() {
        return showAndWait();
    }

    /**
     * Returns the list of SoundFont files currently loaded in this dialog.
     * Package-private for testing.
     *
     * @return the list of loaded SoundFont infos
     */
    List<SoundFontInfo> getLoadedSoundFontsList() {
        return List.copyOf(loadedSoundFonts);
    }

    /**
     * Returns the list of presets currently displayed in this dialog.
     * Package-private for testing.
     *
     * @return the list of displayed presets
     */
    List<SoundFontPreset> getDisplayedPresets() {
        return List.copyOf(presetListView.getItems());
    }

    /**
     * Builds a {@link SoundFontAssignment} from the current selection, or
     * {@code null} if no valid selection has been made.
     */
    SoundFontAssignment buildAssignment() {
        SoundFontInfo selectedFont = soundFontListView.getSelectionModel().getSelectedItem();
        SoundFontPreset selectedPreset = presetListView.getSelectionModel().getSelectedItem();
        if (selectedFont == null || selectedPreset == null) {
            return null;
        }
        return new SoundFontAssignment(
                selectedFont.path(),
                selectedPreset.bank(),
                selectedPreset.program(),
                selectedPreset.name());
    }

    private void onLoadSoundFont() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open SoundFont File");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("SoundFont 2 Files", "*.sf2"));
        List<File> files = chooser.showOpenMultipleDialog(getOwner());
        if (files == null) {
            return;
        }
        for (File file : files) {
            loadSoundFontFile(file.toPath());
        }
    }

    private void loadSoundFontFile(Path path) {
        try {
            SoundFontInfo info = renderer.loadSoundFont(path);
            loadedSoundFonts.add(info);
            soundFontListView.getSelectionModel().select(info);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to load SoundFont: " + path, e);
        }
    }

    private void onSoundFontSelected(SoundFontInfo soundFont) {
        if (soundFont == null) {
            presetListView.getItems().clear();
            return;
        }
        presetListView.setItems(FXCollections.observableArrayList(soundFont.presets()));
    }

    private void onPresetSelected(SoundFontPreset preset) {
        if (preset == null) {
            return;
        }
        previewPreset(preset);
    }

    private void previewPreset(SoundFontPreset preset) {
        try {
            renderer.selectPreset(0, preset.bank(), preset.program());
            renderer.sendEvent(com.benesquivelmusic.daw.sdk.midi.MidiEvent.noteOn(0, 60, 100));
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(500);
                    renderer.sendEvent(com.benesquivelmusic.daw.sdk.midi.MidiEvent.noteOff(0, 60));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (Exception e) {
            LOG.log(Level.FINE, "Preview failed for preset: " + preset.name(), e);
        }
    }

    private void preselectAssignment(SoundFontAssignment assignment) {
        for (SoundFontInfo info : loadedSoundFonts) {
            if (info.path().equals(assignment.soundFontPath())) {
                soundFontListView.getSelectionModel().select(info);
                for (SoundFontPreset preset : presetListView.getItems()) {
                    if (preset.bank() == assignment.bank()
                            && preset.program() == assignment.program()) {
                        presetListView.getSelectionModel().select(preset);
                        break;
                    }
                }
                break;
            }
        }
    }

    /**
     * Formats the display text for a preset, showing bank:program and name.
     * Package-private for testing.
     *
     * @param preset the preset to format
     * @return the formatted display string
     */
    static String formatPresetDisplay(SoundFontPreset preset) {
        return String.format("%03d:%03d — %s", preset.bank(), preset.program(), preset.name());
    }

    /**
     * Custom list cell that displays SoundFont file information.
     */
    private static final class SoundFontCell extends ListCell<SoundFontInfo> {
        @Override
        protected void updateItem(SoundFontInfo info, boolean empty) {
            super.updateItem(info, empty);
            if (empty || info == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(info.path().getFileName().toString()
                        + " (" + info.presets().size() + " presets)");
                setGraphic(IconNode.of(DawIcon.LIBRARY, 14));
            }
        }
    }

    /**
     * Custom list cell that displays preset bank, program, and name.
     */
    private static final class PresetCell extends ListCell<SoundFontPreset> {
        @Override
        protected void updateItem(SoundFontPreset preset, boolean empty) {
            super.updateItem(preset, empty);
            if (empty || preset == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(formatPresetDisplay(preset));
                setGraphic(IconNode.of(DawIcon.MUSIC_NOTE, 14));
            }
        }
    }
}
