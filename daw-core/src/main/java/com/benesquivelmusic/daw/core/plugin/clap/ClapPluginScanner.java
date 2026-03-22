package com.benesquivelmusic.daw.core.plugin.clap;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Scans the filesystem for CLAP plugin bundles ({@code .clap} files).
 *
 * <p>CLAP plugins are distributed as platform-specific shared libraries with
 * the {@code .clap} extension. This scanner searches well-known system paths
 * and user-specified directories for plugin files.</p>
 *
 * <h2>Default Search Paths</h2>
 * <ul>
 *   <li><b>Linux:</b> {@code ~/.clap}, {@code /usr/lib/clap}</li>
 *   <li><b>macOS:</b> {@code ~/Library/Audio/Plug-Ins/CLAP}, {@code /Library/Audio/Plug-Ins/CLAP}</li>
 *   <li><b>Windows:</b> {@code %LOCALAPPDATA%\Programs\Common\CLAP},
 *       {@code %COMMONPROGRAMFILES%\CLAP}</li>
 * </ul>
 */
public final class ClapPluginScanner {

    private final List<Path> searchPaths;

    /**
     * Creates a scanner with the default system search paths for the current OS.
     */
    public ClapPluginScanner() {
        this(defaultSearchPaths());
    }

    /**
     * Creates a scanner with the specified search paths.
     *
     * @param searchPaths the directories to scan for {@code .clap} files
     */
    public ClapPluginScanner(List<Path> searchPaths) {
        Objects.requireNonNull(searchPaths, "searchPaths must not be null");
        this.searchPaths = List.copyOf(searchPaths);
    }

    /**
     * Returns the configured search paths.
     *
     * @return unmodifiable list of search paths
     */
    public List<Path> getSearchPaths() {
        return searchPaths;
    }

    /**
     * Scans all configured search paths for {@code .clap} files.
     *
     * <p>Only regular files (or directories that are CLAP bundles) with the
     * {@code .clap} extension are returned. Non-existent or unreadable
     * directories are silently skipped.</p>
     *
     * @return a list of paths to discovered CLAP plugin files
     */
    public List<Path> scan() {
        var results = new ArrayList<Path>();
        for (Path dir : searchPaths) {
            if (Files.isDirectory(dir)) {
                scanDirectory(dir, results);
            }
        }
        return Collections.unmodifiableList(results);
    }

    /**
     * Scans a single directory for {@code .clap} files (non-recursive).
     *
     * @param directory the directory to scan
     * @return a list of paths to discovered CLAP plugin files in this directory
     */
    public List<Path> scanDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        var results = new ArrayList<Path>();
        scanDirectory(directory, results);
        return Collections.unmodifiableList(results);
    }

    private void scanDirectory(Path directory, List<Path> results) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.clap")) {
            for (Path entry : stream) {
                results.add(entry);
            }
        } catch (IOException _) {
            // Skip unreadable directories
        }
    }

    /**
     * Returns the default CLAP plugin search paths for the current operating system.
     *
     * @return the default search paths
     */
    public static List<Path> defaultSearchPaths() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", "");

        if (os.contains("mac")) {
            return List.of(
                    Path.of(home, "Library", "Audio", "Plug-Ins", "CLAP"),
                    Path.of("/Library", "Audio", "Plug-Ins", "CLAP")
            );
        } else if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            String commonFiles = System.getenv("COMMONPROGRAMFILES");
            var paths = new ArrayList<Path>();
            if (localAppData != null) {
                paths.add(Path.of(localAppData, "Programs", "Common", "CLAP"));
            }
            if (commonFiles != null) {
                paths.add(Path.of(commonFiles, "CLAP"));
            }
            return Collections.unmodifiableList(paths);
        } else {
            // Linux and other UNIX-like systems
            return List.of(
                    Path.of(home, ".clap"),
                    Path.of("/usr", "lib", "clap")
            );
        }
    }
}
