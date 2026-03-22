package com.benesquivelmusic.daw.sdk.session;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Interface for importing a DAW session from an external file format.
 *
 * <p>Implementations handle parsing a session interchange file (e.g., DAWproject)
 * and mapping its contents to the internal project representation. Features that
 * cannot be mapped should be logged as warnings in the returned
 * {@link SessionImportResult} rather than causing import failure.</p>
 */
public interface SessionImporter {

    /**
     * Imports a session from the specified file.
     *
     * @param file the session file to import
     * @return the import result containing the reconstructed project and any warnings
     * @throws IOException if an I/O error occurs while reading the file
     */
    SessionImportResult importSession(Path file) throws IOException;

    /**
     * Returns the human-readable name of the format this importer handles.
     *
     * @return the format name (e.g., "DAWproject")
     */
    String formatName();

    /**
     * Returns the file extension associated with this format (without the leading dot).
     *
     * @return the file extension (e.g., "dawproject")
     */
    String fileExtension();
}
