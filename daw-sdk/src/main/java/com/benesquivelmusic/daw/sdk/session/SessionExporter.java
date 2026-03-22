package com.benesquivelmusic.daw.sdk.session;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Interface for exporting a DAW session to an external file format.
 *
 * <p>Implementations serialize the internal project representation into a
 * session interchange file (e.g., DAWproject). Features that cannot be
 * represented in the target format should be logged as warnings in the
 * returned {@link SessionExportResult}.</p>
 */
public interface SessionExporter {

    /**
     * Exports a session to the specified output directory.
     *
     * @param session   the session data to export
     * @param outputDir the directory in which to write the exported file
     * @param baseName  the base filename (without extension)
     * @return the export result containing the output path and any warnings
     * @throws IOException if an I/O error occurs while writing the file
     */
    SessionExportResult exportSession(SessionData session, Path outputDir, String baseName) throws IOException;

    /**
     * Returns the human-readable name of the format this exporter produces.
     *
     * @return the format name (e.g., "DAWproject")
     */
    String formatName();

    /**
     * Returns the file extension produced by this exporter (without the leading dot).
     *
     * @return the file extension (e.g., "dawproject")
     */
    String fileExtension();
}
