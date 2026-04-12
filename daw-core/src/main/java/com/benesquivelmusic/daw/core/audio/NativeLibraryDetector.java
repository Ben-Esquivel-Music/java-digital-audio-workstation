package com.benesquivelmusic.daw.core.audio;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects the availability of required native libraries at application
 * startup. For each library, the detector searches the bundled
 * {@code native/} directory first (via {@code java.library.path}), then
 * falls back to the OS-level system library loader.
 *
 * <p>Results are returned as immutable {@link NativeLibraryStatus} records
 * that include the resolved absolute path from which each library was
 * loaded, suitable for display in a System Capabilities panel.</p>
 */
public final class NativeLibraryDetector {

    private NativeLibraryDetector() {
        // utility class
    }

    /**
     * Probes all known native libraries and returns their availability
     * status. The search order for each library is:
     * <ol>
     *   <li>Bundled libraries in {@code java.library.path} directories</li>
     *   <li>OS-level system library loader</li>
     * </ol>
     *
     * @return an unmodifiable list of {@link NativeLibraryStatus} for every
     *         known native library
     */
    public static List<NativeLibraryStatus> detectAll() {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<NativeLibraryStatus> results = new ArrayList<>();

        results.add(detect(os, "libportaudio", "portaudio", 2,
                "Real-time audio I/O"));
        results.add(detect(os, "FluidSynth", "fluidsynth", 3,
                "SoundFont synthesis (MIDI playback)"));
        results.add(detect(os, "libmp3lame", "mp3lame", 0,
                "MP3 export"));
        results.add(detect(os, "libogg", "ogg", 0,
                "OGG Vorbis import and export"));
        results.add(detect(os, "libvorbis", "vorbis", 0,
                "OGG Vorbis import and export"));
        results.add(detect(os, "libvorbisenc", "vorbisenc", 2,
                "OGG Vorbis import and export"));
        results.add(detect(os, "libvorbisfile", "vorbisfile", 3,
                "OGG Vorbis import and export"));

        return List.copyOf(results);
    }

    /**
     * Detects a single native library by its base name and SOVERSION.
     *
     * @param displayName  the human-readable name (e.g. "libogg")
     * @param baseName     the base name for platform-specific file resolution
     * @param soVersion    the SONAME version number
     * @param requiredFor  description of what the library is needed for
     * @return a {@link NativeLibraryStatus} for the library
     */
    static NativeLibraryStatus detect(String os, String displayName,
                                      String baseName, int soVersion,
                                      String requiredFor) {
        String[] names = platformLibraryNames(os, baseName, soVersion);

        // 1. Search bundled libraries in java.library.path
        String bundledPath = searchLibraryPath(names);
        if (bundledPath != null) {
            return NativeLibraryStatus.found(displayName, requiredFor, bundledPath);
        }

        // 2. Fall back to OS-level system loader
        for (String name : names) {
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup.libraryLookup(name, arena);
                return NativeLibraryStatus.found(displayName, requiredFor,
                        "(system: " + name + ")");
            } catch (IllegalArgumentException _) {
                // try next candidate
            }
        }

        return NativeLibraryStatus.missing(displayName, requiredFor);
    }

    /**
     * Searches {@code java.library.path} directories for any of the given
     * library filenames and returns the absolute path of the first match,
     * or {@code null} if none is found.
     */
    private static String searchLibraryPath(String... fileNames) {
        String libraryPath = System.getProperty("java.library.path", "");
        if (libraryPath.isEmpty()) {
            return null;
        }
        for (String dir : libraryPath.split(File.pathSeparator)) {
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
                        // Verify the library is actually loadable
                        try (Arena arena = Arena.ofConfined()) {
                            SymbolLookup.libraryLookup(candidate, arena);
                            return candidate.toString();
                        } catch (IllegalArgumentException _) {
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

    private static String[] platformLibraryNames(String os, String baseName,
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
}
