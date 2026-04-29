package com.benesquivelmusic.daw.app.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists the user's most-recently-executed command-palette entries to
 * {@code ~/.daw/command-palette-recents.json}.
 *
 * <p>The on-disk format is a tiny flat JSON array of string IDs — the
 * stable {@link CommandPaletteEntry#id()} for each recent — most-recent
 * first:</p>
 * <pre>{@code
 * ["NEW_PROJECT", "PLAY_STOP", "ADD_AUDIO_TRACK"]
 * }</pre>
 *
 * <p>Up to {@link #MAX_RECENTS} entries are kept. Unknown or corrupt files
 * are treated as "no recents." This store is intentionally dependency-free
 * (no Jackson) to match the lightweight JSON-handling style used elsewhere
 * in the project (see {@code MetronomeSettingsStore}).</p>
 */
public final class CommandPaletteRecentsStore {

    private static final Logger LOG = Logger.getLogger(CommandPaletteRecentsStore.class.getName());

    /** Default relative path under {@code user.home}. */
    public static final String DEFAULT_RELATIVE_PATH = ".daw/command-palette-recents.json";

    /** Maximum number of recent entries persisted (and surfaced in the UI). */
    public static final int MAX_RECENTS = 5;

    private final Path file;

    /** Creates a store rooted at {@code ~/.daw/command-palette-recents.json}. */
    public CommandPaletteRecentsStore() {
        this(Path.of(System.getProperty("user.home", "."))
                .resolve(DEFAULT_RELATIVE_PATH));
    }

    /** Creates a store at an explicit path (used by tests). */
    public CommandPaletteRecentsStore(Path file) {
        this.file = Objects.requireNonNull(file, "file must not be null");
    }

    /** Returns the on-disk path used by this store. */
    public Path file() {
        return file;
    }

    /**
     * Loads the persisted recents, most-recent first. Returns an empty list
     * if the file is missing or unreadable.
     */
    public List<String> load() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<String> ids = parseJsonArray(json);
            if (ids == null) {
                return List.of();
            }
            // Defensive cap.
            if (ids.size() > MAX_RECENTS) {
                return List.copyOf(ids.subList(0, MAX_RECENTS));
            }
            return List.copyOf(ids);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not read recents file " + file, e);
            return List.of();
        }
    }

    /**
     * Records {@code id} as the most-recently-used entry, deduplicating
     * earlier occurrences and trimming the list to {@link #MAX_RECENTS}.
     * Returns the new list, most-recent first.
     */
    public List<String> recordExecution(String id) {
        Objects.requireNonNull(id, "id must not be null");
        List<String> current = new ArrayList<>(load());
        current.removeIf(existing -> existing.equals(id));
        current.add(0, id);
        while (current.size() > MAX_RECENTS) {
            current.remove(current.size() - 1);
        }
        save(current);
        return Collections.unmodifiableList(current);
    }

    /**
     * Persists the given list (most-recent first) to disk. Failures are
     * logged at {@code FINE} — the palette must remain usable when the
     * filesystem is read-only or otherwise unwritable.
     */
    public void save(List<String> ids) {
        Objects.requireNonNull(ids, "ids must not be null");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = writeJsonArray(ids);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not write recents file " + file, e);
        }
    }

    // ── JSON helpers (flat array of strings) ────────────────────────────────
    static String writeJsonArray(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(ids.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    static List<String> parseJsonArray(String json) {
        if (json == null) return null;
        String s = json.trim();
        if (!s.startsWith("[") || !s.endsWith("]")) {
            return null;
        }
        s = s.substring(1, s.length() - 1).trim();
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) return out;
        int i = 0;
        while (i < s.length()) {
            // skip whitespace and commas
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) {
                i++;
            }
            if (i >= s.length()) break;
            if (s.charAt(i) != '"') {
                return null;
            }
            i++; // opening quote
            StringBuilder val = new StringBuilder();
            while (i < s.length() && s.charAt(i) != '"') {
                char ch = s.charAt(i);
                if (ch == '\\' && i + 1 < s.length()) {
                    char next = s.charAt(i + 1);
                    val.append(switch (next) {
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        default -> next;
                    });
                    i += 2;
                } else {
                    val.append(ch);
                    i++;
                }
            }
            if (i >= s.length()) return null; // unterminated string
            i++; // closing quote
            out.add(val.toString());
        }
        return out;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
}
