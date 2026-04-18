package com.benesquivelmusic.daw.core.audio;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * OS-aware native library loader for the FFM API (JEP 454).
 *
 * <p>Centralises the platform-specific library name resolution and
 * search strategy shared by the OGG Vorbis importer/exporter and
 * other native codec bindings.  The search order is:</p>
 * <ol>
 *   <li>Bundled libraries in {@code java.library.path} (project-built)</li>
 *   <li>OS-level library loader (system-installed)</li>
 * </ol>
 */
public final class NativeLibraryLoader {

    private NativeLibraryLoader() {
        // utility class
    }

    /**
     * Loads a native library in an OS-aware way, preferring bundled
     * libraries in {@code java.library.path} over system-installed ones.
     *
     * @param arena     the arena for the library lifetime
     * @param baseName  the library base name (e.g. "vorbis", "vorbisenc", "ogg")
     * @param soVersion the SONAME version number (e.g. 0 for libvorbis.so.0)
     * @return a {@link SymbolLookup} for the loaded library
     * @throws UnsupportedOperationException if the library cannot be found
     */
    public static SymbolLookup loadLibrary(Arena arena, String baseName,
                                           int soVersion) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] names = platformLibraryNames(os, baseName, soVersion);

        // 1. Prefer bundled libraries in java.library.path
        Optional<SymbolLookup> bundled = searchLibraryPath(arena, names);
        if (bundled.isPresent()) {
            return bundled.get();
        }

        // 2. Fall back to OS-level library loader (system-installed)
        for (String name : names) {
            try {
                return SymbolLookup.libraryLookup(name, arena);
            } catch (IllegalArgumentException _) {
                // try next candidate
            }
        }

        // Platform-aware, library-specific error message
        String installHint = installHint(os, baseName);
        String searchedNames = String.join(", ", names);
        String libraryPath = System.getProperty("java.library.path", "");
        throw new UnsupportedOperationException(
                "Could not load lib" + baseName + " from bundled native directory "
                        + (libraryPath.isEmpty() ? "(none configured)" : libraryPath)
                        + " or system libraries (tried: " + searchedNames + "). "
                        + "Install lib" + baseName + " (e.g., " + installHint + ").");
    }

    /**
     * Searches {@code java.library.path} directories for any of the given
     * library filenames, loading via {@link SymbolLookup#libraryLookup(Path, Arena)}.
     *
     * @param arena     the arena for the library lifetime
     * @param fileNames platform-specific library file names to search for
     * @return an {@link Optional} containing the loaded library, or empty
     */
    public static Optional<SymbolLookup> searchLibraryPath(Arena arena,
                                                           String... fileNames) {
        Path found = findFirstLoadableInLibraryPath(fileNames);
        if (found != null) {
            try {
                return Optional.of(SymbolLookup.libraryLookup(found, arena));
            } catch (IllegalArgumentException | UnsatisfiedLinkError _) {
                // race: file disappeared or became unloadable between find and load
            }
        }
        return Optional.empty();
    }

    /**
     * Searches {@code java.library.path} for the first regular file matching
     * any of the given filenames that can be loaded as a native library.
     * Returns the absolute {@link Path} of the first loadable candidate, or
     * {@code null} if none is found.
     *
     * <p>This is the shared helper used by both the loader (to obtain a
     * {@link SymbolLookup}) and the detector (to resolve the on-disk path).
     * Keeping the path-scanning logic in one place prevents drift between
     * the two code paths.</p>
     *
     * @param fileNames platform-specific library file names to search for
     * @return the absolute path of the first loadable candidate, or null
     */
    static Path findFirstLoadableInLibraryPath(String... fileNames) {
        String libraryPath = System.getProperty("java.library.path", "");
        if (libraryPath.isEmpty()) {
            return null;
        }
        for (String dir : libraryPath.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path dirPath = Path.of(dir).normalize();
                if (dirPath.toString().isEmpty()) {
                    continue;
                }
                for (String fileName : fileNames) {
                    Path candidate = dirPath.resolve(fileName).toAbsolutePath();
                    if (Files.isRegularFile(candidate)) {
                        try (Arena arena = Arena.ofConfined()) {
                            SymbolLookup.libraryLookup(candidate, arena);
                            return candidate;
                        } catch (IllegalArgumentException | UnsatisfiedLinkError _) {
                            // file exists but not loadable — try next
                        }
                    }
                }
            } catch (InvalidPathException _) {
                // malformed path segment — skip
            }
        }
        return null;
    }

    // ── Internal helpers ───────────────────────────────────────────────

    /**
     * Returns platform-specific library file names for the given base name
     * and SOVERSION. This is the single source of truth for platform name
     * resolution, shared by both {@link NativeLibraryLoader} and
     * {@link NativeLibraryDetector}.
     *
     * @param os        lowercase OS name from {@code os.name}
     * @param baseName  the library base name (e.g. "vorbis")
     * @param soVersion the SONAME version number
     * @return candidate file names in priority order
     */
    static String[] platformLibraryNames(String os, String baseName,
                                          int soVersion) {
        if (os.contains("win")) {
            return new String[]{baseName + ".dll", "lib" + baseName + ".dll"};
        } else if (os.contains("mac")) {
            return new String[]{
                    "lib" + baseName + "." + soVersion + ".dylib",
                    "lib" + baseName + ".dylib"};
        } else {
            return new String[]{
                    "lib" + baseName + ".so." + soVersion,
                    "lib" + baseName + ".so"};
        }
    }

    private static String installHint(String os, String baseName) {
        if (os.contains("win")) {
            return "build with CMake and ensure " + baseName
                    + ".dll is in the application directory or PATH";
        } else if (os.contains("mac")) {
            String brewPkg = switch (baseName) {
                case "ogg" -> "libogg";
                case "vorbis", "vorbisenc", "vorbisfile" -> "libvorbis";
                default -> "lib" + baseName;
            };
            return "'brew install " + brewPkg + "' on macOS";
        } else {
            String debPkg = switch (baseName) {
                case "ogg" -> "libogg0";
                case "vorbis" -> "libvorbis0a";
                case "vorbisenc" -> "libvorbisenc2";
                case "vorbisfile" -> "libvorbisfile3";
                default -> "lib" + baseName + "0";
            };
            return "'apt install " + debPkg + "' on Debian/Ubuntu";
        }
    }
}
