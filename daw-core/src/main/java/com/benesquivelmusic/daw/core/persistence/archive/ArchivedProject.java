package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.project.DawProject;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of {@link ProjectArchiver#openArchive}: the freshly loaded project
 * together with the temporary directory it was extracted into and a list of
 * any asset paths that could not be resolved.
 *
 * <p>Callers are responsible for the lifecycle of {@link #extractedDir()}.
 * It typically lives until the project is closed or saved elsewhere.</p>
 *
 * @param project        the deserialized project, with asset paths rewritten
 *                       to point at files inside {@link #extractedDir()}
 * @param extractedDir   the temporary directory the archive was extracted into
 * @param header         the parsed archive header
 * @param missingAssets  asset paths that the resolver could not locate
 */
public record ArchivedProject(
        DawProject project,
        Path extractedDir,
        ArchiveHeader header,
        List<String> missingAssets
) {
    public ArchivedProject {
        missingAssets = List.copyOf(missingAssets);
    }
}
