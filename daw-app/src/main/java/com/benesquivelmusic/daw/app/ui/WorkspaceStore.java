package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.ui.PanelState;
import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;
import com.benesquivelmusic.daw.sdk.ui.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists user-saved {@link Workspace}s as one JSON file per workspace
 * under {@code ~/.daw/workspaces/} (the directory is per-user, never
 * per-project, so a user's "Mixing" layout is available in every project).
 *
 * <p>Each file is named {@code <slug>.json} where {@code <slug>} is the
 * workspace name lower-cased and stripped of filesystem-unsafe characters.
 * The on-disk format is hand-rolled JSON to match the dependency-free
 * style used elsewhere in the project (see
 * {@link CommandPaletteRecentsStore} and {@code MetronomeSettingsStore}):</p>
 *
 * <pre>{@code
 * {
 *   "name":"Mixing",
 *   "panelStates":{ "mixer":{"visible":true,"zoom":1.0,"scrollX":0.0,"scrollY":0.0}, ... },
 *   "openDialogs":["audio-settings"],
 *   "panelBounds":{ "mixer":{"x":0,"y":0,"width":800,"height":600}, ... }
 * }
 * }</pre>
 *
 * <p>Workspaces can also be exported to and imported from arbitrary
 * paths via {@link #exportTo(Workspace, Path)} and
 * {@link #importFrom(Path)} for sharing with collaborators.</p>
 */
public final class WorkspaceStore {

    private static final Logger LOG = Logger.getLogger(WorkspaceStore.class.getName());

    /** Default relative directory under {@code user.home}. */
    public static final String DEFAULT_RELATIVE_DIR = ".daw/workspaces";

    private final Path directory;

    /** Creates a store rooted at {@code ~/.daw/workspaces/}. */
    public WorkspaceStore() {
        this(Path.of(System.getProperty("user.home", "."))
                .resolve(DEFAULT_RELATIVE_DIR));
    }

    /** Creates a store rooted at an explicit directory (used by tests). */
    public WorkspaceStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
    }

    /** Returns the on-disk directory used by this store. */
    public Path directory() {
        return directory;
    }

    /**
     * Lists all stored workspaces. Returns an empty list if the directory
     * does not yet exist or cannot be read.
     */
    public List<Workspace> listAll() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Workspace> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            for (Path p : stream) {
                Workspace ws = readFile(p);
                if (ws != null) out.add(ws);
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not list workspaces directory " + directory, e);
        }
        return List.copyOf(out);
    }

    /**
     * Saves a workspace, overwriting any existing file with the same
     * (slugged) name. Returns the resulting on-disk path.
     */
    public Path save(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(slugify(workspace.name()) + ".json");
            Files.writeString(target, toJson(workspace), StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not save workspace " + workspace.name(), e);
            return directory.resolve(slugify(workspace.name()) + ".json");
        }
    }

    /** Deletes the workspace with the given name. Returns {@code true} if a file was removed. */
    public boolean delete(String name) {
        Objects.requireNonNull(name, "name must not be null");
        Path target = directory.resolve(slugify(name) + ".json");
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not delete workspace " + name, e);
            return false;
        }
    }

    /** Exports a workspace to the given path (used for sharing). */
    public void exportTo(Workspace workspace, Path target) throws IOException {
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(target, toJson(workspace), StandardCharsets.UTF_8);
    }

    /**
     * Imports a workspace from the given path. The imported workspace is
     * <em>not</em> automatically added to the store; call {@link #save}
     * to persist it.
     */
    public Workspace importFrom(Path source) throws IOException {
        Objects.requireNonNull(source, "source must not be null");
        Workspace ws = readFile(source);
        if (ws == null) {
            throw new IOException("File is not a valid workspace JSON: " + source);
        }
        return ws;
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private Workspace readFile(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return WorkspaceJson.parse(json);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not read workspace file " + file, e);
            return null;
        }
    }

    static String slugify(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                sb.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                sb.append(Character.toLowerCase(c));
            } else if (c == ' ' || c == '_' || c == '-') {
                sb.append('-');
            }
            // drop everything else (slashes, dots, NUL, …)
        }
        String slug = sb.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) slug = "workspace";
        return slug.toLowerCase(Locale.ROOT);
    }

    static String toJson(Workspace ws) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"name\":\"").append(escape(ws.name())).append('"');
        sb.append(",\"panelStates\":{");
        boolean first = true;
        for (Map.Entry<String, PanelState> e : ws.panelStates().entrySet()) {
            if (!first) sb.append(',');
            first = false;
            PanelState st = e.getValue();
            sb.append('"').append(escape(e.getKey())).append("\":{")
                    .append("\"visible\":").append(st.visible())
                    .append(",\"zoom\":").append(num(st.zoom()))
                    .append(",\"scrollX\":").append(num(st.scrollX()))
                    .append(",\"scrollY\":").append(num(st.scrollY()))
                    .append('}');
        }
        sb.append("},\"openDialogs\":[");
        for (int i = 0; i < ws.openDialogs().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(ws.openDialogs().get(i))).append('"');
        }
        sb.append("],\"panelBounds\":{");
        first = true;
        for (Map.Entry<String, Rectangle2D> e : ws.panelBounds().entrySet()) {
            if (!first) sb.append(',');
            first = false;
            Rectangle2D r = e.getValue();
            sb.append('"').append(escape(e.getKey())).append("\":{")
                    .append("\"x\":").append(num(r.x()))
                    .append(",\"y\":").append(num(r.y()))
                    .append(",\"width\":").append(num(r.width()))
                    .append(",\"height\":").append(num(r.height()))
                    .append('}');
        }
        sb.append("}}");
        return sb.toString();
    }

    private static String num(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            // integer-valued — emit without trailing zeros
            return Long.toString((long) d);
        }
        return Double.toString(d);
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

    // Package-private access to the parser for unit tests.
    static Workspace parseJson(String json) {
        return WorkspaceJson.parse(json);
    }

    /** Package-private helper for guaranteeing iteration order. */
    static Map<String, PanelState> orderedPanelStates(Map<String, PanelState> in) {
        return new LinkedHashMap<>(in);
    }
}
