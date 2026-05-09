package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiveSummary;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.nio.file.Path;
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
 * <p>This class is a thin wrapper around standard JavaFX {@link Alert}s
 * styled with {@link DarkThemeHelper}; it has no state of its own.</p>
 */
final class ArchiveSummaryDialog {

    private ArchiveSummaryDialog() {
        // utility class
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
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Missing Assets");
        alert.setHeaderText(missingAssetPaths.size()
                + " referenced asset" + (missingAssetPaths.size() == 1 ? "" : "s")
                + " could not be found on disk.");
        StringBuilder body = new StringBuilder("These files will be omitted from the archive:\n\n");
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
        alert.setContentText(body.toString());
        ButtonType continueBtn = new ButtonType("Continue with missing assets");
        ButtonType cancelBtn = ButtonType.CANCEL;
        alert.getButtonTypes().setAll(continueBtn, cancelBtn);
        DarkThemeHelper.applyTo(alert);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == continueBtn;
    }

    /**
     * Shows a post-archive success summary listing asset count and total
     * payload size. Returns immediately after the user dismisses the
     * dialog.
     */
    static void showSummary(ProjectArchiveSummary summary) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Archive Saved");
        alert.setHeaderText(formatHeadline(summary));
        alert.setContentText("Archive: " + summary.outputPath());
        DarkThemeHelper.applyTo(alert);
        alert.showAndWait();
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
