package com.benesquivelmusic.daw.core.audio;

import java.util.Objects;

/**
 * Immutable status record for a native library detected by
 * {@link NativeLibraryDetector}.
 *
 * @param libraryName  the human-readable library name (e.g. "libogg")
 * @param requiredFor  a description of what this library is needed for
 *                     (e.g. "OGG Vorbis import and export")
 * @param available    {@code true} if the library was found and is loadable
 * @param detectedPath the resolved path from which the library was loaded.
 *                     This is an absolute filesystem path when the library was
 *                     found in a bundled directory or resolved from a well-known
 *                     system location. On platforms where the system loader does
 *                     not expose the on-disk path (Windows, macOS), this may be
 *                     a system-loader indicator string of the form
 *                     {@code "(system: <filename>)"}. Empty string if the
 *                     library was not found.
 */
public record NativeLibraryStatus(
        String libraryName,
        String requiredFor,
        boolean available,
        String detectedPath) {

    public NativeLibraryStatus {
        Objects.requireNonNull(libraryName, "libraryName must not be null");
        Objects.requireNonNull(requiredFor, "requiredFor must not be null");
        Objects.requireNonNull(detectedPath, "detectedPath must not be null");
    }

    /**
     * Creates a status representing a missing library.
     */
    public static NativeLibraryStatus missing(String libraryName, String requiredFor) {
        return new NativeLibraryStatus(libraryName, requiredFor, false, "");
    }

    /**
     * Creates a status representing a found library at the given path.
     */
    public static NativeLibraryStatus found(String libraryName, String requiredFor,
                                            String detectedPath) {
        return new NativeLibraryStatus(libraryName, requiredFor, true, detectedPath);
    }
}
