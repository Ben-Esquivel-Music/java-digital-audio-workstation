package com.benesquivelmusic.daw.core.audioimport;

import java.nio.file.Path;

/**
 * Listener for audio file import progress.
 *
 * <p>Implementations can use this to display progress indicators
 * in the UI during large file imports.</p>
 */
public interface ImportProgressListener {

    /**
     * Called when import of a file begins.
     *
     * @param file        the file being imported
     * @param fileIndex   the zero-based index of this file in the batch
     * @param totalFiles  the total number of files being imported
     */
    void onFileStarted(Path file, int fileIndex, int totalFiles);

    /**
     * Called periodically to report progress on the current file.
     *
     * @param file     the file being imported
     * @param progress a value between 0.0 and 1.0 indicating progress
     */
    void onProgress(Path file, double progress);

    /**
     * Called when import of a file completes successfully.
     *
     * @param file   the file that was imported
     * @param result the import result
     */
    void onFileCompleted(Path file, AudioImportResult result);

    /**
     * Called when import of a file fails.
     *
     * @param file  the file that failed to import
     * @param error the error that occurred
     */
    void onFileError(Path file, Exception error);

    /** A no-op listener that ignores all events. */
    ImportProgressListener NONE = new ImportProgressListener() {
        @Override
        public void onFileStarted(Path file, int fileIndex, int totalFiles) {
        }

        @Override
        public void onProgress(Path file, double progress) {
        }

        @Override
        public void onFileCompleted(Path file, AudioImportResult result) {
        }

        @Override
        public void onFileError(Path file, Exception error) {
        }
    };
}
