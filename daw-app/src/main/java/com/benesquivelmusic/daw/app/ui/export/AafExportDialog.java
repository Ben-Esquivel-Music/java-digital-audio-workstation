package com.benesquivelmusic.daw.app.ui.export;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.core.export.aaf.AafExportConfig;
import com.benesquivelmusic.daw.core.export.aaf.AafFrameRate;
import com.benesquivelmusic.daw.core.export.aaf.AafTimecode;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Modal dialog for configuring an OMF / AAF interchange export bound for
 * a film-post DAW.
 *
 * <p>The dialog presents:</p>
 * <ul>
 *   <li>a frame-rate selector (23.976, 24, 25, 29.97, 30 fps)</li>
 *   <li>a start-timecode entry field (the user-configured "session start"
 *       — typically {@code 01:00:00:00} per film convention)</li>
 *   <li>an embed-vs-reference toggle (self-contained vs. smaller external-
 *       reference file)</li>
 *   <li>a per-track inclusion list (one checkbox per audio track)</li>
 *   <li>a composition-name field</li>
 * </ul>
 *
 * <p>The dialog returns a fully-populated {@link AafExportConfig} on OK,
 * or {@code null} on cancel. The actual export is performed by the
 * {@code AafExportService} in {@code daw-core}; this dialog only builds
 * the request.</p>
 */
public final class AafExportDialog extends Dialog<AafExportConfig> {

    /**
     * Lightweight description of a track that can appear in the
     * inclusion list.
     *
     * @param trackIndex the project track index
     * @param name       the display name
     */
    public record TrackOption(int trackIndex, String name) {
        public TrackOption {
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    private final List<TrackOption> trackOptions;
    private final ComboBox<AafFrameRate> frameRateCombo;
    private final TextField startTimecodeField;
    private final TextField compositionNameField;
    private final CheckBox embedMediaCheckBox;
    private final List<CheckBox> trackCheckBoxes = new ArrayList<>();

    /**
     * @param trackOptions       the candidate audio tracks
     * @param defaultCompName    the default composition name (typically
     *                           the project name)
     */
    public AafExportDialog(List<TrackOption> trackOptions, String defaultCompName) {
        Objects.requireNonNull(trackOptions, "trackOptions must not be null");
        Objects.requireNonNull(defaultCompName, "defaultCompName must not be null");
        this.trackOptions = List.copyOf(trackOptions);

        setTitle("Export OMF / AAF");
        setHeaderText("Editorial → post handoff: AAF 1.2 (or OMF 2.0 fallback)");

        // ── Frame rate ─────────────────────────────────────────────────────
        frameRateCombo = new ComboBox<>();
        frameRateCombo.getItems().addAll(AafFrameRate.values());
        frameRateCombo.setValue(AafFrameRate.FPS_24);
        frameRateCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(AafFrameRate fr) {
                return fr == null ? "" : fr.label() + " fps";
            }
            @Override public AafFrameRate fromString(String s) { return null; }
        });

        // ── Start TC ───────────────────────────────────────────────────────
        startTimecodeField = new TextField("01:00:00:00");
        startTimecodeField.setPromptText("HH:MM:SS:FF");

        // ── Composition name ──────────────────────────────────────────────
        compositionNameField = new TextField(defaultCompName);

        // ── Embed vs reference ────────────────────────────────────────────
        embedMediaCheckBox = new CheckBox(
                "Embed source media (self-contained file, larger size)");
        embedMediaCheckBox.setSelected(false);

        // ── Track inclusion list ──────────────────────────────────────────
        VBox trackBox = new VBox(4);
        for (TrackOption opt : this.trackOptions) {
            CheckBox cb = new CheckBox(opt.name());
            cb.setSelected(true);
            trackCheckBoxes.add(cb);
            trackBox.getChildren().add(cb);
        }
        if (this.trackOptions.isEmpty()) {
            trackBox.getChildren().add(new Label("(no audio tracks available)"));
        }

        // ── Layout ─────────────────────────────────────────────────────────
        GridPane top = new GridPane();
        top.setHgap(8);
        top.setVgap(6);
        top.setPadding(new Insets(8));
        int row = 0;
        top.add(new Label("Composition name:"), 0, row);
        top.add(compositionNameField, 1, row++);
        top.add(new Label("Frame rate:"), 0, row);
        top.add(frameRateCombo, 1, row++);
        top.add(new Label("Start timecode:"), 0, row);
        top.add(startTimecodeField, 1, row++);
        top.add(embedMediaCheckBox, 0, row++, 2, 1);

        TitledPane tracksPane = new TitledPane("Included tracks", trackBox);
        tracksPane.setCollapsible(false);

        VBox content = new VBox(8, top, tracksPane);
        content.setPadding(new Insets(8));
        content.setPrefWidth(440);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        DarkThemeHelper.applyTo(this);

        setResultConverter(button -> button == ButtonType.OK ? buildConfig() : null);
    }

    /** Builds the {@link AafExportConfig} from the current form state. */
    private AafExportConfig buildConfig() {
        AafFrameRate fr = frameRateCombo.getValue();
        AafTimecode startTc;
        try {
            startTc = AafTimecode.parse(startTimecodeField.getText().trim(), fr);
        } catch (RuntimeException e) {
            // Fall back to zero TC on invalid input rather than throwing
            // out of the dialog's result converter.
            startTc = AafTimecode.zero(fr);
        }
        List<Integer> included = new ArrayList<>();
        for (int i = 0; i < trackCheckBoxes.size(); i++) {
            if (trackCheckBoxes.get(i).isSelected()) {
                included.add(trackOptions.get(i).trackIndex());
            }
        }
        String name = compositionNameField.getText() == null
                || compositionNameField.getText().isBlank()
                ? "Untitled" : compositionNameField.getText().trim();
        return new AafExportConfig(fr, startTc,
                embedMediaCheckBox.isSelected(),
                included, name);
    }
}
