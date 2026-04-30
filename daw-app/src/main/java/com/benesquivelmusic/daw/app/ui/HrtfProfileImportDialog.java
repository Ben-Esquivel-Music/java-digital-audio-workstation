package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.spatial.binaural.HrtfImportController;
import com.benesquivelmusic.daw.core.spatial.binaural.HrtfProfileLibrary;
import com.benesquivelmusic.daw.core.spatial.binaural.SofaFileReader;
import com.benesquivelmusic.daw.sdk.spatial.PersonalizedHrtfProfile;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Modal dialog driving the "Import SOFA…" workflow for personalized HRTF
 * profiles (story 174).
 *
 * <p>Pipeline:</p>
 * <ol>
 *   <li>"Choose File…" opens a file picker filtered to {@code *.sofa}.</li>
 *   <li>The selected file is parsed and validated by
 *       {@link HrtfImportController}; on schema failure the error message from
 *       {@link SofaFileReader.ImportResult} is shown in red.</li>
 *   <li>On success the dialog renders a hemisphere-coverage preview (a
 *       wireframe-style projection of measurement positions onto a unit
 *       sphere) and lists every advisory from {@code ImportResult.warnings()},
 *       including sparse-coverage and resampling notices.</li>
 *   <li>Clicking "Import" copies the profile into
 *       {@link HrtfProfileLibrary} and closes the dialog with the imported
 *       profile name as the result.</li>
 * </ol>
 *
 * <p>The dialog is intentionally a thin shell on top of
 * {@link HrtfImportController} — all I/O and validation happens in the
 * controller, which is testable without an FX runtime.</p>
 */
public final class HrtfProfileImportDialog extends Dialog<String> {

    private final HrtfImportController controller;

    private final Label fileLabel;
    private final Label statusLabel;
    private final ListView<String> warningsList;
    private final CoveragePreview coveragePreview;
    private final Button importButton;

    private Path selectedFile;
    private SofaFileReader.ImportResult pendingResult;

    /**
     * Creates a new SOFA import dialog bound to the given library and session.
     *
     * @param library           the user's HRTF profile library
     * @param sessionSampleRate the active session's sample rate, in Hz
     */
    public HrtfProfileImportDialog(HrtfProfileLibrary library, double sessionSampleRate) {
        this(new HrtfImportController(
                Objects.requireNonNull(library, "library must not be null"),
                sessionSampleRate));
    }

