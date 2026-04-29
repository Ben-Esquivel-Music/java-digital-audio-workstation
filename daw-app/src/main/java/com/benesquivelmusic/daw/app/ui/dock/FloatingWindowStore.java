package com.benesquivelmusic.daw.app.ui.dock;

import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists floating-window bounds across application restarts.
 *
 * <p>The store keeps a single JSON file under
 * {@code ~/.daw/dock/floating.json} mapping panel-id to last-known
 * floating bounds. Restoring the same panel later re-uses the bounds so
 * users don't have to re-position windows after every restart.</p>
 *
 * <p>The store is deliberately tolerant: malformed files are treated as
 * "no remembered bounds" rather than throwing, so a corrupted file can
 * never block application startup.</p>
 */
public final class FloatingWindowStore {

    private static final Logger LOG = Logger.getLogger(FloatingWindowStore.class.getName());

    /** Default per-user directory under {@code user.home}. */
    public static final String DEFAULT_RELATIVE_DIR = ".daw/dock";
    /** Default file name written under {@link #DEFAULT_RELATIVE_DIR}. */
    public static final String DEFAULT_FILE_NAME = "floating.json";

    private final Path file;
    private final Map<String, Rectangle2D> cache = new LinkedHashMap<>();
    private boolean loaded;

    /** Creates a store backed by the default {@code ~/.daw/dock/floating.json}. */
    public FloatingWindowStore() {
        this(defaultPath());
    }

    /** Creates a store backed by the given file path (used by tests). */
    public FloatingWindowStore(Path file) {
        this.file = Objects.requireNonNull(file, "file must not be null");
    }

    private static Path defaultPath() {
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, DEFAULT_RELATIVE_DIR, DEFAULT_FILE_NAME);
    }

    /** Returns the path the store reads from / writes to. */
    public Path file() {
        return file;
    }

    /** Returns the remembered bounds for {@code panelId}, if any. */
    public synchronized Optional<Rectangle2D> getBounds(String panelId) {
        Objects.requireNonNull(panelId, "panelId must not be null");
        ensureLoaded();
        return Optional.ofNullable(cache.get(panelId));
    }

    /** Persists {@code bounds} for {@code panelId}, writing the file synchronously. */
    public synchronized void putBounds(String panelId, Rectangle2D bounds) {
        Objects.requireNonNull(panelId, "panelId must not be null");
        Objects.requireNonNull(bounds, "bounds must not be null");
        ensureLoaded();
        cache.put(panelId, bounds);
        save();
    }

    /** Removes any remembered bounds for {@code panelId}. */
    public synchronized void remove(String panelId) {
        Objects.requireNonNull(panelId, "panelId must not be null");
        ensureLoaded();
        if (cache.remove(panelId) != null) save();
    }

    /** Unmodifiable snapshot of every remembered floating window. */
    public synchronized Map<String, Rectangle2D> snapshot() {
        ensureLoaded();
        return Map.copyOf(cache);
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!Files.isReadable(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            // Re-use DockLayoutJson's parser by wrapping bounds as floating
            // entries — simpler than introducing yet another JSON parser.
            DockLayout layout = DockLayoutJson.parse(json);
            for (DockEntry e : layout.entries().values()) {
                if (e.zone() == DockZone.FLOATING && e.floatingBounds() != null) {
                    cache.put(e.panelId(), e.floatingBounds());
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.FINE, ex, () -> "Could not read floating-window store: " + file);
        }
    }

    private void save() {
        // Serialise as a synthetic DockLayout containing one FLOATING
        // entry per remembered window. This keeps the JSON shape uniform
        // with the workspace-embedded layout.
        Map<String, DockEntry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, Rectangle2D> e : cache.entrySet()) {
            entries.put(e.getKey(), DockEntry.floating(e.getKey(), e.getValue()));
        }
        String json = DockLayoutJson.toJson(DockLayout.of(entries));
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException amne) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, ex, () -> "Could not write floating-window store: " + file);
        }
    }
}
