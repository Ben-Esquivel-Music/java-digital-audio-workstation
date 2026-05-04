package com.benesquivelmusic.daw.core.audio;

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
        results.add(detect(os, "libfdk-aac", "fdk-aac", 2,
                "AAC export"));
        results.add(detect(os, "libogg", "ogg", 0,
                "OGG Vorbis import and export"));
        results.add(detect(os, "libvorbis", "vorbis", 0,
                "OGG Vorbis import and export"));
        results.add(detect(os, "libvorbisenc", "vorbisenc", 2,
                "OGG Vorbis import and export"));
        results.add(detect(os, "libvorbisfile", "vorbisfile", 3,
                "OGG Vorbis import and export"));

        // asioshim is Windows-only — Steinberg's ASIO SDK is Windows-only,
        // and the `daw-core/native/asio/CMakeLists.txt` silently skips the
        // target on Linux / macOS. Probing on non-Windows hosts would
        // always report "missing" and clutter the HelpDialog "Native
        // libraries" tab, so we omit the entry entirely off-platform.
        if (os.contains("win")) {
            results.add(detect(os, "asioshim", "asioshim", 0,
                    "ASIO driver capability bridge (story 224)"));
        }

        return List.copyOf(results);
    }

    /**
     * Returns {@code true} if a native library with the given base name
     * is currently resolvable via the bundled {@code java.library.path}
     * directories or the OS-level system loader.
     *
     * <p>Convenience overload that defaults {@code soVersion} to {@code 0}.
     * This is correct for libraries whose runtime SONAME is 0 (e.g.
     * {@code libogg.so.0}) or whose platform naming has no versioned
     * variant (e.g. {@code asioshim.dll} on Windows). For libraries
     * with a non-zero SONAME (e.g. {@code libvorbisenc.so.2}), use
     * {@link #isAvailable(String, int)} instead.</p>
     *
     * @param baseName the library base name (no {@code lib} prefix, no
     *                 platform suffix); must not be null
     * @return true iff the library is loadable on the current host
     * @see #isAvailable(String, int)
     */
    public static boolean isAvailable(String baseName) {
        return isAvailable(baseName, 0);
    }

    /**
     * Returns {@code true} if a native library with the given base name
     * and SOVERSION is currently resolvable via the bundled
     * {@code java.library.path} directories or the OS-level system loader.
     *
     * <p>This is a lightweight existence check: it does not enumerate
     * symbols and does not consult {@link #detectAll()}'s display-name
     * mapping. Callers pass the same base name they would hand to
     * {@link java.lang.foreign.SymbolLookup#libraryLookup(String,
     * java.lang.foreign.Arena)} (e.g. {@code "asioshim"}, {@code "vorbis"})
     * together with the library's runtime SOVERSION (e.g. {@code 0} for
     * {@code libogg.so.0}, {@code 2} for {@code libvorbisenc.so.2}).</p>
     *
     * <p>The {@code soVersion} is used to generate the versioned
     * platform candidate names via
     * {@link NativeLibraryLoader#platformLibraryNames(String, String, int)},
     * matching the same resolution used by {@link #detectAll()} and
     * {@link NativeLibraryLoader#loadLibrary(Arena, String, int)}.</p>
     *
     * @param baseName  the library base name (no {@code lib} prefix, no
     *                  platform suffix); must not be null
     * @param soVersion the SONAME version number (e.g. {@code 0} for
     *                  {@code libogg.so.0}, {@code 2} for
     *                  {@code libvorbisenc.so.2}); on Windows this
     *                  parameter is ignored since DLLs are unversioned
     * @return true iff the library is loadable on the current host
     */
    public static boolean isAvailable(String baseName, int soVersion) {
        java.util.Objects.requireNonNull(baseName, "baseName must not be null");
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] names = NativeLibraryLoader.platformLibraryNames(os, baseName, soVersion);
        if (NativeLibraryLoader.findFirstLoadableInLibraryPath(names) != null) {
            return true;
        }
        for (String name : names) {
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup.libraryLookup(name, arena);
                return true;
            } catch (IllegalArgumentException | UnsatisfiedLinkError _) {
                // try next candidate
            }
        }
        return false;
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
        String[] names = NativeLibraryLoader.platformLibraryNames(os, baseName, soVersion);

        // 1. Search bundled libraries in java.library.path
        Path bundledPath = NativeLibraryLoader.findFirstLoadableInLibraryPath(names);
        if (bundledPath != null) {
            return NativeLibraryStatus.found(displayName, requiredFor,
                    bundledPath.toString());
        }

        // 2. Fall back to OS-level system loader
        for (String name : names) {
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup.libraryLookup(name, arena);
                // Library is loadable — try to resolve its absolute path
                String resolved = resolveSystemLibraryPath(os, name);
                return NativeLibraryStatus.found(displayName, requiredFor, resolved);
            } catch (IllegalArgumentException | UnsatisfiedLinkError _) {
                // try next candidate
            }
        }

        return NativeLibraryStatus.missing(displayName, requiredFor);
    }

    /**
     * Well-known system library directories to probe when a library was loaded
     * by name (system fallback) and we want the resolved absolute path.
     */
    private static final String[] SYSTEM_LIB_DIRS = {
            "/usr/lib",
            "/usr/lib64",
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu",
            "/usr/local/lib",
            "/usr/local/lib64",
            "/lib",
            "/lib64",
            "/lib/x86_64-linux-gnu",
            "/lib/aarch64-linux-gnu",
    };

    /**
     * Attempts to resolve the absolute path of a system-loaded library by
     * searching well-known system directories.  Falls back to the bare name
     * wrapped in a {@code (system: ...)} marker if no file is found on disk.
     */
    private static String resolveSystemLibraryPath(String os, String fileName) {
        if (os.contains("win") || os.contains("mac")) {
            // On Windows/macOS the system loader doesn't use /usr/lib paths;
            // fall back to the bare name indicator.
            return "(system: " + fileName + ")";
        }
        for (String dir : SYSTEM_LIB_DIRS) {
            try {
                Path candidate = Path.of(dir, fileName).toAbsolutePath();
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
                // Also check for symlinks pointing to versioned names
                if (Files.exists(candidate)) {
                    return candidate.toRealPath().toString();
                }
            } catch (InvalidPathException | java.io.IOException _) {
                // skip unresolvable directory
            }
        }
        return "(system: " + fileName + ")";
    }
}
