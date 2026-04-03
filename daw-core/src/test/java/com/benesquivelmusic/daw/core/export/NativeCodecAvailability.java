package com.benesquivelmusic.daw.core.export;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;

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
        return isLibraryAvailable("libmp3lame.so.0", "libmp3lame.so");
    }

    /** Returns {@code true} if libvorbisenc and libogg are available on this system. */
    static boolean isVorbisAvailable() {
        return isLibraryAvailable("libvorbis.so.0", "libvorbis.so")
                && isLibraryAvailable("libvorbisenc.so.2", "libvorbisenc.so")
                && isLibraryAvailable("libogg.so.0", "libogg.so");
    }

    /** Returns {@code true} if libfdk-aac is available on this system. */
    static boolean isFdkAacAvailable() {
        return isLibraryAvailable("libfdk-aac.so.2", "libfdk-aac.so");
    }

    private static boolean isLibraryAvailable(String soName, String fallbackName) {
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup.libraryLookup(soName, arena);
            return true;
        } catch (IllegalArgumentException e1) {
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup.libraryLookup(fallbackName, arena);
                return true;
            } catch (IllegalArgumentException e2) {
                return false;
            }
        }
    }
}
