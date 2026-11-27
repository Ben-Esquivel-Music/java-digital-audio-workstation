package com.benesquivelmusic.daw.core.audio;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The precondition of the PortAudio positive tests: a PortAudio build sits in
 * a {@code java.library.path} directory. It is found by a file-name glob,
 * never by the loader or the detector, whose answer is what those tests check,
 * so a library the Java side cannot find fails them instead of skipping them.
 */
public final class BundledPortAudio {

    private BundledPortAudio() {
    }

    /**
     * Skips the calling test when no PortAudio build is on
     * {@code java.library.path}.
     */
    public static void assumePresent() {
        assumeTrue(isPresent(), "no file matching " + glob() + " in any java.library.path directory ("
                + System.getProperty("java.library.path", "") + "): the native build did not run, as with"
                + " -DskipNativeBuild=true, so there is no PortAudio build to load");
    }

    /**
     * The file names a PortAudio build can carry on this operating system.
     *
     * @return {@code portaudio*.dll} on Windows, {@code libportaudio*.dylib} on
     *         macOS, {@code libportaudio*.so*} elsewhere
     */
    public static String glob() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "portaudio*.dll";
        }
        if (os.contains("mac")) {
            return "libportaudio*.dylib";
        }
        return "libportaudio*.so*";
    }

    private static boolean isPresent() {
        String glob = glob();
        for (String entry : System.getProperty("java.library.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank() && directoryHolds(entry, glob)) {
                return true;
            }
        }
        return false;
    }

    private static boolean directoryHolds(String entry, String glob) {
        Path directory;
        try {
            directory = Path.of(entry);
        } catch (InvalidPathException malformed) {
            return false;
        }
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (DirectoryStream<Path> matches = Files.newDirectoryStream(directory, glob)) {
            return matches.iterator().hasNext();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }
}
