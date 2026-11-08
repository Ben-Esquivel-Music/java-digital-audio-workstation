package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.persistence.ProjectManager;

import java.time.Instant;

/**
 * Header metadata describing a {@code .dawz} project archive.
 *
 * <p>Written into the archive as {@code archive.properties} and parsed back
 * on open. Records provenance (original absolute root, DAW version, archive
 * date) and an integrity check (SHA-256 of the embedded {@code project.daw}
 * document).</p>
 *
 * @param projectName       the project name at the time of archiving
 * @param archiveDate       when the archive was created
 * @param assetCount        the number of unique assets stored under {@code assets/}
 * @param originalRoot      the absolute path of the original project root
 *                          (informational; aids the missing-asset resolver)
 * @param dawVersion        the DAW build that produced the archive
 * @param projectDocSha256  SHA-256 hash of the {@code project.daw} XML, hex-encoded
 */
public record ArchiveHeader(
        String projectName,
        Instant archiveDate,
        int assetCount,
        String originalRoot,
        String dawVersion,
        String projectDocSha256
) {
    /**
     * File name of the header inside the archive; an archive restore leaves it at the top of the restored
     * folder too ({@link ProjectManager#ARCHIVE_HEADER_FILE_NAME}).
     */
    public static final String FILE_NAME = ProjectManager.ARCHIVE_HEADER_FILE_NAME;

    /** File name of the embedded project document inside the archive. */
    public static final String PROJECT_DOC_NAME = "project.daw";

    /**
     * Directory inside the archive that holds asset files; a restored project keeps them under the folder of
     * the same name ({@link ProjectManager#ASSETS_DIR_NAME}).
     */
    public static final String ASSETS_DIR = ProjectManager.ASSETS_DIR_NAME;
}
