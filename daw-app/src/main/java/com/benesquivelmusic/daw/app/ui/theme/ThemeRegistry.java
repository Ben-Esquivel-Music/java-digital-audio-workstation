package com.benesquivelmusic.daw.app.ui.theme;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads bundled and user themes and exposes them as a single ordered list.
 *
 * <p>Bundled themes are loaded from classpath resources under
 * {@code /themes/}; the bundled set is fixed at three:
 * {@code dark-accessible}, {@code light-accessible}, {@code high-contrast}.
 * User themes are loaded from {@code ~/.daw/themes/*.json}, which the
 * "Duplicate and edit" workflow writes into.</p>
 *
 * <p>If two themes share an {@code id}, the user theme wins — this lets a
 * customized version of a bundled theme override the default.</p>
 */
public final class ThemeRegistry {

    private static final Logger LOG = Logger.getLogger(ThemeRegistry.class.getName());

    /** Stable id of the default theme used at first launch. */
    public static final String DEFAULT_THEME_ID = "dark-accessible";

    /** IDs of bundled themes, in display order. */
    public static final List<String> BUNDLED_IDS = List.of(
            "dark-accessible",
            "light-accessible",
            "high-contrast");

    /** Default relative path under {@code user.home} for user themes. */
    public static final String DEFAULT_USER_THEMES_RELATIVE_PATH = ".daw/themes";

    private final Path userThemesDir;
    private final Map<String, Theme> themes;

    /** Creates a registry rooted at {@code ~/.daw/themes/}. */
    public ThemeRegistry() {
        this(Path.of(System.getProperty("user.home", "."))
                .resolve(DEFAULT_USER_THEMES_RELATIVE_PATH));
    }

    /** Creates a registry with an explicit user-themes directory (for tests). */
    public ThemeRegistry(Path userThemesDir) {
        this.userThemesDir = Objects.requireNonNull(
                userThemesDir, "userThemesDir must not be null");
        this.themes = new LinkedHashMap<>();
        reload();
    }

    /** Reloads bundled and user themes from disk and clears the cache. */
    public void reload() {
        themes.clear();
        for (String id : BUNDLED_IDS) {
            Theme t = loadBundled(id);
            if (t != null) {
                themes.put(t.id(), t);
            }
        }
        loadUserThemes();
    }

    private static Theme loadBundled(String id) {
        String resource = "/themes/" + id + ".json";
        try (InputStream in = ThemeRegistry.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOG.log(Level.WARNING, "Bundled theme not found on classpath: {0}", resource);
                return null;
            }
            return ThemeJson.load(in);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to load bundled theme " + resource, e);
            return null;
        }
    }

    private void loadUserThemes() {
        if (!Files.isDirectory(userThemesDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(userThemesDir, "*.json")) {
            for (Path file : stream) {
                try {
                    Theme t = ThemeJson.load(file);
                    themes.put(t.id(), t); // user theme overrides bundled
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Failed to load user theme " + file, e);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to enumerate user themes in " + userThemesDir, e);
        }
    }

    /** Returns all themes in display order (bundled first, then user). */
    public List<Theme> themes() {
        return List.copyOf(themes.values());
    }

    /** Returns the theme with the given id, if any. */
    public Optional<Theme> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(themes.get(id));
    }

    /**
     * Returns the theme with the given id, falling back to the default theme
     * — and to the first available theme if even the default is missing.
     */
    public Theme findOrDefault(String id) {
        return find(id)
                .or(() -> find(DEFAULT_THEME_ID))
                .orElseGet(() -> {
                    List<Theme> all = themes();
                    if (all.isEmpty()) {
                        throw new IllegalStateException(
                                "No themes available — bundled themes failed to load");
                    }
                    return all.get(0);
                });
    }

    /** Returns the directory used for user themes. */
    public Path userThemesDir() {
        return userThemesDir;
    }

    /**
     * Saves a user theme into {@link #userThemesDir()} as
     * {@code <id>.json} and registers it. Overwrites any existing file
     * with the same id.
     *
     * @return the path the theme was written to
     */
    public Path saveUserTheme(Theme theme) throws IOException {
        Objects.requireNonNull(theme, "theme must not be null");
        Path target = userThemesDir.resolve(theme.id() + ".json");
        ThemeJson.write(theme, target);
        themes.put(theme.id(), theme);
        return target;
    }

    /**
     * Returns audit reports for every loaded theme — used by the picker
     * dialog and by tests asserting bundled themes pass AA.
     */
    public List<ThemeAuditReport> auditAll() {
        List<ThemeAuditReport> out = new ArrayList<>(themes.size());
        for (Theme t : themes.values()) {
            out.add(ThemeAuditReport.audit(t));
        }
        return Collections.unmodifiableList(out);
    }
}
