package com.benesquivelmusic.daw.core.export;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility for checking native codec library availability at test time.
 * Used with JUnit {@link org.junit.jupiter.api.Assumptions} to skip
 * tests that require native libraries not installed on the system.
 */
final class NativeCodecAvailability {

    private NativeCodecAvailability() {
        // utility class
    }

    /** Returns {@code true} if libmp3lame is available on this system. */
    static boolean isLameAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return isAnyLibraryAvailable("libmp3lame", "mp3lame", "lame");
        } else if (os.contains("mac")) {
            return isAnyLibraryAvailable("libmp3lame.dylib", "libmp3lame.0.dylib");
        } else {
            return isAnyLibraryAvailable("libmp3lame.so.0", "libmp3lame.so");
        }
    }

    /** Returns {@code true} if libvorbisenc and libogg are available on this system. */
    static boolean isVorbisAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return isAnyLibraryAvailable("vorbis", "libvorbis")
                    && isAnyLibraryAvailable("vorbisenc", "libvorbisenc")
                    && isAnyLibraryAvailable("ogg", "libogg");
        } else if (os.contains("mac")) {
            return isAnyLibraryAvailable("libvorbis.dylib", "libvorbis.0.dylib")
                    && isAnyLibraryAvailable("libvorbisenc.dylib", "libvorbisenc.2.dylib")
                    && isAnyLibraryAvailable("libogg.dylib", "libogg.0.dylib");
        } else {
            return isAnyLibraryAvailable("libvorbis.so.0", "libvorbis.so")
                    && isAnyLibraryAvailable("libvorbisenc.so.2", "libvorbisenc.so")
                    && isAnyLibraryAvailable("libogg.so.0", "libogg.so");
        }
    }

    /** Returns {@code true} if libfdk-aac is available on this system. */
    static boolean isFdkAacAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return isAnyLibraryAvailable("fdk-aac", "libfdk-aac");
        } else if (os.contains("mac")) {
            return isAnyLibraryAvailable("libfdk-aac.dylib", "libfdk-aac.2.dylib");
        } else {
            return isAnyLibraryAvailable("libfdk-aac.so.2", "libfdk-aac.so");
        }
    }

    private static boolean isAnyLibraryAvailable(String... names) {
        // 1. Try OS-level loader (system-installed libraries)
        for (String name : names) {
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup.libraryLookup(name, arena);
                return true;
            } catch (IllegalArgumentException _) {
                // try next candidate
            }
        }
        // 2. Search java.library.path directories (project-built libraries)
        String libraryPath = System.getProperty("java.library.path", "");
        if (!libraryPath.isEmpty()) {
            // Derive filenames: bare names need platform extension appended
            String[] fileNames = resolveFileNames(names);
            for (String dir : libraryPath.split(File.pathSeparator)) {
                for (String fileName : fileNames) {
                    Path candidate = Path.of(dir, fileName);
                    if (Files.isRegularFile(candidate)) {
                        try (Arena arena = Arena.ofConfined()) {
                            SymbolLookup.libraryLookup(candidate, arena);
                            return true;
                        } catch (IllegalArgumentException _) {
                            // file exists but not loadable
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Ensures each name has a platform file extension for path-based lookup. */
    private static String[] resolveFileNames(String... names) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String ext;
        if (os.contains("win")) {
            ext = ".dll";
        } else if (os.contains("mac")) {
            ext = ".dylib";
        } else {
            ext = ".so";
        }
        String[] result = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            result[i] = names[i].contains(".") ? names[i] : names[i] + ext;
        }
        return result;
    }
}
