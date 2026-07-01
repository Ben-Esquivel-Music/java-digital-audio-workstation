package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.dialogs.LegacyDialog;
import com.benesquivelmusic.daw.core.persistence.archive.ArchiveAssetDecision;
import com.benesquivelmusic.daw.core.persistence.archive.ArchiveOptions;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiveSummary;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dialogs that surface the result of a project-archive operation
 * (Story 189 — <em>Project Archive (ZIP With Assets)</em>).
 *
 * <p>Two flavours are provided:</p>
 * <ul>
 *   <li>{@link #confirmMissingAssets(List)} — pre-archive confirmation
 *       shown when one or more referenced asset files no longer exist on
 *       disk. The user can choose to abort the archive or proceed with
 *       the assets simply omitted from the {@code .dawz} file.</li>
 *   <li>{@link #showSummary(ProjectArchiveSummary)} — post-archive
 *       success notification listing the asset count and total payload
 *       size in human-readable form.</li>
 * </ul>
 *
 * <p>This class is a thin static-helper wrapper (private constructor,
 * no state). It cannot {@code extends DawgDialog} so the §5.9 chrome is
 * delegated to {@link DawgDialog#confirm}/{@link DawgDialog#info}; it is
 * exempt from the structural migration via {@link LegacyDialog}.</p>
 */
@LegacyDialog("Alert-wrapper static helper; chrome delegated to "
        + "DawgDialog.confirm/info — not a Dialog subclass, exempt by annotation")
final class ArchiveSummaryDialog {

    private ArchiveSummaryDialog() {
        // utility class
    }

    record ArchivePlan(ArchiveOptions options,
                       List<ArchiveAssetDecision> missingAssetDecisions) {
        ArchivePlan {
            options = options == null ? ArchiveOptions.defaults() : options;
            missingAssetDecisions = List.copyOf(missingAssetDecisions == null
                    ? List.of()
                    : missingAssetDecisions);
        }
    }

    static Optional<ArchivePlan> chooseArchivePlan(List<String> missingAssetPaths,
                                                   long estimatedAssetBytes,
                                                   Window owner) {
        List<String> missing = missingAssetPaths == null ? List.of() : List.copyOf(missingAssetPaths);
        DawgDialog<ArchivePlan> dialog = new DawgDialog<>();
        dialog.setTitle("Archive Project");
        dialog.setHeaderText("Archive Project");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.sized(DawgDialog.Size.LARGE);

        CheckBox includeImpulseResponses = new CheckBox("Impulse responses");
        includeImpulseResponses.setSelected(true);
        CheckBox includeUnusedTakes = new CheckBox("Unused takes");
        includeUnusedTakes.setSelected(false);
        CheckBox includeSoundFonts = new CheckBox("SoundFonts");
        includeSoundFonts.setSelected(true);

        VBox includeBox = new VBox(8,
                includeImpulseResponses,
                includeUnusedTakes,
                includeSoundFonts,
                muted("Estimated asset payload: " + humanReadableBytes(estimatedAssetBytes)
                        + " · ETA shown in the status strip while packing"));

        List<DecisionRow> rows = new ArrayList<>();
        VBox missingBox = new VBox(8);
        missingBox.setPadding(new Insets(2, 0, 0, 0));
        if (missing.isEmpty()) {
            missingBox.getChildren().add(muted("No missing referenced assets."));
        } else {
            for (String path : missing) {
                DecisionRow row = new DecisionRow(path);
                rows.add(row);
                missingBox.getChildren().add(row.node());
            }
        }

        dialog.addSection("Include", includeBox);
        dialog.addSection("Missing Assets", missingBox);

        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType archive = new ButtonType("Archive", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(cancel, archive);
        Button archiveButton = (Button) dialog.getDialogPane().lookupButton(archive);
        archiveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            Optional<String> invalid = rows.stream()
                    .filter(DecisionRow::requiresLocation)
                    .map(DecisionRow::locatedPathText)
                    .filter(text -> text == null || text.isBlank())
                    .findFirst();
            if (invalid.isPresent()) {
                event.consume();
            }
        });
        dialog.setResultConverter(button -> {
            if (button != archive) {
                return null;
            }
            ArchiveOptions options = new ArchiveOptions(
                    includeImpulseResponses.isSelected(),
                    includeUnusedTakes.isSelected(),
                    includeSoundFonts.isSelected());
            return new ArchivePlan(options,
                    rows.stream().map(DecisionRow::decision).toList());
        });
        return dialog.showAndWait();
    }

    /**
     * Asks the user whether to proceed with the archive when one or more
     * asset files cannot be found on disk and would therefore be omitted
     * from the resulting {@code .dawz}.
     *
     * @param missingAssetPaths the paths recorded in the project that did
     *                          not resolve to a regular file on disk
     * @return {@code true} if the user wants to continue with missing
     *         assets, {@code false} if they want to abort
     */
    static boolean confirmMissingAssets(List<String> missingAssetPaths) {
        String header = missingAssetPaths.size()
                + " referenced asset" + (missingAssetPaths.size() == 1 ? "" : "s")
                + " could not be found on disk.";
        StringBuilder body = new StringBuilder(header)
                .append("\n\nThese files will be omitted from the archive:\n\n");
        int shown = 0;
        for (String path : missingAssetPaths) {
            if (shown >= 10) {
                body.append("  \u2026 and ").append(missingAssetPaths.size() - shown)
                        .append(" more\n");
                break;
            }
            body.append("  \u2022 ").append(path).append('\n');
            shown++;
        }
        body.append("\nContinue with missing assets?");
        // story 276 \u2014 \u00a75.9 chrome via DawgDialog.confirm (NOT a JavaFX
        // Alert, whose header gradient bypasses the author stylesheet).
        DawgDialog<ButtonType> dialog =
                DawgDialog.confirm("Missing Assets", body.toString(),
                        "Continue with missing assets");
        Optional<ButtonType> result = dialog.showAndWait();
        return result.isPresent()
                && result.get().getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("text-muted");
        return label;
    }

    private static final class DecisionRow {
        private final String originalPath;
        private final ComboBox<ArchiveAssetDecision.Action> actionBox =
                new ComboBox<>();
        private final TextField locatedPath = new TextField();
        private final GridPane node = new GridPane();

        DecisionRow(String originalPath) {
            this.originalPath = originalPath;
            actionBox.getItems().setAll(ArchiveAssetDecision.Action.SKIP,
                    ArchiveAssetDecision.Action.LOCATE,
                    ArchiveAssetDecision.Action.USE_STUB);
            actionBox.setValue(ArchiveAssetDecision.Action.SKIP);
            locatedPath.setPromptText("Replacement file");
            locatedPath.setDisable(true);
            Button locate = new Button("Locate\u2026");
            locate.setOnAction(_ -> chooseReplacement());
            locate.disableProperty().bind(actionBox.valueProperty()
                    .isNotEqualTo(ArchiveAssetDecision.Action.LOCATE));
            actionBox.valueProperty().addListener((obs, oldAction, newAction) ->
                    locatedPath.setDisable(newAction != ArchiveAssetDecision.Action.LOCATE));

            node.setHgap(8);
            node.setVgap(4);
            node.add(new Label(originalPath), 0, 0, 3, 1);
            node.add(actionBox, 0, 1);
            node.add(locatedPath, 1, 1);
            node.add(locate, 2, 1);
            HBox.setHgrow(locatedPath, Priority.ALWAYS);
            GridPane.setHgrow(locatedPath, Priority.ALWAYS);
        }

        GridPane node() {
            return node;
        }

        boolean requiresLocation() {
            return actionBox.getValue() == ArchiveAssetDecision.Action.LOCATE;
        }

        String locatedPathText() {
            return locatedPath.getText();
        }

        ArchiveAssetDecision decision() {
            return switch (actionBox.getValue()) {
                case SKIP -> ArchiveAssetDecision.skip(originalPath);
                case USE_STUB -> ArchiveAssetDecision.useStub(originalPath);
                case LOCATE -> ArchiveAssetDecision.locate(originalPath,
                        Path.of(locatedPath.getText().trim()));
            };
        }

        private void chooseReplacement() {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Locate Missing Asset");
            java.io.File selected = chooser.showOpenDialog(node.getScene() == null
                    ? null
                    : node.getScene().getWindow());
            if (selected != null) {
                actionBox.setValue(ArchiveAssetDecision.Action.LOCATE);
                locatedPath.setText(selected.toPath().toString());
            }
        }
    }

    /**
     * Shows a post-archive success summary listing asset count and total
     * payload size. Returns immediately after the user dismisses the
     * dialog.
     */
    static void showSummary(ProjectArchiveSummary summary) {
        // story 276 — §5.9 chrome via DawgDialog.info (NOT a JavaFX
        // Alert, whose header gradient bypasses the author stylesheet).
        DawgDialog.info("Archive Saved",
                formatHeadline(summary) + "\n\nArchive: " + summary.outputPath())
                .showAndWait();
    }

    /**
     * Builds the headline text for the archive-success dialog and the
     * inline notification bar — for example, {@code "42 assets, 1.2 GiB"}.
     * Package-private so it can be unit-tested without a JavaFX toolkit.
     */
    static String formatHeadline(ProjectArchiveSummary summary) {
        return "Archive saved: "
                + summary.uniqueAssetCount() + " asset"
                + (summary.uniqueAssetCount() == 1 ? "" : "s")
                + ", " + humanReadableBytes(summary.totalAssetBytes());
    }

    /**
     * Formats {@code bytes} using IEC binary units (KiB, MiB, GiB, …) with
     * one decimal of precision above the KiB threshold. Bytes are reported
     * verbatim. Package-private for unit tests.
     */
    static String humanReadableBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        double v = bytes;
        int unit = -1;
        do {
            v /= 1024.0;
            unit++;
        } while (v >= 1024.0 && unit < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[unit]);
    }

    /** Builds the path string for a {@code .dawz} link in a notification. */
    static String archivePathDisplay(Path archivePath) {
        if (archivePath == null) {
            return "";
        }
        Path name = archivePath.getFileName();
        return name != null ? name.toString() : archivePath.toString();
    }
}
