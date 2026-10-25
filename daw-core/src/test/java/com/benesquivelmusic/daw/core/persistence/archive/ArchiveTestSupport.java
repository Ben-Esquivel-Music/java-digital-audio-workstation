package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.project.DawProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The archive tests' shorthand for a whole archive on the calling thread —
 * {@link ProjectArchiver#capture} then {@link ProjectArchiver#write} — for
 * tests whose calling thread owns the project. Tests that edit the project
 * while an archive is written take the capture themselves and hand only it
 * to the worker.
 */
final class ArchiveTestSupport {

    private ArchiveTestSupport() {
    }

    /** Archives {@code project} with {@link ArchiveOptions#defaults()} and no missing-asset decisions. */
    static ProjectArchiveSummary archive(ProjectArchiver archiver, DawProject project, Path archiveFile)
            throws IOException {
        return archive(archiver, project, archiveFile, ArchiveOptions.defaults(), List.of());
    }

    /** Archives {@code project} with {@code options} and {@code decisions}. */
    static ProjectArchiveSummary archive(ProjectArchiver archiver, DawProject project, Path archiveFile,
                                         ArchiveOptions options, List<ArchiveAssetDecision> decisions)
            throws IOException {
        return archiver.write(archiver.capture(project, options, decisions), archiveFile);
    }
}
