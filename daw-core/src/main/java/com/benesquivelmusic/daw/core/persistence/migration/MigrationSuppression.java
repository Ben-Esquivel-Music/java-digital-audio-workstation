package com.benesquivelmusic.daw.core.persistence.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Persists the user's "Don't show again for this project" choice for
 * the migration report dialog.
 *
 * <p>The choice is stored as a tiny marker file inside the project
 * directory ({@code .migration-report-suppressed}) whose contents
 * record the schema version that was suppressed. Suppression is
 * version-scoped: if the project file is later migrated again to a
 * higher schema version, the dialog reappears so the user can review
 * the new changes.</p>
 *
 * <p>This is a deliberately tiny, file-system based mechanism — there
 * is no global preferences store involved, so deleting/renaming the
 * project directory cleanly wipes the suppression state too.</p>
 */
public final class MigrationSuppression {

    /** Marker filename written inside the project directory. */
    public static final String MARKER_FILENAME = ".migration-report-suppressed";

    private MigrationSuppression() {
        // utility class
    }

    /**
     * Returns {@code true} if the user has previously chosen to suppress
     * the migration report dialog for the given project at the given
     * target schema version.
     *
     * @param projectDirectory the project directory containing
     *                         {@code project.daw}; may be {@code null}
     *                         in which case suppression is never honoured
     * @param toVersion        the target schema version of the report —
     *                         suppression only applies when it matches
     *                         the recorded version
     */
    public static boolean isSuppressed(Path projectDirectory, int toVersion) {
        if (projectDirectory == null) {
            return false;
        }
        Path marker = projectDirectory.resolve(MARKER_FILENAME);
        if (!Files.exists(marker)) {
            return false;
        }
        try {
            String raw = Files.readString(marker).trim();
            int recorded = Integer.parseInt(raw);
            return recorded >= toVersion;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    /**
     * Records the user's choice to suppress the migration report dialog
     * for this project at the given target schema version. Subsequent
     * loads at that version (or older targets) will not surface the
     * dialog. A future schema bump produces a new {@link MigrationReport}
     * with a higher {@code toVersion} which is not suppressed.
     */
    public static void suppress(Path projectDirectory, int toVersion) {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        try {
            Files.writeString(projectDirectory.resolve(MARKER_FILENAME),
                    Integer.toString(toVersion));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Clears any recorded suppression for the project. Intended for
     * tests and the rare case where the user wants to re-enable the
     * dialog explicitly.
     */
    public static void clear(Path projectDirectory) {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        try {
            Files.deleteIfExists(projectDirectory.resolve(MARKER_FILENAME));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