    /** Test seam — accepts a pre-built controller. */
    HrtfProfileImportDialog(HrtfImportController controller) {
        this.controller = Objects.requireNonNull(controller, "controller must not be null");

        setTitle("Import SOFA HRTF Profile");
        setHeaderText("Import a personalized HRTF (AES SOFA / AES69-2020)");

        fileLabel = new Label("No file selected.");
        fileLabel.setStyle("-fx-text-fill: #bbb; -fx-font-size: 11px;");

        statusLabel = new Label("");
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-text-fill: #ddd; -fx-font-size: 11px;");

        warningsList = new ListView<>();
        warningsList.setPrefHeight(110);
        warningsList.setPlaceholder(new Label("No advisories."));

        coveragePreview = new CoveragePreview(280, 200);

        Button chooseButton = new Button("Choose File…");
        chooseButton.setOnAction(_ -> onChooseFile());

        Label coverageHeader = new Label("Hemisphere coverage preview");
        coverageHeader.setStyle("-fx-text-fill: #ccc; -fx-font-size: 11px; -fx-font-weight: bold;");

        Label warningsHeader = new Label("Advisories");
        warningsHeader.setStyle("-fx-text-fill: #ccc; -fx-font-size: 11px; -fx-font-weight: bold;");

        VBox content = new VBox(8,
                new HBox(8, chooseButton, fileLabel),
                statusLabel,
                coverageHeader,
                coveragePreview,
                warningsHeader,
                warningsList);
        content.setPadding(new Insets(12));
        content.setAlignment(Pos.TOP_LEFT);
        content.setPrefWidth(320);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        importButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        importButton.setText("Import");
        importButton.setDisable(true);
        importButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (pendingResult == null) {
                event.consume();
                return;
            }
            // Persist via the library — controller already validated/parsed/resampled.
            try {
                controller.library().save(pendingResult.profile());
            } catch (IOException ex) {
                event.consume();
                showError("Failed to save profile: " + ex.getMessage());
            }
        });

        setResultConverter(button -> {
            if (button == ButtonType.OK && pendingResult != null) {
                return pendingResult.profile().name();
            }
            return null;
        });

        DarkThemeHelper.applyTo(this);
    }

    // ── File picker ────────────────────────────────────────────────────────

    private void onChooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select SOFA HRTF File");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("SOFA HRTF files", "*.sofa"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        Window owner = getDialogPane().getScene() != null
                ? getDialogPane().getScene().getWindow() : null;
        java.io.File file = chooser.showOpenDialog(owner);
        if (file != null) {
            tryImport(file.toPath());
        }
    }

    /**
     * Programmatically attempt to import the given SOFA file. Visible so
     * tests and outer controllers (e.g. drag-and-drop receivers) can drive
     * the dialog without going through the file chooser.
     *
     * @param sofaFile path to the SOFA file
     * @return {@code true} when validation succeeded and the dialog is now
     *         primed to commit on OK; {@code false} on validation failure
     */
    public boolean tryImport(Path sofaFile) {
        Objects.requireNonNull(sofaFile, "sofaFile must not be null");
        selectedFile = sofaFile;
        fileLabel.setText(sofaFile.getFileName().toString());
        try {
            // Parse + validate + resample WITHOUT persisting; persistence is
            // deferred to the OK button so the user can still cancel after
            // seeing the coverage warnings.
            SofaFileReader.ImportResult result =
                    SofaFileReader.read(sofaFile, controller.sessionSampleRate());
            return acceptResult(result);
        } catch (IOException ex) {
            pendingResult = null;
            importButton.setDisable(true);
            warningsList.getItems().clear();
            coveragePreview.clear();
            showError(ex.getMessage());
            return false;
        }
    }

    /**
     * Test seam: accept a pre-parsed {@link SofaFileReader.ImportResult},
     * driving the dialog's preview/warnings/commit state without touching
     * the file system.
     *
     * @param result the parsed import result to display
     * @return {@code true} (the result is always considered valid)
     */
    public boolean acceptResult(SofaFileReader.ImportResult result) {
        Objects.requireNonNull(result, "result must not be null");
        pendingResult = result;
        statusLabel.setStyle("-fx-text-fill: #8fc28f; -fx-font-size: 11px;");
        statusLabel.setText(String.format(
                "Validated: %d measurements at %.0f Hz%s.",
                result.profile().measurementCount(),
                result.originalSampleRate(),
                result.resampled() ? " (resampled to session rate)" : ""));
        warningsList.getItems().setAll(result.warnings());
        coveragePreview.render(result.profile());
        importButton.setDisable(false);
        return true;
    }

    private void showError(String message) {
        statusLabel.setStyle("-fx-text-fill: #ff7070; -fx-font-size: 11px;");
        statusLabel.setText(message != null ? message : "Unknown error");
    }

    // ── Test accessors ─────────────────────────────────────────────────────

    Label getFileLabel() { return fileLabel; }
    Label getStatusLabel() { return statusLabel; }
    ListView<String> getWarningsList() { return warningsList; }
    CoveragePreview getCoveragePreview() { return coveragePreview; }
    Button getImportButton() { return importButton; }
    Path getSelectedFile() { return selectedFile; }
    SofaFileReader.ImportResult getPendingResult() { return pendingResult; }

    /**
     * Tiny coverage-preview canvas — projects each measurement direction
     * onto a 2D unit-circle disk (azimuth → angle, elevation → radius).
     * It is intentionally simple: a wireframe outline plus a dot per
     * measurement. Sparse coverage is visually obvious (most of the disk
     * is empty); the textual warning in the warnings list reinforces it.
     */
    public static final class CoveragePreview extends Canvas {

        private static final Color WIREFRAME = Color.web("#444");
        private static final Color DOT_UPPER = Color.web("#7ed957");
        private static final Color DOT_LOWER = Color.web("#5fa9d6");

        public CoveragePreview(double width, double height) {
            super(width, height);
            clear();
        }

        /** Clears the canvas and draws the empty wireframe. */
        public void clear() {
            GraphicsContext g = getGraphicsContext2D();
            g.setFill(Color.web("#1d1d1d"));
            g.fillRect(0, 0, getWidth(), getHeight());
            drawWireframe(g);
        }

        /** Renders the measurement positions of {@code profile} as dots. */
        public void render(PersonalizedHrtfProfile profile) {
            Objects.requireNonNull(profile, "profile must not be null");
            clear();
            GraphicsContext g = getGraphicsContext2D();
            double cx = getWidth() / 2.0;
            double cy = getHeight() / 2.0;
            double r = Math.min(cx, cy) - 8.0;
            double[][] positions = profile.measurementPositionsSpherical();
            for (double[] pos : positions) {
                double az = Math.toRadians(pos[0]);
                double el = pos[1];          // degrees, [-90, 90]
                // Project: distance from centre = cos(elevation), so the equator
                // sits on the rim and the poles are at the centre — a polar
                // azimuthal projection split per hemisphere.
                double rad = (1.0 - Math.abs(el) / 90.0) * r;
                double x = cx + rad * Math.cos(az);
                double y = cy - rad * Math.sin(az);
                g.setFill(el >= 0 ? DOT_UPPER : DOT_LOWER);
                g.fillOval(x - 1.5, y - 1.5, 3.0, 3.0);
            }
        }

        private void drawWireframe(GraphicsContext g) {
            g.setStroke(WIREFRAME);
            g.setLineWidth(1.0);
            double cx = getWidth() / 2.0;
            double cy = getHeight() / 2.0;
            double r = Math.min(cx, cy) - 8.0;
            // Outer rim.
            g.strokeOval(cx - r, cy - r, 2 * r, 2 * r);
            // Concentric "elevation" rings at 30°, 60°.
            for (double el : new double[]{30.0, 60.0}) {
                double rr = (1.0 - el / 90.0) * r;
                g.strokeOval(cx - rr, cy - rr, 2 * rr, 2 * rr);
            }
            // Cardinal azimuth crosshairs.
            g.strokeLine(cx - r, cy, cx + r, cy);
            g.strokeLine(cx, cy - r, cx, cy + r);
        }
    }
}
