package com.benesquivelmusic.daw.core.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.prefs.Preferences;

/**
 * Persists a list of recently opened project paths using {@link Preferences}.
 *
 * <p>The list is capped at a configurable maximum. When a project is added
 * that already exists in the list, it is moved to the front. The store
 * survives application restarts because the {@link Preferences} API writes
 * to the platform-native backing store (the Windows registry on Windows,
 * {@code ~/Library/Preferences} on macOS, or {@code ~/.java/.userPrefs} on
 * Linux).</p>
 */
public final class RecentProjectsStore {

    private static final String KEY_PREFIX = "recent_project_";
    private static final String KEY_COUNT = "recent_project_count";
    static final int DEFAULT_MAX_ENTRIES = 10;

    private final Preferences prefs;
    private final int maxEntries;

    /**
     * Creates a store backed by the given {@link Preferences} node.
     *
     * @param prefs      the preferences node to use for storage
     * @param maxEntries the maximum number of recent entries to retain
     */
    public RecentProjectsStore(Preferences prefs, int maxEntries) {
        this.prefs = Objects.requireNonNull(prefs, "prefs must not be null");
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        this.maxEntries = maxEntries;
    }

    /**
     * Creates a store with the default maximum of {@value DEFAULT_MAX_ENTRIES} entries.
     *
     * @param prefs the preferences node to use for storage
     */
    public RecentProjectsStore(Preferences prefs) {
        this(prefs, DEFAULT_MAX_ENTRIES);
    }

    /**
     * Adds a project path to the front of the recent list.
     *
     * <p>If the path already exists in the list, it is moved to the front.
     * If the list exceeds the maximum, the oldest entry is removed.</p>
     *
     * @param projectPath the project directory path
     */
    public void addRecentProject(Path projectPath) {
        Objects.requireNonNull(projectPath, "projectPath must not be null");
        List<String> paths = loadPaths();
        String pathStr = projectPath.toAbsolutePath().toString();

        paths.remove(pathStr);
        paths.addFirst(pathStr);

        while (paths.size() > maxEntries) {
            paths.removeLast();
        }

        savePaths(paths);
    }

    /**
     * Returns the list of recent project paths, most recent first.
     *
     * @return unmodifiable list of recent project paths
     */
    public List<Path> getRecentProjectPaths() {
        List<String> paths = loadPaths();
        List<Path> result = new ArrayList<>(paths.size());
        for (String path : paths) {
            result.add(Path.of(path));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Removes a specific project path from the recent list.
     *
     * @param projectPath the project directory path to remove
     * @return {@code true} if the path was present and removed
     */
    public boolean removeRecentProject(Path projectPath) {
        Objects.requireNonNull(projectPath, "projectPath must not be null");
        List<String> paths = loadPaths();
        String pathStr = projectPath.toAbsolutePath().toString();
        boolean removed = paths.remove(pathStr);
        if (removed) {
            savePaths(paths);
        }
        return removed;
    }

    /**
     * Clears all recent project entries.
     */
    public void clear() {
        int count = prefs.getInt(KEY_COUNT, 0);
        for (int i = 0; i < count; i++) {
            prefs.remove(KEY_PREFIX + i);
        }
        prefs.putInt(KEY_COUNT, 0);
    }

    /**
     * Returns the maximum number of recent entries this store retains.
     *
     * @return the maximum number of entries
     */
    public int getMaxEntries() {
        return maxEntries;
    }

    private List<String> loadPaths() {
        int count = prefs.getInt(KEY_COUNT, 0);
        List<String> paths = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String path = prefs.get(KEY_PREFIX + i, null);
            if (path != null && !path.isBlank()) {
                paths.add(path);
            }
        }
        return paths;
    }

    private void savePaths(List<String> paths) {
        int oldCount = prefs.getInt(KEY_COUNT, 0);
        for (int i = 0; i < oldCount; i++) {
            prefs.remove(KEY_PREFIX + i);
        }
        prefs.putInt(KEY_COUNT, paths.size());
        for (int i = 0; i < paths.size(); i++) {
            prefs.put(KEY_PREFIX + i, paths.get(i));
        }
    }
}
