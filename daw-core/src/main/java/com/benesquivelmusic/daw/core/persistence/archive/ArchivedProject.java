package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;
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
 * @param unreadItems    the saved items the read of the archived project
 *                       document could not honour as written
 *                       ({@link ProjectDeserializer#getUnreadItems()}). A
 *                       document re-serialized from {@link #project()} no
 *                       longer holds what this read dropped or adjusted, but
 *                       still holds the unrecognised-MIDI-effect
 *                       placeholders, written back as saved, which a reopen
 *                       of it reports again; a caller announces this list
 *                       instead of the reopen's, never both, or it counts
 *                       those placeholders twice
 */
public record ArchivedProject(
        DawProject project,
        Path extractedDir,
        ArchiveHeader header,
        List<String> missingAssets,
        List<UnreadProjectItem> unreadItems
) {
    public ArchivedProject {
        missingAssets = List.copyOf(missingAssets);
        unreadItems = List.copyOf(unreadItems);
    }
}
